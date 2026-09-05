package com.example.nav;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.nav.module.datapackage.service.PortablePackageReader;
import com.example.nav.module.datapackage.service.PortablePackageWriter;
import com.example.nav.module.search.dto.SearchEngineDTO;
import com.example.nav.module.user.entity.User;
import com.example.nav.module.user.mapper.UserMapper;
import com.example.nav.security.JwtTokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:search_url_round_trip;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Transactional
class PortableSearchUrlRoundTripTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserMapper users;
    @Autowired JwtTokenService tokens;
    @Autowired PortablePackageWriter writer;
    @Autowired PortablePackageReader reader;

    @Test
    void apostropheTemplateCanBeCreatedExportedReadAndEditedAgain(@TempDir Path temporary) throws Exception {
        String url = "https://example.com/o'reilly?q={keyword}&scope='docs'";
        var dto = new SearchEngineDTO("单引号搜索", "", url, "关键词", 100, true);
        String response = mvc.perform(post("/api/admin/search-engines")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.searchUrl").value(url))
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(response).path("data").path("id").asLong();

        Path archive = temporary.resolve("round-trip.zip");
        Files.write(archive, writer.exportPackage().bytes());
        var parsed = reader.read(archive, temporary.resolve("extracted"));
        assertThat(parsed.errors()).isEmpty();
        var imported = parsed.data().searchEngines().stream()
                .filter(engine -> engine.name().equals(dto.name())).findFirst().orElseThrow();
        assertThat(imported.searchUrl()).isEqualTo(url);

        mvc.perform(put("/api/admin/search-engines/" + id)
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(new SearchEngineDTO(
                                imported.name(), imported.icon(), imported.searchUrl(), imported.placeholder(),
                                imported.sortOrder(), imported.visible()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.searchUrl").value(url));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com/?q=\"unsafe\"",
            "https://example.com/back\\slash?q={keyword}",
            "https://user:pass@example.com/?q={keyword}"
    })
    void allowingApostrophesDoesNotAllowUnsafeTemplateSyntax(String url) throws Exception {
        mvc.perform(post("/api/admin/search-engines")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(new SearchEngineDTO("不安全地址", "", url, "", 100, true))))
                .andExpect(status().isBadRequest());
    }

    private String bearer() {
        User admin = users.selectOne(Wrappers.<User>lambdaQuery().eq(User::getUsername, "admin").last("LIMIT 1"));
        return "Bearer " + tokens.createToken(admin);
    }
}
