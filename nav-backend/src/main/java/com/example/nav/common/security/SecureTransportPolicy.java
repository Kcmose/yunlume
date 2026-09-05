package com.example.nav.common.security;

import com.example.nav.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;

public final class SecureTransportPolicy {

    private final boolean trustForwardedHttps;
    private final List<String> trustedProxyPeers;

    public SecureTransportPolicy(boolean trustForwardedHttps, String trustedProxyPeers) {
        this.trustForwardedHttps = trustForwardedHttps;
        this.trustedProxyPeers = Arrays.stream(trustedProxyPeers == null ? new String[0] : trustedProxyPeers.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    public void requireSecure(HttpServletRequest request, String sensitiveResource) {
        if (request != null && (request.isSecure() || isForwardedHttps(request))) return;

        throw new BusinessException(HttpStatus.FORBIDDEN,
                sensitiveResource + "包含敏感凭据，只允许通过 HTTPS 提交");
    }

    public boolean isForwardedHttps(HttpServletRequest request) {
        String forwardedProto = trustForwardedHttps && request != null && isTrustedProxyPeer(request.getRemoteAddr())
                ? request.getHeader("X-Forwarded-Proto")
                : null;
        return forwardedProto != null
                && "https".equalsIgnoreCase(forwardedProto.split(",", 2)[0].trim());
    }

    private boolean isTrustedProxyPeer(String remoteAddress) {
        if (remoteAddress == null || remoteAddress.isBlank() || trustedProxyPeers.isEmpty()) return false;
        try {
            InetAddress remote = InetAddress.getByName(remoteAddress);
            for (String peer : trustedProxyPeers) {
                for (InetAddress trusted : InetAddress.getAllByName(peer)) {
                    if (remote.equals(trusted)) return true;
                }
            }
        } catch (UnknownHostException ignored) {
            return false;
        }
        return false;
    }
}
