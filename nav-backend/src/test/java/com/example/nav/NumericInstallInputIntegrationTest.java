package com.example.nav;

import com.example.nav.module.install.dto.DatabaseConfigureDTO;
import com.example.nav.module.install.dto.DatabaseConnectionDTO;
import com.example.nav.module.install.dto.RedisConfigureDTO;
import com.example.nav.module.install.dto.RedisConnectionDTO;
import com.example.nav.module.install.service.DatabaseSetupService;
import com.example.nav.module.install.service.RedisSetupService;
import com.example.nav.module.install.vo.DatabaseConfigureVO;
import com.example.nav.module.install.vo.DatabaseTestVO;
import com.example.nav.module.install.vo.RedisConfigureVO;
import com.example.nav.module.install.vo.RedisTestVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 保留生产 JSON 绑定和 MVC；连接探测、票据及配置落盘在服务边界隔离。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:numeric_install_input;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "nav.upload.cleanup-initial-delay-ms=3600000"
})
@AutoConfigureMockMvc
class NumericInstallInputIntegrationTest {
    private static final String DATABASE = "/api/install/database/test";
    private static final String REDIS = "/api/install/redis/test";
    private static final String TICKET = "a".repeat(64);

    @Autowired MockMvc mvc;
    @MockitoBean DatabaseSetupService databaseSetup;
    @MockitoBean RedisSetupService redisSetup;

    @BeforeEach
    void clearStartupInteractions() {
        clearInvocations(databaseSetup, redisSetup);
    }

    static Stream<Arguments> fractionalInputs() {
        return Stream.of(
                Arguments.of(DATABASE, "port", "5432.9"),
                Arguments.of(REDIS, "port", "6379.9"),
                Arguments.of(REDIS, "database", "1.5"),
                Arguments.of(REDIS, "database", "-0.5"),
                Arguments.of(REDIS, "connectTimeoutSeconds", "3.5"),
                Arguments.of(REDIS, "readTimeoutSeconds", "3.5"),
                Arguments.of(DATABASE, "port", "5432.0"),
                Arguments.of(DATABASE, "port", "5.432e3"),
                Arguments.of(REDIS, "database", "\"1.5\""),
                Arguments.of(REDIS, "database", "\"1e0\"")
        );
    }

    @ParameterizedTest
    @MethodSource("fractionalInputs")
    void fractionalInputIsRejectedBeforeAnyConnectionOrConfigurationService(
            String endpoint, String field, String token
    ) throws Exception {
        mvc.perform(post(endpoint).secure(true).contentType(MediaType.APPLICATION_JSON)
                        .content(body(endpoint, field, token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
        verifyNoInteractions(databaseSetup, redisSetup);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void correctedIntegerOrIntegerStringCanObtainAndSubmitItsTicket(boolean asString) throws Exception {
        mvc.perform(post(DATABASE).secure(true).contentType(MediaType.APPLICATION_JSON)
                        .content(body(DATABASE, "port", "5432.5")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(databaseSetup, redisSetup);

        when(databaseSetup.test(any(DatabaseConnectionDTO.class)))
                .thenReturn(new DatabaseTestVO(true, TICKET, Instant.now().plusSeconds(300), "EMPTY", true));
        when(databaseSetup.configure(any(DatabaseConfigureDTO.class)))
                .thenReturn(new DatabaseConfigureVO(true, true, false, true));
        mvc.perform(post(DATABASE).secure(true).contentType(MediaType.APPLICATION_JSON)
                        .content(body(DATABASE, "port", asString ? "\"5432\"" : "5432")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.connectionTicket").value(TICKET));
        var databaseRequest = ArgumentCaptor.forClass(DatabaseConnectionDTO.class);
        verify(databaseSetup).test(databaseRequest.capture());
        assertEquals(Integer.valueOf(5432), databaseRequest.getValue().port());
        mvc.perform(post("/api/install/database/configure").secure(true).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"connectionTicket\":\"" + TICKET + "\",\"initializeSchema\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configured").value(true));
        verify(databaseSetup).configure(new DatabaseConfigureDTO(TICKET, true));

        mvc.perform(post(REDIS).secure(true).contentType(MediaType.APPLICATION_JSON)
                        .content(body(REDIS, "database", "-0.5")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(redisSetup);
        when(redisSetup.test(any(RedisConnectionDTO.class)))
                .thenReturn(new RedisTestVO(true, TICKET, Instant.now().plusSeconds(300)));
        when(redisSetup.configure(any(RedisConfigureDTO.class)))
                .thenReturn(new RedisConfigureVO(true, true));
        String redisBody = asString
                ? """
                  {"host":"redis.example.test","port":"6379","database":"0",
                   "connectTimeoutSeconds":"3","readTimeoutSeconds":"3","password":"test-password"}
                  """
                : body(REDIS, "database", "0");
        mvc.perform(post(REDIS).secure(true).contentType(MediaType.APPLICATION_JSON).content(redisBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.connectionTicket").value(TICKET));
        var redisRequest = ArgumentCaptor.forClass(RedisConnectionDTO.class);
        verify(redisSetup).test(redisRequest.capture());
        assertEquals(Integer.valueOf(6379), redisRequest.getValue().port());
        assertEquals(Integer.valueOf(0), redisRequest.getValue().database());
        assertEquals(Integer.valueOf(3), redisRequest.getValue().connectTimeoutSeconds());
        assertEquals(Integer.valueOf(3), redisRequest.getValue().readTimeoutSeconds());
        mvc.perform(post("/api/install/redis/configure").secure(true).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"connectionTicket\":\"" + TICKET + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configured").value(true));
        verify(redisSetup).configure(new RedisConfigureDTO(TICKET));
    }

    private static String body(String endpoint, String field, String token) {
        if (DATABASE.equals(endpoint)) {
            return """
                    {"host":"postgres.example.test","port":%s,"database":"navigation",
                     "username":"navigation","password":"test-password"}
                    """.formatted(token);
        }
        Map<String, String> values = new HashMap<>(Map.of(
                "port", "6379", "database", "0", "connectTimeoutSeconds", "3", "readTimeoutSeconds", "3"));
        values.put(field, token);
        return """
                {"host":"redis.example.test","port":%s,"database":%s,
                 "connectTimeoutSeconds":%s,"readTimeoutSeconds":%s,"password":"test-password"}
                """.formatted(values.get("port"), values.get("database"),
                values.get("connectTimeoutSeconds"), values.get("readTimeoutSeconds"));
    }
}
