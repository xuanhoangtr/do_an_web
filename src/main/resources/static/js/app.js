/* Shared unobtrusive behaviors for Thymeleaf pages. */
(function () {
    function scrollProfileSection() {
        if (!window.location.hash) return;
        let id;
        try { id = decodeURIComponent(window.location.hash.substring(1)); } catch (_) { return; }
        const section = document.getElementById(id);
        if (section) {
            section.scrollIntoView({block: "start"});
            if (section.matches('[tabindex="-1"]')) section.focus({preventScroll: true});
        }
    }
    window.addEventListener("hashchange", scrollProfileSection);
    document.addEventListener("DOMContentLoaded", scrollProfileSection);
    document.addEventListener("click", event => {
        const link = event.target instanceof Element ? event.target.closest('a[href="#top"]') : null;
        if (!link) return;
        event.preventDefault();
        const behavior = window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "instant" : "smooth";
        window.scrollTo({top: 0, behavior});
    });
    function addCsrfInputs() {
        const token = document.querySelector('meta[name="_csrf"]')?.content;
        const parameterName = document.querySelector('meta[name="_csrf_parameter"]')?.content || "_csrf";
        if (!token) return;
        document.querySelectorAll('form[method="post"], form[method="POST"]').forEach(form => {
            if (form.querySelector(`input[name="${parameterName}"]`)) return;
            const input = document.createElement("input");
            input.type = "hidden";
            input.name = parameterName;
            input.value = token;
            form.appendChild(input);
        });
    }

    function replaceCartContent(html) {
        const parsed = parseServerHtml(html);
        const replacement = parsed.body.querySelector("[data-cart-container]");
        const current = document.querySelector("[data-cart-container]");
        if (!replacement) throw new Error("Phản hồi giỏ hàng không hợp lệ");
        const count = Number(replacement.dataset.cartCount || 0);
        if (!current) {
            const feedback = replacement.querySelector("#cartFeedback");
            if (!feedback) throw new Error("Phản hồi giỏ hàng không hợp lệ");
            updateCartBadge(count);
            showCartFeedback(feedback.textContent, !feedback.classList.contains("alert-danger"));
            return;
        }
        current.replaceWith(document.importNode(replacement, true));
        updateCartBadge(count);
        addCsrfInputs();
        initAddressDirectories();
    }

    function updateCartBadge(count) {
        const badge = document.getElementById("cartBadge");
        if (!badge) return;
        const safeCount = Number.isFinite(count) ? Math.max(0, count) : 0;
        badge.hidden = safeCount <= 0;
        badge.style.display = safeCount <= 0 ? "none" : "";
        badge.textContent = safeCount > 99 ? "99+" : String(safeCount);
    }

    let cartToastTimer;
    function showCartToast(message, success) {
        let toast = document.getElementById("cartToast");
        if (!toast) {
            toast = document.createElement("div");
            toast.id = "cartToast";
            toast.setAttribute("role", "status");
            toast.setAttribute("aria-live", "polite");
            toast.setAttribute("aria-atomic", "true");
            toast.style.zIndex = "1090";
            toast.style.maxWidth = "calc(100vw - 2rem)";
            document.body.appendChild(toast);
        }
        clearTimeout(cartToastTimer);
        toast.className = `alert ${success ? "alert-success" : "alert-danger"} position-fixed top-0 end-0 m-3 shadow-sm`;
        toast.textContent = success ? "Thêm giỏ hàng thành công" : message;
        toast.hidden = false;
        cartToastTimer = setTimeout(() => { toast.hidden = true; }, success ? 3000 : 6000);
    }

    function showCartFeedback(message, success = false) {
        const container = document.querySelector("[data-cart-container]");
        if (!container) {
            showCartToast(message, success);
            return;
        }
        let feedback = container.querySelector("[data-cart-feedback], #cartFeedback");
        if (!feedback) {
            feedback = document.createElement("div");
            feedback.setAttribute("data-cart-feedback", "");
            feedback.setAttribute("role", "status");
            container.prepend(feedback);
        }
        feedback.className = `col-12 alert ${success ? "alert-success" : "alert-danger"} border-0 shadow-sm`;
        feedback.textContent = message;
    }

    async function responseMessage(response) {
        const contentType = response.headers.get("content-type") || "";
        if (contentType.includes("application/json")) {
            try {
                const payload = await response.json();
                return payload.message || payload.detail || payload.error || "Không thể cập nhật giỏ hàng. Vui lòng thử lại.";
            } catch (_) { return "Không thể cập nhật giỏ hàng. Vui lòng thử lại."; }
        }
        try {
            const html = await response.text();
            const parsed = parseServerHtml(html);
            const feedback = parsed.querySelector("#cartFeedback, [role='alert'], .alert");
            return feedback?.textContent?.trim() || "Không thể cập nhật giỏ hàng. Vui lòng thử lại.";
        } catch (_) { return "Không thể cập nhật giỏ hàng. Vui lòng thử lại."; }
    }

    // These fragments are rendered by Thymeleaf with escaped user fields.
    // Parse them as an inert document and import only the selected nodes.
    function parseServerHtml(html) {
        return new DOMParser().parseFromString(html.trim(), "text/html");
    }

    document.addEventListener("DOMContentLoaded", () => {
        addCsrfInputs();
        if (!document.getElementById("liveUpdateConfig") || typeof connectStompUpdates !== "function") return;
        connectStompUpdates({
            onConnect: () => refreshNotificationBadge(),
            onMessage: () => refreshNotificationBadge()
        });
        refreshNotificationBadge();
    });

    async function refreshNotificationBadge() {
        const badge = document.getElementById("notificationBadge");
        if (!badge && !document.getElementById("liveUpdateConfig")) return;
        try {
            const response = await fetch("/notifications/unread-count", {
                headers: { Accept: "application/json" },
                credentials: "same-origin"
            });
            if (!response.ok) return;
            const payload = await response.json();
            const count = Number(payload.count || 0);
            if (!badge) return;
            badge.hidden = count <= 0;
            badge.textContent = count > 99 ? "99+" : String(count);
        } catch (error) {
            /* The notification center still reads durable rows when the socket is down. */
        }
    }

    let pendingConfirmationForm = null;
    let pendingConfirmationSubmitter = null;
    function submitConfirmedForm(form, submitter) {
        form.dataset.confirmedOnce = "true";
        try {
            if (submitter instanceof HTMLElement && submitter.isConnected) form.requestSubmit(submitter);
            else form.requestSubmit();
        } finally {
            // requestSubmit does not dispatch submit when native validation fails.
            // In that case the next user attempt must still require confirmation.
            delete form.dataset.confirmedOnce;
        }
    }
    function confirmationFor(form) {
        const kind = form.dataset.adminConfirm;
        if (kind === "role") {
            const select = form.querySelector('[name="newRole"]');
            const oldRole = select?.dataset.currentRole;
            const newRole = select?.value;
            if (oldRole === newRole) return null;
            return { title: "Thay đổi quyền người dùng",
                message: `Xác nhận đổi quyền từ ${oldRole} thành ${newRole}? Quyền truy cập sẽ thay đổi và các phiên đăng nhập hiện tại sẽ bị thu hồi.`,
                label: "Đổi quyền", tone: "danger" };
        }
        if (kind === "status") {
            const enabled = form.querySelector('[name="enabled"]')?.value === "true";
            return { title: enabled ? "Mở khóa tài khoản" : "Khóa tài khoản",
                message: enabled ? "Tài khoản sẽ được kích hoạt lại. Các phiên đăng nhập hiện tại có thể bị thu hồi."
                    : "Tài khoản sẽ bị vô hiệu hóa và không thể đăng nhập. Các phiên đăng nhập hiện tại sẽ bị thu hồi.",
                label: enabled ? "Mở khóa" : "Khóa tài khoản", tone: enabled ? "primary" : "danger" };
        }
        if (kind === "delete") return { title: "Vô hiệu hóa tài khoản",
            message: "Tài khoản sẽ được đánh dấu đã xóa và bị vô hiệu hóa; các phiên đăng nhập sẽ bị thu hồi. Bản ghi user không bị hard-delete, đơn hàng/lịch sử được giữ nguyên và tài khoản có thể được khôi phục.",
            label: "Vô hiệu hóa tài khoản", tone: "danger" };
        if (kind === "restore") return { title: "Khôi phục tài khoản",
            message: "Tài khoản sẽ được chuyển về trạng thái hoạt động. Các phiên đăng nhập hiện tại có thể bị thu hồi.",
            label: "Khôi phục", tone: "primary" };
        const message = form.dataset.confirmMessage || form.dataset.confirm;
        return message ? { title: form.dataset.confirmTitle || "Xác nhận thao tác", message,
            label: form.dataset.confirmLabel || "Xác nhận", tone: form.dataset.confirmTone || "danger" } : null;
    }
    function confirmationModal() {
        let modal = document.getElementById("confirmation-modal");
        if (modal) return modal;
        modal = document.createElement("div");
        modal.id = "confirmation-modal";
        modal.className = "modal fade";
        modal.tabIndex = -1;
        modal.setAttribute("aria-labelledby", "confirmation-modal-title");
        modal.setAttribute("aria-hidden", "true");
        modal.innerHTML = '<div class="modal-dialog modal-dialog-centered"><div class="modal-content">'
            + '<div class="modal-header"><h2 class="modal-title fs-5" id="confirmation-modal-title"></h2>'
            + '<button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="Đóng"></button></div>'
            + '<div class="modal-body" id="confirmation-modal-message"></div><div class="modal-footer">'
            + '<button type="button" class="btn btn-outline-secondary" data-bs-dismiss="modal">Hủy</button>'
            + '<button type="button" class="btn btn-danger" id="confirmation-modal-submit">Xác nhận</button>'
            + '</div></div></div>';
        document.body.append(modal);
        modal.querySelector("#confirmation-modal-submit").addEventListener("click", () => {
            const form = pendingConfirmationForm;
            const submitter = pendingConfirmationSubmitter;
            pendingConfirmationForm = null;
            pendingConfirmationSubmitter = null;
            window.bootstrap.Modal.getOrCreateInstance(modal).hide();
            if (!form?.isConnected) return;
            submitConfirmedForm(form, submitter);
        });
        modal.addEventListener("hidden.bs.modal", () => {
            pendingConfirmationForm = null;
            pendingConfirmationSubmitter = null;
        });
        return modal;
    }

    document.addEventListener("submit", event => {
        const form = event.target;
        if (!(form instanceof HTMLFormElement)) return;
        if (form.dataset.confirmedOnce === "true") {
            delete form.dataset.confirmedOnce;
            form.dataset.confirmSubmitting = "true";
            // Keep native submitter name/value successful while the duplicate-submit guard is active.
            form.querySelectorAll('button[type="submit"], input[type="submit"]').forEach(button => button.setAttribute("aria-disabled", "true"));
            return;
        }
        if (form.dataset.confirmSubmitting === "true") {
            event.preventDefault();
            return;
        }
        const prompt = confirmationFor(form);
        if (!prompt) return;
        event.preventDefault();
        if (!window.bootstrap?.Modal) {
            if (window.confirm(prompt.message)) {
                submitConfirmedForm(form, event.submitter);
            }
            return;
        }
        const modal = confirmationModal();
        modal.querySelector("#confirmation-modal-title").textContent = prompt.title;
        modal.querySelector("#confirmation-modal-message").textContent = prompt.message;
        const confirmButton = modal.querySelector("#confirmation-modal-submit");
        confirmButton.textContent = prompt.label;
        confirmButton.className = `btn btn-${prompt.tone}`;
        pendingConfirmationForm = form;
        pendingConfirmationSubmitter = event.submitter;
        window.bootstrap.Modal.getOrCreateInstance(modal).show();
    });

    document.addEventListener("click", event => {
        if (event.target.closest("[data-print-invoice]")) window.print();
    });

    document.addEventListener("DOMContentLoaded", () => {
        const albumFile = document.getElementById("albumImage");
        const previewWrap = document.getElementById("imagePreviewWrap");
        const previewImage = document.getElementById("imagePreview");
        let previewUrl = null;
        albumFile?.addEventListener("change", () => {
            if (previewUrl) URL.revokeObjectURL(previewUrl);
            const file = albumFile.files?.[0];
            if (!file || !previewImage || !previewWrap) {
                previewWrap?.classList.add("d-none");
                return;
            }
            previewUrl = URL.createObjectURL(file);
            previewImage.src = previewUrl;
            const name = document.getElementById("imagePreviewName");
            const size = document.getElementById("imagePreviewSize");
            if (name) name.textContent = file.name;
            if (size) size.textContent = `${(file.size / 1024 / 1024).toFixed(2)} MB`;
            previewWrap.classList.remove("d-none");
        });
        window.addEventListener("pagehide", () => { if (previewUrl) URL.revokeObjectURL(previewUrl); }, {once: true});
        document.getElementById("albumLightbox")?.addEventListener("show.bs.modal", event => {
            const trigger = event.relatedTarget;
            if (!trigger) return;
            const image = document.getElementById("albumLightboxImage");
            const title = document.getElementById("albumLightboxTitle");
            if (image) image.src = trigger.dataset.imageUrl || "";
            if (title) title.textContent = trigger.dataset.imageCaption || "Ảnh album";
        });

        const guestSearch = document.getElementById("guestSearch");
        const rsvpFilter = document.getElementById("rsvpFilter");
        const guestRows = [...document.querySelectorAll("#guestTableBody tr[data-guest-name]")];
        const noGuests = document.getElementById("guestNoMatches");
        const filterGuests = () => {
            const query = (guestSearch?.value || "").trim().toLocaleLowerCase("vi");
            const state = rsvpFilter?.value || "";
            let visible = 0;
            guestRows.forEach(row => {
                const show = row.dataset.guestName.toLocaleLowerCase("vi").includes(query)
                    && (!state || row.dataset.rsvp === state);
                row.classList.toggle("d-none", !show);
                if (show) {
                    visible++;
                    const numberCell = row.querySelector("td");
                    if (numberCell) numberCell.textContent = String(visible);
                }
            });
            noGuests?.classList.toggle("d-none", visible > 0 || guestRows.length === 0);
        };
        guestSearch?.addEventListener("input", filterGuests);
        rsvpFilter?.addEventListener("change", filterGuests);

        const timelineRows = document.getElementById("timelineRows");
        const timelineTemplate = document.getElementById("timelineTemplate");
        const timelineEmpty = document.getElementById("timelineEmpty");
        const syncTimelineEmpty = () => timelineEmpty?.classList.toggle("d-none",
            (timelineRows?.querySelectorAll(".timeline-row").length || 0) > 0);
        document.getElementById("addTimelineRow")?.addEventListener("click", () => {
            if (!timelineRows || !timelineTemplate) return;
            if (timelineRows.querySelectorAll(".timeline-row").length >= 100) {
                window.alert("Lịch trình tối đa 100 mốc.");
                return;
            }
            timelineRows.appendChild(timelineTemplate.content.cloneNode(true));
            syncTimelineEmpty();
        });
        timelineRows?.addEventListener("click", event => {
            const button = event.target.closest(".remove-timeline-row");
            if (!button) return;
            const row = button.closest(".timeline-row");
            if (row.dataset.saved === "true" && !window.confirm("Xóa mốc này khỏi lịch trình khi bạn lưu thay đổi?")) return;
            row.remove();
            syncTimelineEmpty();
        });
        document.querySelectorAll(".privacy-approve-form").forEach(form => {
            const check = form.querySelector(".privacy-approve-check");
            const button = form.querySelector(".privacy-approve-button");
            check?.addEventListener("change", () => { if (button) button.disabled = !check.checked; });
        });
    });

    async function loadWards(provinceSelect, wardSelect, selectedWard, status) {
        const provinceCode = provinceSelect.value;
        const notifyCheckout = () => {
            const checkout = provinceSelect.closest("#checkoutForm");
            if (checkout) checkout.dispatchEvent(new Event("checkoutaddresschange"));
        };
        wardSelect.replaceChildren(new Option(
            provinceCode ? "Đang tải phường/xã..." : "Chọn tỉnh/thành trước", ""));
        wardSelect.disabled = true;
        if (!provinceCode) {
            notifyCheckout();
            return;
        }
        try {
            const response = await fetch("/api/v1/provinces/" + encodeURIComponent(provinceCode) + "/wards",
                { headers: { Accept: "application/json" } });
            if (!response.ok) throw new Error("Không thể tải danh sách phường/xã.");
            const wards = await response.json();
            wardSelect.replaceChildren(new Option("Chọn phường / xã", ""));
            wards.forEach(item => wardSelect.add(new Option(item.name, item.code)));
            wardSelect.value = selectedWard || "";
            wardSelect.disabled = false;
            if (status) status.textContent = "";
        } catch (error) {
            wardSelect.replaceChildren(new Option("Không tải được danh sách", ""));
            if (status) status.textContent = error.message;
        } finally {
            notifyCheckout();
        }
    }

    function initAddressDirectories(scope = document) {
        scope.querySelectorAll("[data-address-directory]").forEach(container => {
            if (container.dataset.addressReady === "true") return;
            const province = container.querySelector("[data-address-province]");
            const ward = container.querySelector("[data-address-ward]");
            const status = container.querySelector("[data-address-status]");
            if (!province || !ward) return;
            container.dataset.addressReady = "true";
            const selectedProvince = container.dataset.selectedProvince || province.value || "";
            const selectedWard = container.dataset.selectedWard || ward.value || "";
            province.replaceChildren(new Option("Chọn tỉnh / thành", ""));
            fetch("/api/v1/provinces", { headers: { Accept: "application/json" } })
                .then(response => {
                    if (!response.ok) throw new Error("Không thể tải danh sách tỉnh/thành.");
                    return response.json();
                })
                .then(items => {
                    const activeSource = items[0]?.catalogSource || "LOCAL";
                    container.dataset.activeCatalogSource = activeSource;
                    items.forEach(item => province.add(new Option(item.name, item.code)));
                    const selectedSaved = container.querySelector("[data-fill-saved-address]");
                    const selectedSavedOption = selectedSaved?.options[selectedSaved.selectedIndex];
                    const usesOldSavedCatalog = Boolean(selectedSaved?.value && selectedSavedOption
                        && (selectedSavedOption.dataset.catalogSource || "LOCAL") !== activeSource);
                    if (usesOldSavedCatalog) {
                        const addressId = container.querySelector("#shippingAddressId");
                        if (addressId) addressId.value = "";
                        if (status) status.textContent = "Địa chỉ đã lưu dùng danh mục cũ. Thông tin người nhận được giữ lại; hãy chọn lại tỉnh/thành và phường/xã.";
                    }
                    const desiredProvince = province.value || selectedProvince;
                    const desiredWard = ward.value || selectedWard;
                    const provinceExists = Array.from(province.options).some(option => option.value === desiredProvince);
                    if (desiredProvince && provinceExists) {
                        province.value = desiredProvince;
                        if (desiredWard) loadWards(province, ward, desiredWard, status);
                    } else {
                        province.value = "";
                        ward.replaceChildren(new Option("Chọn tỉnh/thành trước", ""));
                        ward.disabled = true;
                        if (desiredProvince && status && !usesOldSavedCatalog) {
                            status.textContent = "Địa chỉ đã lưu dùng danh mục cũ. Thông tin người nhận được giữ lại; hãy chọn lại tỉnh/thành và phường/xã.";
                            const addressId = container.querySelector("#shippingAddressId");
                            if (addressId) addressId.value = "";
                        }
                        container.querySelector("#checkoutForm")?.dispatchEvent(new Event("checkoutaddresschange"));
                    }
                    if (!items.length && status) status.textContent = "Danh mục địa chỉ hiện chưa sẵn sàng. Vui lòng thử lại sau.";
                })
                .catch(error => {
                    province.replaceChildren(new Option("Không tải được danh sách", ""));
                    if (status) status.textContent = error.message;
                });
            province.addEventListener("change", () => loadWards(province, ward, "", status));
        });
    }

    document.addEventListener("DOMContentLoaded", () => {
        initAddressDirectories();
        const select = document.querySelector("[data-fill-saved-address]");
        if (!select) return;
        select.addEventListener("change", async () => {
            document.getElementById("checkoutForm")?.dispatchEvent(new Event("checkoutaddresschanging"));
            const option = select.options[select.selectedIndex];
            const hasSavedAddress = Boolean(select.value && option);
            const addressId = document.getElementById("shippingAddressId");
            if (addressId) addressId.value = hasSavedAddress ? select.value : "";
            [["fullName", "name"], ["phone", "phone"], ["shippingAddress", "line"]].forEach(([field, key]) => {
                const input = document.getElementById(field);
                if (input) input.value = hasSavedAddress ? (option.dataset[key] || "") : "";
            });
            const province = document.querySelector("[data-address-province]");
            const ward = document.querySelector("[data-address-ward]");
            if (province && ward) {
                const directory = province.closest("[data-address-directory]");
                const activeSource = directory?.dataset.activeCatalogSource || "LOCAL";
                const savedSource = option?.dataset.catalogSource || "LOCAL";
                const status = document.querySelector("[data-address-status]");
                const oldCatalog = hasSavedAddress && savedSource !== activeSource;
                province.value = hasSavedAddress && !oldCatalog ? (option.dataset.provinceCode || "") : "";
                if (oldCatalog) {
                    if (addressId) addressId.value = "";
                    ward.replaceChildren(new Option("Chọn tỉnh/thành trước", ""));
                    ward.disabled = true;
                    if (status) status.textContent = "Địa chỉ đã lưu dùng danh mục cũ. Thông tin người nhận được giữ lại; hãy chọn lại tỉnh/thành và phường/xã.";
                    document.getElementById("checkoutForm")?.dispatchEvent(new Event("checkoutaddresschange"));
                } else {
                    await loadWards(province, ward, hasSavedAddress ? (option.dataset.wardCode || "") : "", status);
                }
            }
        });
    });

    document.addEventListener("DOMContentLoaded", () => {
        const form = document.getElementById("profileAddressForm");
        if (!form) return;
        const cancel = form.querySelector("[data-cancel-address-edit]");
        const setValue = (id, value) => {
            const input = document.getElementById(id);
            if (input) input.value = value || "";
        };
        document.addEventListener("click", async event => {
            const button = event.target.closest("[data-edit-address]");
            if (!button) return;
            form.action = "/profile/addresses/" + encodeURIComponent(button.dataset.id);
            setValue("addressLabel", button.dataset.label);
            setValue("recipientName", button.dataset.name);
            setValue("addressPhone", button.dataset.phone);
            setValue("addressLine", button.dataset.line);
            const province = form.querySelector("[data-address-province]");
            const ward = form.querySelector("[data-address-ward]");
            const activeSource = form.dataset.activeCatalogSource || "LOCAL";
            const status = form.querySelector("[data-address-status]");
            if ((button.dataset.catalogSource || "LOCAL") !== activeSource) {
                province.value = "";
                ward.replaceChildren(new Option("Chọn tỉnh/thành trước", ""));
                ward.disabled = true;
                if (status) status.textContent = "Địa chỉ đã lưu dùng danh mục cũ. Thông tin người nhận được giữ lại; hãy chọn lại tỉnh/thành và phường/xã.";
            } else {
                province.value = button.dataset.provinceCode || "";
                await loadWards(province, ward, button.dataset.wardCode || "", status);
            }
            if (cancel) cancel.hidden = false;
            form.scrollIntoView({ behavior: "smooth", block: "center" });
        });
        cancel?.addEventListener("click", () => {
            form.action = "/profile/addresses";
            ["addressLabel", "recipientName", "addressPhone", "addressLine"].forEach(id => setValue(id, ""));
            const province = form.querySelector("[data-address-province]");
            const ward = form.querySelector("[data-address-ward]");
            province.value = "";
            ward.replaceChildren(new Option("Chọn tỉnh/thành trước", ""));
            ward.disabled = true;
            cancel.hidden = true;
        });
    });

    document.addEventListener("DOMContentLoaded", () => {
        const grid = document.querySelector("[data-lazy-products='true']");
        const sentinel = document.getElementById("productLoadSentinel");
        const fallback = document.getElementById("productLoadMoreFallback");
        if (!grid || !sentinel || !window.IntersectionObserver || grid.dataset.hasNext !== "true") return;
        let loading = false;
        const loadNextPage = async () => {
            if (loading || grid.dataset.hasNext !== "true") return;
            loading = true;
            const nextPage = Number(grid.dataset.page || 0) + 1;
            const params = new URLSearchParams({
                keyword: grid.dataset.keyword || "",
                category: grid.dataset.category || "",
                material: grid.dataset.material || "",
                maxPrice: grid.dataset.maxPrice || "",
                page: String(nextPage),
                size: "24",
                sort: grid.dataset.sort || "newest"
            });
            try {
                const response = await fetch(`/products/fragment?${params.toString()}`, {
                    headers: { "X-Requested-With": "XMLHttpRequest", Accept: "text/html" }
                });
                if (!response.ok) throw new Error("Không thể tải thêm sản phẩm");
                const html = await response.text();
                const parsed = parseServerHtml(html);
                const fragment = document.createDocumentFragment();
                Array.from(parsed.body.childNodes).forEach(node =>
                    fragment.appendChild(document.importNode(node, true)));
                grid.appendChild(fragment);
                grid.dataset.page = String(nextPage);
                const hasNext = response.headers.get("X-Has-Next");
                grid.dataset.hasNext = hasNext === "true" ? "true" : "false";
                if (grid.dataset.hasNext !== "true") {
                    fallback?.remove();
                    observer.disconnect();
                }
                addCsrfInputs();
            } catch (error) {
                // Keep the normal link fallback visible when JavaScript/network fails.
                if (fallback) fallback.hidden = false;
            } finally {
                loading = false;
            }
        };
        if (fallback) fallback.hidden = true;
        const observer = new IntersectionObserver(entries => {
            if (entries.some(entry => entry.isIntersecting)) loadNextPage();
        }, { rootMargin: "500px" });
        observer.observe(sentinel);
    });

    document.addEventListener("change", event => {
        const input = event.target.closest("[data-review-media]");
        if (!input) return;
        if (input.files.length > 5) {
            input.value = "";
            window.alert("Mỗi đánh giá được đính kèm tối đa 5 ảnh.");
        }
    });

    document.addEventListener("DOMContentLoaded", () => {
        const brideInput = document.getElementById("brideName");
        const groomInput = document.getElementById("groomName");
        const dateInput = document.getElementById("weddingDate");
        const bridePreview = document.getElementById("previewBrideName");
        const groomPreview = document.getElementById("previewGroomName");
        const datePreview = document.getElementById("previewWeddingDate");
        const coverInput = document.getElementById("coverImage");
        const coverPreview = document.getElementById("previewCoverImage");
        const coverPlaceholder = document.getElementById("previewCoverPlaceholder");
        if (!brideInput || !groomInput || !bridePreview || !groomPreview) return;

        const syncText = () => {
            bridePreview.textContent = brideInput.value.trim();
            groomPreview.textContent = groomInput.value.trim();
            if (dateInput && datePreview) {
                const parts = dateInput.value.split("-");
                const formatted = parts.length === 3 ? `${parts[2]} · ${parts[1]} · ${parts[0]}` : "";
                datePreview.textContent = formatted;
                datePreview.style.display = formatted ? "" : "none";
            }
        };
        [brideInput, groomInput, dateInput].filter(Boolean).forEach(input => input.addEventListener("input", syncText));
        syncText();

        coverInput?.addEventListener("change", () => {
            const file = coverInput.files?.[0];
            if (!file || !coverPreview) return;
            if (coverPreview.dataset.objectUrl) URL.revokeObjectURL(coverPreview.dataset.objectUrl);
            const objectUrl = URL.createObjectURL(file);
            coverPreview.dataset.objectUrl = objectUrl;
            coverPreview.src = objectUrl;
            coverPreview.style.display = "";
            if (coverPlaceholder) coverPlaceholder.style.display = "none";
        });
    });

    document.addEventListener("click", event => {
        const button = event.target.closest("[data-gallery-image]");
        if (!button) return;
        const image = document.getElementById("mainProductImage");
        const source = button.dataset.image;
        if (!image || !source) return;
        image.src = source;
        document.querySelectorAll(".gallery-thumb").forEach(item => item.classList.remove("active"));
        button.classList.add("active");
    });

    document.addEventListener("change", event => {
        const select = event.target.closest("[data-submit-on-change]");
        if (select?.form) select.form.submit();
    });

    let cartMutationPending = false;
    document.addEventListener("submit", async event => {
        if (event.defaultPrevented) return;
        const form = event.target.closest("form");
        if (!form) return;

        if (!form.matches("form[data-cart-action]")) return;
        if (event.submitter?.name === "redirect" && event.submitter.value === "checkout") return;
        event.preventDefault();
        if (cartMutationPending || form.dataset.pending === "true") return;
        cartMutationPending = true;
        form.dataset.pending = "true";
        const container = document.querySelector("[data-cart-container]");
        if (container) container.setAttribute("aria-busy", "true");
        const buttons = Array.from(document.querySelectorAll("form[data-cart-action] button"), button => [button, button.disabled]);
        buttons.forEach(([button]) => button.disabled = true);
        try {
            const csrfToken = document.querySelector('meta[name="_csrf"]')?.content;
            // The hidden input named "action" shadows HTMLFormElement.action.
            // Read the attribute so fetch receives /cart/update, not an input element.
            const response = await fetch(form.getAttribute("action") || window.location.href, {
                method: "POST",
                body: new FormData(form),
                headers: {
                    "X-Requested-With": "XMLHttpRequest",
                    "Accept": "text/html",
                    ...(csrfToken ? { "X-CSRF-TOKEN": csrfToken } : {})
                }
            });
            if (!response.ok) throw new Error(await responseMessage(response));
            replaceCartContent(await response.text());
        } catch (error) {
            showCartFeedback(error.message || "Không thể cập nhật giỏ hàng. Vui lòng thử lại.");
        } finally {
            cartMutationPending = false;
            form.dataset.pending = "false";
            delete form.dataset.confirmSubmitting;
            document.querySelector("[data-cart-container]")?.removeAttribute("aria-busy");
            buttons.forEach(([button, disabled]) => button.disabled = disabled);
        }
    });
})();

