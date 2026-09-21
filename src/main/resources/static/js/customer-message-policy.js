(function attachCustomerMessagePolicy(root, factory) {
    const policy = factory();
    if (typeof module === "object" && module.exports) module.exports = policy;
    if (root) root.uteCustomerMessagePolicy = policy;
})(typeof globalThis === "object" ? globalThis : null, function createCustomerMessagePolicy() {
    return Object.freeze({
        canReply(status, backendAllows) {
            const normalized = String(status || "").toUpperCase();
            return normalized !== "CLOSED" && backendAllows !== false;
        },
        canStartNew(status, shopAllows) {
            const normalized = String(status || "").toUpperCase();
            return normalized === "CLOSED" && shopAllows !== false;
        }
    });
});
