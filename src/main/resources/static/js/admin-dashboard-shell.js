(function () {
    const sidebar = document.getElementById("dashboardSidebar");
    const toggle = document.getElementById("dashboardMenuToggle");
    const close = document.getElementById("dashboardMenuClose");
    const backdrop = document.getElementById("dashboardSidebarBackdrop");
    if (!sidebar || !toggle || !close) return;

    function setOpen(open) {
        sidebar.classList.toggle("is-open", open);
        document.body.classList.toggle("admin-sidebar-open", open);
        sidebar.setAttribute("aria-hidden", String(!open && window.matchMedia("(max-width: 767px)").matches));
        toggle.setAttribute("aria-expanded", String(open));
        toggle.setAttribute("aria-label", open ? "Đóng menu" : "Mở menu");
        if (open) close.focus();
    }
    toggle.addEventListener("click", () => setOpen(!sidebar.classList.contains("is-open")));
    close.addEventListener("click", () => { setOpen(false); toggle.focus(); });
    backdrop?.addEventListener("click", () => { setOpen(false); toggle.focus(); });
    document.addEventListener("keydown", event => {
        if (event.key === "Escape" && sidebar.classList.contains("is-open")) {
            setOpen(false);
            toggle.focus();
            return;
        }
        if (event.key === "Tab" && sidebar.classList.contains("is-open")) {
            const items = sidebar.querySelectorAll('a[href], button:not([disabled])');
            const first = items[0];
            const last = items[items.length - 1];
            if (!sidebar.contains(document.activeElement)) { event.preventDefault(); (event.shiftKey ? last : first).focus(); }
            else if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
            else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
        }
    });
    window.matchMedia("(min-width: 768px)").addEventListener("change", event => {
        if (event.matches) {
            sidebar.classList.remove("is-open");
            document.body.classList.remove("admin-sidebar-open");
            sidebar.removeAttribute("aria-hidden");
            toggle.setAttribute("aria-expanded", "false");
        } else {
            sidebar.setAttribute("aria-hidden", "true");
        }
    });
})();
