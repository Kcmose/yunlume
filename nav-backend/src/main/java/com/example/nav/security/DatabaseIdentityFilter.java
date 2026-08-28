package com.example.nav.security;

import com.example.nav.common.result.Result;
import com.example.nav.module.install.service.DatabaseIdentityService;
import com.example.nav.module.install.service.DatabaseConfigurationStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class DatabaseIdentityFilter extends OncePerRequestFilter {

    private final DatabaseIdentityService identityService;
    private final DatabaseConfigurationStore configurationStore;
    private final ObjectMapper objectMapper;

    public DatabaseIdentityFilter(
            DatabaseIdentityService identityService,
            DatabaseConfigurationStore configurationStore,
            ObjectMapper objectMapper
    ) {
        this.identityService = identityService;
        this.configurationStore = configurationStore;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (!contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        return "OPTIONS".equalsIgnoreCase(request.getMethod())
                || path.equals("/api/health")
                || path.equals("/api/install/status")
                || path.equals("/api/install/database/test")
                || path.equals("/api/install/database/configure");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (request.getRequestURI().startsWith(request.getContextPath() + "/api/")
                && (configurationStore.isUnconfiguredSource()
                || configurationStore.hasInvalidOrPendingArtifact()
                || (configurationStore.hasPersistedConnection()
                    && !identityService.isIdentityRequired())
                || !identityService.ensureVerified())) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getWriter(), Result.error(503, "数据库实例身份无法验证"));
            return;
        }
        filterChain.doFilter(request, response);
    }
}
