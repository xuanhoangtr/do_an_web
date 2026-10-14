package com.utewedding.app.service;

import com.utewedding.app.dto.request.AssignFulfillmentRequest;
import com.utewedding.app.dto.response.FulfillmentResponse;
import com.utewedding.app.dto.response.ShipperResponse;
import com.utewedding.app.dto.response.ShipperBoardSummary;
import com.utewedding.app.dto.response.ShipperPerformanceSummary;
import com.utewedding.app.domain.OverallFulfillmentStatus;
import com.utewedding.app.domain.FulfillmentStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

public interface FulfillmentService {
    /** Creates the shop fulfillment when the payment rules allow it. */
    void ensureForOrder(UUID orderId);
    void ensureForOrder(UUID orderId, RequestContext context);
    void confirmCodOrder(UUID actorId, UUID orderId, RequestContext context);

    List<FulfillmentResponse> listForOrder(UUID orderId);
    OverallFulfillmentStatus overallForOrder(UUID orderId);
    Page<FulfillmentResponse> listAdmin(Pageable pageable);
    List<ShipperResponse> listShippers();
    Page<FulfillmentResponse> listForShipper(UUID shipperId, Pageable pageable);
    Page<FulfillmentResponse> listForShipperBoard(UUID shipperId, boolean history,
                                                   String search, FulfillmentStatus status,
                                                   Pageable pageable);
    ShipperBoardSummary shipperBoardSummary(UUID shipperId, Instant todayStart, Instant tomorrowStart);

    FulfillmentResponse assign(UUID actorId, UUID fulfillmentId,
                               AssignFulfillmentRequest request, RequestContext context);
    FulfillmentResponse processAsAdmin(UUID actorId, UUID fulfillmentId, RequestContext context);
    FulfillmentResponse confirmPickup(UUID shipperId, UUID fulfillmentId, RequestContext context);
    FulfillmentResponse startShipping(UUID shipperId, UUID fulfillmentId, RequestContext context);
    FulfillmentResponse deliver(UUID shipperId, UUID fulfillmentId, MultipartFile proof,
                                RequestContext context);
    default FulfillmentResponse deliver(UUID shipperId, UUID fulfillmentId, MultipartFile proof,
                                        Long collectedAmount, String collectionReference,
                                        RequestContext context) {
        return deliver(shipperId, fulfillmentId, proof, context);
    }
    FulfillmentResponse getForActor(UUID actorId, UUID fulfillmentId);
    Page<FulfillmentResponse> searchAdmin(String query, FulfillmentStatus status,
                                          UUID shipperId, Instant from, Instant to, Pageable pageable);
    FulfillmentResponse failDelivery(UUID shipperId, UUID fulfillmentId, String reasonCode, String note,
                                     Instant nextAttemptAt, MultipartFile proof, RequestContext context);
    FulfillmentResponse retryDelivery(UUID shipperId, UUID fulfillmentId, Instant nextAttemptAt,
                                      RequestContext context);
    FulfillmentResponse startReturn(UUID shipperId, UUID fulfillmentId, String note, RequestContext context);
    FulfillmentResponse completeReturn(UUID shipperId, UUID fulfillmentId, MultipartFile proof,
                                       String note, RequestContext context);
    FulfillmentResponse reassignCodCollector(UUID actorId, UUID orderId, UUID fulfillmentId, RequestContext context);
    ShipperPerformanceSummary shipperPerformance(UUID shipperId);
}
