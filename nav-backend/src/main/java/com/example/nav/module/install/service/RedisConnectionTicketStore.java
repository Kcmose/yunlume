package com.example.nav.module.install.service;

import com.example.nav.common.config.RedisInstallProperties;
import com.example.nav.common.exception.BusinessException;
import com.example.nav.module.install.model.RedisConnectionSpec;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class RedisConnectionTicketStore {

    private static final int TICKET_BYTES = 32;
    private static final int MAX_TICKETS = 3;

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();
    private final AtomicLong generation = new AtomicLong();
    private final Clock clock;
    private final long ttlSeconds;
    private final ScheduledExecutorService expiryExecutor;

    @Autowired
    public RedisConnectionTicketStore(RedisInstallProperties properties) {
        this(properties, Clock.systemUTC(), 30);
    }

    RedisConnectionTicketStore(RedisInstallProperties properties, Clock clock) {
        this(properties, clock, 30);
    }

    RedisConnectionTicketStore(
            RedisInstallProperties properties,
            Clock clock,
            long minimumTtlSeconds
    ) {
        this.clock = clock;
        this.ttlSeconds = Math.max(minimumTtlSeconds,
                Math.min(properties.getTicketTtlSeconds(), 900));
        this.expiryExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "redis-ticket-expiry");
            thread.setDaemon(true);
            return thread;
        });
    }

    public synchronized IssuedTicket issue(
            RedisConnectionSpec spec,
            String configurationDigest,
            String databaseInstanceId
    ) {
        cleanupExpired();
        if (tickets.size() >= MAX_TICKETS) {
            throw BusinessException.conflict("Redis 连接测试过于频繁，请稍后重试");
        }
        byte[] bytes = new byte[TICKET_BYTES];
        random.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        Instant expiresAt = clock.instant().plusSeconds(ttlSeconds);
        long issuedGeneration = generation.get();
        tickets.put(token, new Ticket(
                spec, configurationDigest, databaseInstanceId, expiresAt, issuedGeneration));
        expiryExecutor.schedule(() -> tickets.computeIfPresent(token, (key, ticket) ->
                        ticket.generation() == issuedGeneration && ticket.expiresAt().equals(expiresAt)
                                ? null : ticket),
                ttlSeconds, TimeUnit.SECONDS);
        return new IssuedTicket(token, expiresAt);
    }

    public synchronized Ticket consume(String token) {
        Ticket ticket = token == null ? null : tickets.remove(token);
        if (ticket == null
                || !ticket.expiresAt().isAfter(clock.instant())
                || ticket.generation() != generation.get()) {
            throw BusinessException.conflict("Redis 连接票据不存在、已使用或已过期，请重新测试连接");
        }
        return ticket;
    }

    public synchronized void advanceGeneration() {
        generation.incrementAndGet();
        tickets.clear();
    }

    @PreDestroy
    void shutdownExpiryExecutor() {
        tickets.clear();
        expiryExecutor.shutdownNow();
    }

    int activeTicketCount() {
        return tickets.size();
    }

    private void cleanupExpired() {
        Instant now = clock.instant();
        long currentGeneration = generation.get();
        tickets.entrySet().removeIf(entry ->
                !entry.getValue().expiresAt().isAfter(now)
                        || entry.getValue().generation() != currentGeneration);
    }

    public record IssuedTicket(String token, Instant expiresAt) {
    }

    public record Ticket(
            RedisConnectionSpec spec,
            String configurationDigest,
            String databaseInstanceId,
            Instant expiresAt,
            long generation
    ) {
    }
}
