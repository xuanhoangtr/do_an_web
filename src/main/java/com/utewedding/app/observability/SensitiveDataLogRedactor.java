package com.utewedding.app.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Rewrites log text so secrets and guest-link capabilities are not retained. */
public final class SensitiveDataLogRedactor {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Pattern JSON_SECRET = Pattern.compile(
            "(?i)(\"(?:password|passwd|pwd|token|access_token|refresh_token|id_token|otp|"
                    + "secret|authorization|cookie|set-cookie|api[_-]?key|api[_-]?secret|"
                    + "hash[_-]?secret|access[_-]?key|secret[_-]?key|private[_-]?key|"
                    + "client[_-]?secret|cvv|email|phone|recipient)\"\\s*:\\s*)"
                    + "\"(?:\\\\.|[^\"\\\\])*\"");
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?i)\\b(password|passwd|pwd|token|access_token|refresh_token|otp|secret|"
                    + "authorization|cookie|api[_-]?key|api[_-]?secret|hash[_-]?secret|"
                    + "access[_-]?key|secret[_-]?key|private[_-]?key|vnp_securehash|signature|"
                    + "email|phone|recipient)\\b(\\s*[:=]\\s*)(?:\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|[^\\s\"',{}\\[\\]]+)");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]{8,}");
    private static final Pattern JWT = Pattern.compile(
            "\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{4,}");
    private static final Pattern PEM = Pattern.compile(
            "-----BEGIN [A-Z ]*PRIVATE KEY-----(?:\\\\n|[\\s\\S])*?-----END [A-Z ]*PRIVATE KEY-----");
    private static final Pattern OTP_PHRASE = Pattern.compile(
            "(?i)((?:mã|ma)(?:\\s+otp|\\s+xác thực|\\s+xac thuc)?(?:\\s+của bạn|\\s+cua ban)?\\s+là\\s*:\\s*)\\d{4,8}");
    private static final Pattern GUEST_LINK = Pattern.compile("(?i)(/k/)[A-Za-z0-9._~-]{4,}");
    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("(?<![A-Za-z0-9])(?:\\+84|0)\\d{9,10}\\b");

    private SensitiveDataLogRedactor() {
    }

    public static String redact(String value) {
        if (value == null || value.isEmpty()) return value;
        String candidate = value.contains("\\\"") ? value.replace("\\\"", "\"") : value;
        String structured = redactStructuredJson(candidate);
        if (structured != null) return structured;
        String redacted = PEM.matcher(candidate).replaceAll("***");
        redacted = JSON_SECRET.matcher(redacted).replaceAll("$1\"***\"");
        redacted = ASSIGNMENT.matcher(redacted).replaceAll("$1$2***");
        redacted = BEARER.matcher(redacted).replaceAll("Bearer ***");
        redacted = JWT.matcher(redacted).replaceAll("***");
        redacted = OTP_PHRASE.matcher(redacted).replaceAll("$1***");
        redacted = GUEST_LINK.matcher(redacted).replaceAll("$1***");
        redacted = EMAIL.matcher(redacted).replaceAll("***");
        redacted = PHONE.matcher(redacted).replaceAll("***");
        return redacted;
    }

    private static String redactStructuredJson(String value) {
        try {
            JsonNode root = JSON.readTree(value);
            if (root == null || !root.isContainerNode()) return null;
            redactNode(root);
            return JSON.writeValueAsString(root);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void redactNode(JsonNode node) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (isSensitiveField(field.getKey())) {
                    object.set(field.getKey(), TextNode.valueOf("***"));
                } else {
                    redactNode(field.getValue());
                }
            }
        } else if (node.isArray()) {
            node.forEach(SensitiveDataLogRedactor::redactNode);
        }
    }

    private static boolean isSensitiveField(String field) {
        String key = field.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return key.equals("password") || key.equals("passwd") || key.equals("pwd")
                || key.contains("token") || key.equals("otp") || key.contains("secret")
                || key.equals("authorization") || key.equals("cookie") || key.equals("setcookie")
                || key.equals("apikey") || key.equals("accesskey") || key.equals("privatekey")
                || key.equals("cvv") || key.equals("email") || key.equals("phone")
                || key.equals("recipient") || key.equals("signature") || key.equals("vnpsecurehash");
    }
}
