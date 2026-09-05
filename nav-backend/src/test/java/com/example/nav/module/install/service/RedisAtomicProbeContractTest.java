package com.example.nav.module.install.service;

import com.example.nav.common.redis.RedisProductionLua;
import com.example.nav.module.datapackage.service.RedisPortableImportScripts;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisAtomicProbeContractTest {

    @Test
    void standaloneAndAtomicProbeShareEveryExactProductionFunctionBody() {
        Map<String, String> functions = new LinkedHashMap<>();
        functions.put("claim", RedisProductionLua.CLAIM);
        functions.put("save", RedisProductionLua.SAVE);
        functions.put("heartbeat", RedisProductionLua.HEARTBEAT);
        functions.put("require_current", RedisProductionLua.REQUIRE_CURRENT);
        functions.put("release", RedisProductionLua.RELEASE);
        functions.put("abandon", RedisProductionLua.ABANDON);
        functions.put("recover", RedisProductionLua.RECOVER);

        var standalone = RedisPortableImportScripts.sources();
        assertEquals(functions.size(), standalone.size());
        int index = 0;
        for (var entry : functions.entrySet()) {
            assertEquals(RedisProductionLua.script(entry.getValue(), entry.getKey()), standalone.get(index++));
            assertEquals(1, occurrences(RedisPortableImportScripts.atomicProbeSource(), entry.getValue()), entry.getKey());
        }
        assertEquals(1, occurrences(RedisPortableImportScripts.atomicProbeSource(),
                RedisProductionLua.ADVANCE_GENERATION));
    }

    @Test
    void atomicProbePreflightsAllExistenceAndAclChecksBeforeMutationAndOwnsCleanup() {
        String source = RedisPortableImportScripts.atomicProbeSource();
        int preflight = source.indexOf("-- Complete command/key ACL preflight");
        int firstMutation = source.indexOf("local execution_ok");
        assertTrue(preflight > 0);
        assertTrue(firstMutation > preflight);
        String preMutationOperations = source.substring(preflight, firstMutation);
        assertTrue(preMutationOperations.contains("redis.acl_check_cmd"));
        assertTrue(preMutationOperations.contains("redis.call('exists'"));
        assertFalse(preMutationOperations.contains("redis.call('set'"));
        assertFalse(preMutationOperations.contains("redis.call('psetex'"));
        assertFalse(preMutationOperations.contains("redis.call('incr'"));
        assertTrue(source.contains("pcall(function()"));
        assertTrue(source.contains("redis.pcall('del', unpack(KEYS))"));
        assertTrue(source.indexOf("redis.pcall('del', unpack(KEYS))") > firstMutation);
    }

    @Test
    void atomicProbeReturnsNamedExactResultsRatherThanAnAggregateCount() {
        String source = RedisPortableImportScripts.atomicProbeSource();
        for (String result : RedisPortableImportScripts.expectedProbeResult()) {
            assertTrue(source.contains("'" + result + "'"), result);
        }
        assertFalse(source.contains("completed = completed + 1"));
    }

    private int occurrences(String source, String fragment) {
        int count = 0;
        for (int index = 0; (index = source.indexOf(fragment, index)) >= 0; index += fragment.length()) count++;
        return count;
    }
}
