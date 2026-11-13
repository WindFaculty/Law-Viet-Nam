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
    const counter = document.getElementById("composer-count");
    const messages = document.getElementById("messages");
    const max = message ? Number(message.getAttribute("maxlength")) || 4000 : 4000;

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

    /* ---- htmx + csrf ---------------------------------------------------- */

    const csrfToken = document.querySelector('meta[name="_csrf"]');
    const csrfHeader = document.querySelector('meta[name="_csrf_header"]');

    if (!csrfToken || !csrfHeader || !window.htmx) {
        return;
    }

    document.body.addEventListener("htmx:configRequest", (event) => {
        event.detail.headers[csrfHeader.content] = csrfToken.content;
    });

    document.body.addEventListener("htmx:afterRequest", (event) => {
        const elt = event.detail.elt;

        if (event.detail.successful
            && elt instanceof HTMLFormElement
            && elt.id === "question-form") {
            elt.reset();
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
        if (event.target instanceof Element && event.target.classList.contains("message-ai")) {
            scrollToNewest();
        }
    });

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
