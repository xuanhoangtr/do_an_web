package com.utewedding.app.service;

import com.utewedding.app.dto.request.CreateWeddingRequest;
import com.utewedding.app.dto.request.ModerateWishRequest;
import com.utewedding.app.dto.request.TimelineItemRequest;
import com.utewedding.app.dto.request.UpdateTimelineRequest;
import com.utewedding.app.dto.request.UpdateWeddingRequest;
import com.utewedding.app.dto.response.TimelineItemResponse;
import com.utewedding.app.dto.response.WeddingResponse;
import com.utewedding.app.dto.response.WishResponse;
import java.util.List;
import java.util.UUID;

public interface WeddingService {
    WeddingResponse create(UUID ownerId, CreateWeddingRequest request, RequestContext context);
    WeddingResponse update(UUID weddingId, UUID ownerId, UpdateWeddingRequest request,
                           RequestContext context);
    WeddingResponse get(UUID weddingId, UUID ownerId);
    List<TimelineItemResponse> getTimeline(UUID weddingId, UUID ownerId);
    List<TimelineItemResponse> updateTimeline(UUID weddingId, UUID ownerId,
                                              UpdateTimelineRequest request,
                                              RequestContext context);
    WishResponse moderateWish(UUID weddingId, UUID wishId, UUID ownerId,
                              ModerateWishRequest request, RequestContext context);
}
