package com.utewedding.app.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ShopSettlementClockConfiguration {
    @Bean
    Clock shopSettlementClock() { return Clock.systemUTC(); }
}
