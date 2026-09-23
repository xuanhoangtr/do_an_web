package com.utewedding.app.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** RS256 access-token signer with an active kid and a previous-key verification ring. */
@Component
public class JwtTokenProvider {

    private final PrivateKey signingKey;
    private final Map<String, PublicKey> verificationKeys;
    private final String activeKid;
    private final String issuer;
    private final String audience;
    private final Duration accessTokenTtl;

    @Autowired
    public JwtTokenProvider(
            @Value("${security.jwt.private-key:${JWT_PRIVATE_KEY:}}") String privateKey,
            @Value("${security.jwt.public-keys:${JWT_PUBLIC_KEYS:}}") String publicKeys,
            @Value("${security.jwt.active-kid:${JWT_ACTIVE_KID:primary-2026}}") String activeKid,
            @Value("${security.jwt.allow-ephemeral:${JWT_ALLOW_EPHEMERAL:false}}") boolean allowEphemeral,
            @Value("${security.jwt.issuer:${JWT_ISSUER:webthiep}}") String issuer,
            @Value("${security.jwt.audience:${JWT_AUDIENCE:webthiep-api}}") String audience,
            @Value("${security.jwt.access-ttl:${JWT_ACCESS_TTL:15m}}") Duration accessTokenTtl) {
        KeyMaterial material = loadKeyMaterial(privateKey, publicKeys, activeKid, allowEphemeral);
        this.signingKey = material.privateKey();
        this.verificationKeys = material.publicKeys();
        this.activeKid = material.activeKid();
        this.issuer = requireText(issuer, "JWT issuer");
        this.audience = requireText(audience, "JWT audience");
        if (accessTokenTtl.isZero() || accessTokenTtl.isNegative()) {
            throw new IllegalArgumentException("JWT access token TTL must be positive");
        }
        this.accessTokenTtl = accessTokenTtl;
    }

    /** Compatibility constructor retained for focused tests; it always uses an ephemeral RSA key. */
    public JwtTokenProvider(String legacySecret, String issuer, String audience, Duration accessTokenTtl) {
        if (legacySecret == null || legacySecret.isBlank()) {
            throw new IllegalArgumentException("JWT access secret must contain at least 256 bits");
        }
        KeyMaterial material = generateEphemeral("test");
        this.signingKey = material.privateKey();
        this.verificationKeys = material.publicKeys();
        this.activeKid = material.activeKid();
        this.issuer = requireText(issuer, "JWT issuer");
        this.audience = requireText(audience, "JWT audience");
        this.accessTokenTtl = accessTokenTtl;
    }

    public String createAccessToken(UserPrincipal principal) {
        return createAccessToken(principal.getId(), principal.getEmail(), principal.getRole(),
                principal.getSessionId() == null ? UUID.randomUUID().toString() : principal.getSessionId(),
                principal.getPasswordVersion());
    }

    public String createAccessToken(UUID userId, String email, AppRole role, String sessionId) {
        return createAccessToken(userId, email, role, sessionId, 0);
    }

