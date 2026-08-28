package com.example.nav.module.install.service;

import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisConnectionVerifierTest {

    @Test
    @SuppressWarnings("unchecked")
    void probeRequiresSetNxPxThenMatchingReadAndDelete() {
        RedisCommands<String, String> commands = mock(RedisCommands.class);
        AtomicReference<String> value = new AtomicReference<>();
        when(commands.set(anyString(), anyString(), any(SetArgs.class)))
                .thenAnswer(invocation -> {
                    value.set(invocation.getArgument(1));
                    return "OK";
                });
        when(commands.get(anyString())).thenAnswer(invocation -> value.get());
        when(commands.del(anyString())).thenReturn(1L);

        new RedisConnectionVerifier().verifyReadWriteCommands(commands);

        verify(commands).set(anyString(), anyString(), any(SetArgs.class));
        verify(commands).get(anyString());
        verify(commands).del(anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void mismatchedReadNeverDeletesAKeyTheProbeDoesNotOwn() {
        RedisCommands<String, String> commands = mock(RedisCommands.class);
        when(commands.set(anyString(), anyString(), any(SetArgs.class))).thenReturn("OK");
        when(commands.get(anyString())).thenReturn("somebody-elses-value");

        assertThrows(IllegalStateException.class,
                () -> new RedisConnectionVerifier().verifyReadWriteCommands(commands));

        verify(commands, times(2)).get(anyString());
        verify(commands, times(0)).del(anyString());
    }
}
