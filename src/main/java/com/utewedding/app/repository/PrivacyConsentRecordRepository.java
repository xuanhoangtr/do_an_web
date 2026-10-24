package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.PrivacyConsentRecordEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PrivacyConsentRecordRepository extends JpaRepository<PrivacyConsentRecordEntity, UUID> {
    List<PrivacyConsentRecordEntity> findByUserIdOrderByRecordedAtDescIdDesc(UUID userId);
    Optional<PrivacyConsentRecordEntity> findFirstByUserIdAndPurposeOrderByRecordedAtDescIdDesc(
            UUID userId, String purpose);
}
