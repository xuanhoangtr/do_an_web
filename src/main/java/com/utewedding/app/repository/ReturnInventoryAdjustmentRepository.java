package com.utewedding.app.repository;
import com.utewedding.app.domain.entity.ReturnInventoryAdjustmentEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ReturnInventoryAdjustmentRepository extends JpaRepository<ReturnInventoryAdjustmentEntity,UUID>{boolean existsByReturnResolutionIdAndProductIdAndKind(UUID resolutionId,UUID productId,String kind);}