    public String createAccessToken(UUID userId, String email, AppRole role, String sessionId,
                                    int passwordVersion) {
        java.time.Instant issuedAt = java.time.Instant.now();
        return Jwts.builder()
                .header().keyId(activeKid).and()
                .issuer(issuer)
                .subject(requireText(userId == null ? null : userId.toString(), "JWT subject"))
                .claim(Claims.AUDIENCE, audience)
                .claim("email", requireText(email, "JWT email"))
                .claim("role", role == null ? null : role.name())
                .claim("sid", requireText(sessionId, "JWT session id"))
                .claim("pwdv", passwordVersion)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(issuedAt.plus(accessTokenTtl)))
                .signWith(signingKey, Jwts.SIG.RS256)
                .compact();
    }

    public Claims validateAndGetClaims(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Bearer token is missing");
        }
        String kid = extractKid(token);
        PublicKey verificationKey = verificationKeys.get(kid);
        if (verificationKey == null) {
            throw new IllegalArgumentException("JWT key id is not trusted");
        }
        Claims claims = Jwts.parser()
                .verifyWith(verificationKey)
                .requireIssuer(issuer)
                .requireAudience(audience)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        requireText(claims.getSubject(), "JWT subject");
        requireText(claims.get("email", String.class), "JWT email");
        requireText(claims.get("sid", String.class), "JWT session id");
        Integer passwordVersion = claims.get("pwdv", Integer.class);
        if (passwordVersion == null || passwordVersion < 0) {
            throw new IllegalArgumentException("JWT password version is missing or invalid");
        }
        AppRole.from(claims.get("role", String.class));
        return claims;
    }

    public long getAccessTokenTtlSeconds() { return accessTokenTtl.toSeconds(); }
    public String getActiveKid() { return activeKid; }
    public Map<String, PublicKey> getVerificationKeys() { return verificationKeys; }

    public Map<String, Object> jwks() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("keys", verificationKeys.entrySet().stream()
                .map(entry -> jwk(entry.getKey(), entry.getValue())).toList());
        return result;
    }

    private Map<String, Object> jwk(String kid, PublicKey key) {
        java.security.interfaces.RSAPublicKey rsa = (java.security.interfaces.RSAPublicKey) key;
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("kty", "RSA");
        value.put("use", "sig");
        value.put("alg", "RS256");
        value.put("kid", kid);
        value.put("n", Base64.getUrlEncoder().withoutPadding()
                .encodeToString(unsigned(rsa.getModulus().toByteArray())));
        value.put("e", Base64.getUrlEncoder().withoutPadding()
                .encodeToString(unsigned(rsa.getPublicExponent().toByteArray())));
        return value;
    }

    private static byte[] unsigned(byte[] value) {
        return value.length > 1 && value[0] == 0 ? java.util.Arrays.copyOfRange(value, 1, value.length) : value;
    }

    private static KeyMaterial loadKeyMaterial(String privateValue, String publicValue,
                                                String configuredKid, boolean allowEphemeral) {
        configuredKid = requireText(configuredKid, "JWT active kid");
        privateValue = readConfiguredValue(privateValue);
        publicValue = readConfiguredValue(publicValue);
        if (privateValue == null || privateValue.isBlank()) {
            if (!allowEphemeral) {
                throw new IllegalArgumentException("JWT RS256 private key is required");
            }
            return generateEphemeral(configuredKid);
        }
        try {
            PrivateKey privateKey = parsePrivateKey(privateValue);
            Map<String, PublicKey> publicRing = parsePublicKeys(publicValue);
            if (publicRing.isEmpty()) {
                publicRing = new LinkedHashMap<>();
                publicRing.put(configuredKid, derivePublicKey(privateKey));
            } else if (!publicRing.containsKey(configuredKid)) {
                publicRing.put(configuredKid, derivePublicKey(privateKey));
            }
            return new KeyMaterial(privateKey, Map.copyOf(publicRing), configuredKid);
        } catch (Exception exception) {
            throw new IllegalArgumentException("JWT RS256 key configuration is invalid", exception);
        }
    }

    private static PrivateKey parsePrivateKey(String value) throws Exception {
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(decodeKey(value, "PRIVATE KEY")));
    }

    private static Map<String, PublicKey> parsePublicKeys(String value) throws Exception {
        Map<String, PublicKey> result = new LinkedHashMap<>();
        if (value == null || value.isBlank()) return result;
        for (String item : value.split(",")) {
            int separator = item.indexOf('=');
            if (separator <= 0) throw new IllegalArgumentException("JWT public key ring must use kid=base64 entries");
            String kid = item.substring(0, separator).trim();
            String keyValue = item.substring(separator + 1).trim();
            result.put(kid, KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(decodeKey(keyValue, "PUBLIC KEY"))));
        }
        return result;
    }

    private static byte[] decodeKey(String value, String type) {
        String normalized = value.trim();
        if (normalized.contains("BEGIN")) {
            normalized = normalized.replace("-----BEGIN " + type + "-----", "")
                    .replace("-----END " + type + "-----", "").replaceAll("\\s", "");
        }
        return Base64.getDecoder().decode(normalized);
    }

    private static String readConfiguredValue(String value) {
        if (value == null || value.isBlank() || !value.startsWith("file:")) return value;
        try {
            return Files.readString(Path.of(value.substring("file:".length()))).trim();
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException("Unable to read JWT key file", exception);
        }
    }

    private static PublicKey derivePublicKey(PrivateKey privateKey) throws Exception {
        if (!(privateKey instanceof RSAPrivateCrtKey rsa)) {
            throw new IllegalArgumentException("RS256 private key must be an RSA CRT key");
        }
        return KeyFactory.getInstance("RSA").generatePublic(
                new RSAPublicKeySpec(rsa.getModulus(), rsa.getPublicExponent()));
    }

    private static KeyMaterial generateEphemeral(String kid) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            return new KeyMaterial(pair.getPrivate(), Map.of(kid, pair.getPublic()), kid);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to create ephemeral RSA test key", exception);
        }
    }

    private static String extractKid(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) throw new IllegalArgumentException("JWT is malformed");
        String header = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\\"kid\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
                .matcher(header);
        if (!matcher.find()) throw new IllegalArgumentException("JWT key id is missing");
        return matcher.group(1);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }

    private record KeyMaterial(PrivateKey privateKey, Map<String, PublicKey> publicKeys, String activeKid) {
    }
}
