package com.example.nav.security;

import com.example.nav.common.result.Result;
import com.example.nav.module.user.entity.User;
import com.example.nav.module.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenService tokenService;
    private final UserMapper userMapper;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationFilter(JwtTokenService tokenService, UserMapper userMapper, ObjectMapper objectMapper) {
        this.tokenService = tokenService;
        this.userMapper = userMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (!contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        return path.equals("/api/health")
                || ("GET".equalsIgnoreCase(request.getMethod()) && path.equals("/api/install/status"))
                || ("POST".equalsIgnoreCase(request.getMethod())
                    && (path.equals("/api/install/check")
                    || path.equals("/api/install/complete")
                    || path.equals("/api/install/database/test")
                    || path.equals("/api/install/database/configure")
                    || path.equals("/api/install/redis/test")
                    || path.equals("/api/install/redis/configure")))
                || path.equals("/api/admin/auth/login")
                || isPathOrChild(path, "/api/public")
                || path.equals("/swagger-ui.html")
                || isPathOrChild(path, "/swagger-ui")
                || isPathOrChild(path, "/v3/api-docs")
                || isPathOrChild(path, "/h2-console");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            var claims = tokenService.parse(authorization.substring(7));
            String role = claims.get("role", String.class);
            if (claims.getSubject() == null || claims.getSubject().isBlank() || role == null || role.isBlank()) {
                throw new IllegalArgumentException("Token claims are incomplete");
            }

            long userId = integralClaim(claims.get("userId"), "userId");
            long claimedTokenVersion = claims.get("ver") == null
                    ? 0
                    : integralClaim(claims.get("ver"), "ver");
            if (userId <= 0 || claimedTokenVersion < 0 || claimedTokenVersion > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Token claims are outside their supported ranges");
            }
            int tokenVersion = (int) claimedTokenVersion;
            User user = userMapper.selectById(userId);
            int currentTokenVersion = user == null || user.getTokenVersion() == null ? 0 : user.getTokenVersion();
            if (user == null
                    || !Boolean.TRUE.equals(user.getStatus())
                    || user.getRole() == null
                    || user.getRole().isBlank()
                    || !claims.getSubject().equals(user.getUsername())
                    || tokenVersion != currentTokenVersion) {
                throw new IllegalArgumentException("Token no longer matches an active user session");
            }
            var authentication = new UsernamePasswordAuthenticationToken(
                    user.getUsername(),
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().toUpperCase(Locale.ROOT)))
            );
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
        } catch (JwtException | IllegalArgumentException exception) {
            SecurityContextHolder.clearContext();
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getWriter(), Result.error(401, "Token 无效或已过期"));
        }
    }

    private boolean isPathOrChild(String path, String root) {
        return path.equals(root) || path.startsWith(root + "/");
    }

    private long integralClaim(Object value, String name) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("Token claim " + name + " is not numeric");
        }
        long integralValue = number.longValue();
        if (!Double.isFinite(number.doubleValue()) || number.doubleValue() != (double) integralValue) {
            throw new IllegalArgumentException("Token claim " + name + " is not an integer");
        }
        return integralValue;
    }
}
