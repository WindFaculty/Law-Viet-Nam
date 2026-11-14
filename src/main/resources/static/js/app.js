(() => {
    "use strict";

    /* ---- theme toggle --------------------------------------------------- */

    const THEME_KEY = "lawrag-theme";
    const root = document.documentElement;
    const themeToggle = document.getElementById("theme-toggle");

    const preferredTheme = () => {
        try {
            const saved = window.localStorage.getItem(THEME_KEY);
            if (saved === "light" || saved === "dark") {
                return saved;
            }
        } catch (_) {
            // Ignore storage access errors in restricted contexts.
        }
        return window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches
            ? "dark"
            : "light";
    };

    const applyTheme = (theme) => {
        root.setAttribute("data-theme", theme);
        if (themeToggle) {
            const isDark = theme === "dark";
            themeToggle.setAttribute("aria-pressed", String(isDark));
            themeToggle.setAttribute(
                "title",
                isDark ? "Chuyển sang giao diện sáng" : "Chuyển sang giao diện tối"
            );
        }
    };

    applyTheme(preferredTheme());

    if (themeToggle) {
        themeToggle.addEventListener("click", () => {
            const next = root.getAttribute("data-theme") === "dark" ? "light" : "dark";
            applyTheme(next);
            try {
                window.localStorage.setItem(THEME_KEY, next);
            } catch (_) {
                // Ignore storage errors.
            }
        });
    }

    /* ---- composer & prompt suggestions ---------------------------------- */

    const form = document.getElementById("question-form");
    const message = document.getElementById("message");
    const personaRadios = Array.from(document.querySelectorAll('input[name="personaCode"][form="question-form"]'));
    const personaHint = document.getElementById("persona-hint");
    const counter = document.getElementById("composer-count");
    const messages = document.getElementById("messages");
    const max = message ? Number(message.getAttribute("maxlength")) || 4000 : 4000;

    const getSelectedPersonaRadio = () => personaRadios.find((radio) => radio.checked) || personaRadios[0] || null;

    const updatePersonaUI = () => {
        if (!personaRadios.length) {
            return;
        }
        const selected = getSelectedPersonaRadio();
        personaRadios.forEach((radio) => {
            const label = radio.closest(".persona-btn");
            if (label) {
                label.classList.toggle("is-selected", radio.checked);
            }
        });
        if (personaHint && selected) {
            const desc = selected.getAttribute("data-description") || "";
            const isDefault = selected.getAttribute("data-default") === "true";
            personaHint.textContent = isDefault && desc ? `Mặc định · ${desc}` : desc;
        }
    };

    if (personaRadios.length) {
        personaRadios.forEach((radio) => {
            radio.addEventListener("change", updatePersonaUI);
        });
        updatePersonaUI();
    }

    const updateCount = () => {
        if (!message || !counter) {
            return;
        }
        const used = message.value.length;
        counter.textContent = `${used} / ${max}`;
        counter.classList.toggle("is-near-limit", used >= max - 200);
    };

    const grow = () => {
        if (!message) {
            return;
        }
        message.style.height = "auto";
        message.style.height = `${Math.min(message.scrollHeight, 224)}px`;
    };

    const syncEmptyState = () => {
        if (!messages) {
            return;
        }
        const empty = document.getElementById("chat-empty");
        if (empty && messages.querySelector(".message")) {
            empty.hidden = true;
        }
    };

    const scrollToNewest = () => {
        if (!messages) {
            return;
        }
        const reduced = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
        const last = messages.lastElementChild;
        if (!last) {
            return;
        }
        last.scrollIntoView({ behavior: reduced ? "auto" : "smooth", block: "nearest" });
    };

    if (message) {
        message.addEventListener("input", () => {
            updateCount();
            grow();
        });
        message.addEventListener("keydown", (event) => {
            if (event.key === "Enter" && (event.ctrlKey || event.metaKey)) {
                event.preventDefault();
                form && form.requestSubmit();
            }
        });
        updateCount();
        grow();
    }

    document.addEventListener("click", (event) => {
        const trigger = event.target instanceof Element
            ? event.target.closest("[data-prompt]")
            : null;
        if (!trigger || !message) {
            return;
        }
        const prompt = trigger.getAttribute("data-prompt");
        if (!prompt) {
            return;
        }
        message.value = prompt;
        updateCount();
        grow();
        message.focus();
    });

    syncEmptyState();

    /* ---- forum & admin interactive controls (toggle, confirm, RAG draft) - */

    const csrfToken = document.querySelector('meta[name="_csrf"]');
    const csrfHeader = document.querySelector('meta[name="_csrf_header"]');

    document.addEventListener("click", (event) => {
        const toggleBtn = event.target instanceof Element
            ? event.target.closest("[data-toggle-target]")
            : null;
        if (toggleBtn) {
            const targetId = toggleBtn.getAttribute("data-toggle-target");
            const panel = targetId ? document.getElementById(targetId) : null;
            if (panel) {
                panel.hidden = !panel.hidden;
                if (!panel.hidden) {
                    const field = panel.querySelector("textarea, input:not([type='hidden'])");
                    if (field instanceof HTMLElement) {
                        field.focus();
                    }
                }
            }
            return;
        }

        const confirmTrigger = event.target instanceof Element
            ? event.target.closest("[data-confirm]")
            : null;
        if (confirmTrigger) {
            const confirmMsg = confirmTrigger.getAttribute("data-confirm");
            if (confirmMsg && !window.confirm(confirmMsg)) {
                event.preventDefault();
                return;
            }
        }
    });

    document.addEventListener("click", async (event) => {
        const draftBtn = event.target instanceof Element
            ? event.target.closest(".js-ai-draft")
            : null;
        if (!draftBtn) {
            return;
        }
        const url = draftBtn.getAttribute("data-draft-url");
        const textareaId = draftBtn.getAttribute("data-target-textarea");
        const personaSelectId = draftBtn.getAttribute("data-persona-select");
        const statusId = draftBtn.getAttribute("data-status-target");
        const textarea = textareaId ? document.getElementById(textareaId) : null;
        const personaEl = personaSelectId ? document.getElementById(personaSelectId) : null;
        const statusEl = statusId ? document.getElementById(statusId) : null;
        if (!url || !textarea) {
            return;
        }

        const headers = { "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8" };
        if (csrfToken && csrfHeader && csrfHeader.content) {
            headers[csrfHeader.content] = csrfToken.content;
        }

        draftBtn.disabled = true;
        if (statusEl) {
            statusEl.textContent = "Đang truy xuất kho văn bản để tạo gợi ý...";
        }
        try {
            const body = new URLSearchParams();
            if (personaEl && personaEl.value) {
                body.set("personaCode", personaEl.value);
            }
            const response = await fetch(url, { method: "POST", headers, body });
            if (!response.ok) {
                if (statusEl) {
                    statusEl.textContent = `Không thể tạo gợi ý (mã ${response.status}).`;
                }
                return;
            }
            const data = await response.json();
            if (data.success && data.draft) {
                textarea.value = data.draft;
                textarea.focus();
            }
            if (statusEl) {
                statusEl.textContent = data.message || "";
            }
        } catch (_) {
            if (statusEl) {
                statusEl.textContent = "Mất kết nối khi gọi trợ lý RAG.";
            }
        } finally {
            draftBtn.disabled = false;
        }
    });

    /* ---- htmx + csrf + sse stream --------------------------------------- */

    const attachStreamIfNeeded = (article) => {
        if (!window.EventSource || !window.htmx || !(article instanceof Element)) {
            return;
        }
        if (article.getAttribute("data-status") !== "PROCESSING_LOCAL") {
            return;
        }
        const streamUrl = article.getAttribute("data-stream-url");
        if (!streamUrl || article.dataset.streamBound === "true") {
            return;
        }
        article.dataset.streamBound = "true";

        const pollHook = article.querySelector(".poll-hook");
        if (pollHook) {
            pollHook.remove();
        }

        const fragmentUrl = streamUrl.replace(/\/stream$/, "/fragment");
        const stageEl = article.querySelector(".js-stream-stage");
        const previewEl = article.querySelector(".js-stream-preview");
        const textEl = article.querySelector(".js-stream-text");
        const shimmerEl = article.querySelector(".shimmer-lines");
        const badgeEl = article.querySelector(".message-state-badge");
        const hintEl = article.querySelector(".state-loading .muted");

        if (hintEl) {
            hintEl.textContent = "Đang truyền phát tiến trình và câu trả lời trực tiếp…";
        }

        const source = new EventSource(streamUrl);
        let finalized = false;

        const finalize = () => {
            if (finalized) {
                return;
            }
            finalized = true;
            source.close();
            if (!document.body.contains(article)) {
                return;
            }
            window.htmx.ajax("GET", fragmentUrl, { target: article, swap: "outerHTML" });
        };

        source.addEventListener("stage", (event) => {
            try {
                const payload = JSON.parse(event.data);
                if (stageEl && payload && payload.message) {
                    stageEl.textContent = payload.message;
                }
            } catch (_) {
                // Ignore malformed stage payload.
            }
        });

        source.addEventListener("delta", (event) => {
            try {
                const payload = JSON.parse(event.data);
                if (!payload || typeof payload.text !== "string") {
                    return;
                }
                if (previewEl && previewEl.hidden) {
                    previewEl.hidden = false;
                }
                if (shimmerEl) {
                    shimmerEl.hidden = true;
                }
                if (badgeEl) {
                    badgeEl.textContent = "Đang trả lời…";
                }
                if (textEl) {
                    textEl.textContent += payload.text;
                }
                scrollToNewest();
            } catch (_) {
                // Ignore malformed delta payload.
            }
        });

        source.addEventListener("complete", finalize);
        source.addEventListener("error", finalize);
    };

    const scanStreams = (scope) => {
        if (!(scope instanceof Element)) {
            return;
        }
        if (scope.matches(".message-ai[data-stream-url]")) {
            attachStreamIfNeeded(scope);
        }
        scope.querySelectorAll(".message-ai[data-stream-url]").forEach(attachStreamIfNeeded);
    };

    if (!csrfToken || !csrfHeader || !window.htmx) {
        return;
    }

    document.body.addEventListener("htmx:configRequest", (event) => {
        event.detail.headers[csrfHeader.content] = csrfToken.content;
        if (event.detail.elt instanceof HTMLFormElement && event.detail.elt.id === "question-form") {
            event.detail.headers["X-Chat-Stream"] = "true";
        }
    });

    document.body.addEventListener("htmx:afterRequest", (event) => {
        const elt = event.detail.elt;

        if (event.detail.successful
            && elt instanceof HTMLFormElement
            && elt.id === "question-form") {
            const selectedRadio = getSelectedPersonaRadio();
            const chosenPersona = selectedRadio ? selectedRadio.value : null;
            elt.reset();
            if (chosenPersona && personaRadios.length) {
                personaRadios.forEach((radio) => {
                    radio.checked = radio.value === chosenPersona;
                });
                updatePersonaUI();
            }
            if (message) {
                message.style.height = "";
            }
            updateCount();
            const key = document.getElementById("idempotency-key");
            if (key) {
                key.value = window.crypto && typeof window.crypto.randomUUID === "function"
                    ? window.crypto.randomUUID()
                    : String(Date.now()) + Math.random().toString(16).slice(2);
            }
            syncEmptyState();
            scrollToNewest();
            return;
        }

        if (!event.detail.successful
            && event.detail.requestConfig
            && !event.detail.requestConfig.path.includes("/fragment")) {
            const status = event.detail.xhr ? event.detail.xhr.status : 0;
            toast(status >= 400
                ? `Không gửi được yêu cầu (mã ${status}). Vui lòng thử lại.`
                : "Mất kết nối. Vui lòng kiểm tra mạng rồi thử lại.", true);
        }
    });

    /* A polled message swaps its whole article; keep it in view. */
    document.body.addEventListener("htmx:afterSwap", (event) => {
        syncEmptyState();
        scanStreams(event.target);
        if (event.target instanceof Element && event.target.classList.contains("message-ai")) {
            scrollToNewest();
        }
    });

    if (messages) {
        scanStreams(messages);
    }

    /* ---- toast ---------------------------------------------------------- */

    const stack = document.getElementById("toast-stack");

    function toast(text, isError) {
        if (!stack) {
            return;
        }
        const el = document.createElement("div");
        el.className = `toast ${isError ? "toast-error" : "toast-ok"}`;
        el.setAttribute("role", "status");
        el.textContent = text;
        stack.appendChild(el);
        window.setTimeout(() => el.remove(), isError ? 7000 : 4000);
    }
})();
