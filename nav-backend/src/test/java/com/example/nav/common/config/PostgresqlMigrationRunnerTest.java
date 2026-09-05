package com.example.nav.common.config;

import com.example.nav.module.install.service.DatabaseConfigurationStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.sql.init.dependency.DatabaseInitializationDependencyConfigurer;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostgresqlMigrationRunnerTest {

    @Test
    void migrationIsAStructuralDependencyOfDatabaseInitializingBeans() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(DatabaseInitializationDependencyConfigurer.class, LifecycleTestConfiguration.class);
            context.refresh();

            BeanDefinition dependent = context.getBeanFactory().getBeanDefinition("databaseQueryingBean");
            assertEquals("postgresqlMigrationRunner", dependent.getDependsOn()[0]);
            assertTrue(context.getBean(AtomicBoolean.class).get());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(DatabaseInitializationDependencyConfigurer.class)
    static class LifecycleTestConfiguration {

        @Bean
        AtomicBoolean migrationCompleted() {
            return new AtomicBoolean();
        }

        @Bean
        @DependsOnDatabaseInitialization
        Object databaseQueryingBean(AtomicBoolean migrationCompleted) {
            assertTrue(migrationCompleted.get(), "database bean initialized before migration");
            return new Object();
        }

        @Bean
        PostgresqlMigrationRunner postgresqlMigrationRunner(AtomicBoolean migrationCompleted) {
            DataSource dataSource = mock(DataSource.class);
            DatabaseConfigurationStore store = mock(DatabaseConfigurationStore.class);
            when(store.isUnconfiguredSource()).thenAnswer(invocation -> {
                migrationCompleted.set(true);
                return true;
            });
            return new PostgresqlMigrationRunner(dataSource, store);
        }
    }

    @Test
    void freshUnconfiguredInstallDoesNotTouchPlaceholderDatasource() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        DatabaseConfigurationStore store = mock(DatabaseConfigurationStore.class);
        when(store.isUnconfiguredSource()).thenReturn(true);

        new PostgresqlMigrationRunner(dataSource, store).afterPropertiesSet();

        verify(dataSource, never()).getConnection();
    }

    @Test
    void packagedMigrationHasPinnedCanonicalChecksum() throws Exception {
        byte[] sql;
        try (var input = getClass().getResourceAsStream(
                "/database/migrations/20260904_0004_portable_import_operations.sql")) {
            sql = input.readAllBytes();
        }
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sql));
        assertEquals(PostgresqlMigrationRunner.MIGRATION_CHECKSUM, checksum);
        assertEquals("4de5e2df8c8f6780f6d1b25e16ee1dd99b7335c7b7475afb83c63f78cfa7ac63", checksum);
        assertTrue(new String(sql, StandardCharsets.UTF_8).contains("CREATE TABLE portable_import_operation"));
        assertTrue(new String(sql, StandardCharsets.UTF_8).contains(
                "CONSTRAINT chk_site_config_version_range CHECK (version >= 0)"));
    }
}
