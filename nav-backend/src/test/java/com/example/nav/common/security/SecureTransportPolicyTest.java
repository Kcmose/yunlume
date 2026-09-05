package com.example.nav.common.security;

import com.example.nav.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecureTransportPolicyTest {

    @Test
    void spoofedForwardedProtoIsRejectedWithoutATrustedPeer() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.20");
        request.addHeader("X-Forwarded-Proto", "https");

        assertThatThrownBy(() -> new SecureTransportPolicy(true, "127.0.0.1")
                .requireSecure(request, "数据库配置"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void forwardedHttpsIsAcceptedOnlyFromConfiguredProxyPeer() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-Proto", "https");

        assertThatCode(() -> new SecureTransportPolicy(true, "127.0.0.1,::1")
                .requireSecure(request, "数据库配置"))
                .doesNotThrowAnyException();
    }

    @Test
    void directHttpsIsAlwaysAccepted() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSecure(true);
        request.setRemoteAddr("203.0.113.20");

        assertThatCode(() -> new SecureTransportPolicy(false, "")
                .requireSecure(request, "数据库配置"))
                .doesNotThrowAnyException();
    }
}
