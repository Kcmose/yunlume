package com.example.nav.common.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.CorsFilter;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class CorsConfigTest {
    @ParameterizedTest
    @CsvSource({
            "true,127.0.0.1,https,nav.example,https://nav.example,200",
            "true,127.0.0.1,https,nav.example:443,https://nav.example,200",
            "true,127.0.0.1,https,nav.example:8443,https://nav.example:8443,200",
            "true,127.0.0.1,https,[::1]:8443,https://[::1]:8443,200",
            "false,127.0.0.1,https,nav.example,https://nav.example,403",
            "true,203.0.113.20,https,nav.example,https://nav.example,403",
            "true,127.0.0.1,http,nav.example,https://nav.example,403",
            "true,127.0.0.1,https,nav.example,https://evil.example,403",
            "true,127.0.0.1,https,nav.example:8443,https://nav.example,403",
            "true,127.0.0.1,https,nav.example,https://nav.example/path,403",
            "true,127.0.0.1,https,nav.example,https://user@nav.example,403",
            "true,127.0.0.1,https,nav.example,null,403"
    })
    void onlyAllowsTheOriginalHttpsHostThroughATrustedImmediateProxy(
            boolean trust, String peer, String proto, String host, String origin, int expected
    ) throws Exception {
        DatabaseInstallProperties proxy = new DatabaseInstallProperties();
        proxy.setTrustForwardedHttps(trust);
        proxy.setTrustedProxyPeers("127.0.0.1");
        CorsFilter filter = new CorsFilter(new CorsConfig().corsConfigurationSource(new CorsProperties(), proxy));
        MockHttpServletRequest request = request(peer, proto, host, origin);
        // 即使伪造其他转发字段与 Origin 一致，也不能改变原 Host 的信任边界。
        request.addHeader("X-Forwarded-Host", "evil.example");
        request.addHeader("X-Forwarded-Port", "443");
        request.addHeader("Forwarded", "proto=https;host=evil.example");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();
        filter.doFilter(request, response, (incoming, outgoing) -> reached.set(true));
        assertThat(response.getStatus()).isEqualTo(expected);
        assertThat(reached.get()).isEqualTo(expected == 200);
        if (expected == 200) assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo(origin);
    }

    @Test
    void retainsExplicitCrossOriginConfigurationAndDoesNotShareDynamicOriginsAcrossRequests() throws Exception {
        CorsProperties cors = new CorsProperties();
        cors.setAllowedOrigins(List.of("https://admin.example"));
        DatabaseInstallProperties proxy = new DatabaseInstallProperties();
        proxy.setTrustForwardedHttps(true);
        proxy.setTrustedProxyPeers("127.0.0.1");
        CorsFilter filter = new CorsFilter(new CorsConfig().corsConfigurationSource(cors, proxy));
        MockHttpServletResponse accepted = new MockHttpServletResponse();
        filter.doFilter(request("127.0.0.1", "https", "nav.example", "https://nav.example"), accepted, (a, b) -> {});
        assertThat(accepted.getStatus()).isEqualTo(200);

        MockHttpServletResponse untrusted = new MockHttpServletResponse();
        filter.doFilter(request("203.0.113.20", "https", "nav.example", "https://nav.example"), untrusted, (a, b) -> {});
        assertThat(untrusted.getStatus()).isEqualTo(403);

        MockHttpServletResponse explicit = new MockHttpServletResponse();
        filter.doFilter(request("203.0.113.20", "http", "nav.example", "https://admin.example"), explicit, (a, b) -> {});
        assertThat(explicit.getStatus()).isEqualTo(200);
    }

    private MockHttpServletRequest request(String peer, String proto, String host, String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/auth/login");
        request.setRemoteAddr(peer);
        request.setServerName("nav.example");
        request.setServerPort(8080);
        request.addHeader("Host", host);
        request.addHeader("Origin", origin);
        request.addHeader("X-Forwarded-Proto", proto);
        return request;
    }
}
