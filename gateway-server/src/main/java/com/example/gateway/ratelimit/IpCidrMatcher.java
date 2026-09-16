package com.example.gateway.ratelimit;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * IP 白名单匹配器，支持单 IP（IPv4/IPv6）与 CIDR 表示法（如 192.168.0.0/16、10.0.0.0/8、::1/128）。
 */
public final class IpCidrMatcher {

    private final InetAddress network;
    private final int prefixBits;

    private IpCidrMatcher(InetAddress network, int prefixBits) {
        this.network = network;
        this.prefixBits = prefixBits;
    }

    /**
     * 编译一条白名单规则。
     *
     * @param rule 单 IP 或 CIDR
     * @throws IllegalArgumentException 规则非法时抛出
     */
    public static IpCidrMatcher compile(String rule) {
        String trimmed = rule.trim();
        int slash = trimmed.indexOf('/');
        try {
            if (slash < 0) {
                InetAddress addr = InetAddress.getByName(trimmed);
                return new IpCidrMatcher(addr, addr.getAddress().length * 8);
            }
            InetAddress addr = InetAddress.getByName(trimmed.substring(0, slash));
            int prefix = Integer.parseInt(trimmed.substring(slash + 1));
            int maxBits = addr.getAddress().length * 8;
            if (prefix < 0 || prefix > maxBits) {
                throw new IllegalArgumentException("CIDR 前缀长度非法: " + rule);
            }
            return new IpCidrMatcher(addr, prefix);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("无法解析的 IP 白名单规则: " + rule, e);
        }
    }

    /** 判断给定 IP 是否命中本规则。 */
    public boolean matches(InetAddress address) {
        byte[] ruleBytes = network.getAddress();
        byte[] targetBytes = address.getAddress();
        if (ruleBytes.length != targetBytes.length) {
            return false; // IPv4 / IPv6 不混比
        }
        int fullBytes = prefixBits / 8;
        int remainBits = prefixBits % 8;
        for (int i = 0; i < fullBytes; i++) {
            if (ruleBytes[i] != targetBytes[i]) {
                return false;
            }
        }
        if (remainBits == 0) {
            return true;
        }
        int mask = 0xFF << (8 - remainBits);
        return (ruleBytes[fullBytes] & mask) == (targetBytes[fullBytes] & mask);
    }
}
