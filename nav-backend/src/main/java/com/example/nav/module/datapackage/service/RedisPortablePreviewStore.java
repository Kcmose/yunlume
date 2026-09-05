package com.example.nav.module.datapackage.service;

import com.example.nav.common.exception.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Component
@ConditionalOnProperty(name = "spring.cache.type", havingValue = "redis")
class RedisPortablePreviewStore implements PortablePreviewStore {
    private static final String PREFIX = "nav:portable-import:preview:pending:slot:";
    private static final String RESERVE = """
            local now = tonumber(ARGV[2])
            local total = 0
            local free = nil
            for i = 1, 8 do
              local raw = redis.call('get', KEYS[i])
              local item = raw and cjson.decode(raw) or nil
              if not item or item.retainUntilMillis <= now then
                for chunk = 1, 64 do redis.call('del', KEYS[8 + (i - 1) * 64 + chunk]) end
                if raw then redis.call('del', KEYS[i]) end
                if not free then free = i end
              else
                total = total + 2 * item.archiveBytes + 67108864
              end
            end
            if ARGV[1] == 'cleanup' then return 1 end
            local item = cjson.decode(ARGV[4])
            if not free or total + tonumber(ARGV[3]) > 536870912 then return -1 end
            if item.expiresAtMillis <= now then return -2 end
            item.slot = free - 1
            redis.call('psetex', KEYS[free], 86400000, cjson.encode(item))
            return free
            """;
    private static final String FIND = """
            for i = 1, #KEYS do
              local raw = redis.call('get', KEYS[i])
              if raw then
                local item = cjson.decode(raw)
                if item.token == ARGV[1] and tostring(item.userId) == ARGV[2] and item.ready
                  and (item.activeJobId == cjson.null or not item.activeJobId)
                  and item.expiresAtMillis > tonumber(ARGV[3]) then return raw end
              end
            end
            return nil
            """;
    private static final String PUT_CHUNK = """
            local raw = redis.call('get', KEYS[1])
            if not raw then return -1 end
            local item = cjson.decode(raw)
            if item.token ~= ARGV[1] or item.ready or item.retainUntilMillis <= tonumber(ARGV[2]) then return -1 end
            local offset = tonumber(ARGV[3]) * 1048576
            local size = math.min(1048576, item.archiveBytes - offset)
            if size <= 0 or #ARGV[4] ~= size then return -1 end
            redis.call('psetex', KEYS[2], 86400000, ARGV[4])
            item.retainUntilMillis = tonumber(ARGV[2]) + 86400000
            redis.call('psetex', KEYS[1], 86400000, cjson.encode(item))
            return 1
            """;
    private static final String PUBLISH = """
            local raw = redis.call('get', KEYS[1])
            if not raw then return -1 end
            local item = cjson.decode(raw)
            if item.token ~= ARGV[1] or item.ready or item.retainUntilMillis <= tonumber(ARGV[2]) then return -1 end
            for i = 2, #KEYS do
              local chunk = redis.call('get', KEYS[i])
              if not chunk or #chunk ~= math.min(1048576, item.archiveBytes - (i - 2) * 1048576) then return -1 end
            end
            local published = cjson.decode(ARGV[3])
            redis.call('psetex', KEYS[1], 900000, cjson.encode(published))
            return 1
            """;
    private static final String ACTIVATE = """
            local raw = redis.call('get', KEYS[1])
            if not raw then return -1 end
            local item = cjson.decode(raw)
            local now = tonumber(ARGV[2])
            if item.token ~= ARGV[1] or item.retainUntilMillis <= now then return -1 end
            if ARGV[4] == 'processing' then
              if item.ready or (item.activeJobId and item.activeJobId ~= cjson.null) then return -1 end
              item.retainUntilMillis = now + 86400000
              redis.call('psetex', KEYS[1], 86400000, cjson.encode(item))
              return 1
            end
            if not item.ready then return -1 end
            if ARGV[4] == 'activate' then
              if item.expiresAtMillis <= now or (item.activeJobId and item.activeJobId ~= cjson.null) then return -1 end
            elseif item.activeJobId ~= ARGV[3] then return -1 end
            for i = 2, #KEYS do
              if redis.call('pexpire', KEYS[i], 86400000) ~= 1 then return -1 end
            end
            item.activeJobId = ARGV[3]
            item.retainUntilMillis = now + 86400000
            redis.call('psetex', KEYS[1], 86400000, cjson.encode(item))
            return 1
            """;
    private static final String GET_CHUNK = """
            local raw = redis.call('get', KEYS[1])
            if not raw then return nil end
            local item = cjson.decode(raw)
            if item.token ~= ARGV[1] or item.activeJobId ~= ARGV[2]
              or item.retainUntilMillis <= tonumber(ARGV[3]) then return nil end
            return redis.call('get', KEYS[2])
            """;
    private static final String RELEASE = """
            local raw = redis.call('get', KEYS[1])
            if not raw then return 0 end
            local item = cjson.decode(raw)
            if item.token ~= ARGV[1] then return 0 end
            local active = item.activeJobId and item.activeJobId ~= cjson.null and item.activeJobId or ''
            if active ~= ARGV[2] then return 0 end
            for i = 2, #KEYS do redis.call('del', KEYS[i]) end
            redis.call('del', KEYS[1])
            return 1
            """;

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Autowired
    RedisPortablePreviewStore(StringRedisTemplate redis, ObjectMapper mapper) {
        this(redis, mapper, Clock.systemUTC());
    }

