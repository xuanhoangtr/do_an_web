package com.utewedding.app.service.impl;

import com.utewedding.app.domain.ReturnCaseStatus;
import com.utewedding.app.repository.ReturnCaseRepository;
import java.time.Instant;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Releases reservations when a customer does not ship an approved return in time. */
@Component
public class ReturnCaseExpiryJob {
    private final ReturnCaseRepository cases;
    private final ReturnCaseOperationsService operations;

    public ReturnCaseExpiryJob(ReturnCaseRepository cases, ReturnCaseOperationsService operations) {
        this.cases = cases;
        this.operations = operations;
    }

    @Scheduled(fixedDelayString = "${returns.expiry.fixed-delay-ms:60000}",
            initialDelayString = "${returns.expiry.initial-delay-ms:60000}")
    @SchedulerLock(name = "return-case-expiry", lockAtMostFor = "10m")
    public void expireDueCases() {
        Instant now = Instant.now();
        for (var id : cases.findExpiredCaseIds(ReturnCaseStatus.AWAITING_RETURN, now).stream().limit(200).toList()) {
            operations.expireAwaitingReturn(id, now);
        }
    }
}
