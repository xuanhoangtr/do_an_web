/* Customer side of the grouped checkout API. The server resolves every row and shop. */
(function () {
    document.addEventListener("DOMContentLoaded", () => {
        const form = document.querySelector("form[data-grouped-checkout]");
        if (!form) return;

        const feedback = document.querySelector("[data-grouped-feedback]");
        const previewStatus = document.querySelector("[data-preview-status]");
        const placeButton = form.querySelector("[data-place-group-order]");
        const refreshButton = form.querySelector("[data-refresh-group-preview]");
        const initialIds = Array.from(form.querySelectorAll("[data-initial-selected-cart-row]"))
            .map(input => input.value).filter(Boolean);
        const selectedCartItemIds = [...new Set(initialIds)];
        const selectionSignature = [...selectedCartItemIds].sort().join(",");
        const stateKey = "ute-grouped-checkout-state";
        const shopCards = new Map(Array.from(form.querySelectorAll("[data-checkout-shop]"))
            .map(card => [card.dataset.shopId, card]));
        const rowShop = new Map();
        shopCards.forEach((card, shopId) => {
            card.querySelectorAll("[data-checkout-cart-row]").forEach(row => {
                rowShop.set(row.dataset.cartItemId, shopId);
            });
        });
        const selectedShopIds = [...new Set(selectedCartItemIds.map(id => rowShop.get(id)).filter(Boolean))];
        const quoteIds = new Map();
        let latestPreview = null;
        let latestRequest = 0;
        let debounceTimer;
        let quoteExpiryTimer;
        let previewPending = false;
        let previewReady = false;
        let placing = false;
        let idempotencyKey = form.dataset.idempotencyKey;

        const text = (selector, value) => {
            const element = document.querySelector(selector);
            if (element) element.textContent = value;
        };
        const money = value => value == null || value === "" || !Number.isFinite(Number(value))
            ? "—" : new Intl.NumberFormat("vi-VN").format(Number(value)) + " đ";
        const cleanCode = value => (value || "").trim() || null;

        function rotateIdempotencyKey() {
            if (window.crypto?.randomUUID) idempotencyKey = window.crypto.randomUUID();
            else idempotencyKey = `group-${Date.now()}-${Math.random().toString(36).slice(2)}`;
            form.dataset.idempotencyKey = idempotencyKey;
        }

        function readSavedState() {
            try { return JSON.parse(window.sessionStorage.getItem(stateKey) || "null"); }
            catch (_) { return null; }
        }

        function saveState() {
            const shopOptions = {};
            shopCards.forEach((card, shopId) => {
                shopOptions[shopId] = {};
                card.querySelectorAll("[data-shop-field]").forEach(input => {
                    shopOptions[shopId][input.dataset.shopField] = input.value;
                });
            });
            const saved = {
                selectionSignature,
                shared: Object.fromEntries(["fullName", "phone", "shippingAddress", "provinceCode", "wardCode"]
                    .map(name => [name, form.querySelector(`[name="${name}"]`)?.value || ""])),
                shippingAddressId: document.getElementById("shippingAddressId")?.value || "",
                selectedAddressId: document.getElementById("savedAddress")?.value || "",
                paymentMethod: form.querySelector("[data-payment-method]:checked")?.value || "COD",
                shopOptions
            };
            try { window.sessionStorage.setItem(stateKey, JSON.stringify(saved)); }
            catch (_) { /* Checkout remains usable when session storage is blocked. */ }
        }

        function restoreState() {
            const saved = readSavedState();
            if (!saved || saved.selectionSignature !== selectionSignature) return;
            Object.entries(saved.shared || {}).forEach(([name, value]) => {
                const input = form.querySelector(`[name="${name}"]`);
                if (input && value) input.value = value;
            });
            const addressId = document.getElementById("shippingAddressId");
            const addressSelect = document.getElementById("savedAddress");
            if (addressId) addressId.value = saved.shippingAddressId || "";
            if (addressSelect) addressSelect.value = saved.selectedAddressId || "";
            const selectedMethod = Array.from(form.querySelectorAll("[data-payment-method]"))
                .find(input => input.value === (saved.paymentMethod || "COD"));
            if (selectedMethod) selectedMethod.checked = true;
            Object.entries(saved.shopOptions || {}).forEach(([shopId, options]) => {
                const card = shopCards.get(shopId);
                if (!card) return;
                Object.entries(options).forEach(([fieldName, value]) => {
                    const input = card.querySelector(`[data-shop-field="${fieldName}"]`);
                    if (input) input.value = value;
                });
            });
        }

        function addressPayload() {
            const addressId = document.getElementById("shippingAddressId")?.value || "";
            const addressSelect = document.getElementById("savedAddress");
            const isCurrentSavedAddress = addressId && (!addressSelect || addressSelect.value === addressId);
            if (isCurrentSavedAddress) return {shippingAddressId: addressId};
            return {shipping: {
                recipientName: form.querySelector('[name="fullName"]')?.value.trim() || "",
                phone: form.querySelector('[name="phone"]')?.value.trim() || "",
                addressLine: form.querySelector('[name="shippingAddress"]')?.value.trim() || "",
                provinceCode: form.querySelector('[name="provinceCode"]')?.value || "",
                wardCode: form.querySelector('[name="wardCode"]')?.value || ""
            }};
        }

        function currentRequest(refreshQuotes = false) {
            const paymentMethod = form.querySelector("[data-payment-method]:checked")?.value || "COD";
            return {
                selectedCartItemIds,
                ...addressPayload(),
                paymentMethod,
                shops: selectedShopIds.map(shopId => {
                    const card = shopCards.get(shopId);
                    const value = key => card?.querySelector(`[data-shop-field="${key}"]`)?.value || "";
                    return {
                        shopId,
                        discountCode: cleanCode(value("discountCode")),
                        shippingDiscountCode: cleanCode(value("shippingDiscountCode")),
                        customerNote: value("customerNote").trim(),
                        shippingQuoteId: refreshQuotes ? null : (quoteIds.get(shopId) || null)
                    };
                })
            };
        }

        async function responseError(response) {
            let body = null;
            try { body = await response.json(); } catch (_) { /* response may not contain JSON */ }
            if (response.status === 403) return "Phiên làm việc đã hết hạn. Tải lại trang để tiếp tục.";
            return body?.detail || body?.message || body?.title || body?.error
                || `Không thể hoàn tất thao tác (mã ${response.status}).`;
        }

        function showFeedback(message, kind = "danger") {
            if (!feedback) return;
            feedback.classList.remove("d-none", "alert-danger", "alert-warning", "alert-info", "alert-success");
            feedback.classList.add(`alert-${kind}`);
            feedback.textContent = message;
        }

        function setShopField(card, key, value) {
            const target = card.querySelector(`[data-shop-total-field="${key}"]`);
            if (!target) return;
            const discount = ["productDiscountAmount", "voucherDiscountAmount", "shippingDiscountAmount"].includes(key);
            target.textContent = discount && Number(value) > 0 ? `−${money(value)}` : money(value);
        }

        function updateShop(shop) {
            const card = shopCards.get(shop.shopId);
            if (!card) return;
            card.querySelector("[data-shop-total]").textContent = money(shop.totalAmount);
            ["subtotal", "productDiscountAmount", "voucherDiscountAmount", "shippingFeeBeforeDiscount",
                "shippingDiscountAmount", "totalAmount"].forEach(key => setShopField(card, key, shop[key]));
            const quoteStatus = card.querySelector("[data-shop-quote-status]");
            const quoteExpiry = shop.shippingQuoteExpiresAt || shop.quoteExpiresAt || shop.expiresAt;
            const expiry = quoteExpiry ? Date.parse(quoteExpiry) : NaN;
            const providerCode = shop.shippingProviderCode || shop.shippingProvider;
            if (quoteStatus) {
                quoteStatus.textContent = providerCode
                    ? `${providerCode}${Number.isFinite(expiry) ? ` · Báo giá đến ${new Intl.DateTimeFormat("vi-VN", {dateStyle: "short", timeStyle: "short"}).format(new Date(expiry))}` : " · Báo giá đã được kiểm tra"}`
                    : Number.isFinite(expiry) ? `Báo giá đến ${new Intl.DateTimeFormat("vi-VN", {dateStyle: "short", timeStyle: "short"}).format(new Date(expiry))}`
                        : "Báo giá được tính theo địa chỉ nhận hàng.";
            }
            const error = card.querySelector("[data-shop-error]");
            if (error) {
                error.textContent = shop.error || "";
                error.classList.toggle("d-none", !shop.error);
            }
            const rows = Array.from(card.querySelectorAll("[data-checkout-cart-row]"))
                .filter(row => selectedCartItemIds.includes(row.dataset.cartItemId));
            card.querySelector("[data-shop-item-count]").textContent = `${rows.length} sản phẩm đã chọn`;
            rows.forEach(row => {
                const line = (shop.lines || []).find(candidate => candidate.cartItemId === row.dataset.cartItemId
                    || candidate.cartRowId === row.dataset.cartItemId || candidate.id === row.dataset.cartItemId);
                const amount = row.querySelector("strong");
                if (line && amount) amount.textContent = money(line.lineTotal ?? line.totalAmount);
            });
        }

        function updateGroupTotals(result) {
            const totals = result.totals || result;
            ["subtotal", "productDiscountAmount", "voucherDiscountAmount", "shippingFeeBeforeDiscount",
                "shippingDiscountAmount", "totalAmount"].forEach(key => {
                const target = document.querySelector(`[data-group-total="${key}"]`);
                if (!target) return;
                const amount = totals[key];
                const discount = ["productDiscountAmount", "voucherDiscountAmount", "shippingDiscountAmount"].includes(key);
                target.textContent = discount && Number(amount) > 0 ? `−${money(amount)}` : money(amount);
            });
        }

        function clearQuoteExpiry() {
            window.clearTimeout(quoteExpiryTimer);
            quoteExpiryTimer = null;
        }

        function scheduleQuoteExpiry(result) {
            clearQuoteExpiry();
            const expiryCandidates = [result.expiresAt, ...(result.shops || []).map(shop =>
                shop.shippingQuoteExpiresAt || shop.quoteExpiresAt)].map(value => Date.parse(value || ""))
                .filter(value => Number.isFinite(value) && value > Date.now());
            if (!expiryCandidates.length) return;
            const earliest = Math.min(...expiryCandidates);
            quoteExpiryTimer = window.setTimeout(() => {
                latestPreview = null;
                if (placeButton) placeButton.disabled = true;
                if (previewStatus) previewStatus.textContent = "Báo giá đã hết hạn. Làm mới giá và phí giao hàng trước khi đặt.";
            }, Math.min(earliest - Date.now(), 2_147_000_000));
        }

        async function preview({refreshQuotes = false, conflict = false} = {}) {
            window.clearTimeout(debounceTimer);
            const requestId = ++latestRequest;
            latestPreview = null;
            previewReady = false;
            previewPending = true;
            if (placeButton) placeButton.disabled = true;
            if (refreshButton) refreshButton.disabled = true;
            if (previewStatus) previewStatus.textContent = conflict
                ? "Giá hoặc báo giá đã thay đổi. Đang tính lại tổng tiền cho các lựa chọn của bạn."
                : "Đang kiểm tra giá, voucher và phí giao hàng theo từng cửa hàng.";
            if (!conflict && feedback) feedback.classList.add("d-none");
            saveState();
            const csrfToken = document.querySelector('meta[name="_csrf"]')?.content;
            try {
                const response = await fetch("/api/checkout/preview", {
                    method: "POST",
                    credentials: "same-origin",
                    headers: {
                        "Content-Type": "application/json",
                        Accept: "application/json",
                        ...(csrfToken ? {"X-CSRF-TOKEN": csrfToken} : {})
                    },
                    body: JSON.stringify(currentRequest(refreshQuotes))
                });
                if (!response.ok) throw new Error(await responseError(response));
                const result = await response.json();
                if (requestId !== latestRequest) return false;
                latestPreview = result;
                quoteIds.clear();
                (result.shops || []).forEach(shop => {
                    updateShop(shop);
                    if (shop.shippingQuoteId) quoteIds.set(shop.shopId, shop.shippingQuoteId);
                });
                updateGroupTotals(result);
                const selectedCount = selectedCartItemIds.length;
                const shopCount = (result.shops || []).length;
                text("[data-selected-items-copy]", `${selectedCount} sản phẩm từ ${shopCount} cửa hàng sẽ được đặt trong một lần xác nhận.`);
                const totals = result.totals || result;
                const ready = result.ready === true && selectedCount > 0 && result.previewRevision != null
                    && totals.totalAmount != null && Number.isFinite(Number(totals.totalAmount))
                    && (result.shops || []).length === selectedShopIds.length
                    && result.shops.every(shop => shop.buyable === true && !shop.error);
                previewReady = ready;
                if (ready) {
                    previewStatus.textContent = conflict
                        ? "Tổng tiền đã được làm mới. Hãy kiểm tra thay đổi rồi xác nhận lại."
                        : "Giá, voucher và báo giá giao hàng đã sẵn sàng.";
                    if (feedback && conflict) showFeedback("Tổng tiền đã thay đổi. Hãy kiểm tra tổng mới rồi xác nhận lại đơn hàng.", "warning");
                    if (placeButton) placeButton.disabled = false;
                    scheduleQuoteExpiry(result);
                } else {
                    previewStatus.textContent = "Chưa thể đặt toàn bộ lựa chọn. Xem thông tin theo cửa hàng hoặc làm mới báo giá.";
                    const apiErrors = Array.isArray(result.errors) ? result.errors.map(error =>
                        typeof error === "string" ? error : error?.message || error?.detail || "").filter(Boolean).join(" ") : "";
                    if (feedback) showFeedback((result.shops || []).find(shop => shop.error)?.error
                        || apiErrors || "Một hoặc nhiều cửa hàng chưa có báo giá còn hiệu lực.");
                    if (placeButton) placeButton.disabled = true;
                    scheduleQuoteExpiry(result);
                }
                return ready;
            } catch (error) {
                if (requestId !== latestRequest) return false;
                previewReady = false;
                if (previewStatus) previewStatus.textContent = "Chưa hoàn tất kiểm tra giá.";
                showFeedback(error.message || "Không thể kiểm tra tổng tiền. Hãy thử lại.");
                if (placeButton) placeButton.disabled = true;
                return false;
            } finally {
                if (requestId === latestRequest) {
                    previewPending = false;
                    if (refreshButton) refreshButton.disabled = false;
                }
            }
        }

        function queuePreview(refreshQuotes = false) {
            latestRequest++;
            latestPreview = null;
            previewReady = false;
            previewPending = true;
            if (placeButton) placeButton.disabled = true;
            window.clearTimeout(debounceTimer);
            debounceTimer = window.setTimeout(() => preview({refreshQuotes}), 350);
        }

        restoreState();
        form.querySelectorAll("[data-checkout-cart-row]").forEach(row => {
            row.hidden = !selectedCartItemIds.includes(row.dataset.cartItemId);
        });
        shopCards.forEach((card, shopId) => {
            card.hidden = !selectedShopIds.includes(shopId);
        });

        form.addEventListener("input", event => {
            const target = event.target;
            if (target.matches("[data-shop-field]")) {
                saveState();
                queuePreview(false);
                return;
            }
            if (target.matches('[name="fullName"], [name="phone"], [name="shippingAddress"]')) {
                const addressId = document.getElementById("shippingAddressId");
                const addressSelect = document.getElementById("savedAddress");
                if (addressId) addressId.value = "";
                if (addressSelect) addressSelect.value = "";
                quoteIds.clear();
                saveState();
                queuePreview(true);
            }
        });
        form.addEventListener("change", event => {
            if (event.target.matches("[data-payment-method]")) {
                saveState();
                queuePreview(false);
                return;
            }
            if (event.target.matches('[name="provinceCode"], [name="wardCode"]')) {
                const addressId = document.getElementById("shippingAddressId");
                const addressSelect = document.getElementById("savedAddress");
                if (addressId) addressId.value = "";
                if (addressSelect) addressSelect.value = "";
                quoteIds.clear();
                saveState();
                queuePreview(true);
            }
        });
        form.addEventListener("checkoutaddresschanging", () => {
            quoteIds.clear();
            latestPreview = null;
            if (placeButton) placeButton.disabled = true;
        });
        form.addEventListener("checkoutaddresschange", () => {
            saveState();
            preview({refreshQuotes: true});
        });
        refreshButton?.addEventListener("click", () => preview({refreshQuotes: true}));

        form.addEventListener("submit", async event => {
            event.preventDefault();
            if (placing || previewPending) return;
            if (!latestPreview) {
                await preview({refreshQuotes: true});
                return;
            }
            placing = true;
            if (placeButton) placeButton.disabled = true;
            if (previewStatus) previewStatus.textContent = "Đang tạo các đơn hàng đã chọn.";
            const csrfToken = document.querySelector('meta[name="_csrf"]')?.content;
            const request = {
                ...currentRequest(false),
                idempotencyKey,
                previewRevision: latestPreview.previewRevision,
                expectedTotalAmount: (latestPreview.totals || latestPreview).totalAmount
            };
            try {
                const response = await fetch("/api/checkout/place", {
                    method: "POST",
                    credentials: "same-origin",
                    headers: {
                        "Content-Type": "application/json",
                        Accept: "application/json",
                        ...(csrfToken ? {"X-CSRF-TOKEN": csrfToken} : {})
                    },
                    body: JSON.stringify(request)
                });
                if (response.status === 409) {
                    const message = await responseError(response);
                    latestPreview = null;
                    if (previewStatus) previewStatus.textContent = "Tổng tiền đã thay đổi. Đang cập nhật báo giá.";
                    showFeedback(`${message} Các lựa chọn của bạn vẫn được giữ lại.`, "warning");
                    rotateIdempotencyKey();
                    await preview({refreshQuotes: true, conflict: true});
                    return;
                }
                if (!response.ok) throw new Error(await responseError(response));
                const result = await response.json();
                if (!result.groupId) throw new Error("Đơn đã được ghi nhận nhưng thiếu mã nhóm. Vui lòng xem lịch sử đơn hàng.");
                try { window.sessionStorage.removeItem(stateKey); window.sessionStorage.removeItem("ute-grouped-cart-selection"); }
                catch (_) { /* Navigation still works when storage is blocked. */ }
                window.location.assign(`/checkout-groups/${encodeURIComponent(result.groupId)}/success`);
            } catch (error) {
                showFeedback(error.message || "Không thể tạo đơn hàng. Kiểm tra kết nối rồi thử lại.");
                if (previewStatus) previewStatus.textContent = "Các lựa chọn vẫn được giữ lại. Bạn có thể thử đặt lại.";
            } finally {
                placing = false;
                if (placeButton) placeButton.disabled = !previewReady;
            }
        });

        preview({refreshQuotes: true});
    });
})();
