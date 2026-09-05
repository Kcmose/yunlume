package com.example.nav.module.install.service;

import com.example.nav.module.datapackage.service.RedisPortableImportScripts;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisRuntimeAclContractTest {

    private static final Set<String> REQUIRED = Set.of(
            "ping", "select", "info", "set", "get", "del", "eval", "evalsha",
            "exists", "incr", "psetex", "pexpire");

    @Test
    void advertisedAclCommandsExactlyMatchVerifierAndDocumentation() throws Exception {
        assertEquals(REQUIRED, RedisConnectionVerifier.requiredAclCommands());
        String readme = Files.readString(Path.of("../README.md")).toLowerCase();
        String fixture = Files.readString(Path.of("../ops/install-e2e.sh")).toLowerCase();
        for (String command : REQUIRED) {
            assertTrue(readme.contains(command), "README missing " + command);
            assertTrue(fixture.contains("+" + command), "install fixture missing +" + command);
        }
    }

    @Test
    void exactImportAclMatrixIncludesEveryCommandOnEveryProductionKeyShape() {
        String source = RedisPortableImportScripts.atomicProbeSource();
        assertAcl(source, "exists", "nav:portable-import:preview:00000000000000000000000000000000");
        assertAcl(source, "psetex", "nav:portable-import:preview:00000000000000000000000000000000");
        assertAcl(source, "get", "nav:portable-import:preview:00000000000000000000000000000000");
        assertAcl(source, "del", "nav:portable-import:preview:00000000000000000000000000000000");
        for (String family : Set.of("job:00000000000000000000000000000000", "current:0")) {
            assertAcl(source, "psetex", "nav:portable-import:" + family);
            assertAcl(source, "get", "nav:portable-import:" + family);
            assertAcl(source, "del", "nav:portable-import:" + family);
        }
        for (String command : Set.of("exists", "psetex", "get", "pexpire", "del")) {
            assertAcl(source, command, "nav:portable-import:lock");
        }
        assertAcl(source, "incr", "nav:portable-import:fence-sequence");
    }

    @Test
    void exactCacheAclMatrixChecksEveryVersionAndPayloadOperation() {
        String source = RedisPortableImportScripts.atomicProbeSource();
        for (String cacheName : Set.of("publicSiteConfig", "publicNavigation",
                "publicSearchEngines", "publicCustomLinks")) {
            String versionKey = "nav:public-cache-version:" + cacheName;
            assertTrue(source.contains("require_acl('get', version_key)"), versionKey + " GET");
            assertTrue(source.contains("require_acl('set', version_key"), versionKey + " SET");
            String payloadKey = "nav:cache::" + cacheName + "::1";
            assertTrue(source.contains("require_acl('get', payload_key)"), payloadKey + " GET");
            assertTrue(source.contains("require_acl('set', payload_key"), payloadKey + " SET");
            assertTrue(source.contains("require_acl('psetex', payload_key"), payloadKey + " PSETEX");
            assertTrue(source.contains("require_acl('del', payload_key"), payloadKey + " DEL");
            assertTrue(source.contains("'" + cacheName + "'"));
        }
    }

    @Test
    void springCacheUsesProvisionedNamespace() throws Exception {
        String yaml = Files.readString(Path.of("src/main/resources/application.yml"));
        assertTrue(yaml.contains("key-prefix: \"nav:cache::\""));
        assertTrue(yaml.contains("use-key-prefix: true"));
    }

    private void assertAcl(String source, String command, String key) {
        assertTrue(source.contains("{'" + command + "', '" + key + "'")
                        || source.contains("require_acl('" + command + "', '" + key + "'"),
                command + " " + key);
    }
}
