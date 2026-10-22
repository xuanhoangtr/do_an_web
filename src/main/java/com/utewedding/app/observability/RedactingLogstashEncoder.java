package com.utewedding.app.observability;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import net.logstash.logback.encoder.LogstashEncoder;

/**
 * JSON logs for every profile except local. Only the correlation id is copied
 * from MDC; the encoded document is then passed through the redactor.
 */
public class RedactingLogstashEncoder extends LogstashEncoder {

    public RedactingLogstashEncoder() {
        setCustomFields("{\"service\":\"utewedding-app\"}");
    }

    @Override
    public byte[] encode(ILoggingEvent event) {
        byte[] raw = super.encode(restricted(event));
        if (raw == null || raw.length == 0) return raw;
        String text = new String(raw, StandardCharsets.UTF_8);
        String redacted = SensitiveDataLogRedactor.redact(text);
        if (redacted.equals(text)) return raw;
        return redacted.getBytes(StandardCharsets.UTF_8);
    }

    private static LoggingEvent restricted(ILoggingEvent event) {
        LoggingEvent copy = new LoggingEvent();
        copy.setTimeStamp(event.getTimeStamp());
        copy.setLevel(event.getLevel());
        copy.setLoggerName(event.getLoggerName());
        copy.setThreadName(event.getThreadName());
        copy.setMessage(SensitiveDataLogRedactor.redact(event.getFormattedMessage()));
        if (event.getThrowableProxy() instanceof ThrowableProxy proxy) {
            copy.setThrowableProxy(proxy);
        }
        Map<String, String> mdc = new LinkedHashMap<>();
        Map<String, String> source = event.getMDCPropertyMap();
        if (source != null && source.get(RequestCorrelationFilter.MDC_KEY) != null) {
            mdc.put(RequestCorrelationFilter.MDC_KEY, source.get(RequestCorrelationFilter.MDC_KEY));
        }
        copy.setMDCPropertyMap(mdc);
        return copy;
    }
}
