package com.example.nav.security;

import com.example.nav.common.config.JwtProperties;
import com.example.nav.module.user.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

@Service
public class JwtTokenService {

    private final JwtProperties properties;
    private final SecretKey signingKey;

    public JwtTokenService(JwtProperties properties) {
        this.properties = properties;
        if (properties.getSecret() == null || properties.getSecret().getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("nav.jwt.secret must contain at least 32 UTF-8 bytes");
        }
        this.signingKey = Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String createToken(User user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.getExpirationMinutes(), ChronoUnit.MINUTES);
        return Jwts.builder()
                .subject(user.getUsername())
                .claim("userId", user.getId())
                .claim("role", user.getRole())
                .claim("ver", user.getTokenVersion() == null ? 0 : user.getTokenVersion())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public long getExpirationSeconds() {
        return properties.getExpirationMinutes() * 60;
    }
}
