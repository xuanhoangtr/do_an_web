/* Shared Vietnamese validation and native form submission feedback. */
(function () {
    function validationMessage(field) {
        const v = field.validity;
        if (v.valueMissing) return "Vui lòng điền hoặc chọn thông tin này.";
        if (v.typeMismatch) return field.type === "email" ? "Vui lòng nhập email hợp lệ." : "Vui lòng nhập địa chỉ hợp lệ (https://...).";
        if (v.rangeUnderflow) return `Giá trị phải từ ${field.min} trở lên.`;
        if (v.rangeOverflow) return `Giá trị không được vượt quá ${field.max}.`;
        if (v.tooShort) return `Vui lòng nhập ít nhất ${field.minLength} ký tự.`;
        if (v.tooLong) return `Vui lòng nhập tối đa ${field.maxLength} ký tự.`;
        if (v.patternMismatch) return field.dataset.validationMessage || "Thông tin chưa đúng định dạng.";
        if (v.stepMismatch || v.badInput) return "Vui lòng nhập một giá trị số hợp lệ.";
        return "Thông tin chưa hợp lệ. Vui lòng kiểm tra lại.";
    }
    document.addEventListener("invalid", event => {
        const field = event.target;
        if (typeof field.setCustomValidity !== "function") return;
        // Recompute after clearing the previous custom error.
        field.setCustomValidity("");
        if (!field.validity.valid) field.setCustomValidity(validationMessage(field));
        field.setAttribute("aria-invalid", "true");
    }, true);
    function clearError(event) {
        const field = event.target;
        if (typeof field.setCustomValidity !== "function") return;
        field.setCustomValidity("");
        field.removeAttribute("aria-invalid");
        field.classList?.remove("is-invalid");
        const group = field.closest?.(".mb-3, .form-group, .form-floating, [class*='col-']") || field.parentElement;
        const feedback = group?.querySelector(".invalid-feedback");
        if (feedback) {
            feedback.textContent = "";
            feedback.hidden = true;
            feedback.classList?.remove("d-block");
        }
    }
    document.addEventListener("input", clearError);
    document.addEventListener("change", clearError);

    const labels = new WeakMap();
    document.addEventListener("submit", event => {
        const form = event.target;
        if (!(form instanceof HTMLFormElement) || form.getAttribute("method")?.toLowerCase() !== "post" || event.defaultPrevented) return;
        // AJAX forms own their loading/error lifecycle.
        if (form.matches("[data-cart-action], [data-checkout-form]")) return;
        if (form.dataset.nativeSubmitting === "true") { event.preventDefault(); return; }
        form.dataset.nativeSubmitting = "true";
        form.setAttribute("aria-busy", "true");
        const button = event.submitter;
        if (button instanceof HTMLButtonElement) {
            labels.set(button, button.textContent);
            button.textContent = "Đang xử lý…";
            // Keep successful submitter name/value in the native request.
            button.setAttribute("aria-disabled", "true");
        }
    });
    window.addEventListener("pageshow", () => {
        document.querySelectorAll("form[data-native-submitting]").forEach(form => {
            delete form.dataset.nativeSubmitting;
            delete form.dataset.confirmSubmitting;
            form.removeAttribute("aria-busy");
            form.querySelectorAll("button").forEach(button => {
                if (labels.has(button)) { button.textContent = labels.get(button); labels.delete(button); }
                button.removeAttribute("aria-disabled");
            });
        });
    });
    document.addEventListener("DOMContentLoaded", () => {
        const target = document.querySelector("[data-auth-feedback], [data-form-feedback], .is-invalid");
        const pane = target?.closest(".tab-pane:not(.active)");
        const tab = pane && document.querySelector(`[data-bs-target="#${CSS.escape(pane.id)}"]`);
        if (tab && window.bootstrap?.Tab) {
            tab.addEventListener("shown.bs.tab", () => target.focus(), { once: true });
            window.bootstrap.Tab.getOrCreateInstance(tab).show();
        } else target?.focus();
    });
})();
