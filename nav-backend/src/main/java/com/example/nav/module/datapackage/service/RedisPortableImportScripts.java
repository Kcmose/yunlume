package com.example.nav.module.datapackage.service;

import com.example.nav.common.redis.RedisProductionLua;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

/** Single production source of truth for portable-import Redis scripts and their installer probe. */
public final class RedisPortableImportScripts {
    public static final DefaultRedisScript<Long> CLAIM = script(RedisProductionLua.CLAIM, "claim");
    public static final DefaultRedisScript<Long> SAVE = script(RedisProductionLua.SAVE, "save");
    public static final DefaultRedisScript<Long> HEARTBEAT = script(RedisProductionLua.HEARTBEAT, "heartbeat");
    public static final DefaultRedisScript<Long> REQUIRE_CURRENT = script(
            RedisProductionLua.REQUIRE_CURRENT, "require_current");
    public static final DefaultRedisScript<Long> RELEASE = script(RedisProductionLua.RELEASE, "release");
    public static final DefaultRedisScript<Long> ABANDON = script(RedisProductionLua.ABANDON, "abandon");
    public static final DefaultRedisScript<Long> RECOVER = script(RedisProductionLua.RECOVER, "recover");

    private static final List<String> EXPECTED_PROBE_RESULT = List.of(
            "advance", "1",
            "claim", "1",
            "save", "1",
            "heartbeat", "1",
            "require_current", "1",
            "abandon", "1",
            "release", "1",
            "recover", "1");

    private static final String ATOMIC_PROBE = buildAtomicProbe();

    private RedisPortableImportScripts() {}

    public static List<String> sources() {
        return List.of(CLAIM.getScriptAsString(), SAVE.getScriptAsString(), HEARTBEAT.getScriptAsString(),
                REQUIRE_CURRENT.getScriptAsString(), RELEASE.getScriptAsString(), ABANDON.getScriptAsString(),
                RECOVER.getScriptAsString());
    }

    public static String atomicProbeSource() {
        return ATOMIC_PROBE;
    }

    public static List<String> expectedProbeResult() {
        return EXPECTED_PROBE_RESULT;
    }

    private static DefaultRedisScript<Long> script(String function, String name) {
        return new DefaultRedisScript<>(RedisProductionLua.script(function, name), Long.class);
    }

