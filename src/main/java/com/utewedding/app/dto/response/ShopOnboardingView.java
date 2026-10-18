package com.utewedding.app.dto.response;

import com.utewedding.app.domain.entity.ShopApplicationEntity;
import com.utewedding.app.domain.entity.ShopEntity;
import com.utewedding.app.domain.entity.ShopStatusHistoryEntity;
import com.utewedding.app.dto.request.ShopApplicationForm;
import java.util.List;

/** Customer-owned onboarding state; the form is editable only after rejection or before first submission. */
public record ShopOnboardingView(
        ShopEntity shop,
        ShopApplicationEntity latestApplication,
        ShopApplicationForm form,
        List<ShopStatusHistoryEntity> history,
        boolean canSubmit,
        boolean membershipRevoked,
        boolean canOpenVendorWorkspace) {

    /** Backward-compatible constructor for callers that do not need workspace access yet. */
    public ShopOnboardingView(ShopEntity shop,
            ShopApplicationEntity latestApplication,
            ShopApplicationForm form,
            List<ShopStatusHistoryEntity> history,
            boolean canSubmit,
            boolean membershipRevoked) {
        this(shop, latestApplication, form, history, canSubmit, membershipRevoked, false);
    }
}
