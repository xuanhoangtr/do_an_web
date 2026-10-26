/* global connectStompUpdates */
(function () {
    const badgeSelector = "[data-admin-attention]";
    const applySnapshot = function (counts) {
        if (!counts) return;
        document.querySelectorAll(badgeSelector).forEach(function (badge) {
            const value = Math.max(0, Number(counts[badge.dataset.adminAttention] || 0));
            badge.hidden = value === 0;
            badge.textContent = value > 99 ? "99+" : String(value);
        });
    };
    const refresh = async function () {
        const badges = Array.from(document.querySelectorAll(badgeSelector));
        if (badges.length === 0) return;
        try {
            const response = await fetch("/api/v1/admin/attention", {
                headers: { Accept: "application/json" },
                credentials: "same-origin"
            });
            if (!response.ok) return;
            applySnapshot(await response.json());
        } catch (_) {
            // The next poll refreshes counts if the connection is temporarily unavailable.
        }
    };
    window.refreshAdminAttention = refresh;
    document.addEventListener("DOMContentLoaded", function () {
        if (!document.querySelector(badgeSelector)) return;
        refresh();
        if (typeof connectStompUpdates === "function") {
            connectStompUpdates({
                destinations: ["/user/queue/admin/attention"],
                onConnect: refresh,
                onMessage: applySnapshot
            });
        }
        window.setInterval(function () {
            if (document.visibilityState === "visible") refresh();
        }, 30000);
        document.addEventListener("visibilitychange", function () {
            if (document.visibilityState === "visible") refresh();
        });
    });
})();
