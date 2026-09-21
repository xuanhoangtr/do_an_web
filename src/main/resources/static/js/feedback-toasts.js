(function () {
    function showFeedbackToast(message, options = {}) {
        if (!message?.trim()) return;
        let container = document.getElementById("feedbackToasts");
        if (!container) {
            container = document.createElement("div");
            container.id = "feedbackToasts";
            container.className = "position-fixed top-0 end-0 p-3";
            container.style.zIndex = "1040";
            container.style.width = "min(380px, 100vw)";
            container.setAttribute("aria-live", "polite");
            container.setAttribute("aria-relevant", "additions");
            document.body.appendChild(container);
        }
        while (container.children.length >= 3) container.firstElementChild.remove();
        const toast = document.createElement("div");
        toast.className = `alert alert-${options.tone === "danger" ? "danger" : "success"} shadow-sm d-flex align-items-start gap-3 mb-2`;
        toast.setAttribute("role", "status");
        toast.setAttribute("aria-atomic", "true");
        const text = document.createElement("span");
        text.className = "flex-grow-1 text-break";
        text.textContent = message;
        const close = document.createElement("button");
        close.type = "button";
        close.className = "btn-close flex-shrink-0 mt-1";
        close.setAttribute("aria-label", "Đóng thông báo");
        const timer = window.setTimeout(() => toast.remove(), 5000);
        close.addEventListener("click", () => {
            window.clearTimeout(timer);
            toast.remove();
        });
        toast.append(text, close);
        container.appendChild(toast);
        return toast;
    }

    window.showFeedbackToast = showFeedbackToast;
    document.addEventListener("DOMContentLoaded", () => {
        const moveFeedback = () => {
            const messages = new Set();
            document.querySelectorAll("[data-flash-success], #cartFeedback.alert-success, [data-cart-feedback].alert-success")
                .forEach(source => {
                    const message = source.textContent.trim();
                    if (!messages.has(message)) showFeedbackToast(message);
                    messages.add(message);
                    source.remove();
                });
        };
        moveFeedback();
        new MutationObserver(moveFeedback).observe(document.body, {childList: true, subtree: true});
    });
})();
