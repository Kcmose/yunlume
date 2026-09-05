package com.example.nav.common.redis;

import java.util.List;

/** Shared Lua function bodies used by both production scripts and the atomic installer probe. */
public final class RedisProductionLua {

    public static final String ADVANCE_GENERATION = """
            local function advance_generation(keys, args)
              local function generation(value)
                if value == '0' then return 0 end
                if string.match(value, '^[1-9][0-9]*$') == nil then return nil end
                if string.len(value) > 10 then return nil end
                if string.len(value) == 10 and value > '2147483647' then return nil end
                return tonumber(value)
              end
              local raw_current = redis.call('get', keys[1]) or '0'
              local current = generation(raw_current)
              local requested = generation(args[1])
              if current == nil or requested == nil then
                return redis.error_reply('invalid cache generation')
              end
              if current > requested then
                if current == 2147483647 then
                  return redis.error_reply('cache generation overflow')
                end
                local next_generation = current + 1
                redis.call('set', keys[1], tostring(next_generation))
                return next_generation
              end
              if requested > current then redis.call('set', keys[1], args[1]) end
              return requested
            end
            """;

    public static final String CLAIM = """
            local function claim(keys, args)
              if redis.call('exists', keys[1]) == 1 then return -2 end
              if redis.call('exists', keys[4]) == 1 then return -3 end
              local token = redis.call('incr', keys[5])
              local owner = args[1] .. ':' .. token
              redis.call('psetex', keys[1], args[3], args[1])
              redis.call('psetex', keys[2], args[3], args[2])
              redis.call('psetex', keys[3], args[3], args[1])
              redis.call('psetex', keys[4], args[4], owner)
              return token
            end
            """;

    public static final String SAVE = """
            local function save(keys, args)
              if redis.call('get', keys[4]) ~= args[3] then return 0 end
              redis.call('psetex', keys[1], args[2], args[1])
              redis.call('psetex', keys[2], args[2], args[4])
              redis.call('psetex', keys[3], args[2], args[4])
              return 1
            end
            """;

    public static final String HEARTBEAT = """
            local function heartbeat(keys, args)
              if redis.call('get', keys[1]) ~= args[1] then return 0 end
              redis.call('pexpire', keys[1], args[2])
              return 1
            end
            """;

    public static final String REQUIRE_CURRENT = """
            local function require_current(keys, args)
              if redis.call('get', keys[1]) == args[1] then return 1 end
              return 0
            end
            """;

    public static final String RELEASE = """
            local function release(keys, args)
              if redis.call('get', keys[1]) == args[1] then
                return redis.call('del', keys[1])
              end
              return 0
            end
            """;

    public static final String ABANDON = """
            local function abandon(keys, args)
              if redis.call('get', keys[4]) ~= args[2] then return 0 end
              if redis.call('get', keys[1]) == args[1] then redis.call('del', keys[1]) end
              if redis.call('get', keys[2]) == args[1] then redis.call('del', keys[2]) end
              if redis.call('get', keys[3]) == args[1] then redis.call('del', keys[3]) end
              redis.call('del', keys[4])
              return 1
            end
            """;

    public static final String RECOVER = """
            local function recover(keys, args)
              if redis.call('get', keys[1]) ~= args[1] then return 0 end
              local lock = redis.call('get', keys[2])
              if lock and string.sub(lock, 1, string.len(args[4]) + 1) == args[4] .. ':' then return 0 end
              redis.call('psetex', keys[1], args[3], args[2])
              return 1
            end
            """;

    private RedisProductionLua() {}

    public static String script(String functionSource, String functionName) {
        return functionSource + "\nreturn " + functionName + "(KEYS, ARGV)\n";
    }

    public static List<String> portableImportFunctions() {
        return List.of(CLAIM, SAVE, HEARTBEAT, REQUIRE_CURRENT, RELEASE, ABANDON, RECOVER);
    }
}
