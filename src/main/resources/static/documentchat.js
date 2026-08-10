(function () {
    const STORAGE_KEY = "documentschat.conversationId";
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

    const ragBannerEl = document.getElementById("ragBanner");
    const docsUploadFormEl = document.getElementById("docsUploadForm");
    const docsFileInputEl = document.getElementById("docsFileInput");
    const uploadDocsButtonEl = document.getElementById("uploadDocsButton");
    const clearDocsButtonEl = document.getElementById("clearDocsButton");
    const docsStatusEl = document.getElementById("docsStatus");
    const docsListEl = document.getElementById("docsList");

    if (!workspaceMain || !statusEl || !threadEl || !inputEl || !sendButtonEl) {
        return;
    }

    let conversationId = sessionStorage.getItem(STORAGE_KEY) || "";
    let pending = false;
    let docsBusy = false;
    let abortController = null;
    let ragAvailable = false;
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

    function setDocsStatus(message, isError) {
        if (!docsStatusEl) {
            return;
        }
        docsStatusEl.textContent = message || "";
        docsStatusEl.classList.toggle("error", Boolean(isError));
    }

    function setPending(value) {
        pending = value;
        inputEl.disabled = value;
        sendButtonEl.disabled = false;
        sendButtonEl.classList.toggle("stop-mode", value);
        sendButtonEl.setAttribute("aria-label", value ? "Stop generating" : "Send");
        sendButtonEl.innerHTML = value ? STOP_ICON : SEND_ICON;
    }

    function setDocsBusy(value) {
        docsBusy = value;
        if (docsFileInputEl) {
            docsFileInputEl.disabled = value || !ragAvailable;
        }
        if (uploadDocsButtonEl) {
            uploadDocsButtonEl.disabled = value || !ragAvailable;
        }
        if (clearDocsButtonEl) {
            clearDocsButtonEl.disabled = value || !ragAvailable;
        }
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

    function decorateRenderedMessage(contentEl) {
        contentEl.querySelectorAll("a[href]").forEach(function (linkEl) {
            linkEl.setAttribute("target", "_blank");
            linkEl.setAttribute("rel", "noopener noreferrer");
        });

        Array.from(contentEl.querySelectorAll("table")).forEach(function (tableEl) {
            if (!tableEl.parentNode) {
                return;
            }
            const wrapper = document.createElement("div");
            wrapper.classList.add("table-wrap");
            tableEl.parentNode.insertBefore(wrapper, tableEl);
            wrapper.appendChild(tableEl);
        });
    }

    function appendMessage(role, content, isError, citations) {
        const messageEl = document.createElement("article");
        messageEl.classList.add("chat-message", role);
        if (isError) {
            messageEl.classList.add("error");
        }

        const bodyEl = document.createElement("div");
        bodyEl.classList.add("message-body");
        if (role === "assistant" && !isError) {
            const safeHtml = renderMarkdownToSafeHtml(content);
            if (safeHtml) {
                messageEl.classList.add("markdown");
                bodyEl.innerHTML = safeHtml;
                decorateRenderedMessage(bodyEl);
            } else {
                bodyEl.textContent = content;
            }
        } else {
            bodyEl.textContent = content;
        }
        messageEl.appendChild(bodyEl);

        if (role === "assistant" && Array.isArray(citations) && citations.length > 0) {
            const citationsEl = document.createElement("div");
            citationsEl.classList.add("message-citations");
            citations.forEach(function (citation) {
                const value = (citation || "").trim();
                if (!value) {
                    return;
                }
                const chip = document.createElement("span");
                chip.classList.add("citation-chip");
                chip.textContent = value;
                citationsEl.appendChild(chip);
            });
            if (citationsEl.childElementCount > 0) {
                messageEl.appendChild(citationsEl);
            }
        }

        threadEl.appendChild(messageEl);
        threadEl.scrollTop = threadEl.scrollHeight;
    }

    function renderDocumentList(documents) {
        if (!docsListEl) {
            return;
        }

        docsListEl.innerHTML = "";
        if (!Array.isArray(documents) || documents.length === 0) {
            const emptyEl = document.createElement("li");
            emptyEl.classList.add("docs-empty");
            emptyEl.textContent = "No local documents indexed.";
            docsListEl.appendChild(emptyEl);
            return;
        }

        documents.forEach(function (name) {
            const item = document.createElement("li");
            item.textContent = name;
            docsListEl.appendChild(item);
        });
    }

    function renderRagBanner(available, message) {
        if (!ragBannerEl) {
            return;
        }

        if (available) {
            ragBannerEl.classList.add("rag-banner-hidden");
            ragBannerEl.textContent = "";
            return;
        }

        ragBannerEl.textContent = message || "RAG unavailable. DocumentsChat will continue in chat-only mode.";
        ragBannerEl.classList.remove("rag-banner-hidden");
    }

    async function refreshRagStatus() {
        try {
            const response = await fetch("/api/documentchat/rag/status", {
                headers: {
                    "Accept": "application/json"
                }
            });
            const payload = await response.json().catch(function () {
                return {};
            });

            if (!response.ok) {
                throw new Error(payload.error || "Could not load RAG status.");
            }

            ragAvailable = Boolean(payload.available);
            renderRagBanner(ragAvailable, payload.message);

            if (!ragAvailable) {
                setDocsStatus(payload.message || "RAG is unavailable. Upload and retrieval are disabled.", false);
            } else {
                setDocsStatus("", false);
            }
        } catch (error) {
            ragAvailable = false;
            renderRagBanner(false, "RAG unavailable. DocumentsChat will continue in chat-only mode.");
            setDocsStatus("Could not verify RAG status.", true);
        } finally {
            setDocsBusy(false);
        }
    }

    async function refreshDocumentList() {
        if (!docsListEl) {
            return;
        }

        try {
            const response = await fetch("/api/documentchat/documents", {
                headers: {
                    "Accept": "application/json"
                }
            });
            const payload = await response.json().catch(function () {
                return {};
            });

            if (!response.ok) {
                throw new Error(payload.error || "Could not load documents.");
            }

            renderDocumentList(payload.documents || []);
        } catch (error) {
            setDocsStatus(error.message || "Could not load indexed documents.", true);
        }
    }

    async function uploadDocuments(files) {
        if (!files || files.length === 0) {
            setDocsStatus("Choose one or more PDF or text files first.", true);
            return;
        }

        if (!ragAvailable) {
            setDocsStatus("RAG is unavailable, so uploads are disabled.", true);
            return;
        }

        const formData = new FormData();
        Array.from(files).forEach(function (file) {
            formData.append("files", file);
        });

        setDocsBusy(true);
        setDocsStatus("Uploading and indexing documents...", false);

        try {
            const response = await fetch("/api/documentchat/documents", {
                method: "POST",
                body: formData
            });
            const payload = await response.json().catch(function () {
                return {};
            });

            if (!response.ok) {
                throw new Error(payload.message || payload.error || "Document upload failed.");
            }

            setDocsStatus(payload.message || "Documents indexed successfully.", false);
            if (docsFileInputEl) {
                docsFileInputEl.value = "";
            }
            await refreshDocumentList();
        } catch (error) {
            setDocsStatus(error.message || "Document upload failed.", true);
        } finally {
            setDocsBusy(false);
        }
    }

    async function clearDocuments() {
        if (!ragAvailable) {
            setDocsStatus("RAG is unavailable, so there are no indexed documents to clear.", false);
            return;
        }

        setDocsBusy(true);
        setDocsStatus("Clearing indexed documents...", false);

        try {
            const response = await fetch("/api/documentchat/documents", {
                method: "DELETE",
                headers: {
                    "Accept": "application/json"
                }
            });
            const payload = await response.json().catch(function () {
                return {};
            });

            if (!response.ok) {
                throw new Error(payload.message || payload.error || "Could not clear documents.");
            }

            setDocsStatus(payload.message || "Documents cleared.", false);
            await refreshDocumentList();
        } catch (error) {
            setDocsStatus(error.message || "Could not clear documents.", true);
        } finally {
            setDocsBusy(false);
        }
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
        setStatus("DocumentsChat is generating a response...", false);
        setPending(true);
        abortController = new AbortController();

        try {
            const response = await fetch("/api/documentchat", {
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
                const errorMessage = payload.error || "DocumentsChat could not process your request.";
                appendMessage("assistant", errorMessage, true);
                setStatus(errorMessage, true);
                return;
            }

            const reply = (payload.reply || "").trim();
            if (!reply) {
                appendMessage("assistant", "DocumentsChat returned an empty response.", true);
                setStatus("DocumentsChat returned an empty response.", true);
                return;
            }

            const citations = Array.isArray(payload.citations) ? payload.citations : [];
            appendMessage("assistant", reply, false, citations);
            setStatus("", false);
        } catch (error) {
            if (error && error.name === "AbortError") {
                setStatus("DocumentsChat response stopped. You can send a new prompt.", false);
                return;
            }
            const networkError = "Could not reach DocumentsChat server.";
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

    if (docsUploadFormEl) {
        docsUploadFormEl.addEventListener("submit", function (event) {
            event.preventDefault();
            if (docsBusy) {
                return;
            }
            uploadDocuments(docsFileInputEl ? docsFileInputEl.files : null);
        });
    }

    if (clearDocsButtonEl) {
        clearDocsButtonEl.addEventListener("click", function () {
            if (!docsBusy) {
                clearDocuments();
            }
        });
    }

    setPending(false);
    setDocsBusy(false);
    refreshRagStatus().then(function () {
        refreshDocumentList();
    });
})();
