package com.example.nav.module.install.service;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 只清理测试明确创建/写入且值仍属于本次操作的键，失败现场保留供排查。 */
public final class OwnedRedisTestKeys {
    private final Map<String, String> values = new LinkedHashMap<>();
    private boolean verified;

    public synchronized void verifyEmpty(long size) {
        verified = false;
        assertTrue(values.isEmpty(), "Previous Redis fixtures must be resolved before reusing the lifecycle");
        assertEquals(0L, size, "Redis integration service must be dedicated and empty");
        verified = true;
    }

    public synchronized boolean isVerified() { return verified; }

    public synchronized void create(RedisCommands<String, String> commands, String key, String value) {
        assertTrue(verified, "Redis isolation must be verified before creating fixtures");
        assertTrue(commands.setnx(key, value), "Redis test fixture collides with an existing key");
        recordWritten(key, value);
    }

    /** 调用者必须已成功执行该精确键和值的创建/写入，不能登记枚举或回读的未知值。 */
    public synchronized void recordWritten(String key, String value) {
        assertTrue(verified, "Redis isolation must be verified before recording fixtures");
        values.put(key, value);
    }

    public synchronized void expectValue(String key, String value) {
        assertTrue(values.containsKey(key), "Redis fixture must already be owned");
        values.put(key, value);
    }

    public synchronized void cleanup(RedisCommands<String, String> commands) {
        // BeforeEach 抛异常后 JUnit 仍执行 AfterEach；未经验证时不能触碰目标库。
        if (!verified) return;
        try {
            for (var entry : values.entrySet()) {
                Long removed = commands.eval("""
                        local current = redis.call('GET', KEYS[1])
                        if not current then return 0 end
                        if current ~= ARGV[1] then return -1 end
                        return redis.call('DEL', KEYS[1])
                        """, ScriptOutputType.INTEGER, new String[]{entry.getKey()}, entry.getValue());
                assertTrue(removed != null && removed >= 0, "Redis fixture was replaced; preserving evidence");
            }
            assertEquals(0L, commands.dbsize(), "Unexpected Redis residue is preserved for inspection");
            values.clear();
        } finally { verified = false; }
    }
}
