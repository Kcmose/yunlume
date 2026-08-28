package com.example.nav.module.install.dto;

import com.example.nav.module.install.model.RedisTlsMode;
import jakarta.validation.constraints.Size;

public record RedisConnectionDTO(
        @Size(max = 253, message = "Redis 主机名过长")
        String host,

        Integer port,

        @Size(max = 128, message = "Redis 用户名过长")
        String username,

        @Size(max = 1024, message = "Redis 密码过长")
        String password,

        Integer database,

        RedisTlsMode tlsMode,

        @Size(max = 65536, message = "CA 证书不能超过 64KiB")
        String caCertificatePem,

        Boolean acknowledgeInsecureTransport,

        Integer connectTimeoutSeconds,

        Integer readTimeoutSeconds
) {
    @Override
    public String toString() {
        return "RedisConnectionDTO[host=<redacted>, port=" + port
                + ", username=<redacted>, password=<redacted>, database=" + database
                + ", tlsMode=" + tlsMode + ", caCertificatePem=<redacted>"
                + ", acknowledgeInsecureTransport=" + acknowledgeInsecureTransport
                + ", connectTimeoutSeconds=" + connectTimeoutSeconds
                + ", readTimeoutSeconds=" + readTimeoutSeconds + "]";
    }
}
