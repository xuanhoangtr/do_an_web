(function () {
    const roleStatus = {
        OPEN: "Mới tiếp nhận", IN_PROGRESS: "Đang xử lý", WAITING_CUSTOMER: "Chờ khách phản hồi",
        RESOLVED: "Đã giải quyết", CLOSED: "Đã đóng"
    };

    document.addEventListener("DOMContentLoaded", () => {
        const customerRoot = document.getElementById("supportChatRoot");
        if (customerRoot?.dataset.unifiedInbox === "true") return;
        const staffRoot = document.getElementById("adminSupportChatRoot");
        const shopRoot = document.getElementById("shopChatRoot");
        const root = shopRoot || customerRoot || staffRoot;
        if (!root) return;
        const shop = Boolean(shopRoot);
        const staff = shop || root.dataset.supportChatMode === "staff";
        const state = {
            root, staff, ticketId: null, currentPage: 0, listPage: 0, listHasMore: false,
            messages: new Map(), attachments: [], pendingKey: null, sendFailed: false,
            typingTimer: null, typingExpiry: null, requestBusy: false, polling: null,
            syncBusy: false, syncPending: false,
            filter: root.dataset.mine === "true" ? "mine" : (root.dataset.unassigned === "true" ? "unassigned" :
                (root.dataset.needsReply === "false" ? "all" : "needsReply")),
            needsReply: root.dataset.needsReply !== "false", query: root.dataset.query || "",
            status: root.dataset.status || ""
        };
        const $ = selector => root.querySelector(selector);
        const listView = $(`[data-chat-list-view]`);
        const threadView = $(`[data-chat-thread-view]`);
        const list = $(`[data-chat-conversation-list]`);
        const composer = $(`[data-chat-composer]`);
        const textarea = $(`[data-chat-message]`);
        const fileInput = $(`[data-chat-files]`);
        const messageBox = $(`[data-chat-messages]`);
        const listMore = $(`[data-chat-list-more]`);
        const messageMore = $(`[data-chat-message-more]`);
        const attachmentPreview = $(`[data-chat-attachment-preview]`);
        const errorBox = $(`[data-chat-error]`);
        const fallbackCsrf = document.querySelector('meta[name="_csrf"]')?.content;

        const request = async (url, options = {}) => {
            const headers = new Headers(options.headers || {});
            headers.set("Accept", "application/json");
            if (options.body && !(options.body instanceof FormData)) headers.set("Content-Type", "application/json");
            if (options.method && options.method !== "GET") {
                const token = document.querySelector('meta[name="_csrf"]')?.content || fallbackCsrf;
                if (token) headers.set("X-CSRF-TOKEN", token);
                headers.set("X-Requested-With", "XMLHttpRequest");
            }
            const response = await fetch(url, { credentials: "same-origin", cache: "no-store", ...options, headers });
            if (response.status === 204) return null;
            const type = response.headers.get("content-type") || "";
            const result = type.includes("application/json") ? await response.json() : { detail: await response.text() };
            if (!response.ok) {
                const error = new Error(result.detail || result.message || result.title || "Không gửi được tin nhắn. Hãy thử lại.");
                error.status = response.status;
                error.payload = result;
                throw error;
            }
            return result;
        };

        const api = path => `${root.dataset.apiBase || (staff ? "/admin/support/chat" : "/support/chat")}${path}`;
        const makeKey = () => window.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(16).slice(2)}`;
        const escInitial = value => (value || "").trim().slice(0, 1).toLocaleUpperCase("vi-VN") || "H";
        const fmtDate = value => {
            if (!value) return "";
            return new Intl.DateTimeFormat("vi-VN", { day: "2-digit", month: "2-digit", hour: "2-digit", minute: "2-digit" }).format(new Date(value));
        };
        const textNode = (tag, value, className) => {
            const node = document.createElement(tag);
            if (className) node.className = className;
            node.textContent = value || "";
            return node;
        };
        const jsonBody = value => JSON.stringify(value);

        function setError(message) { if (errorBox) errorBox.textContent = message || ""; }
        function setOpen(open) {
            if (!customerRoot || shop) return;
            customerRoot.hidden = !open;
            document.querySelectorAll("[data-support-chat-open]").forEach(button => button.setAttribute("aria-expanded", String(open)));
            document.body.classList.toggle("support-chat-open", open);
            if (open) {
                customerRoot.querySelector(".support-chat-panel")?.focus({ preventScroll: true });
                openInitialTarget();
                refreshUnread();
            }
        }
        function openNew(orderId, returnCaseId) {
            state.ticketId = null;
            state.ticketStatus = "OPEN";
            state.messages.clear();
            state.attachments = [];
            state.pendingKey = null;
            state.sendFailed = false;
            if (orderId) root.dataset.orderId = orderId;
            if (returnCaseId) root.dataset.returnCaseId = returnCaseId;
            root.dataset.newConversation = shop ? "false" : "true";
            renderAttachments();
            showThread();
            setHeader({ subject: "Cuộc trò chuyện mới", status: "OPEN", number: "" });
            renderMessages();
            setComposerEnabled(true);
            setError("");
            textarea?.focus();
        }
        function showList() {
            if (shop) root.classList.remove("is-thread-open");
            if (listView) listView.hidden = false;
            if (threadView) threadView.hidden = true;
        }
        function showThread() {
            if (shop) root.classList.add("is-thread-open");
            if (listView) listView.hidden = true;
            if (threadView) threadView.hidden = false;
            const empty = $(`[data-staff-empty]`);
            if (empty) empty.hidden = true;
        }
        function setHeader(conversation) {
            state.ticketStatus = conversation.status || "OPEN";
            const number = $(`[data-chat-ticket-number]`);
            const title = $(`[data-chat-title]`);
            const customer = $(`[data-chat-customer]`);
            const status = $(`[data-chat-ticket-status]`);
            if (number) number.textContent = conversation.number || "HỘI THOẠI MỚI";
            if (title) title.textContent = conversation.subject || "Hỗ trợ khách hàng";
            if (customer) customer.textContent = conversation.customerName || "";
            if (status) {
                status.textContent = roleStatus[conversation.status] || "";
                status.className = staff ? "support-inbox-status" : "support-chat-status";
            }
            if (staff) {
                const assignee = $(`[data-chat-assignee]`);
                const participants = $(`[data-chat-participants]`);
                const claim = $(`[data-staff-claim]`);
                const resolve = $(`[data-staff-resolve]`);
                if (assignee) assignee.textContent = conversation.assigneeName || "Chưa nhận";
                if (participants) participants.textContent = (conversation.participants || []).map(item => item.name).filter(Boolean).join(" · ");
                if (claim) claim.hidden = Boolean(conversation.assigneeId);
                if (resolve) resolve.hidden = conversation.status === "RESOLVED" || conversation.status === "CLOSED";
            }
            const context = $(`[data-chat-context]`);
            if (context) {
                const pieces = [];
                if (conversation.orderId) pieces.push(`${staff ? "Đơn hàng" : "Đơn hàng"}: ${conversation.orderId}`);
                if (conversation.returnCaseId) pieces.push(`Yêu cầu trả hàng: ${conversation.returnCaseId}`);
                if (conversation.fulfillmentId) pieces.push(`Kiện hàng: ${conversation.fulfillmentId}`);
                context.textContent = pieces.join(" · ");
                context.hidden = pieces.length === 0;
            }
            const afterHours = $(`[data-chat-after-hours]`);
            if (afterHours) afterHours.hidden = !(conversation.outsideBusinessHours && !staff);
            const isClosed = conversation.status === "CLOSED" || (shop && root.dataset.shopVendor === "true" && conversation.status === "RESOLVED");
            setComposerEnabled(!isClosed);
        }
        function setComposerEnabled(enabled) {
            if (!composer) return;
            composer.querySelectorAll("textarea, input[type=file], input[type=checkbox], button[type=submit]").forEach(control => {
                control.disabled = !enabled || (state.sendFailed && control !== composer.querySelector("button[type=submit]"));
            });
            const send = $(`[data-chat-send]`);
            if (send) send.textContent = state.sendFailed ? "Thử gửi lại" : (staff ? "Gửi phản hồi" : "Gửi tin");
        }

        function renderConversationRows(items) {
            if (!list) return;
            if (state.listPage === 0) list.replaceChildren();
            if (!items.length && state.listPage === 0) {
                const empty = textNode("p", staff ? "Không có hội thoại nào trong bộ lọc này." : shop ? "Chưa có hội thoại với cửa hàng này." : "Bạn chưa có hội thoại nào. Hãy bắt đầu trò chuyện với UTEWedding.", staff ? "support-chat-empty" : "support-chat-empty");
                list.append(empty);
                return;
            }
            items.forEach(item => {
                const button = document.createElement("button");
                button.type = "button";
                button.className = `${staff ? "support-inbox-ticket" : "support-chat-conversation"}${item.id === state.ticketId ? " is-selected" : ""}`;
                button.dataset.conversationId = item.id;
                const main = document.createElement("span");
                main.className = staff ? "support-inbox-ticket-main" : "support-chat-conversation-main";
                const top = document.createElement("span");
                top.className = staff ? "support-inbox-ticket-title" : "support-chat-conversation-top";
                top.append(textNode("strong", item.subject || item.number));
                top.append(textNode("time", fmtDate(item.lastActivityAt)));
                main.append(top);
                main.append(textNode("span", item.preview || "Chưa có tin nhắn", staff ? "support-inbox-ticket-preview" : "support-chat-conversation-preview"));
                const bottom = document.createElement("span");
                bottom.className = staff ? "support-inbox-ticket-meta" : "support-chat-conversation-bottom";
                bottom.append(textNode("span", `${item.number} · ${roleStatus[item.status] || item.status}`));
                if (item.unreadMessages > 0) bottom.append(textNode("span", item.unreadMessages > 99 ? "99+" : String(item.unreadMessages), "support-chat-unread"));
                main.append(bottom);
                if (!staff) button.append(textNode("span", escInitial(item.subject), "support-chat-avatar"));
                button.append(main);
                list.append(button);
            });
        }

        async function loadConversations(append = false) {
            try {
                const page = append ? state.listPage + 1 : 0;
                const params = new URLSearchParams({ page: String(page) });
                if (staff && !shop) {
                    if (state.query) params.set("q", state.query);
                    if (state.filter === "mine") params.set("mine", "true");
                    if (state.filter === "unassigned") params.set("unassigned", "true");
                    if (state.status) params.set("status", state.status);
                    params.set("needsReply", String(state.needsReply));
                }
                const result = await request(`${api("/conversations")}?${params}`);
                if (!append) state.listPage = 0;
                renderConversationRows(result.items || []);
                state.listPage = result.page || 0;
                state.listHasMore = Boolean(result.hasMore);
                if (listMore) listMore.hidden = !state.listHasMore;
                return result.items || [];
            } catch (error) {
                if (list) list.replaceChildren(textNode("p", "Không tải được hội thoại. Vui lòng thử tải lại.", "support-chat-empty"));
                return [];
            }
        }

        async function openInitialTarget() {
            if (root.dataset.newConversation === "true" && !state.ticketId) {
                openNew(root.dataset.orderId, root.dataset.returnCaseId);
                root.dataset.newConversation = "false";
                return;
            }
            const selectedId = root.dataset.selectedId || root.dataset.conversationId;
            if (selectedId) {
                await openConversation(selectedId);
                root.dataset.conversationId = "";
                root.dataset.selectedId = "";
                return;
            }
            const items = await loadConversations();
            const newestOpen = items.find(item => item.status !== "CLOSED" && item.status !== "RESOLVED");
            if (newestOpen && customerRoot) await openConversation(newestOpen.id);
            else if (customerRoot) showList();
        }

        async function openConversation(id) {
            state.ticketId = id;
            state.messages.clear();
            state.currentPage = 0;
            showThread();
            if (messageBox) messageBox.replaceChildren(textNode("p", "Đang tải tin nhắn…", "support-chat-loading"));
            try {
                const detail = await request(api(`/conversations/${id}`));
                setHeader(detail);
                const page = await request(api(`/conversations/${id}/messages?page=0`));
                addMessages(page.messages || []);
                state.currentPage = page.page || 0;
                if (messageMore) messageMore.hidden = !page.hasMore;
                if (document.visibilityState === "visible") await markRead();
                const typers = await request(api(`/conversations/${id}/typing`)).catch(() => []);
                if (typers?.length) showTyping(typers.map(person => person.name).join(", "));
                if (document.querySelector("[data-support-chat-open]")) loadConversations();
            } catch (error) {
                if (messageBox) messageBox.replaceChildren(textNode("p", "Không thể mở hội thoại này.", "support-chat-empty"));
                setError(error.message);
            }
        }

        function addMessages(messages) {
            messages.forEach(message => state.messages.set(message.id, message));
            renderMessages();
        }
        function renderMessages() {
            if (!messageBox) return;
            const wasAtBottom = messageBox.scrollHeight - messageBox.scrollTop - messageBox.clientHeight < 100;
            messageBox.replaceChildren();
            const messages = [...state.messages.values()].sort((a, b) => new Date(a.createdAt) - new Date(b.createdAt));
            if (!messages.length) messageBox.append(textNode("p", state.ticketId ? "Hãy gửi tin nhắn để bắt đầu trao đổi." : "Hãy nhập tin nhắn đầu tiên.", "support-chat-empty"));
            messages.forEach(message => {
                const bubble = document.createElement("article");
                bubble.className = `support-chat-message${message.authoredByCurrentUser ? " is-mine" : ""}${message.visibility === "INTERNAL" ? " is-internal" : ""}`;
                bubble.dataset.messageId = message.id;
                const meta = document.createElement("div");
                meta.className = "support-chat-message-meta";
                const authorLabel = shop ? message.authorName : message.visibility === "INTERNAL" ? `${message.authorName} · Ghi chú nội bộ` : `${message.authorName}${message.authorRole === "USER" || message.authorRole === "CUSTOMER" ? " · Khách" : " · Nhân viên"}`;
                meta.append(textNode("span", authorLabel), textNode("time", fmtDate(message.createdAt)));
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
                        image.alt = "Ảnh khách gửi hỗ trợ";
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

        async function loadOlderMessages() {
            if (!state.ticketId) return;
            const button = messageMore;
            if (button) button.disabled = true;
            try {
                const page = await request(api(`/conversations/${state.ticketId}/messages?page=${state.currentPage + 1}`));
                state.currentPage = page.page;
                const oldScrollHeight = messageBox.scrollHeight;
                addMessages(page.messages || []);
                messageBox.scrollTop = messageBox.scrollHeight - oldScrollHeight;
                if (button) button.hidden = !page.hasMore;
            } catch (_) { setError("Không tải được lịch sử cũ."); }
            finally { if (button) button.disabled = false; }
        }

        async function markRead() {
            if (!state.ticketId || document.visibilityState !== "visible") return;
            try {
                await request(api(`/conversations/${state.ticketId}/read`), { method: "POST" });
                refreshUnread();
            } catch (_) { /* a later focus/poll will retry the read marker */ }
        }

        async function syncOpenConversation() {
            if (!state.ticketId) return;
            if (state.syncBusy) { state.syncPending = true; return; }
            state.syncBusy = true;
            try {
                do {
                    state.syncPending = false;
                    const ticketId = state.ticketId;
                    if (!ticketId) return;
                    try {
                        const [detail, page] = await Promise.all([
                            request(api(`/conversations/${ticketId}`)),
                            request(api(`/conversations/${ticketId}/messages?page=0`))
                        ]);
                        if (ticketId !== state.ticketId) continue;
                        setHeader(detail);
                        addMessages(page.messages || []);
                        state.currentPage = 0;
                        if (messageMore) messageMore.hidden = !page.hasMore;
                        if (staff || (customerRoot && !customerRoot.hidden)) await markRead();
                    } catch (_) { /* the next socket signal or poll retries */ }
                } while (state.syncPending);
            } finally {
                state.syncBusy = false;
            }
        }

        async function refreshUnread() {
            try {
                const result = await request(api("/unread-count"));
                const value = Math.max(0, Number(result.count || 0));
                document.querySelectorAll("[data-support-chat-unread], [data-support-staff-unread]").forEach(badge => {
                    badge.hidden = value === 0;
                    badge.textContent = value > 99 ? "99+" : String(value);
                });
            } catch (_) { /* unread badges recover on the next poll or socket signal */ }
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
                    renderAttachments();
                });
                wrapper.append(image, remove);
                attachmentPreview.append(wrapper);
            });
        }

        async function uploadSelected(files) {
            if (!files.length) return;
            if (state.attachments.length + files.length > 5) { setError("Mỗi tin nhắn đính kèm tối đa 5 ảnh."); return; }
            if (fileInput) fileInput.disabled = true;
            setError("");
            const form = new FormData();
            files.forEach(file => form.append("files", file));
            try {
                const uploaded = await request(api("/attachments"), { method: "POST", body: form });
                uploaded.forEach((image, index) => state.attachments.push({ ...image, name: image.name || files[index]?.name, previewUrl: URL.createObjectURL(files[index]) }));
                renderAttachments();
            } catch (error) { setError(error.message); }
            finally {
                if (fileInput) { fileInput.value = ""; fileInput.disabled = state.sendFailed; }
            }
        }

        async function sendMessage(event) {
            event.preventDefault();
            if (state.requestBusy) return;
            const body = textarea?.value || "";
            if (!body.trim() && !state.attachments.length) { setError("Hãy nhập tin nhắn hoặc đính kèm ảnh."); return; }
            if (!state.pendingKey) state.pendingKey = makeKey();
            state.requestBusy = true;
            setError("");
            if (textarea) textarea.disabled = true;
            if (fileInput) fileInput.disabled = true;
            const internal = staff && Boolean($(`[data-chat-internal]`)?.checked);
            const payload = staff && !shop
                ? { body, internal, attachmentMediaIds: state.attachments.map(item => item.id) }
                : { body, attachmentMediaIds: state.attachments.map(item => item.id),
                    orderId: root.dataset.orderId || null, returnCaseId: root.dataset.returnCaseId || null,
                    fulfillmentId: root.dataset.fulfillmentId || null, ...(shop ? { category: "GENERAL" } : {}) };
            try {
                const path = state.ticketId ? `/conversations/${state.ticketId}/messages` : "/conversations";
                const result = await request(api(path), { method: "POST", headers: { "Idempotency-Key": state.pendingKey }, body: jsonBody(payload) });
                state.ticketId = result.conversationId;
                root.dataset.conversationId = state.ticketId;
                state.pendingKey = null;
                state.sendFailed = false;
                state.attachments.forEach(item => URL.revokeObjectURL(item.previewUrl));
                state.attachments = [];
                renderAttachments();
                if (textarea) { textarea.value = ""; textarea.disabled = false; }
                const internalCheck = $(`[data-chat-internal]`);
                if (internalCheck) internalCheck.checked = false;
                if (result.outsideBusinessHours && !staff && $(`[data-chat-after-hours]`)) $(`[data-chat-after-hours]`).hidden = false;
                await openConversation(state.ticketId);
                await loadConversations();
                refreshUnread();
            } catch (error) {
                if (error.status === 409 && error.payload?.code === "STAFF_PARTICIPATION_REQUIRED") {
                    const agentName = error.payload.assignedAgentName || "nhân viên khác";
                    if (window.confirm(`${agentName} đang hỗ trợ khách này. Bạn muốn tham gia cùng ${agentName}?`)) {
                        try {
                            await request(api(`/conversations/${state.ticketId}/join`), { method: "POST" });
                            const retry = await request(api(`/conversations/${state.ticketId}/messages`), {
                                method: "POST", headers: { "Idempotency-Key": state.pendingKey }, body: jsonBody(payload)
                            });
                            state.pendingKey = null;
                            state.sendFailed = false;
                            state.attachments.forEach(item => URL.revokeObjectURL(item.previewUrl));
                            state.attachments = [];
                            renderAttachments();
                            if (textarea) { textarea.value = ""; textarea.disabled = false; }
                            await openConversation(retry.conversationId);
                            await loadConversations();
                        } catch (retryError) { state.sendFailed = true; setError(retryError.message); }
                    } else {
                        state.pendingKey = null;
                        if (textarea) textarea.disabled = false;
                        if (fileInput) fileInput.disabled = false;
                    }
                } else {
                    state.sendFailed = true;
                    setError(`${error.message} Bản nháp được giữ lại; bấm “Thử gửi lại” để gửi cùng yêu cầu.`);
                }
            } finally {
                state.requestBusy = false;
                if (!state.sendFailed) {
                    if (textarea) textarea.disabled = state.ticketStatus === "CLOSED";
                    if (fileInput) fileInput.disabled = state.ticketStatus === "CLOSED";
                }
                setComposerEnabled(state.ticketStatus !== "CLOSED");
            }
        }

        function showTyping(names) {
            const label = $(`[data-chat-typing]`);
            if (!label) return;
            label.textContent = names ? `${names} đang soạn tin…` : "";
            if (state.typingExpiry) clearTimeout(state.typingExpiry);
            if (names) state.typingExpiry = window.setTimeout(() => { label.textContent = ""; }, 10000);
        }

        function publishTyping(active) {
            if (!state.ticketId) return;
            const internal = Boolean($(`[data-chat-internal]`)?.checked);
            window.clearTimeout(state.typingTimer);
            state.typingTimer = window.setTimeout(() => {
                request(api("/typing"), { method: "POST", body: jsonBody({ conversationId: state.ticketId, active, internal }) }).catch(() => {});
            }, active ? 500 : 0);
        }

        function startPolling() {
            if (state.polling) return;
            state.polling = window.setInterval(async () => {
                const isOpen = staff || (customerRoot && !customerRoot.hidden);
                if (!isOpen || document.visibilityState !== "visible") return;
                // Keep a low-frequency sync as a safety net. A connected STOMP
                // socket does not prove that this subscription received every
                // signal (for example after reconnect or a missed frame).
                await loadConversations();
                if (state.ticketId) {
                    await syncOpenConversation();
                }
                refreshUnread();
            }, 15000);
        }

        function handleSignal(signal) {
            if (signal?.type === "READ_CHANGED") { refreshUnread(); return; }
            if (!signal || signal.type === "TYPING") {
                if (signal?.ticketId === state.ticketId && signal.actorId !== currentUserId()) {
                    showTyping(signal.active ? (signal.actorName || "Nhân viên") : "");
                }
            }
            if (state.ticketId && signal?.ticketId === state.ticketId) {
                syncOpenConversation();
            }
            loadConversations();
            refreshUnread();
        }

        function currentUserId() { return root.dataset.currentUserId || ""; }

        function bindEvents() {
            document.querySelectorAll("[data-support-chat-open]").forEach(button => button.addEventListener("click", () => setOpen(true)));
            document.querySelectorAll(".support-chat-context-link").forEach(link => link.addEventListener("click", event => {
                if (!customerRoot || shop) return;
                event.preventDefault();
                openNew(link.dataset.supportChatOrderId, link.dataset.supportChatReturnCaseId);
                setOpen(true);
            }));
            customerRoot?.querySelectorAll("[data-chat-close]").forEach(button => button.addEventListener("click", () => setOpen(false)));
            customerRoot?.querySelector("[data-chat-back]")?.addEventListener("click", () => { showList(); loadConversations(); });
            root.querySelector("[data-chat-new]")?.addEventListener("click", () => openNew(root.dataset.orderId, root.dataset.returnCaseId));
            root.querySelector("[data-shop-chat-back]")?.addEventListener("click", showList);
            list?.addEventListener("click", event => {
                const button = event.target.closest("[data-conversation-id]");
                if (button) openConversation(button.dataset.conversationId);
            });
            listMore?.addEventListener("click", () => loadConversations(true));
            messageMore?.addEventListener("click", loadOlderMessages);
            composer?.addEventListener("submit", sendMessage);
            fileInput?.addEventListener("change", () => uploadSelected(Array.from(fileInput.files || [])));
            textarea?.addEventListener("input", () => publishTyping(Boolean(textarea.value.trim())));
            textarea?.addEventListener("blur", () => publishTyping(false));
            $(`[data-chat-internal]`)?.addEventListener("change", () => publishTyping(false));
            document.addEventListener("keydown", event => {
                if (event.key === "Escape" && customerRoot && !customerRoot.hidden) setOpen(false);
            });
            document.addEventListener("visibilitychange", () => {
                if (document.visibilityState === "visible" && state.ticketId && (!customerRoot || !customerRoot.hidden)) markRead();
            });
            if (staff && !shop) {
                root.querySelectorAll("[data-staff-filter]").forEach(button => button.addEventListener("click", () => {
                    root.querySelectorAll("[data-staff-filter]").forEach(item => item.classList.toggle("is-active", item === button));
                    state.filter = button.dataset.staffFilter;
                    state.needsReply = state.filter === "needsReply";
                    if (state.filter !== "mine" && state.filter !== "unassigned") {
                        state.filter = state.needsReply ? "needsReply" : "all";
                    }
                    state.status = "";
                    state.listPage = 0;
                    loadConversations();
                }));
                $(`[data-staff-search]`)?.addEventListener("submit", event => {
                    event.preventDefault();
                    state.query = $(`[data-staff-query]`)?.value.trim() || "";
                    state.listPage = 0;
                    loadConversations();
                });
                $(`[data-staff-query]`)?.addEventListener("input", () => {
                    window.clearTimeout(state.searchTimer);
                    state.searchTimer = window.setTimeout(() => {
                        state.query = $(`[data-staff-query]`).value.trim();
                        state.listPage = 0;
                        loadConversations();
                    }, 350);
                });
                $(`[data-staff-claim]`)?.addEventListener("click", async () => {
                    if (!state.ticketId) return;
                    try { await request(api(`/conversations/${state.ticketId}/claim`), { method: "POST" }); await openConversation(state.ticketId); await loadConversations(); }
                    catch (error) { setError(error.message); }
                });
                $(`[data-staff-resolve]`)?.addEventListener("click", async () => {
                    if (!state.ticketId || !window.confirm("Đánh dấu hội thoại này đã được giải quyết?")) return;
                    try {
                        await request(api(`/conversations/${state.ticketId}/status`), {
                            method: "POST", headers: { "Idempotency-Key": makeKey() }, body: jsonBody({ status: "RESOLVED" })
                        });
                        await openConversation(state.ticketId);
                        await loadConversations();
                    } catch (error) { setError(error.message); }
                });
            }
        }

        function openOnPage() {
            if (!staff && root.dataset.autoOpen === "true") setOpen(true);
            else if (staff) {
                const queryInput = $(`[data-staff-query]`);
                if (queryInput) queryInput.value = state.query;
                loadConversations();
                refreshUnread();
                if (root.dataset.selectedId) openConversation(root.dataset.selectedId);
            } else {
                refreshUnread();
            }
        }

        bindEvents();
        openOnPage();
        startPolling();
        if (typeof window.connectStompUpdates === "function" && document.getElementById("liveUpdateConfig")) {
            window.connectStompUpdates({
                destinations: ["/user/queue/support-chat"],
                onConnect: () => { loadConversations(); refreshUnread(); syncOpenConversation(); },
                onMessage: handleSignal
            });
        }
    });
})();
