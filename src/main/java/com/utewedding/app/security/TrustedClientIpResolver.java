package com.utewedding.app.security;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Resolves a client address without trusting forwarded headers from the public internet. */
@Component
public class TrustedClientIpResolver {
    private final List<Cidr> trustedProxies;

    @Autowired
    public TrustedClientIpResolver(
            @Value("${app.trusted-proxy-cidrs:127.0.0.1/32,::1/128}") String configuredCidrs) {
        this.trustedProxies = parseCidrs(configuredCidrs);
    }

    /** Constructor intended for focused tests and non-Spring callers. */
    TrustedClientIpResolver(List<String> configuredCidrs) {
        this.trustedProxies = configuredCidrs.stream().map(Cidr::parse).toList();
    }

    static TrustedClientIpResolver loopbackOnly() {
        return new TrustedClientIpResolver(List.of("127.0.0.1/32", "::1/128"));
    }

    public String resolve(HttpServletRequest request) {
        String remote = normalize(request.getRemoteAddr());
        if (remote == null || !isTrusted(remote)) return fallback(remote);

        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) return fallback(remote);
        String[] hops = forwarded.split(",", -1);
        List<String> addresses = new ArrayList<>(hops.length);
        for (String hop : hops) {
            String address = normalize(hop);
            if (address == null) return fallback(remote);
            addresses.add(address);
        }
        for (int i = addresses.size() - 1; i >= 0; i--) {
            String address = addresses.get(i);
            if (!isTrusted(address)) return address;
        }
        return fallback(remote);
    }

    boolean isTrustedProxy(String address) {
        String remote = normalize(address);
        return remote != null && isTrusted(remote);
    }

    private String fallback(String remote) { return remote == null ? "unknown" : remote; }

    private boolean isTrusted(String address) {
        try {
            InetAddress parsed = InetAddress.getByName(address);
            return trustedProxies.stream().anyMatch(cidr -> cidr.contains(parsed));
        } catch (UnknownHostException exception) {
            return false;
        }
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String candidate = value.trim();
        if (candidate.isEmpty() || candidate.startsWith("[") || candidate.endsWith("]")
                || candidate.contains("%") || !isIpLiteral(candidate)) return null;
        try {
            return InetAddress.getByName(candidate).getHostAddress();
        } catch (UnknownHostException exception) {
            return null;
        }
    }

    private static boolean isIpLiteral(String value) {
        if (value.indexOf(':') >= 0) return true;
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) return false;
        for (String octet : octets) {
            if (octet.isEmpty() || !octet.chars().allMatch(Character::isDigit)) return false;
            try {
                if (Integer.parseInt(octet) > 255) return false;
            } catch (NumberFormatException exception) {
                return false;
            }
        }
        return true;
    }

    private static List<Cidr> parseCidrs(String configured) {
        List<Cidr> result = new ArrayList<>();
        for (String value : configured == null ? new String[0] : configured.split(",")) {
            if (!value.isBlank()) result.add(Cidr.parse(value.trim()));
        }
        if (result.isEmpty()) throw new IllegalArgumentException("At least one trusted proxy CIDR is required");
        return List.copyOf(result);
    }

    /** Validates the reverse-proxy allowlist during production startup. */
    public static void validateConfiguredCidrs(String configured) {
        parseCidrs(configured);
    }

    private record Cidr(byte[] network, int prefixLength) {
        static Cidr parse(String value) {
            String[] parts = value.split("/", -1);
            if (parts.length != 2) throw new IllegalArgumentException("Invalid trusted proxy CIDR: " + value);
            try {
                if (!isIpLiteral(parts[0].trim())) {
                    throw new IllegalArgumentException("Invalid trusted proxy CIDR: " + value);
                }
                InetAddress address = InetAddress.getByName(parts[0]);
                int prefix = Integer.parseInt(parts[1]);
                if (prefix < 0 || prefix > address.getAddress().length * 8) {
                    throw new IllegalArgumentException("Invalid trusted proxy CIDR: " + value);
                }
                byte[] network = address.getAddress().clone();
                int fullBytes = prefix / 8;
                int remainingBits = prefix % 8;
                if (remainingBits != 0) network[fullBytes] &= (byte) (0xff << (8 - remainingBits));
                for (int i = fullBytes + (remainingBits == 0 ? 0 : 1); i < network.length; i++) network[i] = 0;
                return new Cidr(network, prefix);
            } catch (NumberFormatException | UnknownHostException exception) {
                throw new IllegalArgumentException("Invalid trusted proxy CIDR: " + value, exception);
            }
        }

        boolean contains(InetAddress address) {
            byte[] candidate = address.getAddress();
            if (candidate.length != network.length) return false;
            int fullBytes = prefixLength / 8;
            for (int i = 0; i < fullBytes; i++) if (candidate[i] != network[i]) return false;
            int remainingBits = prefixLength % 8;
            return remainingBits == 0
                    || (candidate[fullBytes] & (0xff << (8 - remainingBits)))
                    == (network[fullBytes] & (0xff << (8 - remainingBits)));
        }
    }
}
