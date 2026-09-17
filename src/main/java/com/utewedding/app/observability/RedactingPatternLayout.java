package com.utewedding.app.observability;

import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;

/** Console layout used by the local profile. The formatted line is redacted. */
public class RedactingPatternLayout extends PatternLayout {

    @Override
    public String doLayout(ILoggingEvent event) {
        return SensitiveDataLogRedactor.redact(super.doLayout(event));
    }
}
