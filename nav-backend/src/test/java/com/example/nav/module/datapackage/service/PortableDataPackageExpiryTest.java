package com.example.nav.module.datapackage.service;

import com.example.nav.common.exception.BusinessException;
import com.example.nav.module.datapackage.model.PortablePackageModels.PreviewResponse;
import com.example.nav.module.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class PortableDataPackageExpiryTest {

    @Autowired
    private PortablePackageWriter packageWriter;
    @Autowired
    private PortablePackageReader packageReader;
    @Autowired
    private PortableDataSnapshotService snapshotService;
    @Autowired
    private PortableImportTransactionService transactionService;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void previewTokenExpiresAfterFifteenMinutes(@TempDir Path temporary) {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-12T00:00:00Z"));
        PortableDataPackageService service = new PortableDataPackageService(
                packageWriter,
                packageReader,
                snapshotService,
                transactionService,
                userMapper,
                objectMapper,
                new SyncTaskExecutor(),
                clock,
                temporary.resolve("previews")
        );
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                "admin",
                "unused",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );
        byte[] archive = packageWriter.exportPackage().bytes();
        PreviewResponse preview = service.preview(
                new MockMultipartFile("file", "portable.zip", "application/zip", archive),
                authentication
        );
        assertNotNull(preview.previewToken());

        clock.advance(Duration.ofMinutes(15));
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.confirm(preview.previewToken(), authentication)
        );
        assertEquals(404, exception.getStatus().value());
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
