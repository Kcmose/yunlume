package com.example.nav;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.nav.common.dto.SortItemDTO;
import com.example.nav.common.exception.BusinessException;
import com.example.nav.module.search.dto.SearchEngineDTO;
import com.example.nav.module.search.service.SearchEngineService;
import com.example.nav.module.search.vo.SearchEngineVO;
import com.example.nav.module.user.entity.User;
import com.example.nav.module.user.mapper.UserMapper;
import com.example.nav.security.JwtTokenService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:search_partial_update_regressions;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class SearchEnginePartialUpdateRegressionTest extends ConcurrentDatabaseTestSupport {

    @Autowired SearchEngineService searches;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired UserMapper users;
    @Autowired JwtTokenService tokens;

    @Test
    @Transactional
    void updatingOnlyNameKeepsOmittedAndExplicitNullFields() throws Exception {
        var engine = createEngine("原名称");
        mvc.perform(put("/api/admin/search-engines/" + engine.id())
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"已修改\",\"icon\":null,\"visible\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("已修改"))
                .andExpect(jsonPath("$.data.icon").value("OLD_ICON"))
                .andExpect(jsonPath("$.data.placeholder").value("OLD_PLACEHOLDER"))
                .andExpect(jsonPath("$.data.searchUrl").value(engine.searchUrl()))
                .andExpect(jsonPath("$.data.sortOrder").value(500))
                .andExpect(jsonPath("$.data.visible").value(true));

        var stored = stored(engine.id());
        assertThat(stored.name()).isEqualTo("已修改");
        assertThat(stored.icon()).isEqualTo(engine.icon());
        assertThat(stored.placeholder()).isEqualTo(engine.placeholder());
    }

    @Test
    @Transactional
    void emptyOptionalTextClearsWithoutRepostingUnchangedFields() throws Exception {
        var engine = createEngine("清空字段");
        mvc.perform(put("/api/admin/search-engines/" + engine.id())
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"icon\":\"\",\"placeholder\":\"  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.icon").value(""))
                .andExpect(jsonPath("$.data.placeholder").value(""))
                .andExpect(jsonPath("$.data.name").value(engine.name()));
        var fields = jdbc.queryForMap("SELECT icon, placeholder FROM search_engine WHERE id = ?", engine.id());
        assertThat(fields.get("icon")).isNull();
        assertThat(fields.get("placeholder")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"name\":\"只有名称\"}", "{\"searchUrl\":\"https://example.com/\"}"})
    @Transactional
    void creationStillRequiresNameAndUrl(String json) throws Exception {
        long count = jdbc.queryForObject("SELECT COUNT(*) FROM search_engine", Long.class);
        mvc.perform(post("/api/admin/search-engines")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM search_engine", Long.class)).isEqualTo(count);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"name\":\" \"}", "{\"name\":\"\"}", "{\"name\":\"\u2003\"}", "{\"searchUrl\":\"\"}",
            "{\"searchUrl\":\"https://example.com/?q={other}\"}", "{\"sortOrder\":-1}"
    })
    @Transactional
    void providedInvalidFieldsStillFailWithoutChangingState(String json) throws Exception {
        var engine = createEngine("校验失败保留");
        int version = version();
        mvc.perform(put("/api/admin/search-engines/" + engine.id())
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest());
        assertThat(stored(engine.id())).isEqualTo(engine);
        assertThat(version()).isEqualTo(version);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void overlappingEditAndTargetedMutationsPreserveBothRequestOrders(boolean editFirst) throws Exception {
        var engine = createEngine("并发原名称");
        var firstApplied = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var secondSession = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(2);
        Runnable edit = () -> searches.update(engine.id(), new SearchEngineDTO(
                "并发修改", null, null, null, null, null));
        Runnable targeted = () -> {
            searches.setVisible(engine.id(), false);
            searches.sort(List.of(new SortItemDTO(engine.id(), 777)));
        };
        try {
            var first = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                (editFirst ? edit : targeted).run();
                firstApplied.countDown();
                await(releaseFirst);
            }));
            assertThat(firstApplied.await(10, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                secondSession.set(databaseSessionId());
                secondStarted.countDown();
                (editFirst ? targeted : edit).run();
            }));
            assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
            awaitBlockedSession(secondSession.get());
            releaseFirst.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        var merged = stored(engine.id());
        assertThat(merged.name()).isEqualTo("并发修改");
        assertThat(merged.visible()).isFalse();
        assertThat(merged.sortOrder()).isEqualTo(777);
        assertThat(merged.icon()).isEqualTo(engine.icon());
        assertThat(merged.searchUrl()).isEqualTo(engine.searchUrl());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM search_engine WHERE is_default = TRUE AND visible = TRUE", Long.class)).isEqualTo(1);
    }

    @Test
    @Transactional
    void partialHideStillTransfersDefaultWithoutChangingOtherFields() {
        var engine = createEngine("原默认");
        searches.setDefault(engine.id());
        var result = searches.update(engine.id(), new SearchEngineDTO(null, null, null, null, null, false));
        assertThat(result.visible()).isFalse();
        assertThat(result.isDefault()).isFalse();
        assertThat(result.name()).isEqualTo(engine.name());
        assertThat(result.searchUrl()).isEqualTo(engine.searchUrl());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM search_engine WHERE is_default = TRUE AND visible = TRUE", Long.class)).isEqualTo(1);
    }

    @Test
    void generationFailureRollsBackEditAndARecoveredRetryCanSucceed() {
        var engine = createEngine("失败前名称");
        int previousVersion = version();
        try {
            jdbc.update("UPDATE site_config SET version = ? WHERE id = 1", Integer.MAX_VALUE);
            assertThatThrownBy(() -> searches.update(engine.id(), new SearchEngineDTO(
                    "不应提交", null, null, null, null, false)))
                    .isInstanceOfSatisfying(BusinessException.class,
                            error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.CONFLICT));
            assertThat(stored(engine.id())).isEqualTo(engine);
            assertThat(version()).isEqualTo(Integer.MAX_VALUE);
        } finally {
            jdbc.update("UPDATE site_config SET version = ? WHERE id = 1", previousVersion);
        }
        var recovered = searches.update(engine.id(), new SearchEngineDTO(
                "重试成功", null, null, null, null, false));
        assertThat(recovered.name()).isEqualTo("重试成功");
        assertThat(recovered.visible()).isFalse();
        assertThat(version()).isEqualTo(previousVersion + 1);
    }

    @Test
    @Transactional
    void deletedTargetReturnsNotFoundInsteadOfSuccessfulEdit() throws Exception {
        var engine = createEngine("随后删除");
        searches.delete(engine.id());
        int version = version();
        mvc.perform(put("/api/admin/search-engines/" + engine.id())
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"过期编辑\"}"))
                .andExpect(status().isNotFound());
        assertThat(version()).isEqualTo(version);
    }

    private SearchEngineVO createEngine(String name) {
        var created = searches.create(new SearchEngineDTO(name, "OLD_ICON", "https://example.com/?q={keyword}",
                "OLD_PLACEHOLDER", 500, true));
        return stored(created.id());
    }

    private SearchEngineVO stored(Long id) {
        return searches.listAll().stream().filter(engine -> engine.id().equals(id)).findFirst().orElseThrow();
    }

    private int version() {
        return jdbc.queryForObject("SELECT version FROM site_config WHERE id = 1", Integer.class);
    }

    private String bearer() {
        User admin = users.selectOne(Wrappers.<User>lambdaQuery().eq(User::getUsername, "admin").last("LIMIT 1"));
        return "Bearer " + tokens.createToken(admin);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("并发测试等待超时");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("并发测试被中断", exception);
        }
    }
}
