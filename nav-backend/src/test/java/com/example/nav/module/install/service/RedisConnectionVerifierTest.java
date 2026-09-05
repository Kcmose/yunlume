package com.example.nav.module.install.service;

import com.example.nav.module.datapackage.service.RedisPortableImportScripts;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisConnectionVerifierTest {

    @Test
    @SuppressWarnings("unchecked")
    void probesExactRuntimeFamiliesAndLeavesAllCleanupInsideAtomicLua() {
        RedisCommands<String, String> commands = readyCommands();

        new RedisConnectionVerifier().verifyRuntimeCommands(commands, 0, "verify-owner");

        ArgumentCaptor<String> script = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String[]> keys = ArgumentCaptor.forClass(String[].class);
        verify(commands).eval(script.capture(), eq(ScriptOutputType.MULTI), keys.capture(), any(String[].class));
        Set<String> probedKeys = Arrays.stream(keys.getValue()).collect(Collectors.toSet());
        assertTrue(probedKeys.stream().anyMatch(key -> key.startsWith("nav:portable-import:lock:probe-")));
        assertTrue(probedKeys.stream().anyMatch(key -> key.startsWith("nav:portable-import:fence-sequence:probe-")));
        assertTrue(probedKeys.stream().noneMatch(key -> key.equals("nav:portable-import:lock")));
        assertTrue(probedKeys.stream().noneMatch(key -> key.equals("nav:portable-import:fence-sequence")));
        for (String family : Set.of("preview", "job", "current", "recover-job")) {
            assertTrue(probedKeys.stream().anyMatch(key -> key.startsWith("nav:portable-import:" + family)), family);
        }
        assertTrue(script.getValue().contains("redis.pcall('del', unpack(KEYS))"));
        assertTrue(script.getValue().contains("{'incr', 'nav:portable-import:fence-sequence'}"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void structuredBranchMismatchIsRejected() {
        RedisCommands<String, String> commands = readyCommands();
        when(commands.eval(anyString(), eq(ScriptOutputType.MULTI), any(String[].class), any(String[].class)))
                .thenReturn(List.of("advance", "1", "claim", "0"));

        assertThrows(IllegalStateException.class,
                () -> new RedisConnectionVerifier().verifyReadWriteCommands(commands));
    }

    @Test
    void rejectsRedisOlderThanSevenBecauseAclCheckCmdIsUnavailable() {
        assertThrows(IllegalStateException.class,
                () -> new RedisConnectionVerifier().verifyServerVersion("redis_version:6.2.14\r\n"));
        new RedisConnectionVerifier().verifyServerVersion("redis_version:7.4.1\r\n");
    }

    @SuppressWarnings("unchecked")
    private RedisCommands<String, String> readyCommands() {
        RedisCommands<String, String> commands = mock(RedisCommands.class);
        when(commands.ping()).thenReturn("PONG");
        when(commands.info("server")).thenReturn("redis_version:7.4.1\r\n");
        when(commands.select(0)).thenReturn("OK");
        List<String> result = RedisPortableImportScripts.expectedProbeResult();
        when(commands.eval(anyString(), eq(ScriptOutputType.MULTI), any(String[].class), any(String[].class)))
                .thenReturn(result);
        when(commands.evalsha(anyString(), eq(ScriptOutputType.MULTI), any(String[].class), any(String[].class)))
                .thenReturn(result);
        return commands;
    }
}