(function () {
    document.addEventListener("DOMContentLoaded", () => {
        const button = document.querySelector("[data-favorite-product]");
        if (!button) return;
        const productId = button.dataset.productId;
        const token = document.querySelector('meta[name="_csrf"]')?.content;
        const label = button.querySelector("[data-favorite-label]");
        const sync = state => {
            button.setAttribute("aria-pressed", String(state.favorited));
            button.firstChild.textContent = state.favorited ? "♥ " : "♡ ";
            if (label) label.textContent = state.favorited ? "Bỏ yêu thích" : "Thêm vào yêu thích";
        };
        const request = async method => {
            const response = await fetch(`/products/${productId}/favorite`, {
                method, credentials: "same-origin",
                headers: { "X-CSRF-TOKEN": token || "", Accept: "application/json" }
            });
            if (!response.ok) throw new Error("Không thể cập nhật yêu thích");
            return response.json();
        };
        request("GET").then(sync).catch(() => {});
        button.addEventListener("click", async () => {
            button.disabled = true;
            try { sync(await request(button.getAttribute("aria-pressed") === "true" ? "DELETE" : "POST")); }
            catch (error) { window.alert(error.message); }
            finally { button.disabled = false; }
        });
    });

})();

async function postForm(url, formData) {
    const response = await fetch(url, {
        method: "POST",
        body: formData,
        headers: { "X-Requested-With": "XMLHttpRequest" }
    });
    if (!response.ok) throw new Error("Không thể hoàn tất thao tác");
    return response;
}

