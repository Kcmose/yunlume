package com.example.nav.common.config;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;

/** 明文 PostgreSQL 只连接经过校验的私网数字地址，不让驱动再次解析域名。 */
public final class PostgresqlPrivateNetwork {
    private PostgresqlPrivateNetwork() {}

    public static List<String> resolve(String host) {
        try {
            if (host == null || host.isBlank()) throw new IllegalArgumentException();
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0 || addresses.length > 16
                    || Arrays.stream(addresses).anyMatch(address -> !isPrivate(address))) {
                throw new IllegalArgumentException();
            }
            return Arrays.stream(addresses).map(InetAddress::getHostAddress)
                    .map(value -> value.toLowerCase(java.util.Locale.ROOT)).distinct().sorted().toList();
        } catch (UnknownHostException | IllegalArgumentException exception) {
            throw new IllegalStateException("明文 PostgreSQL 只允许可解析的可信私网地址", exception);
        }
    }

    public static boolean isPrivate(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 255, second = bytes[1] & 255;
            return first == 10 || (first == 172 && second >= 16 && second <= 31)
                    || (first == 192 && second == 168);
        }
        // IPv6 ULA，排除云实例元数据地址。
        return bytes.length == 16 && (bytes[0] & 254) == 252
                && !address.getHostAddress().equalsIgnoreCase("fd00:ec2:0:0:0:0:0:254");
    }

    public static String authority(String address, int port) {
        return (address.contains(":") ? "[" + address + "]" : address) + ":" + port;
    }
}
