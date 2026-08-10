(function () {
    const STORAGE_KEY = "missionchat.conversationId";
    const MAX_CHARS = 4000;
    const SEND_ICON = '<svg viewBox="0 0 24 24"><path d="M5 4l15 8-15 8 3-8-3-8z"></path></svg>';
    const STOP_ICON = '<svg viewBox="0 0 24 24"><rect x="7" y="7" width="10" height="10" rx="1.5"></rect></svg>';

    const workspaceMain = document.querySelector(".workspace-main");
    const introSection = document.getElementById("chatIntro");
    const suggestionsSection = document.getElementById("chatSuggestions");
    const modelStatusBarEl = document.getElementById("modelStatusBar");
    const suggestionButtons = Array.from(document.querySelectorAll(".suggestion-card"));
    const statusEl = document.getElementById("chatStatus");
    const threadEl = document.getElementById("chatThread");
    const inputEl = document.getElementById("composerInput");
    const sendButtonEl = document.getElementById("sendButton");

    if (!workspaceMain || !statusEl || !threadEl || !inputEl || !sendButtonEl) {
        return;
    }

    let conversationId = sessionStorage.getItem(STORAGE_KEY) || "";
    let pending = false;
    let abortController = null;
    const markdownRendererAvailable = Boolean(
        window.marked && typeof window.marked.parse === "function"
            && window.DOMPurify && typeof window.DOMPurify.sanitize === "function"
    );

    if (markdownRendererAvailable && typeof window.marked.setOptions === "function") {
        window.marked.setOptions({
            gfm: true,
            breaks: true
        });
    }

    function toSuggestionText(button) {
        const cloned = button.cloneNode(true);
        const quote = cloned.querySelector(".quote-mark");
        if (quote) {
            quote.remove();
        }
        return (cloned.textContent || "").replace(/\s+/g, " ").trim();
    }

    function setStatus(message, isError) {
        statusEl.textContent = message || "";
        statusEl.classList.toggle("error", Boolean(isError));
    }

    function renderModelStatusBar(payload) {
        if (!modelStatusBarEl) {
            return;
        }

        modelStatusBarEl.innerHTML = "";

        const heading = document.createElement("span");
        heading.className = "model-heading";
        heading.textContent = "AI Model:";
        modelStatusBarEl.appendChild(heading);

        const modelName = payload && typeof payload.modelName === "string" ? payload.modelName.trim() : "";
        const chip = document.createElement("span");
        chip.className = "model-chip";
        if (payload && payload.available && modelName) {
            chip.textContent = modelName;
        } else {
            chip.classList.add("unavailable");
            chip.textContent = "not available";
        }
        modelStatusBarEl.appendChild(chip);
    }

    async function loadModelStatus() {
        if (!modelStatusBarEl) {
            return;
        }

        try {
            const response = await fetch("/api/chat/model/status", {
                method: "GET",
                headers: {
                    "Accept": "application/json"
                }
            });
            if (!response.ok) {
                renderModelStatusBar({available: false, modelName: null});
                return;
            }

            const payload = await response.json();
            renderModelStatusBar(payload);
        } catch (error) {
            renderModelStatusBar({available: false, modelName: null});
        }
    }

    function setPending(value) {
        pending = value;
        inputEl.disabled = value;
        sendButtonEl.disabled = false;
        sendButtonEl.classList.toggle("stop-mode", value);
        sendButtonEl.setAttribute("aria-label", value ? "Stop generating" : "Send");
        sendButtonEl.innerHTML = value ? STOP_ICON : SEND_ICON;
    }

    function ensureChatView() {
        workspaceMain.classList.add("has-chat");
        if (introSection) {
            introSection.classList.add("is-hidden");
        }
        if (suggestionsSection) {
            suggestionsSection.classList.add("is-hidden");
        }
    }

    function renderMarkdownToSafeHtml(markdownText) {
        if (!markdownRendererAvailable) {
            return "";
        }

        try {
            const rawHtml = window.marked.parse(markdownText || "");
            if (!rawHtml || !rawHtml.trim()) {
                return "";
            }
            return window.DOMPurify.sanitize(rawHtml, {
                USE_PROFILES: {html: true},
                FORBID_TAGS: ["style", "script", "iframe", "object", "embed", "form"]
            });
        } catch (error) {
            return "";
        }
    }

    function decorateRenderedMessage(messageEl) {
        messageEl.querySelectorAll("a[href]").forEach(function (linkEl) {
            linkEl.setAttribute("target", "_blank");
            linkEl.setAttribute("rel", "noopener noreferrer");
        });

        Array.from(messageEl.querySelectorAll("table")).forEach(function (tableEl) {
            if (!tableEl.parentNode) {
                return;
            }
            const wrapper = document.createElement("div");
            wrapper.classList.add("table-wrap");
            tableEl.parentNode.insertBefore(wrapper, tableEl);
            wrapper.appendChild(tableEl);
        });
    }

    function appendMessage(role, content, isError) {
        const messageEl = document.createElement("article");
        messageEl.classList.add("chat-message", role);
        if (isError) {
            messageEl.classList.add("error");
        }

        if (role === "assistant" && !isError) {
            const safeHtml = renderMarkdownToSafeHtml(content);
            if (safeHtml) {
                messageEl.classList.add("markdown");
                messageEl.innerHTML = safeHtml;
                decorateRenderedMessage(messageEl);
            } else {
                messageEl.textContent = content;
            }
        } else {
            messageEl.textContent = content;
        }

        threadEl.appendChild(messageEl);
        threadEl.scrollTop = threadEl.scrollHeight;
    }

    async function sendMessage(rawMessage) {
        const message = (rawMessage || "").trim();
        if (!message || pending) {
            return;
        }
        if (message.length > MAX_CHARS) {
            setStatus("Please keep your message under 4000 characters.", true);
            return;
        }

        ensureChatView();
        appendMessage("user", message, false);
        inputEl.value = "";
        setStatus("MissionChat is generating a response...", false);
        setPending(true);
        abortController = new AbortController();

        try {
            const response = await fetch("/api/chat", {
                method: "POST",
                headers: {
                    "Content-Type": "application/json"
                },
                signal: abortController.signal,
                body: JSON.stringify({
                    message: message,
                    conversationId: conversationId || null
                })
            });

            const payload = await response.json().catch(function () {
                return {};
            });

            if (payload.conversationId) {
                conversationId = payload.conversationId;
                sessionStorage.setItem(STORAGE_KEY, conversationId);
            }

            if (!response.ok) {
                const errorMessage = payload.error || "MissionChat could not process your request.";
                appendMessage("assistant", errorMessage, true);
                setStatus(errorMessage, true);
                return;
            }

            const reply = (payload.reply || "").trim();
            if (!reply) {
                appendMessage("assistant", "MissionChat returned an empty response.", true);
                setStatus("MissionChat returned an empty response.", true);
                return;
            }

            appendMessage("assistant", reply, false);
            setStatus("", false);
        } catch (error) {
            if (error && error.name === "AbortError") {
                setStatus("MissionChat response stopped. You can send a new prompt.", false);
                return;
            }
            const networkError = "Could not reach MissionChat server.";
            appendMessage("assistant", networkError, true);
            setStatus(networkError, true);
        } finally {
            abortController = null;
            setPending(false);
            inputEl.focus();
        }
    }

    function stopMessage() {
        if (!pending || !abortController) {
            return;
        }
        abortController.abort();
    }

    suggestionButtons.forEach(function (button) {
        button.addEventListener("click", function () {
            sendMessage(toSuggestionText(button));
        });
    });

    sendButtonEl.addEventListener("click", function () {
        if (pending) {
            stopMessage();
            return;
        }
        sendMessage(inputEl.value);
    });

    inputEl.addEventListener("keydown", function (event) {
        if (event.key === "Enter" && !event.shiftKey) {
            event.preventDefault();
            sendMessage(inputEl.value);
        }
    });

    renderModelStatusBar({available: false, modelName: null});
    loadModelStatus();
    setPending(false);
})();
