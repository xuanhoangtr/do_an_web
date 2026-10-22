package com.utewedding.app.service.impl;

import com.utewedding.app.domain.StockDisposition;
import com.utewedding.app.domain.entity.*;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.repository.*;
import jakarta.persistence.EntityManager;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReturnInventoryService {
    private final ReturnInventoryAdjustmentRepository adjustments;private final ReturnCaseItemRepository items;
    private final OrderItemRepository orders;private final ProductRepository products;private final EntityManager manager;
    public ReturnInventoryService(ReturnInventoryAdjustmentRepository adjustments,ReturnCaseItemRepository items,OrderItemRepository orders,ProductRepository products,EntityManager manager){this.adjustments=adjustments;this.items=items;this.orders=orders;this.products=products;this.manager=manager;}
    @Transactional
    public void apply(UUID caseId,UUID resolution,List<ReturnResolutionItemEntity> lines,boolean exchange){
        var restock=new TreeMap<UUID,Integer>();var outgoing=new TreeMap<UUID,Integer>();
        for(var line:lines){var item=items.findById(line.getReturnCaseItemId()).orElseThrow();var original=orders.findById(item.getOrderItemId()).orElseThrow();
            if(original.getProductId()==null){if(exchange)throw new BusinessConflictException("Sản phẩm này không hỗ trợ đổi hàng.");continue;}
            if(item.getStockDisposition()==StockDisposition.RESTOCK)restock.merge(original.getProductId(),line.getQuantity(),Math::addExact);
            if(exchange)outgoing.merge(original.getProductId(),line.getQuantity(),Math::addExact);
        }
        var ids=new TreeSet<UUID>();ids.addAll(restock.keySet());ids.addAll(outgoing.keySet());
        for(var id:ids){var product=products.findByIdForUpdateIncludingInactive(id).orElseThrow();
            int returned=restock.getOrDefault(id,0);
            if(returned>0&&!adjustments.existsByReturnResolutionIdAndProductIdAndKind(resolution,id,"RETURN_RESTOCK")){
                if(products.incrementStock(id,returned,product.getVersion())!=1)throw new BusinessConflictException("Tồn kho đã thay đổi.");
                adjustments.saveAndFlush(new ReturnInventoryAdjustmentEntity(caseId,resolution,id,returned,"RETURN_RESTOCK"));manager.refresh(product);
            }
            int quantity=outgoing.getOrDefault(id,0);
            if(quantity>0&&!adjustments.existsByReturnResolutionIdAndProductIdAndKind(resolution,id,"EXCHANGE_OUT")){
                if(products.decrementStockIfAvailable(id,quantity,product.getVersion())!=1)throw new BusinessConflictException("Không đủ tồn kho để đổi hàng.");
                adjustments.saveAndFlush(new ReturnInventoryAdjustmentEntity(caseId,resolution,id,quantity,"EXCHANGE_OUT"));manager.refresh(product);
            }
        }
    }
}
