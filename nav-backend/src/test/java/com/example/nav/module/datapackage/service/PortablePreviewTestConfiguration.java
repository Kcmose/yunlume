package com.example.nav.module.datapackage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Clock;

/** 每个重建的测试Context使用独立持久根，避免测试遗留预检消耗下一用例的生产额度。 */
@TestConfiguration
public class PortablePreviewTestConfiguration {
    @Bean
    @Primary
    PortablePreviewStore isolatedPreviewStore(ObjectMapper mapper) throws IOException {
        return new FilePortablePreviewStore(mapper, Clock.systemUTC(), Files.createTempDirectory("preview-store-test-"));
    }
}
