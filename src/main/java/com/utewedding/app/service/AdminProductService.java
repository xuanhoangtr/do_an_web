package com.utewedding.app.service;

import com.utewedding.app.dto.request.AdminProductForm;
import com.utewedding.app.dto.response.AdminProductResponse;
import java.util.UUID;

public interface AdminProductService {
    AdminProductResponse get(UUID productId);
    AdminProductResponse create(UUID actorId, AdminProductForm command, RequestContext context);
    AdminProductResponse update(UUID actorId, UUID productId, AdminProductForm command, RequestContext context);
    AdminProductResponse toggle(UUID actorId, UUID productId, RequestContext context);
    AdminProductResponse hide(UUID actorId, UUID productId, RequestContext context);
}