function connectLiveUpdates(endpoint, onMessage) {
    if (!window.WebSocket) return null;
    const socket = new WebSocket(endpoint);
    socket.addEventListener("message", event => onMessage?.(JSON.parse(event.data)));
    return socket;
}

function connectStompUpdates(options, legacyTopic, legacyHandler) {
    if (!window.StompJs) return null;
    const config = typeof options === "string"
        ? { brokerUrl: options, destinations: [legacyTopic], onMessage: legacyHandler }
        : (options || {});
    const brokerUrl = config.brokerUrl || "/ws-utewedding";
    const renderedDestinations = document.querySelector('meta[name="websocket-destinations"]')?.content || "";
    const allowedDestinations = renderedDestinations.split(",")
        .map(destination => destination.trim())
        .filter(Boolean);
    const requestedDestinations = config.destinations || allowedDestinations;
    const destinations = requestedDestinations
        .filter(destination => typeof destination === "string")
        .filter(destination => allowedDestinations.length === 0
            || allowedDestinations.includes(destination));
    const state = window.__uteSharedStomp || (window.__uteSharedStomp = {
        client: null, handlers: new Map(), callbacks: new Set(), subscriptions: new Map(), connected: false
    });
    destinations.forEach(destination => {
        if (!state.handlers.has(destination)) state.handlers.set(destination, new Set());
        if (typeof config.onMessage === "function") state.handlers.get(destination).add(config.onMessage);
    });
    if (typeof config.onConnect === "function") state.callbacks.add(config.onConnect);

    const subscribe = destination => {
        if (!state.connected || state.subscriptions.has(destination) || !state.client) return;
        state.subscriptions.set(destination, state.client.subscribe(destination, frame => {
            try {
                const payload = JSON.parse(frame.body);
                state.handlers.get(destination)?.forEach(handler => handler(payload));
            } catch (_) { /* ignore malformed live-update events */ }
        }));
    };
    if (!state.client) {
        const rawToken = config.token || document.querySelector('meta[name="access-token"]')?.content;
        const token = rawToken && /^Bearer\s/i.test(rawToken) ? rawToken : (rawToken ? `Bearer ${rawToken}` : null);
        const client = new window.StompJs.Client({
            webSocketFactory: () => window.SockJS ? new window.SockJS(brokerUrl) : new WebSocket(brokerUrl),
            connectHeaders: token ? { Authorization: token } : {},
            reconnectDelay: 5000
        });
        state.client = client;
        client.onConnect = () => {
            state.connected = true;
            state.handlers.forEach((_, destination) => subscribe(destination));
            state.callbacks.forEach(callback => callback());
        };
        client.onWebSocketClose = () => {
            state.connected = false;
            state.subscriptions.clear();
        };
        client.onStompError = () => {
            state.connected = false;
            state.subscriptions.clear();
        };
        client.activate();
    } else if (state.connected) {
        destinations.forEach(subscribe);
    }
    return state.client;
}