    private static String buildAtomicProbe() {
        return RedisProductionLua.ADVANCE_GENERATION
                + RedisProductionLua.CLAIM
                + RedisProductionLua.SAVE
                + RedisProductionLua.HEARTBEAT
                + RedisProductionLua.REQUIRE_CURRENT
                + RedisProductionLua.RELEASE
                + RedisProductionLua.ABANDON
                + RedisProductionLua.RECOVER
                + """

                local value = ARGV[1]
                local job_id = ARGV[2]
                local json = ARGV[3]
                local failed = ARGV[4]
                local ttl = ARGV[5]

                local function require_acl(command, ...)
                  if not redis.acl_check_cmd(command, ...) then
                    return false
                  end
                  return true
                end

                -- Complete command/key ACL preflight. No Redis mutation is permitted above this point.
                for i = 1, #KEYS do
                  if not require_acl('exists', KEYS[i]) then
                    return redis.error_reply('exact runtime key ACL capability missing')
                  end
                end
                if not require_acl('get', KEYS[1]) or not require_acl('set', KEYS[1], '1')
                  or not require_acl('exists', KEYS[2]) or not require_acl('psetex', KEYS[2], ttl, job_id)
                  or not require_acl('psetex', KEYS[3], ttl, json)
                  or not require_acl('psetex', KEYS[4], ttl, job_id)
                  or not require_acl('exists', KEYS[5]) or not require_acl('psetex', KEYS[5], ttl, value)
                  or not require_acl('get', KEYS[5]) or not require_acl('pexpire', KEYS[5], ttl)
                  or not require_acl('del', KEYS[5])
                  or not require_acl('incr', KEYS[6])
                  or not require_acl('get', KEYS[2]) or not require_acl('del', KEYS[2])
                  or not require_acl('get', KEYS[3]) or not require_acl('del', KEYS[3])
                  or not require_acl('get', KEYS[4]) or not require_acl('del', KEYS[4])
                  or not require_acl('get', KEYS[7]) or not require_acl('psetex', KEYS[7], ttl, json)
                  or not require_acl('set', KEYS[8], value, 'NX', 'PX', ttl)
                  or not require_acl('get', KEYS[8])
                  or not require_acl('psetex', KEYS[8], ttl, value)
                  or not require_acl('del', KEYS[8])
                  or not require_acl('del', unpack(KEYS)) then
                  return redis.error_reply('exact runtime key ACL capability missing')
                end

                -- Exact fixed production shapes, including the global import lock/fence and all public caches.
                local exact = {
                  {'exists', 'nav:portable-import:preview:00000000000000000000000000000000'},
                  {'psetex', 'nav:portable-import:preview:00000000000000000000000000000000', ttl, value},
                  {'get', 'nav:portable-import:preview:00000000000000000000000000000000'},
                  {'del', 'nav:portable-import:preview:00000000000000000000000000000000'},
                  {'psetex', 'nav:portable-import:job:00000000000000000000000000000000', ttl, value},
                  {'get', 'nav:portable-import:job:00000000000000000000000000000000'},
                  {'del', 'nav:portable-import:job:00000000000000000000000000000000'},
                  {'psetex', 'nav:portable-import:current:0', ttl, value},
                  {'get', 'nav:portable-import:current:0'},
                  {'del', 'nav:portable-import:current:0'},
                  {'exists', 'nav:portable-import:lock'},
                  {'psetex', 'nav:portable-import:lock', ttl, value},
                  {'get', 'nav:portable-import:lock'},
                  {'pexpire', 'nav:portable-import:lock', ttl},
                  {'del', 'nav:portable-import:lock'},
                  {'incr', 'nav:portable-import:fence-sequence'}
                }
                for _, check in ipairs(exact) do
                  if not redis.acl_check_cmd(unpack(check)) then
                    return redis.error_reply('exact production import ACL capability missing')
                  end
                end
                -- 共享预检仅使用既有命令；检查每个固定slot/chunk，避免键模式ACL只放行旧token索引。
                for slot = 0, 7 do
                  local metadata = 'nav:portable-import:preview:pending:slot:' .. slot
                  for chunk = -1, 63 do
                    local key = chunk == -1 and metadata or metadata .. ':chunk:' .. chunk
                    if not require_acl('get', key) or not require_acl('psetex', key, ttl, value)
                      or not require_acl('pexpire', key, ttl) or not require_acl('del', key) then
                      return redis.error_reply('exact production preview ACL capability missing')
                    end
                  end
                end
                local cache_names = {'publicSiteConfig', 'publicNavigation', 'publicSearchEngines', 'publicCustomLinks'}
                for _, cache_name in ipairs(cache_names) do
                  local version_key = 'nav:public-cache-version:' .. cache_name
                  local payload_key = 'nav:cache::' .. cache_name .. '::1'
                  if not require_acl('get', version_key) or not require_acl('set', version_key, '1')
                    or not require_acl('get', payload_key) or not require_acl('set', payload_key, value, 'PX', ttl)
                    or not require_acl('psetex', payload_key, ttl, value)
                    or not require_acl('del', payload_key) then
                    return redis.error_reply('exact production cache ACL capability missing')
                  end
                end

                for i = 1, #KEYS do
                  if redis.call('exists', KEYS[i]) ~= 0 then
                    return redis.error_reply('production probe key collision')
                  end
                end

                local execution_ok, results = pcall(function()
                  local output = {}
                  local function exact_result(name, actual, expected)
                    if actual ~= expected then error('production branch mismatch: ' .. name) end
                    output[#output + 1] = name
                    output[#output + 1] = tostring(actual)
                  end

                  exact_result('advance', advance_generation({KEYS[1]}, {'1'}), 1)
                  exact_result('claim', claim({KEYS[2], KEYS[3], KEYS[4], KEYS[5], KEYS[6]},
                    {job_id, json, ttl, ttl}), 1)
                  local owner = job_id .. ':1'
                  exact_result('save', save({KEYS[3], KEYS[2], KEYS[4], KEYS[5]},
                    {json, ttl, owner, job_id}), 1)
                  exact_result('heartbeat', heartbeat({KEYS[5]}, {owner, ttl}), 1)
                  exact_result('require_current', require_current({KEYS[5]}, {owner}), 1)
                  exact_result('abandon', abandon({KEYS[2], KEYS[3], KEYS[4], KEYS[5]},
                    {job_id, owner}), 1)

                  redis.call('psetex', KEYS[5], ttl, owner)
                  exact_result('release', release({KEYS[5]}, {owner}), 1)

                  redis.call('psetex', KEYS[7], ttl, json)
                  exact_result('recover', recover({KEYS[7], KEYS[5]}, {json, failed, ttl, job_id}), 1)

                  local cache_set = redis.call('set', KEYS[8], value, 'NX', 'PX', ttl)
                  if type(cache_set) ~= 'table' or cache_set.ok ~= 'OK' then
                    error('cache SET branch mismatch')
                  end
                  if redis.call('get', KEYS[8]) ~= value then error('cache GET branch mismatch') end
                  redis.call('psetex', KEYS[8], ttl, value)
                  if redis.call('del', KEYS[8]) ~= 1 then error('cache DEL branch mismatch') end
                  return output
                end)

                -- Finalization is part of this same atomic execution. Java never guesses ownership or deletes.
                local cleanup = redis.pcall('del', unpack(KEYS))
                if type(cleanup) == 'table' and cleanup.err then
                  return redis.error_reply('production probe cleanup failed')
                end
                for i = 1, #KEYS do
                  local absent = redis.pcall('exists', KEYS[i])
                  if type(absent) == 'table' and absent.err or absent ~= 0 then
                    return redis.error_reply('production probe cleanup verification failed')
                  end
                end
                if not execution_ok then
                  return redis.error_reply('production probe execution failed')
                end
                return results
                """;
    }
}
