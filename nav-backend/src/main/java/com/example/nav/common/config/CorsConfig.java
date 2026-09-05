package com.example.nav.common.config;

import com.example.nav.common.security.SecureTransportPolicy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;
import java.net.URI;
import java.net.URISyntaxException;

@Configuration
public class CorsConfig {

    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties properties, DatabaseInstallProperties proxyProperties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(properties.getAllowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                "Authorization", "Content-Type", "Accept"));
        configuration.setExposedHeaders(List.of("Authorization", "Content-Disposition"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        SecureTransportPolicy transport = new SecureTransportPolicy(
                proxyProperties.isTrustForwardedHttps(), proxyProperties.getTrustedProxyPeers());
        return request -> {
            CorsConfiguration configured = source.getCorsConfiguration(request);
            if (configured == null || !transport.isForwardedHttps(request) || !isExternalSameOrigin(request)) {
                return configured;
            }
            // TLS 在可信代理终止时，Servlet 仍看见 HTTP；只补充原 Host 的 HTTPS 同源，
            // 不信任 X-Forwarded-Host/Port，也不扩大其他跨域来源的白名单。
            CorsConfiguration sameOrigin = new CorsConfiguration(configured);
            sameOrigin.addAllowedOrigin(request.getHeader("Origin"));
            return sameOrigin;
        };
    }

    private boolean isExternalSameOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        String host = request.getHeader("Host");
        if (origin == null || host == null || host.isBlank()) return false;
        try {
            URI external = new URI("https://" + host);
            URI candidate = new URI(origin);
            return isAuthorityOnly(external) && isAuthorityOnly(candidate)
                    && "https".equalsIgnoreCase(candidate.getScheme())
                    && external.getHost().equalsIgnoreCase(candidate.getHost())
                    && httpsPort(external) == httpsPort(candidate);
        } catch (URISyntaxException error) {
            return false;
        }
    }

    private boolean isAuthorityOnly(URI uri) {
        return uri.getHost() != null && uri.getUserInfo() == null
                && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                && uri.getRawQuery() == null && uri.getRawFragment() == null
                && uri.getPort() >= -1 && uri.getPort() <= 65535;
    }

    private int httpsPort(URI uri) {
        return uri.getPort() == -1 ? 443 : uri.getPort();
    }
}
