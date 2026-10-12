/* Multi-shop cart selection. The server still resolves ownership and availability. */
(function () {
    const selectionKey = "ute-grouped-cart-selection";
    const selectedIds = () => Array.from(document.querySelectorAll(
        "[data-grouped-cart-selection='true'] [data-cart-row-select]:checked"))
        .map(input => input.dataset.cartItemId).filter(Boolean);

    function readSavedSelection() {
        try {
            const raw = window.sessionStorage.getItem(selectionKey);
            return raw == null ? null : new Set(JSON.parse(raw));
        } catch (_) {
            return null;
        }
    }

    function saveSelection(ids) {
        try { window.sessionStorage.setItem(selectionKey, JSON.stringify(ids)); }
        catch (_) { /* The checkboxes remain usable when storage is unavailable. */ }
    }

    function syncCart(root, initialize = false) {
        if (!root || root.dataset.groupedCartSelection !== "true") return;
        const rows = Array.from(root.querySelectorAll("[data-cart-row-select]"));
        if (initialize) {
            const saved = readSavedSelection();
            if (saved) rows.forEach(input => { input.checked = saved.has(input.dataset.cartItemId); });
        }

        const selected = rows.filter(input => input.checked);
        const count = root.querySelector("[data-grouped-selection-count]");
        const submit = root.querySelector("[data-grouped-cart-submit]");
        const shopChecks = Array.from(root.querySelectorAll("[data-shop-select-all]"));
        shopChecks.forEach(check => {
            const shopRows = rows.filter(input => input.dataset.shopId === check.dataset.shopId && !input.disabled);
            const chosen = shopRows.filter(input => input.checked).length;
            check.checked = shopRows.length > 0 && chosen === shopRows.length;
            check.indeterminate = chosen > 0 && chosen < shopRows.length;
        });

        const shopCount = new Set(selected.map(input => input.dataset.shopId)).size;
        const amount = selected.reduce((sum, input) => sum + (Number(input.dataset.lineTotal) || 0), 0);
        if (count) {
            count.textContent = selected.length === 0
                ? "Chọn sản phẩm bạn muốn mua."
                : `${selected.length} sản phẩm từ ${shopCount} cửa hàng · ${new Intl.NumberFormat("vi-VN").format(amount)} đ`;
        }
        if (submit) submit.disabled = selected.length === 0;
        saveSelection(selected.map(input => input.dataset.cartItemId));
    }

    document.addEventListener("DOMContentLoaded", () => {
        let root = document.querySelector("[data-grouped-cart-selection='true']");
        if (!root) return;
        syncCart(root, true);

        document.addEventListener("change", event => {
            const check = event.target.closest("[data-shop-select-all]");
            if (check) {
                root.querySelectorAll("[data-cart-row-select]").forEach(input => {
                    if (input.dataset.shopId === check.dataset.shopId && !input.disabled) input.checked = check.checked;
                });
                syncCart(root);
                return;
            }
            if (event.target.matches("[data-cart-row-select]")) syncCart(root);
        });

        document.addEventListener("submit", event => {
            const form = event.target.closest("form[data-grouped-cart-form]");
            if (!form) return;
            const ids = selectedIds();
            if (!ids.length) {
                event.preventDefault();
                return;
            }
            form.querySelectorAll("[data-selected-cart-row-id]").forEach(input => input.remove());
            ids.forEach(id => {
                const input = document.createElement("input");
                input.type = "hidden";
                input.name = "selectedCartItemIds";
                input.value = id;
                input.dataset.selectedCartRowId = "true";
                form.appendChild(input);
            });
        });

        const observer = new MutationObserver(() => {
            const replacement = document.querySelector("[data-grouped-cart-selection='true']");
            if (replacement && replacement !== root) {
                root = replacement;
                syncCart(root, true);
            }
        });
        observer.observe(document.body, {childList: true, subtree: true});
    });
})();