    RedisPortablePreviewStore(StringRedisTemplate redis, ObjectMapper mapper, Clock clock) {
        this.redis = redis;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public Entry reserve(String token, long userId, long bytes, Instant expiresAt) {
        Entry request = new Entry(-1, token, userId, bytes, null, null, expiresAt.toEpochMilli(),
                false, null, clock.instant().plus(ACTIVE_TTL).toEpochMilli());
        Long slot = integer(RESERVE, allKeys(), utf8("reserve"), utf8(clock.millis()),
                utf8(request.reservedBytes()), json(request));
        if (slot == null) throw unavailable("无法确认预检容量是否已预留", null);
        if (slot == -2) throw PortablePreviewStore.missing();
        if (slot < 0) throw PortablePreviewStore.full();
        if (slot < 1 || slot > MAX_PREVIEWS) throw unavailable("预检预留结果无效", null);
        return new Entry(Math.toIntExact(slot - 1), token, userId, bytes, null, null,
                expiresAt.toEpochMilli(), false, null, request.retainUntilMillis());
    }

    @Override
    public Entry publish(Entry reservation, String sha256, String revision, Path archive, Runnable releaseWorkspace) {
        if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) throw PortablePreviewStore.missing();
        try {
            try (var input = Files.newInputStream(archive, LinkOption.NOFOLLOW_LINKS)) {
                for (int chunk = 0; chunk < reservation.chunks(); chunk++) {
                    int expected = (int) Math.min(CHUNK_BYTES, reservation.archiveBytes() - (long) chunk * CHUNK_BYTES);
                    byte[] bytes = input.readNBytes(expected);
                    if (bytes.length != expected) throw new IOException("预检归档大小变化");
                    requireOne(integer(PUT_CHUNK, List.of(metadataKey(reservation.slot()), chunkKey(reservation.slot(), chunk)),
                            utf8(reservation.token()), utf8(clock.millis()), utf8(chunk), bytes));
                }
                if (input.read() != -1) throw new IOException("预检归档大小变化");
            }
            releaseWorkspace.run();
            Entry published = reservation.published(sha256, revision, clock.instant());
            requireOne(integer(PUBLISH, entryKeys(reservation, false), utf8(reservation.token()),
                    utf8(clock.millis()), json(published)));
            return published;
        } catch (IOException failure) { throw unavailable("无法共享导入预检归档", failure); }
    }

    @Override
    public Optional<Entry> find(String token, long userId) {
        byte[] result = value(FIND, metadataKeys(), utf8(token), utf8(userId), utf8(clock.millis()));
        if (result == null) return Optional.empty();
        try {
            if (result.length > 4096) throw new IOException("预检元数据超过限制");
            Entry entry = mapper.readValue(result, Entry.class);
            if (!token.equals(entry.token()) || userId != entry.userId() || entry.slot() < 0 || entry.slot() >= MAX_PREVIEWS) {
                throw new IOException("预检归属无效");
            }
            entry.reservedBytes();
            return Optional.of(entry);
        } catch (IOException failure) { throw unavailable("无法读取共享预检", failure); }
    }

    @Override
    public Entry activate(Entry preview, String jobId) {
        requireOne(integer(ACTIVATE, entryKeys(preview, false), utf8(preview.token()),
                utf8(clock.millis()), utf8(jobId), utf8("activate")));
        return preview.active(jobId, clock.instant());
    }

