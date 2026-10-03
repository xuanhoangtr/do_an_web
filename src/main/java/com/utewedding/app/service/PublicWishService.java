package com.utewedding.app.service;

import com.utewedding.app.dto.request.SubmitWishRequest;
import com.utewedding.app.dto.response.WishCreatedResponse;
import java.util.UUID;

public interface PublicWishService {
    WishCreatedResponse submit(UUID weddingId, SubmitWishRequest request);
}
