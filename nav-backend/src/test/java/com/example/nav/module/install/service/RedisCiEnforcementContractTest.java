package com.example.nav.module.install.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisCiEnforcementContractTest {

    @Test
    void checkedBackendCiCannotSkipRealRedisSevenSuites() throws Exception {
        String workflow = Files.readString(Path.of("../.github/workflows/publish-images.yml"));
        assertTrue(workflow.contains("image: redis:7.4"));
        assertTrue(workflow.contains("REDIS_ACL_HOST: 127.0.0.1"));
        assertTrue(workflow.contains("REDIS_CACHE_RECOVERY_HOST: 127.0.0.1"));
        assertTrue(workflow.contains("REDIS_REAL_TESTS_REQUIRED: \"true\""));
        assertTrue(workflow.contains("ACL SETUSER nav_readonly"));
        assertTrue(workflow.contains("ACL SETUSER nav_writeonly"));

        String aclTest = Files.readString(Path.of(
                "src/test/java/com/example/nav/module/install/service/RedisRealAclIntegrationTest.java"));
        String recoveryTest = Files.readString(Path.of(
                "src/test/java/com/example/nav/module/publicdata/PublicDataCacheRecoveryIntegrationTest.java"));
        assertTrue(aclTest.contains("RealRedisTestGuard.require"));
        assertTrue(recoveryTest.contains("RealRedisTestGuard.require"));
        assertTrue(!aclTest.contains("EnabledIfEnvironmentVariable"));
        assertTrue(!recoveryTest.contains("EnabledIfEnvironmentVariable"));
    }
}