    @Override
    public void copyArchive(Entry preview, Path target) {
        if (preview.activeJobId() == null) throw PortablePreviewStore.missing();
        try (var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            for (int chunk = 0; chunk < preview.chunks(); chunk++) {
                byte[] bytes = value(GET_CHUNK, List.of(metadataKey(preview.slot()), chunkKey(preview.slot(), chunk)),
                        utf8(preview.token()), utf8(preview.activeJobId()), utf8(clock.millis()));
                long expected = Math.min(CHUNK_BYTES, preview.archiveBytes() - (long) chunk * CHUNK_BYTES);
                if (bytes == null || bytes.length != expected) throw PortablePreviewStore.missing();
                output.write(bytes);
            }
        } catch (IOException failure) { throw unavailable("无法重建共享预检归档", failure); }
        try { PortablePreviewWorkspace.privateFile(target); }
        catch (IOException failure) { throw unavailable("无法保护预检工作文件", failure); }
    }

    @Override
    public void renew(Entry preview) {
        if (preview.activeJobId() == null) throw PortablePreviewStore.missing();
        requireOne(integer(ACTIVATE, entryKeys(preview, false), utf8(preview.token()),
                utf8(clock.millis()), utf8(preview.activeJobId()), utf8("renew")));
    }

    @Override
    public void renewProcessing(Entry reservation) {
        requireOne(integer(ACTIVATE, List.of(metadataKey(reservation.slot())), utf8(reservation.token()),
                utf8(clock.millis()), utf8(""), utf8("processing")));
    }

    @Override
    public void release(Entry preview) {
        if (integer(RELEASE, entryKeys(preview, true), utf8(preview.token()),
                utf8(preview.activeJobId() == null ? "" : preview.activeJobId())) == null) {
            throw unavailable("无法确认预检容量是否已释放", null);
        }
    }

    @Override
    public void cleanupExpired() { requireOne(integer(RESERVE, allKeys(), utf8("cleanup"), utf8(clock.millis()))); }

    private List<String> metadataKeys() {
        List<String> keys = new ArrayList<>();
        for (int slot = 0; slot < MAX_PREVIEWS; slot++) keys.add(metadataKey(slot));
        return keys;
    }

    private List<String> allKeys() {
        List<String> keys = metadataKeys();
        for (int slot = 0; slot < MAX_PREVIEWS; slot++) {
            for (int chunk = 0; chunk < MAX_CHUNKS; chunk++) keys.add(chunkKey(slot, chunk));
        }
        return keys;
    }

    private List<String> entryKeys(Entry entry, boolean allChunks) {
        List<String> keys = new ArrayList<>(List.of(metadataKey(entry.slot())));
        for (int chunk = 0; chunk < (allChunks ? MAX_CHUNKS : entry.chunks()); chunk++) keys.add(chunkKey(entry.slot(), chunk));
        return keys;
    }

    static String metadataKey(int slot) { return PREFIX + slot; }
    static String chunkKey(int slot, int chunk) { return metadataKey(slot) + ":chunk:" + chunk; }

    private Long integer(String script, List<String> keys, byte[]... args) {
        return redis.execute((RedisCallback<Long>) connection -> connection.scriptingCommands().eval(
                utf8(script), ReturnType.INTEGER, keys.size(), arguments(keys, args)));
    }

    private byte[] value(String script, List<String> keys, byte[]... args) {
        return redis.execute((RedisCallback<byte[]>) connection -> connection.scriptingCommands().eval(
                utf8(script), ReturnType.VALUE, keys.size(), arguments(keys, args)));
    }

    private byte[][] arguments(List<String> keys, byte[][] args) {
        List<byte[]> values = new ArrayList<>(keys.size() + args.length);
        keys.forEach(key -> values.add(utf8(key)));
        values.addAll(Arrays.asList(args));
        return values.toArray(byte[][]::new);
    }

    private byte[] json(Entry entry) {
        try { return mapper.writeValueAsBytes(entry); }
        catch (JsonProcessingException failure) { throw unavailable("无法编码预检元数据", failure); }
    }

    private static byte[] utf8(Object value) { return value.toString().getBytes(StandardCharsets.UTF_8); }
    private void requireOne(Long result) {
        if (result == null) throw unavailable("共享预检操作结果暂时无法确认", null);
        if (result != 1) throw PortablePreviewStore.missing();
    }
    private BusinessException unavailable(String message, Exception cause) {
        BusinessException exception = new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, message);
        if (cause != null) exception.initCause(cause);
        return exception;
    }
}
