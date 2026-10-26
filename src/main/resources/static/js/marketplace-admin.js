/* Confirmation handling is centralized in app.js for every page. */
document.addEventListener("DOMContentLoaded", () => {
    document.querySelectorAll("[data-marketplace-modal]").forEach(modal => {
        // Bootstrap's fixed dialogs must sit outside the scrolling table/card.
        document.body.appendChild(modal);
        modal.querySelectorAll("label").forEach((label, index) => {
            const field = label.parentElement.querySelector("input, select, textarea");
            if (!field) return;
            if (!field.id) field.id = `${modal.id}-field-${index}`;
            label.htmlFor = field.id;
        });
    });

    document.querySelectorAll("[data-voucher-form]").forEach(form => {
        const type = form.querySelector("[name='type']");
        const value = form.querySelector("[name='valueAmount']");
        const valueField = form.querySelector("[data-voucher-value-field]");
        const valueLabel = form.querySelector("[data-voucher-value-label]");
        const valueHelp = form.querySelector("[data-voucher-value-help]");
        const capField = form.querySelector("[data-voucher-cap-field]");
        const cap = form.querySelector("[name='maximumDiscountAmount']");
        if (!type || !value || !valueField) return;

        const updateVoucherFields = () => {
            const code = type.value;
            const freeShipping = code === "SHIPPING_FREE";
            const shippingFixed = code === "SHIPPING_FIXED";
            if (freeShipping) value.value = "0";
            else if (value.disabled) value.value = "";
            value.disabled = freeShipping;
            value.required = !freeShipping;
            value.min = freeShipping ? "0" : "1";
            valueField.hidden = freeShipping;
            if (valueLabel) valueLabel.textContent = code === "PERCENT" ? "Phần trăm giảm"
                : shippingFixed ? "Số tiền giảm phí ship (đ)" : "Số tiền giảm hàng (đ)";
            if (valueHelp) valueHelp.textContent = shippingFixed
                ? "Chỉ áp dụng cho đơn ngoại tỉnh giao qua GHN."
                : code === "PERCENT" ? "Tối đa 100%; tính trên tiền hàng sau ưu đãi sản phẩm."
                    : "Tính trên tiền hàng sau ưu đãi sản phẩm.";
            if (capField) capField.hidden = freeShipping;
            if (cap && freeShipping) cap.value = "";
            if (cap) cap.disabled = freeShipping;
        };
        type.addEventListener("change", updateVoucherFields);
        updateVoucherFields();
    });

    const categoryNodes = new Map();
    document.querySelectorAll("[data-parent-select] option[data-category-id]").forEach(option => {
        categoryNodes.set(option.dataset.categoryId, option.dataset.parentId || null);
    });
    document.querySelectorAll("[data-parent-select]").forEach(select => {
        const form = select.closest("form");
        const editedId = form?.dataset.editCategoryId;
        if (editedId) {
            for (const option of select.options) {
                const candidateId = option.dataset.categoryId;
                if (!candidateId) continue;
                let cursor = candidateId;
                const visited = new Set();
                let invalidParent = cursor === editedId;
                while (!invalidParent && cursor && !visited.has(cursor)) {
                    visited.add(cursor);
                    cursor = categoryNodes.get(cursor) || null;
                    invalidParent = cursor === editedId;
                }
                if (invalidParent && option.value !== select.value) option.disabled = true;
            }
        }
        const search = select.closest(".col-md-4")?.querySelector("[data-parent-search]");
        search?.addEventListener("input", () => {
            const query = search.value.trim().toLocaleLowerCase("vi");
            for (const option of select.options) {
                option.hidden = option.value !== "" && !option.textContent.toLocaleLowerCase("vi").includes(query);
            }
        });
    });
});
