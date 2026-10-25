package com.utewedding.app.service.impl;

import com.utewedding.app.config.MarketplaceFeatureFlags;
import com.utewedding.app.config.MarketplaceFeatureFlags.Feature;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Repairs missed receipt/delivery events; database finance anchors remain the concurrency gate. */
@Component
public class ShopSettlementRepairJob {
    private final ShopSettlementPeriodService periods;
    private final MarketplaceFeatureFlags features;

    public ShopSettlementRepairJob(ShopSettlementPeriodService periods, MarketplaceFeatureFlags features) {
        this.periods = periods;
        this.features = features;
    }

    @Scheduled(fixedDelayString = "${finance.settlement.repair-delay-ms:60000}",
            initialDelayString = "${finance.settlement.repair-initial-delay-ms:60000}")
    @SchedulerLock(name = "shop-settlement-period-repair", lockAtMostFor = "10m")
    public void repair() {
        if (features.isEnabled(Feature.PAYOUT)) periods.repairUnassignedEntries();
    }
}
