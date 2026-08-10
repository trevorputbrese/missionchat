(function () {
    const STORAGE_KEY = "missionchat.conversationId";
    const MAX_CHARS = 4000;
    const SEND_ICON = '<svg viewBox="0 0 24 24"><path d="M5 4l15 8-15 8 3-8-3-8z"></path></svg>';
    const STOP_ICON = '<svg viewBox="0 0 24 24"><rect x="7" y="7" width="10" height="10" rx="1.5"></rect></svg>';

    const workspaceMain = document.querySelector(".workspace-main");
    const introSection = document.getElementById("chatIntro");
    const suggestionsSection = document.getElementById("chatSuggestions");
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

    function appendMessage(role, content, isError) {
        const messageEl = document.createElement("article");
        messageEl.classList.add("chat-message", role);
        if (isError) {
            messageEl.classList.add("error");
        }
        messageEl.textContent = content;
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

    setPending(false);
})();
