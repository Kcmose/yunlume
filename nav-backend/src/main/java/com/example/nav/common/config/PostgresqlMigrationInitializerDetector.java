package com.example.nav.common.config;

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.sql.init.dependency.DatabaseInitializerDetector;

import java.util.Set;

/** Exposes the application migration runner to Spring Boot's database dependency graph. */
public final class PostgresqlMigrationInitializerDetector implements DatabaseInitializerDetector {

    @Override
    public Set<String> detect(ConfigurableListableBeanFactory beanFactory) {
        return Set.of(beanFactory.getBeanNamesForType(PostgresqlMigrationRunner.class, false, false));
    }
}