package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.ReturnResolutionItemEntity;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ReturnResolutionItemRepository extends JpaRepository<ReturnResolutionItemEntity, ReturnResolutionItemEntity.Key> {
    List<ReturnResolutionItemEntity> findByResolutionId(UUID id);
    @Query("select coalesce(sum(i.quantity),0) from ReturnResolutionItemEntity i, ReturnCaseItemEntity c where i.returnCaseItemId=c.id and c.orderItemId=:item")
    long allocatedQuantity(@Param("item") UUID item);
}
