package com.utewedding.app.service.impl;

import com.utewedding.app.domain.FulfillmentStatus;
import com.utewedding.app.domain.entity.FulfillmentShipmentEntity;
import com.utewedding.app.domain.entity.OrderEntity;
import com.utewedding.app.domain.entity.OrderFulfillmentEntity;
import com.utewedding.app.domain.entity.OrderShippingSnapshotEntity;
import com.utewedding.app.domain.entity.OutboxEventEntity;
import com.utewedding.app.domain.entity.ShippingProviderOperationEntity;
import com.utewedding.app.exception.BusinessConflictException;
import com.utewedding.app.exception.ResourceNotFoundException;
import com.utewedding.app.repository.FulfillmentShipmentRepository;
import com.utewedding.app.repository.OrderFulfillmentRepository;
import com.utewedding.app.repository.OrderRepository;
import com.utewedding.app.repository.OrderShippingSnapshotRepository;
import com.utewedding.app.repository.ShippingProviderOperationRepository;
import com.utewedding.app.service.RequestContext;
import com.utewedding.app.service.port.OutboxDispatcher;
import com.utewedding.app.service.impl.OutboxService;
import com.utewedding.app.service.port.ShippingProvider;
import com.utewedding.app.service.port.ShippingProviderException;
import com.utewedding.app.exception.AmbiguousOutboxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.utewedding.app.domain.ShopAddressKind;
import com.utewedding.app.domain.entity.ShopAddressRevisionEntity;
import com.utewedding.app.domain.entity.ShopShippingBindingEntity;
import com.utewedding.app.domain.entity.ShippingProviderAccountEntity;
import com.utewedding.app.repository.ShopAddressRevisionRepository;
import com.utewedding.app.repository.ShopShippingBindingRepository;
import com.utewedding.app.repository.ShippingProviderAccountRepository;

@Service
public class ShippingOperationsService implements OutboxDispatcher {
    public static final String EVENT = "SHIPPING_PROVIDER_OPERATION";
    private static final int MAX_LOCK_RETRIES = 3;

    private final FulfillmentShipmentRepository shipments;
    private final ShippingProviderOperationRepository operations;
    private final OrderFulfillmentRepository fulfillments;
    private final OrderRepository orders;
    private final OrderShippingSnapshotRepository snapshots;
    private final ShippingProviderRegistry providers;
    private final OutboxService outbox;
    private final ShippingOperationPersistence persistence;
    private final TransactionTemplate writeTransaction;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ShopShippingBindingRepository shippingBindings;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ShopAddressRevisionRepository addressRevisions;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ShippingProviderAccountRepository providerAccounts;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ObjectMapper json;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.utewedding.app.repository.CodObligationRepository codObligations;
    @org.springframework.beans.factory.annotation.Value("${app.marketplace.ghn-shipping.enabled:false}")
    private boolean shopGhnShippingEnabled;

    public ShippingOperationsService(FulfillmentShipmentRepository shipments,
                                     ShippingProviderOperationRepository operations,
                                     OrderFulfillmentRepository fulfillments,
                                     OrderRepository orders,
                                     OrderShippingSnapshotRepository snapshots,
                                     ShippingProviderRegistry providers, OutboxService outbox,
                                     ShippingOperationPersistence persistence,
                                     PlatformTransactionManager transactionManager) {
        this.shipments = shipments;
        this.operations = operations;
        this.fulfillments = fulfillments;
        this.orders = orders;
        this.snapshots = snapshots;
        this.providers = providers;
        this.outbox = outbox;
        this.persistence = persistence;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.writeTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** The idempotent database operation retries only PostgreSQL deadlock/serialization aborts. */
    public FulfillmentShipmentEntity requestCreate(java.util.UUID fulfillmentId, String providerCode,
                                                    String idempotencyKey, RequestContext context) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 190) {
            throw new BusinessConflictException("Idempotency-Key là bắt buộc.");
        }
        String key = idempotencyKey.trim();
        String requestHash = hash("CREATE|" + fulfillmentId + "|" + providerCode);
        for (int attempt = 1; ; attempt++) {
            try {
                return writeTransaction.execute(status -> requestCreateInTransaction(
                        fulfillmentId, providerCode, key, requestHash, context));
            } catch (DataIntegrityViolationException exception) {
                ShippingProviderOperationEntity existing = operations.findByIdempotencyKey(key).orElse(null);
                if (existing != null && requestHash.equals(existing.getRequestHash())) {
                    return shipments.findById(existing.getShipmentId()).orElseThrow();
                }
                throw exception;
            } catch (RuntimeException exception) {
                if (attempt >= MAX_LOCK_RETRIES || !isRetryablePostgresAbort(exception)) throw exception;
                try {
                    Thread.sleep(40L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw exception;
                }
            }
        }
    }

