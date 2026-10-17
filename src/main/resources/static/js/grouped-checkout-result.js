/* Read group state through the owner-checked API; child orders remain shop-scoped. */
(function () {
    document.addEventListener("DOMContentLoaded", () => {
        const page = document.querySelector("[data-checkout-group-result]");
        if (!page) return;
        const status = page.querySelector("[data-group-load-status]");
        const body = page.querySelector("[data-group-result-body]");
        const orders = page.querySelector("[data-group-orders]");
        const paymentGuidance = page.querySelector("[data-group-payment-guidance]");
        const paymentAction = page.querySelector("[data-group-payment-action]");
        const paymentButton = page.querySelector("[data-start-group-payment]");
        const paymentActionStatus = page.querySelector("[data-group-payment-action-status]");
        const groupId = page.dataset.groupId;
        const groupUrl = page.dataset.groupUrl;
        let paymentSessionPending = false;

        const money = value => Number.isFinite(Number(value))
            ? new Intl.NumberFormat("vi-VN").format(Number(value)) + " đ" : "—";
        const showStatus = (message, kind = "info") => {
            status.classList.remove("alert-info", "alert-danger", "alert-success", "alert-warning");
            status.classList.add(`alert-${kind}`);
            status.textContent = message;
            status.hidden = false;
        };
        const setValue = (selector, value) => {
            const target = page.querySelector(selector);
            if (target) target.textContent = value == null || value === "" ? "—" : String(value);
        };
        const addField = (container, label, value) => {
            const row = document.createElement("p");
            row.className = "small text-secondary mb-1";
            row.textContent = `${label}: ${value || "—"}`;
            container.appendChild(row);
        };

        const showPaymentMessage = (message, kind = "info") => {
            if (!paymentActionStatus) return;
            paymentActionStatus.classList.remove("alert-info", "alert-danger", "alert-success", "alert-warning");
            paymentActionStatus.classList.add(`alert-${kind}`);
            paymentActionStatus.textContent = message;
            paymentActionStatus.hidden = !message;
        };

        async function responseMessage(response) {
            try {
                const result = await response.json();
                if (typeof result?.message === "string" && result.message.trim()) return result.message;
            } catch (_) {
                // Fall through to a local message when the API response is not JSON.
            }
            return response.status === 403
                ? "Phiên đăng nhập đã hết hạn hoặc bạn không có quyền thanh toán nhóm đơn hàng này."
                : `Không thể chuẩn bị thanh toán VNPAY (mã ${response.status}). Vui lòng thử lại.`;
        }

        async function startGroupPayment() {
            if (!paymentButton || paymentSessionPending || !groupId) return;
            paymentSessionPending = true;
            paymentButton.disabled = true;
            paymentButton.textContent = "Đang chuẩn bị thanh toán…";
            showPaymentMessage("");
            let redirecting = false;
            try {
                const csrfToken = document.querySelector('meta[name="_csrf"]')?.content;
                const response = await fetch(`/api/checkout-groups/${encodeURIComponent(groupId)}/payment-session`, {
                    method: "POST",
                    credentials: "same-origin",
                    headers: {
                        Accept: "application/json",
                        ...(csrfToken ? {"X-CSRF-TOKEN": csrfToken} : {})
                    }
                });
                if (!response.ok) throw new Error(await responseMessage(response));

                const result = await response.json();
                if (result.status === "READY") {
                    if (typeof result.paymentUrl !== "string" || !result.paymentUrl.trim()) {
                        throw new Error("Chưa nhận được liên kết thanh toán VNPAY. Vui lòng thử lại.");
                    }
                    redirecting = true;
                    window.location.assign(result.paymentUrl);
                    return;
                }
                if (result.status === "PROCESSING") {
                    showPaymentMessage(result.message || "Yêu cầu thanh toán đang được xử lý. Vui lòng theo dõi trạng thái đơn hàng.");
                    return;
                }
                if (result.status === "BLOCKED") {
                    showPaymentMessage(result.message || "Nhóm đơn hàng hiện chưa thể thanh toán bằng VNPAY.", "warning");
                    return;
                }
                showPaymentMessage(result.message || "Chưa thể chuẩn bị thanh toán VNPAY. Vui lòng thử lại.", "warning");
            } catch (error) {
                showPaymentMessage(error.message || "Không thể chuẩn bị thanh toán VNPAY. Vui lòng thử lại.", "danger");
            } finally {
                paymentSessionPending = false;
                if (!redirecting && paymentButton) {
                    paymentButton.disabled = false;
                    paymentButton.textContent = "Thanh toán bằng VNPAY";
                }
            }
        }

        paymentButton?.addEventListener("click", startGroupPayment);

        function renderGroup(group) {
            setValue("[data-group-number]", group.groupNumber);
            setValue("[data-group-payment-status]", group.paymentStatus);
            setValue("[data-group-fulfillment-status]", typeof group.fulfillmentSummary === "string"
                ? group.fulfillmentSummary : group.fulfillmentSummary?.status);
            setValue("[data-group-total]", money(group.totalAmount));
            orders.replaceChildren();
            (group.orders || []).forEach(order => {
                const card = document.createElement("article");
                card.className = "border rounded-3 p-3 p-md-4";
                const heading = document.createElement("div");
                heading.className = "d-flex flex-wrap justify-content-between align-items-start gap-2 mb-3";
                const title = document.createElement("h3");
                title.className = "h5 mb-0";
                title.textContent = order.shopName || "Cửa hàng";
                const orderNumber = document.createElement("strong");
                orderNumber.textContent = order.orderNumber || "Đơn hàng";
                heading.append(title, orderNumber);
                card.appendChild(heading);
                const details = document.createElement("div");
                details.className = "row g-2";
                const payment = document.createElement("div");
                payment.className = "col-md-4";
                addField(payment, "Thanh toán", order.paymentStatus);
                const fulfillment = document.createElement("div");
                fulfillment.className = "col-md-4";
                addField(fulfillment, "Giao hàng", order.fulfillmentStatus);
                const total = document.createElement("div");
                total.className = "col-md-4";
                addField(total, "Thành tiền", money(order.totalAmount));
                details.append(payment, fulfillment, total);
                card.appendChild(details);
                if (order.orderId) {
                    const link = document.createElement("a");
                    link.className = "btn btn-sm btn-outline-primary mt-3";
                    link.href = `/orders/${encodeURIComponent(order.orderId)}`;
                    link.textContent = "Xem đơn hàng";
                    card.appendChild(link);
                }
                orders.appendChild(card);
            });
            const isVnpay = String(group.paymentMethod || "").toUpperCase() === "VNPAY";
            const paymentStatus = String(group.paymentStatus || "").toUpperCase();
            const lifecycleStatus = String(group.lifecycleStatus || "").toUpperCase();
            const expiry = Date.parse(group.expiresAt || "");
            const expiryIsKnown = Boolean(group.expiresAt);
            const notExpired = !expiryIsKnown || (Number.isFinite(expiry) && expiry > Date.now());
            const lifecycleIsOpen = !lifecycleStatus || lifecycleStatus === "PENDING";
            const canStartVnpayPayment = isVnpay && paymentStatus === "PENDING" && notExpired && lifecycleIsOpen;
            if (paymentAction) paymentAction.hidden = !canStartVnpayPayment;
            if (paymentGuidance) {
                paymentGuidance.textContent = String(group.paymentMethod || "").toUpperCase() === "COD"
                    ? "Thanh toán COD được thu riêng cho từng đơn khi nhận hàng; trạng thái mỗi cửa hàng sẽ cập nhật độc lập."
                    : isVnpay && (paymentStatus === "EXPIRED" || (Number.isFinite(expiry) && expiry <= Date.now()))
                        ? "Thời hạn thanh toán nhóm đã kết thúc. Hãy xem trạng thái của từng đơn trong lịch sử."
                        : canStartVnpayPayment
                            ? "Nhóm VNPAY đang chờ thanh toán. Bạn có thể tiếp tục thanh toán bên dưới hoặc xem trạng thái trong lịch sử đơn hàng."
                            : isVnpay
                                ? "Hãy xem trạng thái thanh toán của nhóm và từng đơn trong lịch sử đơn hàng."
                            : "";
                paymentGuidance.hidden = !paymentGuidance.textContent;
            }
            body.classList.remove("d-none");
            status.hidden = true;
        }

        async function loadGroup() {
            showStatus("Đang tải trạng thái đơn hàng…");
            try {
                const response = await fetch(groupUrl, {
                    credentials: "same-origin",
                    headers: {Accept: "application/json"}
                });
                if (!response.ok) throw new Error(response.status === 403
                    ? "Bạn không có quyền xem nhóm đơn hàng này."
                    : `Không thể tải trạng thái đơn hàng (mã ${response.status}).`);
                renderGroup(await response.json());
            } catch (error) {
                showStatus(error.message || "Không thể tải trạng thái đơn hàng. Hãy thử lại.", "danger");
                let retry = page.querySelector("[data-retry-group-load]");
                if (!retry) {
                    retry = document.createElement("button");
                    retry.type = "button";
                    retry.className = "btn btn-outline-primary mt-2";
                    retry.dataset.retryGroupLoad = "true";
                    retry.textContent = "Tải lại trạng thái";
                    status.after(retry);
                    retry.addEventListener("click", loadGroup);
                }
            }
        }

        loadGroup();
    });
})();
