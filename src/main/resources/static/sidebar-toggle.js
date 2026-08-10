(function () {
    const STORAGE_KEY = "missionchat.sidebarCollapsed";

    const bodyEl = document.body;
    const toggleButtonEl = document.querySelector("[data-sidebar-toggle]");

    if (!bodyEl || !toggleButtonEl) {
        return;
    }

    function readStoredCollapsedState() {
        try {
            return window.localStorage.getItem(STORAGE_KEY) === "1";
        } catch (error) {
            return false;
        }
    }

    function writeStoredCollapsedState(collapsed) {
        try {
            window.localStorage.setItem(STORAGE_KEY, collapsed ? "1" : "0");
        } catch (error) {
            // Ignore storage write failures (for example when blocked by browser policy).
        }
    }

    function applyState(collapsed, persist) {
        bodyEl.classList.toggle("sidebar-collapsed", collapsed);
        toggleButtonEl.setAttribute("aria-expanded", String(!collapsed));
        toggleButtonEl.setAttribute("aria-label", collapsed ? "Expand menu" : "Collapse menu");
        toggleButtonEl.setAttribute("title", collapsed ? "Expand menu" : "Collapse menu");
        toggleButtonEl.textContent = "";

        if (persist) {
            writeStoredCollapsedState(collapsed);
        }
    }

    applyState(readStoredCollapsedState(), false);

    toggleButtonEl.addEventListener("click", function () {
        const nextCollapsed = !bodyEl.classList.contains("sidebar-collapsed");
        applyState(nextCollapsed, true);
    });
})();