    public java.util.UUID requestTrack(java.util.UUID shipmentId, java.util.UUID actorId, RequestContext context) {
        return requestProviderOperation(shipmentId, "TRACK", actorId, context);
    }

    public java.util.UUID requestCancel(java.util.UUID shipmentId, java.util.UUID actorId, RequestContext context) {
        return requestProviderOperation(shipmentId, "CANCEL", actorId, context);
    }

    private java.util.UUID requestProviderOperation(java.util.UUID shipmentId, String operation,
                                                    java.util.UUID actorId, RequestContext context) {
        FulfillmentShipmentEntity shipment = shipments.findById(shipmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy vận đơn."));
        if (!"GHN".equalsIgnoreCase(shipment.getProviderCode())) {
            throw new BusinessConflictException("Thao tác này chỉ áp dụng cho vận đơn GHN.");
        }
        ShippingProvider provider = providers.require(shipment.getProviderCode());
        ShippingProvider.Capability capability = "TRACK".equals(operation)
                ? ShippingProvider.Capability.TRACK : ShippingProvider.Capability.CANCEL;
        if (!provider.capabilities().contains(capability)) {
            throw new BusinessConflictException("GHN chưa hỗ trợ thao tác này.");
        }
        long bucket = "TRACK".equals(operation) ? java.time.Instant.now().getEpochSecond() / 300 : 0;
        String key = "admin-" + operation.toLowerCase(java.util.Locale.ROOT) + ":" + shipmentId
                + ("TRACK".equals(operation) ? ":" + bucket : "");
        String requestHash = hash(operation + "|" + shipmentId);
        return persistence.requestProviderOperation(shipmentId, operation, key, requestHash, actorId, context).orderId();
    }

    private FulfillmentShipmentEntity requestCreateInTransaction(java.util.UUID fulfillmentId,
                                                                  String providerCode, String key,
                                                                  String requestHash, RequestContext context) {
        // Completion uses order -> fulfillment -> shipment -> operation. CREATE replays
        // acquire the same business locks before locking the idempotency operation.
        OrderFulfillmentEntity located = fulfillments.findById(fulfillmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy fulfillment."));
        OrderEntity order = orders.findByIdForUpdate(located.getOrderId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đơn hàng."));
        OrderFulfillmentEntity fulfillment = fulfillments.findByIdForUpdate(fulfillmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy fulfillment."));
        if (!order.getId().equals(fulfillment.getOrderId())
                || !order.getShopId().equals(fulfillment.getShopId())) {
            throw new BusinessConflictException("Fulfillment đã được chuyển sang đơn hàng hoặc cửa hàng khác.");
        }

        ShippingProviderOperationEntity old = operations.findByIdempotencyKeyForUpdate(key).orElse(null);
        if (old != null) {
            if (!old.getRequestHash().equals(requestHash)) {
                throw new BusinessConflictException("Idempotency-Key đã dùng với payload khác.");
            }
            FulfillmentShipmentEntity replay = old.getShipmentId() == null ? null
                    : shipments.findById(old.getShipmentId()).orElse(null);
            if (replay == null || !fulfillmentId.equals(replay.getFulfillmentId())) {
                throw new BusinessConflictException("Idempotency-Key đã dùng cho fulfillment khác.");
            }
            if ("REJECTED".equals(old.getStatus())) {
                old.setStatus("PENDING");
                old.setLastError(null);
                old.setSanitizedResponse("{}");
                old.setNextAttemptAt(java.time.Instant.now());
                outbox.enqueue(EVENT, "ShippingProviderOperation", old.getId(),
                        "shipping:" + key + ":retry:" + (old.getAttemptCount() + 1),
                        "{\"operationId\":\"" + old.getId() + "\"}", context);
            }
            return replay;
        }

        OrderShippingSnapshotEntity snapshot = snapshots.findByOrderId(order.getId())
                .orElseThrow(() -> new BusinessConflictException("Order thiếu shipping snapshot."));
        if (!order.getShopId().equals(snapshot.getShopId())
                || !snapshot.getProviderCode().equalsIgnoreCase(providerCode)) {
            throw new BusinessConflictException("Cửa hàng hoặc provider không khớp shipping snapshot.");
        }
        ShippingProvider provider = providers.require(providerCode);
        if (!provider.capabilities().contains(ShippingProvider.Capability.CREATE)) {
            throw new BusinessConflictException("Provider không hỗ trợ tạo shipment.");
        }
        if (fulfillment.getStatus() == FulfillmentStatus.CONFIRMED) {
            fulfillment.setStatus(FulfillmentStatus.PROCESSING);
        }
        if (fulfillment.getStatus() != FulfillmentStatus.PROCESSING) {
            throw new BusinessConflictException("Fulfillment không thể tạo shipment ở trạng thái hiện tại.");
        }

        List<FulfillmentShipmentEntity> activeShipments = shipments
                .findByFulfillmentIdAndStatusNotInOrderByCreatedAtDesc(fulfillmentId, List.of(
                        FulfillmentStatus.DELIVERED, FulfillmentStatus.RETURNED, FulfillmentStatus.CANCELLED));
        if (!activeShipments.isEmpty()) {
            throw new BusinessConflictException(
                    "Fulfillment đã có vận đơn đang hoạt động; hãy dùng lại CREATE idempotency key hiện có.");
        }

        FulfillmentShipmentEntity shipment = new FulfillmentShipmentEntity();
        shipment.setFulfillmentId(fulfillment.getId());
        shipment.setShopId(fulfillment.getShopId());
        shipment.setProviderCode(provider.code());
        shipment.setMethodCode(snapshot.getMethodCode());
        shipment.setServiceCode(snapshot.getServiceCode());
        if (!com.utewedding.app.domain.ShopDefaults.PLATFORM_SHOP_ID.equals(fulfillment.getShopId())
                && "COD".equalsIgnoreCase(order.getPaymentMethod()) && codObligations == null) {
            throw new BusinessConflictException("Không thể xác minh nghĩa vụ COD của kiện hàng.");
        }
        long expectedCod = codObligations == null ? 0L : codObligations.findByFulfillmentId(fulfillment.getId())
                .map(com.utewedding.app.domain.entity.CodObligationEntity::getExpectedCollectable).orElse(0L);
        var destination = new com.utewedding.app.dto.request.ShippingAddressRequest(order.getShippingRecipientName(),
                order.getShippingPhone(), order.getShippingAddress(), order.getShippingProvinceCode(),
                order.getShippingWardCode());
        ShippingProvider.GhnShipmentContext ghnContext = snapshotContext(snapshot, destination, expectedCod);
        if (ghnContext != null && (!order.getShopId().equals(ghnContext.shopId())
                || !snapshot.getShopId().equals(ghnContext.shopId()))) {
            throw new BusinessConflictException("Ngữ cảnh GHN không khớp cửa hàng của shipping snapshot.");
        }
        if (ghnContext != null && !shopGhnShippingEnabled) {
            throw new BusinessConflictException("Vận chuyển GHN theo tài khoản cửa hàng chưa được bật.");
        }
        if (ghnContext != null) {
            shipment.setProviderAccountId(ghnContext.accountId());
            shipment.setProviderContextSnapshot(toJson(ghnContext));
        } else if (!com.utewedding.app.domain.ShopDefaults.PLATFORM_SHOP_ID.equals(fulfillment.getShopId())
                && "GHN".equalsIgnoreCase(provider.code())) {
            throw new BusinessConflictException("Thiếu snapshot tài khoản GHN của cửa hàng; chưa tạo yêu cầu giao.");
        }
        shipment = shipments.saveAndFlush(shipment);

        ShippingProviderOperationEntity operation = new ShippingProviderOperationEntity();
        operation.setShipmentId(shipment.getId());
        operation.setShopId(shipment.getShopId());
        operation.setOperation("CREATE");
        operation.setIdempotencyKey(key);
        operation.setRequestHash(requestHash);
        operation.setSanitizedRequest("{\"fulfillmentId\":\"" + fulfillment.getId() + "\"}");
        operation = operations.saveAndFlush(operation);
        outbox.enqueue(EVENT, "ShippingProviderOperation", operation.getId(), "shipping:" + key,
                "{\"operationId\":\"" + operation.getId() + "\"}", context);
        return shipment;
    }

    @Override
    public boolean supports(String type) {
        return EVENT.equals(type);
    }

    @Override
    public void dispatch(OutboxEventEntity event) {
        ShippingOperationPersistence.Task task = persistence.prepare(event.getAggregateId());
        ShippingProvider provider = providers.require(task.providerCode());
        try {
            if ("TRACK".equals(task.operation())) {
                requireSavedGhnContext(task);
                ShippingProvider.TrackingResult tracking = provider.track(new ShippingProvider.OperationRequest(
                        task.idempotencyKey(), task.providerShipmentId(), task.trackingNumber(), task.ghnContext()));
                persistence.succeedTracking(task.operationId(), tracking);
                return;
            }
            if ("CANCEL".equals(task.operation())) requireSavedGhnContext(task);
            ShippingProvider.ShipmentResult result = switch (task.operation()) {
                case "CREATE" -> provider.create(new ShippingProvider.CreateShipment(task.idempotencyKey(),
                        task.orderNumber(), task.fulfillmentReference(), task.codAmount(), task.destination(),
                        task.serviceCode(), task.parcel(), task.addressCatalogSource(), task.ghnContext()));
                case "CANCEL" -> provider.cancel(new ShippingProvider.OperationRequest(task.idempotencyKey(),
                        task.providerShipmentId(), task.trackingNumber(), task.ghnContext()));
                case "PICKUP" -> provider.requestPickup(new ShippingProvider.OperationRequest(task.idempotencyKey(),
                        task.providerShipmentId(), task.trackingNumber(), task.ghnContext()));
                default -> throw new IllegalArgumentException("Unsupported shipping operation");
            };
            persistence.succeed(task.operationId(), result);
        } catch (ShippingProviderException exception) {
            boolean ambiguous = exception.outcome() == ShippingProviderException.Outcome.UNKNOWN;
            persistence.recordProviderFailure(task.operationId(), exception.getMessage(),
                    exception.diagnostics(), ambiguous);
            if (ambiguous) throw new AmbiguousOutboxException(exception.getMessage());
        } catch (com.utewedding.app.exception.BusinessConflictException
                 | IllegalArgumentException exception) {
            persistence.recordProviderFailure(task.operationId(), exception.getMessage(),
                    java.util.Map.of("category", "local_rejection"), false);
        } catch (RuntimeException exception) {
            String message = "Không xác định được kết quả từ GHN; hệ thống sẽ tra mã yêu cầu trước khi thử lại.";
            persistence.recordProviderFailure(task.operationId(), message,
                    java.util.Map.of("category", "unknown_provider_result"), true);
            throw new AmbiguousOutboxException(message);
        }
    }

    private void requireSavedGhnContext(ShippingOperationPersistence.Task task) {
        ShippingProvider.GhnShipmentContext context = task.ghnContext();
        if (!"GHN".equalsIgnoreCase(task.providerCode()) || context == null
                || context.accountId() == null || context.shopId() == null
                || context.externalShopId() == null || context.externalShopId().isBlank()) {
            throw new BusinessConflictException("Thiếu ngữ cảnh tài khoản/ShopId GHN đã lưu cho vận đơn.");
        }
    }

    public void verifyAmbiguousCreated(java.util.UUID operationId, String providerShipmentId,
                                       java.util.UUID actorId, RequestContext context) {
        ShippingOperationPersistence.Task task = requireAmbiguousGhnCreate(operationId);
        ShippingProvider provider = providers.require(task.providerCode());
        ShippingProvider.ShipmentResult verified = provider.verifyCreatedShipment(
                new ShippingProvider.VerificationRequest(providerShipmentId, task.idempotencyKey(),
                        task.codAmount(), task.destination(), task.ghnContext()));
        persistence.resolveVerifiedAmbiguous(operationId, actorId, verified, context);
    }

    public void checkAmbiguousAndRetry(java.util.UUID operationId, java.util.UUID actorId,
                                       RequestContext context) {
        ShippingOperationPersistence.Task task = requireAmbiguousGhnCreate(operationId);
        ShippingProvider provider = providers.require(task.providerCode());
        ShippingProvider.ClientRequestLookup lookup = provider.findByClientRequestId(
                task.operation(), task.idempotencyKey(), task.ghnContext());
        if (lookup == null || lookup.status() == null || lookup.status() == ShippingProvider.LookupStatus.UNKNOWN) {
            throw new BusinessConflictException(
                    "GHN chưa xác nhận được kết quả. Đơn vẫn được giữ để đối soát; chưa thử tạo lại.");
        }
        if (lookup.status() == ShippingProvider.LookupStatus.FOUND && lookup.shipment() != null) {
            ShippingProvider.ShipmentResult verified = provider.verifyCreatedShipment(
                    new ShippingProvider.VerificationRequest(lookup.shipment().providerShipmentId(),
                            task.idempotencyKey(), task.codAmount(), task.destination(), task.ghnContext()));
            persistence.resolveVerifiedAmbiguous(operationId, actorId, verified, context);
            return;
        }
        persistence.retryAmbiguous(operationId, actorId, context);
    }

    private ShippingOperationPersistence.Task requireAmbiguousGhnCreate(java.util.UUID operationId) {
        ShippingOperationPersistence.Task task = persistence.inspect(operationId);
        if (task == null) throw new BusinessConflictException("Shipping operation không còn cần đối soát.");
        if (!"CREATE".equals(task.operation()) || !"GHN".equalsIgnoreCase(task.providerCode())) {
            throw new BusinessConflictException("Chỉ hỗ trợ đối soát tạo vận đơn GHN.");
        }
        return task;
    }

    private static boolean isRetryablePostgresAbort(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql
                    && ("40P01".equals(sql.getSQLState()) || "40001".equals(sql.getSQLState()))) return true;
        }
        return false;
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private ShippingProvider.GhnShipmentContext snapshotContext(OrderShippingSnapshotEntity snapshot,
            com.utewedding.app.dto.request.ShippingAddressRequest destination, long expectedCod) {
        if (snapshot.getShippingBindingId() == null || snapshot.getShippingProviderAccountId() == null) return null;
        if (shippingBindings == null || addressRevisions == null || providerAccounts == null) {
            throw new BusinessConflictException("GHN context repositories are unavailable.");
        }
        ShopShippingBindingEntity binding = shippingBindings.findByIdAndShopId(
                snapshot.getShippingBindingId(), snapshot.getShopId()).orElseThrow(() ->
                new BusinessConflictException("Snapshot không còn binding GHN hợp lệ."));
        ShippingProviderAccountEntity account = providerAccounts.findByIdAndProviderCode(
                snapshot.getShippingProviderAccountId(), "GHN").orElseThrow(() ->
                new BusinessConflictException("Snapshot không còn tài khoản GHN hợp lệ."));
        String routeMode = binding.getRouteMode() == null
                ? "LEGACY_ROUTES" : binding.getRouteMode().trim().toUpperCase(java.util.Locale.ROOT);
        boolean routePolicyValid = switch (routeMode) {
            case "LEGACY_ROUTES" -> binding.isRouteCoverageVerified();
            case "API_QUOTE" -> "PROVIDER_QUOTE".equals(snapshot.getPricingMode());
            default -> false;
        };
        if (!binding.getProviderAccountId().equals(account.getId())
                || binding.getRevision() != snapshot.getShippingBindingRevision()
                || !java.util.Objects.equals(account.getUpdatedAt(), snapshot.getShippingProviderAccountUpdatedAt())
                || !"VERIFIED".equals(account.getStatus()) || !"STAGING".equals(account.getEnvironment())
                || !binding.isActive() || !"VERIFIED".equals(binding.getStatus()) || !routePolicyValid) {
            throw new BusinessConflictException("Binding hoặc tài khoản GHN đã thay đổi sau khi báo giá; hãy báo giá lại.");
        }
        if (expectedCod > 0 && !binding.isCodBeneficiaryVerified()) {
            throw new BusinessConflictException("Chưa xác minh tài khoản nhận COD của binding GHN.");
        }
        ShopAddressRevisionEntity pickup = addressRevisions.findByIdAndShopIdAndAddressKind(
                snapshot.getOriginAddressRevisionId(), snapshot.getShopId(), ShopAddressKind.PICKUP)
                .orElseThrow(() -> new BusinessConflictException("Thiếu địa chỉ lấy hàng trong snapshot GHN."));
        ShopAddressRevisionEntity returned = addressRevisions.findByIdAndShopIdAndAddressKind(
                binding.getReturnAddressRevisionId(), snapshot.getShopId(), ShopAddressKind.RETURN)
                .orElseThrow(() -> new BusinessConflictException("Thiếu địa chỉ hoàn hàng trong binding GHN."));
        if (pickup.getRevision() != snapshot.getOriginAddressRevision()
                || !pickup.getId().equals(binding.getPickupAddressRevisionId())) {
            throw new BusinessConflictException("Địa chỉ lấy hàng không khớp snapshot GHN.");
        }
        return new ShippingProvider.GhnShipmentContext(snapshot.getShopId(), account.getId(),
                account.getCredentialReference(), account.getUpdatedAt(), snapshot.getShippingRouteId(),
                snapshot.getQuoteId(), snapshot.getSelectedGroupFingerprint(), binding.getExternalShopId(),
                binding.getId(), binding.getRevision(),
                pickup.getId(), pickup.getRevision(), address(pickup), returned.getId(), returned.getRevision(),
                address(returned), destination, expectedCod, account.getEnvironment());
    }

    private com.utewedding.app.dto.request.ShippingAddressRequest address(ShopAddressRevisionEntity row) {
        return new com.utewedding.app.dto.request.ShippingAddressRequest(row.getContactName(), row.getContactPhone(),
                row.getAddressLine(), row.getProvinceCode(), row.getWardCode());
    }

    private String toJson(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new BusinessConflictException("Không thể lưu snapshot context GHN."); }
    }
}