(function () {
    document.addEventListener("DOMContentLoaded", () => {
        const form = document.querySelector("form[data-checkout-voucher]");
        const applyButton = form?.querySelector("[data-voucher-preview]");
        const refreshShippingButton = document.querySelector("[data-shipping-refresh]");
        const feedback = document.querySelector("[data-pricing-feedback]");
        const quoteStatus = document.querySelector("[data-shipping-quote-status]");
        const providerCopy = document.querySelector("[data-shipping-provider-copy]");
        const submitButton = form?.querySelector("button[type='submit']");
        const voucherFeedback = form?.querySelector("[data-voucher-feedback]");
        if (!form || !applyButton || !feedback || !submitButton) return;

        const formatMoney = amount => new Intl.NumberFormat("vi-VN").format(amount) + " đ";
        const setText = (id, text) => {
            const element = document.getElementById(id);
            if (element) element.textContent = text;
        };
        const setRow = (id, value) => {
            const row = document.getElementById(id);
            if (row) row.classList.toggle("d-none", !value);
        };
        let pending = false;
        let revision = 0;
        let pricingValid = false;
        let quoteExpiresAt = 0;
        let quoteExpiryTimer;
        const needsQuoteRefresh = () => quoteExpiresAt <= Date.now() + 30_000;
        const clearQuoteExpiry = () => {
            window.clearTimeout(quoteExpiryTimer);
            quoteExpiryTimer = null;
        };
        const expireQuote = () => {
            quoteExpiresAt = 0;
            pricingValid = false;
            form.dataset.pricingStale = "true";
            form.dataset.pricingValid = "false";
            const quote = document.getElementById("shippingQuoteId");
            const expected = document.getElementById("expectedTotalAmount");
            if (quote) quote.value = "";
            if (expected) expected.value = "";
            setText("pricingShipping", "—");
            setText("pricingShippingDiscount", "—");
            setText("pricingTotal", "—");
            setRow("shippingDiscountRow", false);
            if (quoteStatus) quoteStatus.textContent = "Báo giá giao hàng đã hết hạn. Hãy làm mới báo giá để tiếp tục.";
            if (feedback) {
                feedback.classList.remove("d-none", "alert-info", "alert-warning");
                feedback.classList.add("alert-danger");
                feedback.textContent = "Báo giá giao hàng đã hết hạn. Hãy làm mới báo giá trước khi đặt hàng.";
            }
            submitButton.disabled = true;
            if (refreshShippingButton) refreshShippingButton.disabled = false;
        };
        const setQuoteExpiry = expiresAt => {
            clearQuoteExpiry();
            const timestamp = expiresAt ? Date.parse(expiresAt) : NaN;
            if (!Number.isFinite(timestamp) || timestamp <= Date.now()) {
                quoteExpiresAt = 0;
                if (quoteStatus) quoteStatus.textContent = "Chưa có báo giá còn hiệu lực. Hãy làm mới báo giá.";
                if (providerCopy) providerCopy.textContent = "TP.HCM giao nội bộ miễn phí; ngoài TP.HCM, cước GHN Staging được lấy trực tiếp theo địa chỉ và kiện hàng.";
                return false;
            }
            quoteExpiresAt = timestamp;
            const expiryText = new Intl.DateTimeFormat("vi-VN", {
                day: "2-digit", month: "2-digit", year: "numeric", hour: "2-digit", minute: "2-digit"
            }).format(new Date(timestamp));
            if (quoteStatus) quoteStatus.textContent = `Báo giá hiện tại có hiệu lực đến ${expiryText}.`;
            if (providerCopy) {
                providerCopy.textContent = "TP.HCM giao nội bộ miễn phí; ngoài TP.HCM, cước GHN Staging được lấy trực tiếp theo địa chỉ và kiện hàng. Voucher phí vận chuyển được trừ sau khi có báo giá.";
            }
            quoteExpiryTimer = window.setTimeout(expireQuote, Math.max(0, timestamp - Date.now()));
            return true;
        };
        const showPriceChange = () => {
            feedback.classList.remove("d-none", "alert-danger", "alert-info");
            feedback.classList.add("alert-warning");
            feedback.textContent = "Tổng thanh toán đã được cập nhật. Vui lòng kiểm tra tổng tiền mới rồi xác nhận lại.";
            feedback.focus({preventScroll: true});
            feedback.scrollIntoView({block: "center"});
        };
        const preview = async () => {
            revision++;
            if (pending) return false;
            pending = true;
            applyButton.disabled = true;
            submitButton.disabled = true;
            quoteExpiresAt = 0;
            clearQuoteExpiry();
            const previousQuote = document.getElementById("shippingQuoteId");
            const previousExpected = document.getElementById("expectedTotalAmount");
            if (previousQuote) previousQuote.value = "";
            if (previousExpected) previousExpected.value = "";
            pricingValid = false;
            form.dataset.pricingStale = "true";
            form.dataset.pricingValid = "false";
            try {
                while (true) {
                    const requestedRevision = revision;
                    feedback.classList.remove("d-none", "alert-danger", "alert-warning");
                    feedback.classList.add("alert-info");
                    feedback.textContent = "Đang cập nhật phí giao hàng và tổng thanh toán…";
                    if (quoteStatus) quoteStatus.textContent = "Đang làm mới báo giá giao hàng…";
                    if (refreshShippingButton) refreshShippingButton.disabled = true;
                    document.querySelectorAll("[data-initial-pricing-error], [data-legacy-address-warning]")
                        .forEach(element => element.hidden = true);
                    let result;
                    let error;
                    try {
                        const csrfToken = document.querySelector('meta[name="_csrf"]')?.content;
                        const response = await fetch("/checkout/preview", {
                            method: "POST",
                            // No files are uploaded here. URL encoding avoids servlet
                            // multipart part-count limits on the full checkout form.
                            body: new URLSearchParams(new FormData(form)),
                            credentials: "same-origin",
                            headers: {
                                "X-Requested-With": "XMLHttpRequest",
                                Accept: "application/json",
                                ...(csrfToken ? { "X-CSRF-TOKEN": csrfToken } : {})
                            }
                        });
                        if (!response.ok) {
                            console.warn("Checkout preview request failed", response.status,
                                response.headers.get("X-Request-ID") || "no-request-id");
                            throw new Error(response.status === 403
                                ? "Phiên làm việc đã hết hạn. Vui lòng tải lại trang và thử lại."
                                : "Không thể kiểm tra giá hiện tại. Vui lòng thử lại.");
                        }
                        result = await response.json();
                    } catch (requestError) {
                        error = requestError;
                    }
                    if (requestedRevision !== revision) continue;
                    if (error) {
                        feedback.classList.replace("alert-info", "alert-danger");
                        feedback.textContent = error.message || "Không thể kiểm tra giá hiện tại.";
                        form.dataset.pricingStale = "true";
                        pricingValid = false;
                        form.dataset.pricingValid = "false";
                        quoteExpiresAt = 0;
                        clearQuoteExpiry();
                        setText("pricingShipping", "—");
                        setText("pricingShippingDiscount", "—");
                        setText("pricingTotal", "—");
                        setRow("shippingDiscountRow", false);
                        if (quoteStatus) quoteStatus.textContent = "Chưa lấy được báo giá mới. Hãy làm mới báo giá lại.";
                        if (providerCopy) providerCopy.textContent = "TP.HCM giao nội bộ miễn phí; ngoài TP.HCM, cước GHN Staging được lấy trực tiếp theo địa chỉ và kiện hàng.";
                        submitButton.disabled = true;
                        break;
                    }
                    const quote = document.getElementById("shippingQuoteId");
                    if (quote) quote.value = result.shippingQuoteId || "";
                    const pricing = result.pricing;
                    const currentQuote = !!result.shippingQuoteId && setQuoteExpiry(result.shippingQuoteExpiresAt);
                    if (pricing) {
                        setText("pricingSubtotal", formatMoney(pricing.subtotal));
                        setText("pricingProductDiscount", "−" + formatMoney(pricing.productDiscountAmount));
                        setText("pricingVoucherDiscount", "−" + formatMoney(pricing.voucherDiscountAmount));
                        setText("pricingShipping", formatMoney(pricing.shippingFeeBeforeDiscount ?? pricing.shippingFee));
                        setText("pricingShippingDiscount", "−" + formatMoney(pricing.shippingDiscountAmount || 0));
                        setText("pricingShippingDiscountLabel", pricing.shippingVoucherType === "SHIPPING_FREE"
                            ? "Miễn phí vận chuyển" : "Giảm phí vận chuyển");
                        setText("pricingTotal", formatMoney(pricing.totalAmount));
                        const expected = document.getElementById("expectedTotalAmount");
                        if (expected) expected.value = pricing.totalAmount;
                        setRow("productDiscountRow", pricing.productDiscountAmount > 0);
                        setRow("voucherDiscountRow", pricing.voucherDiscountAmount > 0);
                        setRow("shippingDiscountRow", (pricing.shippingDiscountAmount || 0) > 0);
                        pricing.lines.forEach(line => {
                            document.querySelectorAll(`[data-preview-line-total][data-preview-product="${line.productId}"]`)
                                .forEach(element => element.textContent = formatMoney(line.lineTotal));
                        });
                    }
                    if (!currentQuote) {
                        if (quote) quote.value = "";
                        const expected = document.getElementById("expectedTotalAmount");
                        if (expected) expected.value = "";
                        setText("pricingShipping", "—");
                        setText("pricingShippingDiscount", "—");
                        setText("pricingTotal", "—");
                        setRow("shippingDiscountRow", false);
                    }
                    pricingValid = !!pricing && !!result.valid && currentQuote;
                    if (pricingValid) {
                        feedback.textContent = "";
                        feedback.classList.add("d-none");
                    } else {
                        feedback.classList.replace("alert-info", "alert-danger");
                        feedback.textContent = result.message || (!currentQuote
                            ? "Báo giá giao hàng đã hết hạn hoặc không còn hợp lệ. Hãy làm mới báo giá."
                            : pricing
                            ? "Vui lòng kiểm tra mã giảm giá trước khi xác nhận đơn."
                            : "Không thể tính phí giao hàng. Vui lòng kiểm tra địa chỉ.");
                        feedback.classList.remove("d-none");
                    }
                    if (voucherFeedback) {
                        const hasVoucher = [...form.querySelectorAll("[name='discountCode'], [name='shippingDiscountCode']")]
                            .some(input => !!input.value.trim());
                        voucherFeedback.textContent = pricing && hasVoucher ? result.message || "" : "";
                        voucherFeedback.classList.remove("text-success", "text-danger");
                        voucherFeedback.classList.add(result.valid ? "text-success" : "text-danger");
                    }
                    form.dataset.pricingStale = String(!pricing || !currentQuote);
                    form.dataset.pricingValid = String(pricingValid);
                    submitButton.disabled = !pricingValid;
                    break;
                }
            } finally {
                pending = false;
                applyButton.disabled = false;
                if (refreshShippingButton) refreshShippingButton.disabled = false;
            }
            return pricingValid;
        };

        const markStale = () => {
            revision++;
            form.dataset.pricingStale = "true";
            form.dataset.pricingValid = "false";
            pricingValid = false;
            quoteExpiresAt = 0;
            clearQuoteExpiry();
            submitButton.disabled = true;
            const quote = document.getElementById("shippingQuoteId");
            if (quote) quote.value = "";
            const expected = document.getElementById("expectedTotalAmount");
            if (expected) expected.value = "";
            setText("pricingShipping", "—");
            setText("pricingShippingDiscount", "—");
            setText("pricingTotal", "—");
            setRow("shippingDiscountRow", false);
            if (quoteStatus) quoteStatus.textContent = "Địa chỉ hoặc voucher đã đổi; cần báo giá giao hàng mới.";
            if (providerCopy) providerCopy.textContent = "Đang cập nhật lại phí giao hàng theo thông tin mới.";
        };
        applyButton.addEventListener("click", preview);
        refreshShippingButton?.addEventListener("click", preview);
        form.querySelectorAll("[name='discountCode'], [name='shippingDiscountCode']").forEach(input => input.addEventListener("input", () => {
            markStale();
            window.clearTimeout(form.dataset.voucherTimer);
            form.dataset.voucherTimer = window.setTimeout(preview, 500);
        }));
        form.addEventListener("checkoutaddresschange", () => {
            markStale();
            preview();
        });
        form.addEventListener("checkoutaddresschanging", markStale);
        ["shippingAddress", "wardCode"].forEach(name => {
            form.querySelector(`[name='${name}']`)?.addEventListener("change", () => {
                markStale();
                preview();
            });
        });
        form.querySelectorAll("#fullName, #phone, #shippingAddress, #provinceCode, #wardCode")
            .forEach(input => input.addEventListener("input", () => {
                const savedId = document.getElementById("shippingAddressId");
                const selector = document.getElementById("savedAddress");
                if (savedId?.value) {
                    savedId.value = "";
                    if (selector) selector.value = "";
                }
                markStale();
                window.clearTimeout(form.dataset.addressTimer);
                form.dataset.addressTimer = window.setTimeout(preview, 700);
            }));
        form.querySelector("[name='provinceCode']")?.addEventListener("change", markStale);
        form.addEventListener("submit", async event => {
            if (form.dataset.pricingStale === "true") {
                event.preventDefault();
                preview();
                return;
            }
            if (form.dataset.pricingValid !== "true") {
                event.preventDefault();
                feedback.focus({preventScroll: true});
                feedback.scrollIntoView({block: "center"});
                return;
            }
            if (needsQuoteRefresh()) {
                event.preventDefault();
                const previousTotal = document.getElementById("expectedTotalAmount")?.value;
                const submitRevision = revision + 1;
                if (!await preview() || submitRevision !== revision) return;
                if (previousTotal !== document.getElementById("expectedTotalAmount")?.value) {
                    showPriceChange();
                    return;
                }
                form.requestSubmit(submitButton);
            }
        });
        document.addEventListener("visibilitychange", async () => {
            if (document.visibilityState !== "visible" || pending || !pricingValid || !needsQuoteRefresh()) return;
            const previousTotal = document.getElementById("expectedTotalAmount")?.value;
            if (await preview() && previousTotal !== document.getElementById("expectedTotalAmount")?.value) {
                showPriceChange();
            }
        });
        form.dataset.pricingStale = "true";
        form.dataset.pricingValid = "false";
        submitButton.disabled = true;
        preview();
    });
})();

document.addEventListener("click", async event => {
    const button = event.target.closest("[data-copy-text]");
    if (!button) return;
    const value = button.getAttribute("data-copy-text");
    if (!value) return;
    const absolute = value.startsWith("http") ? value : new URL(value, window.location.origin).toString();
    try {
        await navigator.clipboard.writeText(absolute);
        button.textContent = "Đã chép";
    } catch (error) {
        window.prompt("Sao chép liên kết", absolute);
    }
});
