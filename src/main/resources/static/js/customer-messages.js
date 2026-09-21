(function () {
    document.addEventListener("DOMContentLoaded", () => {
        const root = document.getElementById("supportChatRoot");
        if (!root || root.dataset.unifiedInbox !== "true") return;

        const $ = selector => root.querySelector(selector);
        const panel = root.querySelector("[role='dialog']");
        const listView = $(`[data-message-list-view]`);
        const threadView = $(`[data-message-thread-view]`);
        const list = $(`[data-message-list]`);
        const empty = $(`[data-message-empty]`);
        const more = $(`[data-message-more]`);
        const messageBox = $(`[data-message-thread]`);
        const older = $(`[data-message-older]`);
        const textarea = $(`[data-message-body]`);
        const fileInput = $(`[data-message-files]`);
        const composer = $(`[data-message-composer]`);
        const attachmentPreview = $(`[data-chat-attachment-preview]`);
        const historyList = $(`[data-message-history-list]`);
        const historyMore = $(`[data-message-history-more]`);
        const afterHours = $(`[data-message-after-hours]`);
        const messagePolicy = window.uteCustomerMessagePolicy || {
            canReply: (status, backendAllows) => String(status || "").toUpperCase() !== "CLOSED" && backendAllows !== false,
            canStartNew: (status, shopAllows) => String(status || "").toUpperCase() === "CLOSED" && shopAllows !== false
        };
        const state = {
            scope: "ALL", page: 0, hasMore: false, entries: [], activeEntry: null,
            activeTicketId: null, ticketStatus: "", isNewConversation: false, detailCanSend: true,
            currentPage: 0, messages: new Map(), drafts: new Map(), histories: new Map(),
            attachments: [], pendingKey: null, sendFailed: false, requestBusy: false,
            threadSequence: 0, inboxSequence: 0, openSequence: 0, polling: null, syncBusy: false,
            syncPending: false, unreadEndpointAvailable: true, lastTrigger: null
        };
        const fallbackCsrf = document.querySelector('meta[name="_csrf"]')?.content || "";

        const textNode = (tag, value, className) => {
            const node = document.createElement(tag);
            if (className) node.className = className;
            node.textContent = value == null ? "" : String(value);
            return node;
        };
        const makeKey = () => window.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(16).slice(2)}`;
        const formatDate = value => {
            if (!value) return "";
            const date = new Date(value);
            if (Number.isNaN(date.getTime())) return "";
            return new Intl.DateTimeFormat("vi-VN", { day: "2-digit", month: "2-digit", hour: "2-digit", minute: "2-digit" }).format(date);
        };
        const statusLabel = value => ({
            OPEN: "Mới tiếp nhận", IN_PROGRESS: "Đang xử lý", WAITING_CUSTOMER: "Chờ khách phản hồi",
            RESOLVED: "Đã giải quyết", CLOSED: "Đã đóng"
        })[value] || "";
        const entryKey = entry => `${entry.scope}:${entry.contactId || entry.conversationId || "new"}`;
        const draftKey = () => {
            const entry = state.activeEntry;
            if (!entry) return "";
            if (state.isNewConversation) return `${entry.scope}:${entry.contactId || "support"}:new`;
            return `${entry.scope}:${state.activeTicketId || entry.contactId || "new"}`;
        };
        const apiBase = entry => entry?.scope === "SHOP"
            ? `/support/shops/${encodeURIComponent(entry.shopId)}/chat`
            : "/support/chat";
        function shopCanSend(entry) {
            if (!entry || entry.scope !== "SHOP") return true;
            if (entry.canSend === false) return false;
            const shopStatus = String(entry.shopStatus || "").toUpperCase();
            return !shopStatus || shopStatus === "ACTIVE";
        }
        function backendCanSend(detail, entry) {
            if (detail && detail.canSend === false) return false;
            if (entry && entry.canSend === false) return false;
            if (detail && typeof detail.canSend === "boolean") return detail.canSend;
            if (entry && typeof entry.canSend === "boolean") return entry.canSend;
            return true;
        }

        async function request(url, options = {}) {
            const headers = new Headers(options.headers || {});
            headers.set("Accept", "application/json");
            if (options.body && !(options.body instanceof FormData)) headers.set("Content-Type", "application/json");
            if (options.method && options.method.toUpperCase() !== "GET") {
                const token = document.querySelector('meta[name="_csrf"]')?.content || fallbackCsrf;
                if (token) headers.set("X-CSRF-TOKEN", token);
                headers.set("X-Requested-With", "XMLHttpRequest");
            }
            const response = await fetch(url, { credentials: "same-origin", cache: "no-store", ...options, headers });
            if (response.status === 204) return null;
            const contentType = response.headers.get("content-type") || "";
            let result = {};
            if (contentType.includes("json")) result = await response.json();
            else result.detail = await response.text();
            if (!response.ok) {
                const error = new Error(result.detail || result.message || result.title || "Không thể tải tin nhắn. Vui lòng thử lại.");
                error.status = response.status;
                error.payload = result;
                throw error;
            }
            return result;
        }
        const jsonBody = value => JSON.stringify(value);
        const setError = value => { const node = $(`[data-message-error]`); if (node) node.textContent = value || ""; };

        function updateFab(expanded) {
            document.querySelectorAll("[data-customer-messages-open]").forEach(button => button.setAttribute("aria-expanded", String(expanded)));
        }
        function setOpen(open, trigger) {
            if (trigger) state.lastTrigger = trigger;
            root.hidden = !open;
            document.body.classList.toggle("support-chat-open", open);
            updateFab(open);
            if (open) panel?.focus({ preventScroll: true });
            else {
                publishTyping(false);
                (state.lastTrigger && document.contains(state.lastTrigger) ? state.lastTrigger : document.querySelector("[data-customer-messages-open]"))?.focus({ preventScroll: true });
            }
        }
        function showList() {
            listView.hidden = false;
            threadView.hidden = true;
            state.threadSequence++;
            publishTyping(false);
            loadInbox();
        }
        function showThread() {
            listView.hidden = true;
            threadView.hidden = false;
        }
        function updateFilterButtons() {
            root.querySelectorAll("[data-message-filter]").forEach(button => {
                const selected = button.dataset.messageFilter === state.scope;
                button.classList.toggle("is-active", selected);
                button.setAttribute("aria-selected", String(selected));
            });
        }
        function renderEmpty() {
            if (!empty) return;
            empty.replaceChildren();
            const message = state.scope === "SHOP"
                ? "Bạn chưa trò chuyện với cửa hàng nào. Mở một cửa hàng hoặc sản phẩm và chọn Nhắn tin."
                : state.scope === "PLATFORM_SUPPORT"
                    ? "Bạn chưa có cuộc trò chuyện hỗ trợ nào."
                    : "Hộp thư đang trống. Mở một cửa hàng hoặc sản phẩm và chọn Nhắn tin để bắt đầu.";
            empty.append(textNode("span", message));
            if (state.scope !== "PLATFORM_SUPPORT") {
                const link = document.createElement("a");
                link.href = "/shops";
                link.textContent = "Khám phá cửa hàng";
                empty.append(link);
            }
            empty.hidden = false;
        }
        function renderEntry(entry) {
            const key = entryKey(entry);
            const button = document.createElement("button");
            button.type = "button";
            button.className = `support-chat-conversation customer-messages-item${state.activeEntry && entryKey(state.activeEntry) === key && threadView.hidden ? " is-selected" : ""}`;
            button.dataset.inboxKey = key;
            const avatar = document.createElement("span");
            avatar.className = "customer-messages-avatar";
            if (entry.avatarUrl) {
                const image = document.createElement("img");
                image.src = entry.avatarUrl;
                image.alt = "";
                image.loading = "lazy";
                image.onerror = () => { avatar.replaceChildren(textNode("span", (entry.title || "U").trim().slice(0, 1).toLocaleUpperCase("vi-VN"))); };
                avatar.append(image);
            } else {
                avatar.append(textNode("span", (entry.title || "U").trim().slice(0, 1).toLocaleUpperCase("vi-VN")));
            }
            const main = document.createElement("span");
            main.className = "customer-messages-item-main";
            const top = document.createElement("span");
            top.className = "customer-messages-item-top";
            top.append(textNode("strong", entry.title || (entry.scope === "SHOP" ? "Cửa hàng" : "Hỗ trợ UTEWedding")));
            top.append(textNode("time", formatDate(entry.lastActivityAt)));
            main.append(top);
            const preview = entry.preview || (!entry.hasMessages ? "Chưa có tin nhắn" : "Chưa có nội dung xem trước");
            main.append(textNode("span", preview, "customer-messages-item-preview"));
            const bottom = document.createElement("span");
            bottom.className = "customer-messages-item-bottom";
            const kindLabel = entry.scope === "SHOP" ? "Cửa hàng" : "Hỗ trợ UTEWedding";
            bottom.append(textNode("span", kindLabel, "customer-messages-kind"));
            if (entry.hasMessages && entry.status) bottom.append(textNode("span", statusLabel(entry.status) || entry.status));
            const unread = Math.max(0, Number(entry.unreadConversations || 0));
            if (unread > 0) bottom.append(textNode("span", unread > 99 ? "99+" : unread, "support-chat-unread"));
            main.append(bottom);
            button.append(avatar, main);
            return button;
        }
        function renderEntries() {
            if (!list) return;
            list.replaceChildren();
            const entries = state.entries.filter(item => state.scope === "ALL" || item.scope === state.scope);
            if (!entries.length) {
                renderEmpty();
                return;
            }
            empty.hidden = true;
            entries.forEach(entry => list.append(renderEntry(entry)));
        }
        async function loadInbox(append = false) {
            const sequence = ++state.inboxSequence;
            const page = append ? state.page + 1 : 0;
            const scope = state.scope;
            if (!append && list) list.replaceChildren(textNode("p", "Đang tải tin nhắn…", "support-chat-loading"));
            try {
                const params = new URLSearchParams({ scope, page: String(page) });
                const result = await request(`/messages/inbox?${params}`);
                if (sequence !== state.inboxSequence || scope !== state.scope) return result;
                const rows = Array.isArray(result.items) ? result.items : [];
                const merged = append ? [...state.entries, ...rows] : rows;
                const unique = new Map();
                merged.forEach(item => {
                    if (!item?.scope) return;
                    unique.set(entryKey(item), item);
                });
                state.entries = [...unique.values()];
                state.page = Number(result.page ?? page);
                state.hasMore = Boolean(result.hasMore);
                if (more) more.hidden = !state.hasMore;
                renderEntries();
                if (!state.unreadEndpointAvailable) renderFallbackUnread();
                return result;
            } catch (error) {
                if (sequence !== state.inboxSequence) return null;
                state.entries = [];
                if (list) list.replaceChildren(textNode("p", "Không tải được hộp thư. Vui lòng thử lại.", "support-chat-empty"));
                if (empty) empty.hidden = true;
                if (more) more.hidden = true;
                return null;
            }
        }

        function renderFallbackUnread() {
            const total = state.entries.reduce((sum, item) => sum + Math.max(0, Number(item.unreadConversations || 0)), 0);
            setUnreadBadge(total);
        }
        function setUnreadBadge(value) {
            const badge = document.querySelector("[data-customer-messages-unread]");
            if (!badge) return;
            const count = Math.max(0, Number(value || 0));
            badge.hidden = count === 0;
            badge.textContent = count > 99 ? "99+" : String(count);
            const opener = document.querySelector("[data-customer-messages-open]");
            if (opener) opener.setAttribute("aria-label", count > 0 ? `Tin nhắn, ${count} cuộc trò chuyện chưa đọc` : "Mở hộp thư Tin nhắn");
        }
        async function refreshUnread() {
            if (!state.unreadEndpointAvailable) { renderFallbackUnread(); return; }
            try {
                const result = await request("/messages/unread");
                const total = typeof result === "number" ? result : Number(result.total ?? result.totalUnread ?? result.unreadConversations ?? result.count ?? 0);
                setUnreadBadge(total);
            } catch (error) {
                if (error.status === 404 || error.status === 405) {
                    state.unreadEndpointAvailable = false;
                    renderFallbackUnread();
                }
            }
        }

        function saveDraft() {
            const key = draftKey();
            if (!key) return;
            state.drafts.set(key, {
                body: textarea?.value || "",
                attachments: state.attachments,
                pendingKey: state.pendingKey,
                sendFailed: state.sendFailed
            });
        }
        function loadDraft() {
            const draft = state.drafts.get(draftKey()) || { body: "", attachments: [], pendingKey: null, sendFailed: false };
            if (textarea) textarea.value = draft.body;
            state.attachments = draft.attachments || [];
            state.pendingKey = draft.pendingKey || null;
            state.sendFailed = Boolean(draft.sendFailed);
            renderAttachments();
        }
        function renderAttachments() {
            if (!attachmentPreview) return;
            attachmentPreview.replaceChildren();
            state.attachments.forEach((attachment, index) => {
                const wrapper = document.createElement("span");
                wrapper.className = "support-chat-draft-image";
                const image = document.createElement("img");
                image.src = attachment.previewUrl;
                image.alt = attachment.name || "Ảnh đính kèm";
                const remove = document.createElement("button");
                remove.type = "button";
                remove.textContent = "×";
                remove.setAttribute("aria-label", "Bỏ ảnh đính kèm");
                remove.addEventListener("click", () => {
                    URL.revokeObjectURL(attachment.previewUrl);
                    state.attachments.splice(index, 1);
                    saveDraft();
                    renderAttachments();
                });
                wrapper.append(image, remove);
                attachmentPreview.append(wrapper);
            });
        }
        function clearSentDraft(key = draftKey(), sentAttachments = state.attachments, clearVisible = false) {
            sentAttachments.forEach(item => { if (item.previewUrl) URL.revokeObjectURL(item.previewUrl); });
            if (clearVisible) {
                state.attachments = [];
                state.pendingKey = null;
                state.sendFailed = false;
                if (textarea) textarea.value = "";
                renderAttachments();
            }
            state.drafts.delete(key);
        }

        function setComposerEnabled(enabled) {
            if (!composer) return;
            const allowed = messagePolicy.canReply(state.ticketStatus, state.detailCanSend);
            const disabled = !enabled || !allowed || !shopCanSend(state.activeEntry) || state.requestBusy;
            composer.querySelectorAll("textarea, input[type=file], button[type=submit]").forEach(control => { control.disabled = disabled; });
            const send = $(`[data-message-send]`);
            if (send) send.textContent = state.sendFailed ? "Thử gửi lại" : "Gửi tin";
        }
        function renderThreadHeader(entry, detail) {
            const scopeLabel = entry.scope === "SHOP" ? "Cửa hàng" : "Hỗ trợ UTEWedding";
            $(`[data-message-scope]`).textContent = scopeLabel;
            $(`[data-message-title]`).textContent = detail?.subject || entry.title || (entry.scope === "SHOP" ? "Cửa hàng" : "Hỗ trợ UTEWedding");
            const shopLink = $(`[data-message-shop-link]`);
            if (entry.scope === "SHOP" && entry.shopSlug) {
                shopLink.href = `/shops/${encodeURIComponent(entry.shopSlug)}`;
                shopLink.hidden = false;
            } else {
                shopLink.hidden = true;
                shopLink.removeAttribute("href");
            }
            const status = $(`[data-message-status]`);
            const statusValue = detail?.status || entry.status;
            status.textContent = entry.hasMessages && statusValue ? statusLabel(statusValue) || statusValue : "";
            status.hidden = !entry.hasMessages || !statusValue;
            const context = $(`[data-message-context]`);
            const contextLines = [];
            if (entry.scope === "PLATFORM_SUPPORT") {
                if (detail?.orderId) contextLines.push(`Đơn hàng: ${detail.orderId}`);
                if (detail?.returnCaseId) contextLines.push(`Yêu cầu trả hàng: ${detail.returnCaseId}`);
                if (detail?.fulfillmentId) contextLines.push(`Kiện hàng: ${detail.fulfillmentId}`);
            }
            context.textContent = contextLines.join(" · ");
            context.hidden = !contextLines.length;
            afterHours.hidden = entry.scope !== "PLATFORM_SUPPORT" || !detail?.outsideBusinessHours;
            const historyButton = $(`[data-message-history]`);
            historyButton.hidden = entry.scope !== "SHOP" || !entry.hasMessages;
            const newShop = $(`[data-message-new-shop]`);
            newShop.hidden = entry.scope !== "SHOP" || !messagePolicy.canStartNew(state.ticketStatus, shopCanSend(entry));
        }
        function showNoMessages(entry) {
            state.messages.clear();
            state.detailCanSend = backendCanSend(null, entry);
            messageBox.replaceChildren(textNode("p", entry.scope === "SHOP" ? "Chưa có tin nhắn. Gửi lời nhắn đầu tiên cho cửa hàng." : "Hãy nhập tin nhắn đầu tiên.", "support-chat-empty"));
            older.hidden = true;
            state.ticketStatus = "OPEN";
            renderThreadHeader(entry, null);
            setComposerEnabled(true);
        }
        function renderMessages() {
            if (!messageBox) return;
            const wasAtBottom = messageBox.scrollHeight - messageBox.scrollTop - messageBox.clientHeight < 100;
            messageBox.replaceChildren();
            const messages = [...state.messages.values()].sort((a, b) => new Date(a.createdAt) - new Date(b.createdAt));
            if (!messages.length) {
                messageBox.append(textNode("p", "Chưa có tin nhắn trong cuộc trò chuyện này.", "support-chat-empty"));
                return;
            }
            messages.forEach(message => {
                const bubble = document.createElement("article");
                bubble.className = `support-chat-message${message.authoredByCurrentUser ? " is-mine" : ""}`;
                const meta = document.createElement("div");
                meta.className = "support-chat-message-meta";
                meta.append(textNode("span", message.authorName || "UTEWedding"), textNode("time", formatDate(message.createdAt)));
                bubble.append(meta);
                if (message.body) bubble.append(textNode("div", message.body, "support-chat-message-body"));
                if (message.attachments?.length) {
                    const images = document.createElement("div");
                    images.className = "support-chat-message-images";
                    message.attachments.forEach(attachment => {
                        const link = document.createElement("a");
                        link.href = attachment.url;
                        link.target = "_blank";
                        link.rel = "noopener";
                        link.setAttribute("aria-label", "Mở ảnh đính kèm");
                        const image = document.createElement("img");
                        image.src = attachment.url;
                        image.alt = "Ảnh đính kèm trong tin nhắn";
                        image.loading = "lazy";
                        link.append(image);
                        images.append(link);
                    });
                    bubble.append(images);
                }
                messageBox.append(bubble);
            });
            if (wasAtBottom) messageBox.scrollTop = messageBox.scrollHeight;
        }
        function addMessages(messages) {
            (messages || []).forEach(message => state.messages.set(message.id, message));
            renderMessages();
        }
        function showTyping(label) {
            const node = $(`[data-message-typing]`);
            if (node) node.textContent = label ? `${label} đang soạn tin…` : "";
            if (state.typingExpiry) clearTimeout(state.typingExpiry);
            if (label) state.typingExpiry = window.setTimeout(() => { if (node) node.textContent = ""; }, 10000);
        }
        function publishTyping(active) {
            if (!state.activeTicketId || state.activeEntry?.scope === "PLATFORM_SUPPORT" && state.activeEntry?.conversationId == null) return;
            const conversationId = state.activeTicketId;
            const entry = state.activeEntry;
            window.clearTimeout(state.typingTimer);
            state.typingTimer = window.setTimeout(() => {
                request(`${apiBase(entry)}/typing`, {
                    method: "POST", body: jsonBody({ conversationId, active })
                }).catch(() => {});
            }, active ? 500 : 0);
        }
        async function markRead() {
            if (!state.activeTicketId || root.hidden || threadView.hidden || document.visibilityState !== "visible") return;
            try {
                await request(`${apiBase(state.activeEntry)}/conversations/${encodeURIComponent(state.activeTicketId)}/read`, { method: "POST" });
                await Promise.all([refreshUnread(), loadInbox()]);
            } catch (_) { /* the next focus, socket signal, or poll retries */ }
        }

        async function openTicket(entry, ticketId, sequence = ++state.threadSequence) {
            state.activeTicketId = ticketId;
            state.isNewConversation = false;
            state.messages.clear();
            state.currentPage = 0;
            state.ticketStatus = "";
            historyList.hidden = true;
            historyList.replaceChildren();
            historyMore.hidden = true;
            renderThreadHeader(entry, null);
            messageBox.replaceChildren(textNode("p", "Đang tải tin nhắn…", "support-chat-loading"));
            older.hidden = true;
            older.disabled = false;
            if (historyMore) historyMore.disabled = false;
            try {
                const [detail, page] = await Promise.all([
                    request(`${apiBase(entry)}/conversations/${encodeURIComponent(ticketId)}`),
                    request(`${apiBase(entry)}/conversations/${encodeURIComponent(ticketId)}/messages?page=0`)
                ]);
                if (sequence !== state.threadSequence || !state.activeEntry || entryKey(state.activeEntry) !== entryKey(entry) || ticketId !== state.activeTicketId) return;
                state.ticketStatus = detail.status || "";
                state.detailCanSend = backendCanSend(detail, entry);
                state.activeEntry = { ...entry, conversationId: ticketId, hasMessages: true, status: detail.status || entry.status };
                renderThreadHeader(state.activeEntry, detail);
                addMessages(page.messages || []);
                state.currentPage = Number(page.page || 0);
                older.hidden = !page.hasMore;
                loadDraft();
                setComposerEnabled(true);
                if (!root.hidden && !threadView.hidden && document.visibilityState === "visible") await markRead();
                const typers = await request(`${apiBase(entry)}/conversations/${encodeURIComponent(ticketId)}/typing`).catch(() => []);
                if (sequence === state.threadSequence && typers?.length) showTyping(typers.map(item => item.name).filter(Boolean).join(", "));
            } catch (error) {
                if (sequence === state.threadSequence) {
                    messageBox.replaceChildren(textNode("p", "Không thể mở cuộc trò chuyện này.", "support-chat-empty"));
                    setError(error.message);
                }
            }
        }
        function shopIdFromPath() {
            const match = window.location.pathname.match(/^\/support\/shops\/([^/]+)/);
            return match ? decodeURIComponent(match[1]) : "";
        }
        async function replyableHistoryTicket(entry, items) {
            const candidates = (items || []).filter(item => item?.id);
            const fallback = candidates[0] || null;
            if (entry?.canSend === false) return fallback;
            for (const item of candidates) {
                const status = String(item.status || "").toUpperCase();
                if (status === "CLOSED") continue;
                if (status !== "RESOLVED") return item;
                try {
                    const detail = await request(`${apiBase(entry)}/conversations/${encodeURIComponent(item.id)}`);
                    if (backendCanSend(detail, entry)) {
                        return { ...item, status: detail.status || item.status, canSend: detail.canSend };
                    }
                } catch (_) { /* try an older ticket before falling back to the latest history */ }
            }
            return fallback;
        }
        async function shopConversationEntry(shopId, conversationId) {
            if (!shopId || !conversationId) return null;
            const base = state.entries.find(item => item.scope === "SHOP" && item.shopId === shopId);
            try {
                const detail = await request(`/support/shops/${encodeURIComponent(shopId)}/chat/conversations/${encodeURIComponent(conversationId)}`);
                const entry = {
                    ...(base || { scope: "SHOP", shopId, title: "Cửa hàng", unreadConversations: 0 }),
                    conversationId, hasMessages: true, status: detail.status || base?.status
                };
                if (typeof detail.canSend === "boolean") entry.canSend = detail.canSend;
                return entry;
            } catch (_) {
                return null;
            }
        }
        async function openEntry(entry) {
            if (!entry) return;
            saveDraft();
            state.activeEntry = { ...entry };
            state.isNewConversation = false;
            state.activeTicketId = entry.conversationId || null;
            state.ticketStatus = "";
            state.detailCanSend = true;
            state.messages.clear();
            setError("");
            historyList.hidden = true;
            historyList.replaceChildren();
            historyMore.hidden = true;
            older.disabled = false;
            if (historyMore) historyMore.disabled = false;
            if (fileInput) fileInput.disabled = false;
            showThread();
            const sequence = ++state.threadSequence;
            if (!entry.conversationId && entry.scope === "SHOP" && entry.hasMessages && entry.shopId) {
                try {
                    const history = await request(`${apiBase(entry)}/conversations?page=0`);
                    const latest = await replyableHistoryTicket(entry, history.items);
                    if (latest?.id) {
                        const hydrated = { ...entry, conversationId: latest.id, hasMessages: true, status: latest.status || entry.status };
                        state.activeEntry = hydrated;
                        await openTicket(hydrated, latest.id, sequence);
                        return;
                    }
                } catch (_) { /* show the contact row and allow a retry from its history control */ }
            }
            if (!entry.conversationId) {
                state.isNewConversation = true;
                loadDraft();
                renderThreadHeader(entry, null);
                showNoMessages(entry);
                setComposerEnabled(true);
                return;
            }
            loadDraft();
            await openTicket(entry, entry.conversationId, sequence);
        }
        function startNewSupport(orderId = "", returnCaseId = "") {
            saveDraft();
            state.activeEntry = {
                scope: "PLATFORM_SUPPORT", conversationId: null,
                title: "Hỗ trợ UTEWedding", hasMessages: false, isDraft: true
            };
            state.activeTicketId = null;
            state.isNewConversation = true;
            state.ticketStatus = "OPEN";
            state.detailCanSend = true;
            state.messages.clear();
            if (orderId) root.dataset.orderId = orderId;
            if (returnCaseId) root.dataset.returnCaseId = returnCaseId;
            showThread();
            loadDraft();
            renderThreadHeader(state.activeEntry, null);
            showNoMessages(state.activeEntry);
            const context = $(`[data-message-context]`);
            const contextText = [];
            if (root.dataset.orderId) contextText.push(`Đơn hàng: ${root.dataset.orderId}`);
            if (root.dataset.returnCaseId) contextText.push(`Yêu cầu trả hàng: ${root.dataset.returnCaseId}`);
            context.textContent = contextText.join(" · ");
            context.hidden = contextText.length === 0;
            textarea?.focus({ preventScroll: true });
        }
        function startNewShopConversation() {
            if (!state.activeEntry || state.activeEntry.scope !== "SHOP" || !messagePolicy.canStartNew(state.ticketStatus, shopCanSend(state.activeEntry))) return;
            saveDraft();
            state.activeEntry = { ...state.activeEntry, conversationId: null, hasMessages: true };
            state.activeTicketId = null;
            state.isNewConversation = true;
            state.ticketStatus = "OPEN";
            state.detailCanSend = true;
            state.messages.clear();
            showThread();
            loadDraft();
            renderThreadHeader(state.activeEntry, null);
            showNoMessages(state.activeEntry);
            textarea?.focus({ preventScroll: true });
        }
        async function openShopContact(shopId, productId, trigger) {
            if (!shopId) return;
            const openSequence = ++state.openSequence;
            if (trigger) trigger.setAttribute("aria-busy", "true");
            try {
                const body = { shopId };
                if (productId) body.productId = productId;
                const contact = await request("/messages/shop-contacts/open", { method: "POST", body: jsonBody(body) });
                if (openSequence !== state.openSequence) { loadInbox(); return; }
                state.scope = "ALL";
                updateFilterButtons();
                setOpen(true, trigger);
                await loadInbox();
                let entry = state.entries.find(item => item.scope === "SHOP" && item.contactId === contact.contactId);
                if (!entry) {
                    entry = {
                        scope: "SHOP", contactId: contact.contactId, shopId: contact.shopId || shopId,
                        shopSlug: contact.shopSlug, title: contact.shopName || "Cửa hàng",
                        avatarUrl: contact.shopLogoUrl, conversationId: contact.latestConversationId || null,
                        hasMessages: Boolean(contact.hasMessages), unreadConversations: 0,
                        canSend: contact.canSend, shopStatus: contact.shopStatus,
                        status: contact.hasMessages ? undefined : "EMPTY"
                    };
                    state.entries.unshift(entry);
                    renderEntries();
                }
                await openEntry(entry);
                if (!entry.hasMessages) textarea?.focus({ preventScroll: true });
            } catch (error) {
                setOpen(true, trigger);
                showThread();
                setError(error.message || "Không thể mở tin nhắn với cửa hàng.");
            } finally {
                trigger?.removeAttribute("aria-busy");
            }
        }

        async function loadHistory(append = false) {
            const entry = state.activeEntry;
            if (!entry || entry.scope !== "SHOP" || !entry.shopId) return;
            const requestSequence = state.threadSequence;
            const requestEntryKey = entryKey(entry);
            const requestTicketId = state.activeTicketId;
            const isCurrent = () => requestSequence === state.threadSequence
                && requestEntryKey === entryKey(state.activeEntry)
                && requestTicketId === state.activeTicketId;
            const key = entry.contactId || entry.shopId;
            let history = state.histories.get(key) || { items: [], page: -1, hasMore: true };
            if (!append && history.page >= 0) {
                if (!isCurrent()) return;
                renderHistory(history);
                return;
            }
            if (!append) historyList.replaceChildren(textNode("span", "Đang tải các hội thoại…", "support-chat-loading"));
            const pageNumber = append ? history.page + 1 : 0;
            if (historyMore) historyMore.disabled = true;
            try {
                const result = await request(`${apiBase(entry)}/conversations?page=${pageNumber}`);
                if (!isCurrent()) return;
                const rows = result.items || [];
                const all = append ? [...history.items, ...rows] : rows;
                const unique = new Map(all.map(item => [item.id, item]));
                history = { items: [...unique.values()], page: Number(result.page ?? pageNumber), hasMore: Boolean(result.hasMore) };
                state.histories.set(key, history);
                renderHistory(history);
            } catch (_) {
                if (isCurrent()) historyList.replaceChildren(textNode("span", "Không tải được các hội thoại trước.", "support-chat-empty"));
            } finally {
                if (historyMore && isCurrent()) historyMore.disabled = false;
            }
        }
        function renderHistory(history) {
            historyList.replaceChildren();
            history.items.forEach(item => {
                const button = document.createElement("button");
                button.type = "button";
                button.classList.toggle("is-active", item.id === state.activeTicketId);
                const title = item.number || item.subject || "Cuộc trò chuyện";
                button.append(textNode("span", title), textNode("span", statusLabel(item.status) || item.status || ""));
                button.addEventListener("click", () => openTicket(state.activeEntry, item.id));
                historyList.append(button);
            });
            historyMore.hidden = !history.hasMore;
            if (!history.items.length) historyList.append(textNode("span", "Chưa có hội thoại trước.", "support-chat-empty"));
        }
        async function loadOlderMessages() {
            if (!state.activeTicketId) return;
            const requestSequence = state.threadSequence;
            const requestEntryKey = entryKey(state.activeEntry);
            const requestTicketId = state.activeTicketId;
            const requestPage = state.currentPage;
            const isCurrent = () => requestSequence === state.threadSequence
                && requestEntryKey === entryKey(state.activeEntry)
                && requestTicketId === state.activeTicketId;
            older.disabled = true;
            try {
                const page = await request(`${apiBase(state.activeEntry)}/conversations/${encodeURIComponent(requestTicketId)}/messages?page=${requestPage + 1}`);
                if (!isCurrent()) return;
                const oldHeight = messageBox.scrollHeight;
                addMessages(page.messages || []);
                state.currentPage = Number(page.page ?? requestPage + 1);
                messageBox.scrollTop = messageBox.scrollHeight - oldHeight;
                older.hidden = !page.hasMore;
            } catch (_) { if (isCurrent()) setError("Không tải được tin nhắn cũ."); }
            finally { if (isCurrent()) older.disabled = false; }
        }
        async function syncThread() {
            if (!state.activeTicketId || root.hidden || threadView.hidden) return;
            if (state.syncBusy) { state.syncPending = true; return; }
            state.syncBusy = true;
            try {
                do {
                    state.syncPending = false;
                    const id = state.activeTicketId;
                    const seq = state.threadSequence;
                    const entry = state.activeEntry;
                    const [detail, page] = await Promise.all([
                        request(`${apiBase(entry)}/conversations/${encodeURIComponent(id)}`),
                        request(`${apiBase(entry)}/conversations/${encodeURIComponent(id)}/messages?page=0`)
                    ]);
                    if (seq !== state.threadSequence || id !== state.activeTicketId) continue;
                    state.ticketStatus = detail.status || "";
                    state.detailCanSend = backendCanSend(detail, entry);
                    renderThreadHeader(entry, detail);
                    addMessages(page.messages || []);
                    state.currentPage = 0;
                    older.hidden = !page.hasMore;
                    if (document.visibilityState === "visible") await markRead();
                } while (state.syncPending);
            } catch (_) { /* next signal, focus, or poll retries */ }
            finally { state.syncBusy = false; }
        }
        async function uploadSelected(files) {
            if (!files.length) return;
            if (state.attachments.length + files.length > 5) { setError("Mỗi tin nhắn đính kèm tối đa 5 ảnh."); return; }
            const requestSequence = state.threadSequence;
            const requestEntryKey = entryKey(state.activeEntry);
            const requestTicketId = state.activeTicketId;
            const requestDraftKey = draftKey();
            const isCurrent = () => requestSequence === state.threadSequence
                && requestEntryKey === entryKey(state.activeEntry)
                && requestTicketId === state.activeTicketId
                && requestDraftKey === draftKey();
            fileInput.disabled = true;
            setError("");
            const form = new FormData();
            files.forEach(file => form.append("files", file));
            try {
                const uploaded = await request(`${apiBase(state.activeEntry)}/attachments`, { method: "POST", body: form });
                if (!isCurrent()) return;
                uploaded.forEach((item, index) => state.attachments.push({
                    ...item, name: item.name || files[index]?.name,
                    previewUrl: URL.createObjectURL(files[index])
                }));
                saveDraft();
                renderAttachments();
            } catch (error) { if (isCurrent()) setError(error.message); }
            finally {
                fileInput.value = "";
                if (isCurrent()) setComposerEnabled(messagePolicy.canReply(state.ticketStatus, state.detailCanSend));
            }
        }
        async function sendMessage(event) {
            event.preventDefault();
            if (state.requestBusy || !state.activeEntry) return;
            if ($(`[data-message-send]`)?.disabled) return;
            const body = textarea?.value || "";
            if (!body.trim() && !state.attachments.length) { setError("Hãy nhập tin nhắn hoặc đính kèm ảnh."); return; }
            if (!state.pendingKey) state.pendingKey = makeKey();
            state.requestBusy = true;
            state.sendFailed = false;
            setError("");
            setComposerEnabled(true);
            saveDraft();
            const entry = state.activeEntry;
            const ticketId = state.activeTicketId;
            const outgoingDraftKey = draftKey();
            const outgoingAttachments = [...state.attachments];
            const outgoingPendingKey = state.pendingKey;
            const submissionSequence = state.threadSequence;
            const payload = {
                body,
                attachmentMediaIds: state.attachments.map(item => item.id).filter(Boolean)
            };
            if (entry.scope === "SHOP") payload.category = "GENERAL";
            else {
                if (root.dataset.orderId) payload.orderId = root.dataset.orderId;
                if (root.dataset.returnCaseId) payload.returnCaseId = root.dataset.returnCaseId;
                if (root.dataset.fulfillmentId) payload.fulfillmentId = root.dataset.fulfillmentId;
            }
            const path = ticketId ? `/conversations/${encodeURIComponent(ticketId)}/messages` : "/conversations";
            try {
                const result = await request(`${apiBase(entry)}${path}`, {
                    method: "POST", headers: { "Idempotency-Key": outgoingPendingKey }, body: jsonBody(payload)
                });
                const conversationId = result?.conversationId || result?.id || ticketId;
                if (!conversationId) throw new Error("Tin nhắn đã gửi nhưng chưa nhận được mã hội thoại.");
                const stillActive = submissionSequence === state.threadSequence && outgoingDraftKey === draftKey();
                clearSentDraft(outgoingDraftKey, outgoingAttachments, stillActive);
                if (!stillActive) {
                    await Promise.all([loadInbox(), refreshUnread()]);
                    return;
                }
                state.activeTicketId = conversationId;
                state.isNewConversation = false;
                state.activeEntry = { ...entry, conversationId, hasMessages: true, status: result.status || "OPEN" };
                state.ticketStatus = result.status || "OPEN";
                state.detailCanSend = result.canSend !== false;
                await Promise.all([loadInbox(), refreshUnread()]);
                const updated = state.entries.find(item => entry.scope === "SHOP"
                    ? item.scope === "SHOP" && item.contactId === entry.contactId
                    : item.scope === "PLATFORM_SUPPORT" && item.conversationId === conversationId);
                if (updated) state.activeEntry = { ...state.activeEntry, ...updated, conversationId };
                await openTicket(state.activeEntry, conversationId);
            } catch (error) {
                const stillActive = submissionSequence === state.threadSequence && outgoingDraftKey === draftKey();
                state.drafts.set(outgoingDraftKey, {
                    body, attachments: outgoingAttachments, pendingKey: outgoingPendingKey, sendFailed: true
                });
                if (stillActive) {
                    state.sendFailed = true;
                    setError(`${error.message} Bản nháp được giữ lại; bấm “Thử gửi lại” để gửi lại.`);
                    saveDraft();
                }
            } finally {
                state.requestBusy = false;
                setComposerEnabled(messagePolicy.canReply(state.ticketStatus, state.detailCanSend));
            }
        }

        function handleSignal(signal) {
            if (signal?.type === "TYPING") {
                if (signal.ticketId === state.activeTicketId && signal.actorId !== root.dataset.currentUserId) {
                    showTyping(signal.active ? (signal.actorName || "Đối phương") : "");
                }
                return;
            }
            if (signal?.type === "READ_CHANGED") {
                loadInbox();
                refreshUnread();
                return;
            }
            if (root.hidden) {
                loadInbox();
                refreshUnread();
                return;
            }
            loadInbox();
            refreshUnread();
            if (signal?.ticketId && signal.ticketId === state.activeTicketId) syncThread();
        }

        function bindEvents() {
            document.querySelectorAll("[data-customer-messages-open]").forEach(button => button.addEventListener("click", event => {
                if (button.dataset.shopChatConfirmShopId) {
                    event.preventDefault();
                    openShopContact(button.dataset.shopChatConfirmShopId, null, button);
                    return;
                }
                setOpen(true, button);
                listView.hidden = false;
                threadView.hidden = true;
                loadInbox();
                refreshUnread();
            }));
            document.addEventListener("click", event => {
                const entry = event.target.closest("[data-shop-chat-entry]");
                if (!entry) return;
                event.preventDefault();
                openShopContact(entry.dataset.shopId, entry.dataset.productId, entry);
            });
            document.querySelectorAll(".support-chat-context-link").forEach(link => link.addEventListener("click", event => {
                event.preventDefault();
                setOpen(true, link);
                startNewSupport(link.dataset.supportChatOrderId, link.dataset.supportChatReturnCaseId);
            }));
            root.querySelectorAll("[data-customer-messages-close]").forEach(button => button.addEventListener("click", () => setOpen(false)));
            root.querySelectorAll("[data-message-filter]").forEach(button => button.addEventListener("click", () => {
                saveDraft();
                state.scope = button.dataset.messageFilter;
                state.page = 0;
                updateFilterButtons();
                loadInbox();
            }));
            list?.addEventListener("click", event => {
                const button = event.target.closest("[data-inbox-key]");
                if (!button) return;
                const entry = state.entries.find(item => entryKey(item) === button.dataset.inboxKey);
                openEntry(entry);
            });
            more?.addEventListener("click", () => loadInbox(true));
            root.querySelector("[data-message-back]")?.addEventListener("click", showList);
            root.querySelector("[data-message-new-support]")?.addEventListener("click", () => startNewSupport());
            root.querySelector("[data-message-history]")?.addEventListener("click", () => {
                historyList.hidden = !historyList.hidden;
                if (!historyList.hidden) loadHistory();
            });
            historyMore?.addEventListener("click", () => loadHistory(true));
            root.querySelector("[data-message-new-shop]")?.addEventListener("click", startNewShopConversation);
            composer?.addEventListener("submit", sendMessage);
            fileInput?.addEventListener("change", () => uploadSelected(Array.from(fileInput.files || [])));
            textarea?.addEventListener("input", () => { saveDraft(); publishTyping(Boolean(textarea.value.trim())); });
            textarea?.addEventListener("blur", () => publishTyping(false));
            older?.addEventListener("click", loadOlderMessages);
            document.addEventListener("keydown", event => {
                if (root.hidden) return;
                if (event.key === "Escape") { event.preventDefault(); setOpen(false); return; }
                if (event.key !== "Tab" || !panel) return;
                const focusable = [...panel.querySelectorAll("button:not([disabled]), a[href], textarea:not([disabled]), input:not([disabled])")]
                    .filter(item => !item.hidden && item.getClientRects().length);
                if (!focusable.length) return;
                const first = focusable[0], last = focusable[focusable.length - 1];
                if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
                else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
            });
            document.addEventListener("visibilitychange", () => {
                if (document.visibilityState === "visible") {
                    refreshUnread();
                    if (!root.hidden) { loadInbox(); if (!threadView.hidden) syncThread(); }
                }
            });
            window.addEventListener("focus", () => {
                refreshUnread();
                if (!root.hidden) loadInbox();
            });
        }
        function startPolling() {
            if (state.polling) return;
            state.polling = window.setInterval(async () => {
                if (root.hidden || document.visibilityState !== "visible") return;
                await Promise.all([loadInbox(), refreshUnread()]);
                if (!threadView.hidden) await syncThread();
            }, 20000);
        }
        async function openLegacyTarget() {
            const legacySequence = ++state.openSequence;
            const initialThreadSequence = state.threadSequence;
            const initialEntryKey = state.activeEntry ? entryKey(state.activeEntry) : "";
            const initialTicketId = state.activeTicketId;
            const initialIsNewConversation = state.isNewConversation;
            const canContinue = () => legacySequence === state.openSequence
                && initialThreadSequence === state.threadSequence
                && initialTicketId === state.activeTicketId
                && initialIsNewConversation === state.isNewConversation
                && (initialEntryKey ? entryKey(state.activeEntry) === initialEntryKey : !state.activeEntry);
            setOpen(true);
            await loadInbox();
            if (!canContinue()) return;
            const params = new URLSearchParams(window.location.search);
            const shopId = shopIdFromPath();
            const contactId = root.dataset.initialContactId || params.get("contactId");
            if (contactId) {
                let entry = state.entries.find(item => item.scope === "SHOP" && item.contactId === contactId);
                if (!entry) {
                    try {
                        const exact = await request(`/messages/shop-contacts/${encodeURIComponent(contactId)}`);
                        if (!canContinue()) return;
                        const rows = exact?.scope === "SHOP" && exact.contactId === contactId ? [exact] : [];
                        state.entries = [...new Map([...rows, ...state.entries].map(item => [entryKey(item), item])).values()];
                        renderEntries();
                        entry = state.entries.find(item => item.scope === "SHOP" && item.contactId === contactId);
                    } catch (_) { /* use the shop id in the deep-link path as a display fallback */ }
                }
                if (!entry) {
                    const match = window.location.pathname.match(/^\/support\/shops\/([^/]+)/);
                    const shopId = match ? decodeURIComponent(match[1]) : "";
                    if (shopId) entry = { scope: "SHOP", contactId, shopId, title: "Cửa hàng", hasMessages: false, unreadConversations: 0, canSend: false };
                }
                if (entry && canContinue()) await openEntry(entry);
                root.dataset.initialContactId = "";
                return;
            }
            if (params.get("newConversation") === "true") {
                let entry = state.entries.find(item => item.scope === "SHOP" && item.shopId === shopId);
                if (shopId) {
                    try {
                        const history = await request(`/support/shops/${encodeURIComponent(shopId)}/chat/conversations?page=0`);
                        if (!canContinue()) return;
                        const selected = await replyableHistoryTicket(entry || { scope: "SHOP", shopId }, history.items);
                        if (selected?.id) {
                            entry = {
                                ...(entry || { scope: "SHOP", shopId, title: "Cửa hàng", unreadConversations: 0 }),
                                conversationId: selected.id, hasMessages: true, status: selected.status || entry?.status
                            };
                        }
                    } catch (_) { /* keep the inbox list visible if the old link cannot be resolved */ }
                }
                if (entry && canContinue()) await openEntry(entry);
                return;
            }
            const conversationId = root.dataset.conversationId || params.get("conversationId");
            if (conversationId) {
                const entry = shopId
                    ? await shopConversationEntry(shopId, conversationId)
                    : state.entries.find(item => item.scope === "PLATFORM_SUPPORT" && item.conversationId === conversationId)
                        || { scope: "PLATFORM_SUPPORT", conversationId, title: "Hỗ trợ UTEWedding", hasMessages: true };
                if (canContinue()) await openEntry(entry);
                root.dataset.conversationId = "";
            } else if (root.dataset.newConversation === "true") {
                root.dataset.newConversation = "false";
                startNewSupport(root.dataset.orderId, root.dataset.returnCaseId);
            }
        }

        bindEvents();
        updateFilterButtons();
        refreshUnread();
        if (root.dataset.autoOpen === "true" || root.dataset.initialContactId || new URLSearchParams(window.location.search).has("contactId")) openLegacyTarget();
        startPolling();
        if (typeof window.connectStompUpdates === "function" && document.getElementById("liveUpdateConfig")) {
            window.connectStompUpdates({
                destinations: ["/user/queue/support-chat"],
                onConnect: () => { loadInbox(); refreshUnread(); if (!root.hidden) syncThread(); },
                onMessage: handleSignal
            });
        }
    });
})();
