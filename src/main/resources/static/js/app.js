/*
 * Dashboard behaviour, no framework: theme choice, toasts, confirm dialogs (for htmx's hx-confirm
 * and for plain forms with data-confirm), keyboard shortcuts, a loading bar for actions, dragging
 * appointments on the week page and weekly hours on a doctor's page, and small helpers (date picker
 * navigation, password reveal). Every page works without it.
 */
(() => {
    const root = document.documentElement;

    /* Theme: "auto" follows the operating system; the choice is kept per browser. */
    function markTheme() {
        const current = root.dataset.theme || "auto";
        document.querySelectorAll("[data-theme-choice]").forEach(button =>
            button.setAttribute("aria-pressed", String(button.dataset.themeChoice === current)));
    }

    function chooseTheme(choice) {
        if (choice === "light" || choice === "dark") {
            root.dataset.theme = choice;
        } else {
            delete root.dataset.theme;
        }
        try {
            if (choice === "light" || choice === "dark") {
                localStorage.setItem("theme", choice);
            } else {
                localStorage.removeItem("theme");
            }
        } catch (ignored) {
            // Private mode: the choice lasts for this page only.
        }
        markTheme();
    }

    /* Sidebar: collapsed to a rail of icons or not (computers only); the choice is kept per browser. */
    function markSidebar() {
        const collapsed = root.dataset.sidebar === "collapsed";
        document.querySelectorAll("[data-sidebar-toggle]").forEach(button => {
            const label = collapsed ? button.dataset.labelExpand : button.dataset.labelCollapse;
            button.setAttribute("aria-expanded", String(!collapsed));
            button.setAttribute("aria-label", label);
            button.dataset.tip = label;
        });
    }

    document.addEventListener("click", event => {
        if (!event.target.closest("[data-sidebar-toggle]")) {
            return;
        }
        const collapse = root.dataset.sidebar !== "collapsed";
        if (collapse) {
            root.dataset.sidebar = "collapsed";
        } else {
            delete root.dataset.sidebar;
        }
        try {
            if (collapse) {
                localStorage.setItem("sidebar", "collapsed");
            } else {
                localStorage.removeItem("sidebar");
            }
        } catch (ignored) {
            // Private mode: the choice lasts for this page only.
        }
        markSidebar();
    });

    /* Toasts: success ones leave by themselves; errors and sticky ones (an Undo) stay until closed. */
    function initToasts() {
        document.querySelectorAll(".toast:not([data-ready])").forEach(toast => {
            toast.dataset.ready = "1";
            const close = () => {
                toast.classList.add("leaving");
                setTimeout(() => toast.remove(), 260);
            };
            toast.querySelector(".toast-close")?.addEventListener("click", close);
            if (!toast.classList.contains("error") && !toast.classList.contains("sticky")) {
                setTimeout(close, 5000);
            }
        });
    }

    /*
     * Confirm dialog instead of the browser's confirm(); falls back to it without <dialog>. Red by
     * default; {calm: true} for a change that loses nothing, such as moving an appointment.
     */
    function ask(question, {calm = false} = {}) {
        const dialog = document.getElementById("confirm-dialog");
        if (!dialog || typeof dialog.showModal !== "function") {
            return Promise.resolve(window.confirm(question));
        }
        dialog.querySelector("[data-question]").textContent = question;
        dialog.classList.toggle("calm", calm);
        dialog.querySelector(".modal-icon use")?.setAttribute("href", calm ? "#i-move" : "#i-alert");
        const yes = dialog.querySelector("button[value=yes]");
        yes.classList.toggle("danger-solid", !calm);
        yes.classList.toggle("primary", calm);
        dialog.returnValue = "";
        dialog.showModal();
        return new Promise(resolve =>
            dialog.addEventListener("close", () => resolve(dialog.returnValue === "yes"), {once: true}));
    }

    document.addEventListener("htmx:confirm", event => {
        if (!event.detail.question) {
            return;
        }
        event.preventDefault();
        ask(event.detail.question).then(ok => {
            if (ok) {
                event.detail.issueRequest(true);
            }
        });
    });

    /* A form with data-confirm="question?" asks first; data-confirm-calm for one that loses nothing. */
    document.addEventListener("submit", event => {
        const form = event.target;
        if (!form.dataset.confirm || form.dataset.confirmed) {
            return;
        }
        event.preventDefault();
        ask(form.dataset.confirm, {calm: "confirmCalm" in form.dataset}).then(ok => {
            if (ok) {
                form.dataset.confirmed = "1";
                form.requestSubmit(event.submitter);
            }
        });
    });

    /*
     * A form being sent: its button shows a spinner and can't be pressed again, so a double click never
     * books or records twice. Not for htmx's forms (htmx handles them and keeps the page) nor searches.
     * Asked first (data-confirm)? Only once the answer is yes, as that handler stops the first submit.
     */
    document.addEventListener("submit", event => {
        const form = event.target;
        if (event.defaultPrevented || form.method.toLowerCase() !== "post" || form.target === "_blank"
            || form.matches("[hx-post], [hx-put], [hx-delete], [hx-get]")) {
            return;
        }
        const button = event.submitter ?? form.querySelector("button[type=submit], button:not([type])");
        if (!button || button.form !== form) {
            return;
        }
        button.classList.add("is-busy");
        button.setAttribute("aria-busy", "true");
        // After this tick: disabled now, the button's own name and value would not be sent.
        setTimeout(() => {
            button.disabled = true;
        });
    });

    // Back to a page the browser kept: its buttons work again.
    window.addEventListener("pageshow", () => {
        document.querySelectorAll("button.is-busy").forEach(button => {
            button.classList.remove("is-busy");
            button.removeAttribute("aria-busy");
            button.disabled = false;
        });
    });

    /* Shortcuts: an element with data-key="n" (or "slash", "ArrowLeft"...) is clicked or focused. */
    document.addEventListener("keydown", event => {
        if (event.ctrlKey || event.metaKey || event.altKey || event.defaultPrevented) {
            return;
        }
        if (event.target.closest("input, textarea, select, [contenteditable]") || document.querySelector("dialog[open]")) {
            return;
        }
        const key = event.key === "/" ? "slash" : event.key.length === 1 ? event.key.toLowerCase() : event.key;
        const target = document.querySelector(`[data-key="${key}"]`);
        if (!target) {
            return;
        }
        event.preventDefault();
        if (target.matches("input, textarea, select")) {
            target.focus();
        } else {
            target.click();
        }
    });

    /* A thin bar while an action runs; the 30-second refresh stays quiet. */
    document.addEventListener("htmx:beforeRequest", event => {
        if (event.detail.elt.closest("form, button")) {
            root.classList.add("is-loading");
        }
    });
    document.addEventListener("htmx:afterRequest", () => root.classList.remove("is-loading"));

    document.addEventListener("click", event => {
        const themeButton = event.target.closest("[data-theme-choice]");
        if (themeButton) {
            chooseTheme(themeButton.dataset.themeChoice);
        }
        const picker = event.target.closest(".date-input input");
        if (picker && typeof picker.showPicker === "function") {
            try {
                picker.showPicker();
            } catch (ignored) {
                // Some browsers only allow it from certain gestures; the field still works.
            }
        }
        const reveal = event.target.closest("[data-reveal]");
        if (reveal) {
            const input = document.getElementById(reveal.dataset.reveal);
            input.type = input.type === "password" ? "text" : "password";
            reveal.setAttribute("aria-pressed", String(input.type === "text"));
        }
        // Menus close when you click elsewhere.
        document.querySelectorAll("details.menu[open]").forEach(menu => {
            if (!menu.contains(event.target)) {
                menu.open = false;
            }
        });
    });

    document.addEventListener("keydown", event => {
        if (event.key === "Escape") {
            document.querySelectorAll("details.menu[open]").forEach(menu => (menu.open = false));
        }
    });

    /*
     * Slide-over: a link with data-drawer opens its page's .drawer-content in #drawer instead of
     * navigating. Forms inside post normally, so the server's redirects and error pages still apply.
     */
    document.addEventListener("click", event => {
        const link = event.target.closest("a[data-drawer]");
        const drawer = document.getElementById("drawer");
        if (!link || !drawer || typeof drawer.showModal !== "function" || !window.htmx
            || event.ctrlKey || event.metaKey || event.shiftKey || event.button !== 0) {
            return;
        }
        if (link.dataset.justDragged) {
            event.preventDefault(); // the end of a drag, not a tap
            return;
        }
        event.preventDefault();
        const body = document.getElementById("drawer-body");
        const skeleton = document.getElementById("drawer-skeleton");
        if (skeleton) {
            body.replaceChildren(skeleton.content.cloneNode(true));
        } else {
            body.innerHTML = '<div class="drawer-loading"><span class="spinner"></span></div>';
        }
        if (!drawer.open) {
            drawer.showModal();
        }
        window.htmx.ajax("GET", link.href, {target: "#drawer-body", select: ".drawer-content", swap: "innerHTML ignoreTitle:true"})
            .then(() => body.querySelector("input:not([type=hidden]), select")?.focus());
    }, true);

    document.addEventListener("click", event => {
        const close = event.target.closest("[data-drawer-close]");
        const drawer = document.getElementById("drawer");
        if (close && drawer && drawer.open && drawer.contains(close)) {
            event.preventDefault();
            drawer.close();
        }
    });

    document.addEventListener("close", event => {
        if (event.target.id === "drawer") {
            document.getElementById("drawer-body").innerHTML = "";
        }
    }, true);

    /*
     * Week: move an appointment ([data-move-id]) by dragging it onto a free time ([data-drop-time]);
     * after a confirmation, #move-form posts the move. Pointer events, so mouse and touch work alike:
     * with a mouse, press and move; on a touch screen, press and hold for a moment first, so a quick
     * swipe still scrolls the page. Near the top or bottom edge the page scrolls while dragging; on a
     * phone, holding it over a day in the day strip jumps to that day.
     */
    const HOLD_MS = 350;
    const TOUCH_SLOP = 8;
    const MOUSE_SLOP = 5;
    const EDGE = 70;
    const STRIP_DWELL_MS = 300;
    let pending = null;
    let drag = null;

    function startDrag(start) {
        const block = start.block;
        block.classList.remove("holding");
        const rect = block.getBoundingClientRect();
        const ghost = block.cloneNode(true);
        ghost.removeAttribute("href");
        ghost.classList.add("drag-ghost");
        ghost.style.width = rect.width + "px";
        const label = document.createElement("span");
        label.className = "drag-to";
        ghost.appendChild(label);
        document.body.appendChild(ghost);
        // A finger would hide the copy: on touch it floats just above the finger instead of under it.
        drag = {block, ghost, label, target: null, chip: null, chipTimer: 0, pointerId: start.pointerId,
            x: start.x, y: start.y,
            offsetX: start.touch ? rect.width / 2 : start.x - rect.left,
            offsetY: start.touch ? rect.height + 34 : start.y - rect.top, frame: 0};
        block.classList.add("dragging");
        document.body.classList.add("is-dragging");
        navigator.vibrate?.(15);
        follow();
        drag.frame = requestAnimationFrame(autoScroll);
    }

    /* Places the ghost at the finger or mouse, lights up the free time below it and names it on the ghost. */
    function follow() {
        drag.ghost.style.transform = `translate(${drag.x - drag.offsetX}px, ${drag.y - drag.offsetY}px) rotate(-1.5deg)`;
        const point = document.elementFromPoint(drag.x, drag.y);
        const under = point?.closest("[data-drop-time]") ?? null;
        if (under !== drag.target) {
            drag.target?.classList.remove("drop-target");
            under?.classList.add("drop-target");
            drag.target = under;
            drag.label.textContent = under?.dataset.dropLabel ?? "";
        }
        const chip = point?.closest("[data-jump-day]") ?? null;
        if (chip !== drag.chip) {
            drag.chip?.classList.remove("drop-hover");
            clearTimeout(drag.chipTimer);
            chip?.classList.add("drop-hover");
            drag.chip = chip;
            if (chip) {
                // Instantly: a day sliding by under the finger mid-drag is harder to follow.
                drag.chipTimer = setTimeout(() => jumpToDay(chip.dataset.day, "auto"), STRIP_DWELL_MS);
            }
        }
    }

    /*
     * The band the days scroll through: below the day strip once it sticks to the top, above the
     * phone's bottom bar. Dragging near either end of it scrolls, faster the closer to the end.
     */
    function scrollBand() {
        const strip = document.querySelector(".day-strip");
        const stripBox = strip?.offsetParent ? strip.getBoundingClientRect() : null;
        const bar = document.querySelector(".sidebar");
        return {
            top: stripBox && stripBox.top <= 1 ? stripBox.bottom : 0,
            bottom: bar && getComputedStyle(bar).position === "fixed" ? bar.getBoundingClientRect().top : window.innerHeight
        };
    }

    function autoScroll() {
        if (!drag) {
            return;
        }
        const band = scrollBand();
        let step = 0;
        if (drag.y >= band.top && drag.y < band.top + EDGE) {
            step = -(4 + 14 * (band.top + EDGE - drag.y) / EDGE);
        } else if (drag.y > band.bottom - EDGE) {
            step = 4 + 14 * Math.min(1, (drag.y - band.bottom + EDGE) / EDGE);
        }
        if (step) {
            window.scrollBy(0, step);
            follow();
        }
        drag.frame = requestAnimationFrame(autoScroll);
    }

    function endDrag(drop) {
        const {block, ghost, target, chip, chipTimer, frame} = drag;
        cancelAnimationFrame(frame);
        clearTimeout(chipTimer);
        ghost.remove();
        block.classList.remove("dragging");
        target?.classList.remove("drop-target");
        chip?.classList.remove("drop-hover");
        document.body.classList.remove("is-dragging");
        drag = null;
        // The click that follows the drag must not open the appointment's details.
        block.dataset.justDragged = "1";
        setTimeout(() => delete block.dataset.justDragged, 400);
        if (drop && target) {
            confirmMove(block, target);
        }
    }

    function confirmMove(block, target) {
        const form = document.getElementById("move-form");
        if (!form) {
            return;
        }
        const question = form.dataset.question
            .replace("{who}", block.dataset.moveWho)
            .replace("{when}", target.dataset.dropLabel);
        ask(question, {calm: true}).then(ok => {
            if (!ok) {
                return;
            }
            form.elements.appointmentId.value = block.dataset.moveId;
            form.elements.date.value = target.dataset.dropDate;
            form.elements.time.value = target.dataset.dropTime;
            form.submit();
        });
    }

    function cancelPending() {
        if (pending) {
            clearTimeout(pending.timer);
            pending.block.classList.remove("holding");
            pending = null;
        }
    }

    document.addEventListener("pointerdown", event => {
        const block = event.target.closest("[data-move-id]");
        if (!block || drag || event.button !== 0) {
            return;
        }
        pending = {block, pointerId: event.pointerId, x: event.clientX, y: event.clientY,
            touch: event.pointerType !== "mouse", timer: 0};
        if (pending.touch) {
            block.classList.add("holding"); // sinks while held, so it's clear that holding does something
            pending.timer = setTimeout(() => {
                const start = pending;
                pending = null;
                startDrag(start);
            }, HOLD_MS);
        }
    });

    document.addEventListener("pointermove", event => {
        if (drag && event.pointerId === drag.pointerId) {
            drag.x = event.clientX;
            drag.y = event.clientY;
            follow();
            return;
        }
        if (!pending || event.pointerId !== pending.pointerId) {
            return;
        }
        const moved = Math.hypot(event.clientX - pending.x, event.clientY - pending.y);
        if (pending.touch && moved > TOUCH_SLOP) {
            cancelPending(); // the finger is scrolling the page
        } else if (!pending.touch && moved > MOUSE_SLOP) {
            const start = {...pending, x: event.clientX, y: event.clientY};
            pending = null;
            startDrag(start);
        }
    });

    document.addEventListener("pointerup", event => {
        cancelPending();
        if (drag && event.pointerId === drag.pointerId) {
            endDrag(true);
        }
    });

    document.addEventListener("pointercancel", () => {
        cancelPending();
        if (drag) {
            endDrag(false);
        }
    });

    // While dragging on a touch screen the page must not scroll under the finger.
    document.addEventListener("touchmove", event => {
        if (drag) {
            event.preventDefault();
        }
    }, {passive: false});

    // Holding an appointment shouldn't bring up the phone's link menu.
    document.addEventListener("contextmenu", event => {
        if ((drag || pending) && event.target.closest("[data-move-id]")) {
            event.preventDefault();
        }
    });

    document.addEventListener("keydown", event => {
        if (event.key === "Escape" && drag) {
            endDrag(false);
        }
    });

    /*
     * Week on a phone: the day strip jumps to a day and shows which day is at the top; the page lands
     * on the day the server names (data-focus-day: today, or the day just changed).
     */
    const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
    const smooth = () => (reducedMotion.matches ? "auto" : "smooth");
    let jumpedTo = null;

    function jumpToDay(date, behavior) {
        const day = document.getElementById("day-" + date);
        if (day) {
            jumpedTo = date;
            day.scrollIntoView({block: "start", behavior});
        }
    }

    document.addEventListener("click", event => {
        const chip = event.target.closest("[data-jump-day]");
        if (chip && !event.ctrlKey && !event.metaKey) {
            event.preventDefault();
            jumpToDay(chip.dataset.day, smooth());
        }
    });

    // Scrolling by hand ends a jump; the jumps' own scrolling (and a drag's) doesn't.
    ["wheel", "touchmove"].forEach(type => window.addEventListener(type, () => {
        if (!drag) {
            jumpedTo = null;
        }
    }, {passive: true}));

    /*
     * The current day: the one jumped to while it is on screen (the last days can't always reach the
     * top), otherwise the last one whose top has gone under the strip.
     */
    function markCurrentDay() {
        const strip = document.querySelector(".day-strip");
        if (!strip?.offsetParent) {
            return;
        }
        const line = strip.getBoundingClientRect().bottom + 12;
        let current = null;
        for (const day of document.querySelectorAll(".week-board > .day-col")) {
            if (day.getBoundingClientRect().top > line) {
                break;
            }
            current = day.id.slice("day-".length);
        }
        const jumped = jumpedTo && document.getElementById("day-" + jumpedTo)?.getBoundingClientRect();
        if (jumped && jumped.top < window.innerHeight && jumped.bottom > line) {
            current = jumpedTo;
        }
        strip.querySelectorAll("[data-jump-day]").forEach(chip => {
            chip.classList.toggle("current", chip.dataset.day === current);
            chip.toggleAttribute("aria-current", chip.dataset.day === current);
        });
    }

    let markQueued = false;
    window.addEventListener("scroll", () => {
        if (!markQueued) {
            markQueued = true;
            requestAnimationFrame(() => {
                markQueued = false;
                markCurrentDay();
            });
        }
    }, {passive: true});

    function landOnFocusDay() {
        const board = document.querySelector(".week-board[data-focus-day]");
        const strip = document.querySelector(".day-strip");
        const navigation = performance.getEntriesByType?.("navigation")[0];
        // Not on a reload or Back (the browser puts the page back where it was), nor past a #day-... link.
        if (board && strip?.offsetParent && !location.hash && (!navigation || navigation.type === "navigate")) {
            jumpToDay(board.dataset.focusDay, "auto");
        }
        markCurrentDay();
    }

    /* Opening a closed day: bring its little form into view (on a phone it opens under the bottom bar). */
    document.addEventListener("toggle", event => {
        const details = event.target;
        if (details.matches?.(".open-day") && details.open) {
            details.querySelector("form")?.scrollIntoView({block: "nearest", behavior: smooth()});
        }
    }, true);

    /*
     * Booking form: the patient dropdown ([data-combo]). Opening it loads the patients booked before
     * (htmx, on "combo-open"); typing searches. Arrow keys move through the list, Enter picks, Escape
     * or a click outside closes. Picking fills phone, name and language (the hint below then confirms
     * the patient) and shows them in the field; typing another phone there clears that.
     */
    function comboParts(combo) {
        return {
            trigger: combo.querySelector("[data-combo-trigger]"),
            panel: combo.querySelector("[data-combo-panel]"),
            search: combo.querySelector("[data-patient-search]"),
            clear: combo.querySelector("[data-combo-clear]"),
            form: combo.closest("form")
        };
    }

    const comboOptions = combo => [...combo.querySelectorAll("[data-pick]")];

    function openCombo(combo) {
        const {trigger, panel, search} = comboParts(combo);
        if (!panel.hidden) {
            return;
        }
        panel.hidden = false;
        trigger.setAttribute("aria-expanded", "true");
        search.focus();
        if (!combo.querySelector("#patient-results > *")) {
            combo.classList.add("loading");
            window.htmx?.trigger(search, "combo-open");
        }
    }

    function closeCombo(combo, focusTrigger) {
        const {trigger, panel} = comboParts(combo);
        if (panel.hidden) {
            return;
        }
        panel.hidden = true;
        trigger.setAttribute("aria-expanded", "false");
        if (focusTrigger) {
            trigger.focus();
        }
    }

    function setActive(combo, index) {
        const {search} = comboParts(combo);
        const options = comboOptions(combo);
        options.forEach((option, i) => {
            option.classList.toggle("active", i === index);
            option.setAttribute("aria-selected", String(i === index));
        });
        if (options[index]) {
            search.setAttribute("aria-activedescendant", options[index].id);
            options[index].scrollIntoView({block: "nearest"});
        } else {
            search.removeAttribute("aria-activedescendant");
        }
    }

    /* Shows the chosen patient in the field (copying the option's avatar), or the placeholder for null. */
    function showChosen(combo, chosen) {
        const box = combo.querySelector("[data-combo-chosen]");
        combo.querySelector("[data-combo-empty]").hidden = Boolean(chosen);
        box.hidden = !chosen;
        comboParts(combo).clear.hidden = !chosen;
        if (chosen) {
            const avatar = box.querySelector("[data-chosen-avatar]");
            avatar.textContent = chosen.avatar?.textContent ?? "";
            avatar.style.cssText = chosen.avatar?.style.cssText ?? "";
            box.querySelector("[data-chosen-name]").textContent = chosen.name;
            box.querySelector("[data-chosen-phone]").textContent = chosen.phone;
        }
    }

    function pickPatient(combo, option) {
        const {form} = comboParts(combo);
        form.querySelector("[name=name]").value = option.dataset.name;
        form.querySelectorAll("[name=language]").forEach(radio => (radio.checked = radio.value === option.dataset.language));
        const phone = form.querySelector("[name=phone]");
        phone.value = option.dataset.phone;
        showChosen(combo, {name: option.dataset.name, phone: option.dataset.phone, avatar: option.querySelector(".avatar")});
        // Either may have changed (a family shares a phone); the hint is asked again for one that did.
        phone.dispatchEvent(new Event("input", {bubbles: true}));
        form.querySelector("[name=name]").dispatchEvent(new Event("input", {bubbles: true}));
        closeCombo(combo, false);
        form.querySelector("button[type=submit]")?.focus();
    }

    /* Marks what was typed in the names found, and makes the first one the one Enter picks. */
    function refreshComboResults(combo) {
        combo.classList.remove("loading");
        const query = comboParts(combo).search.value.trim().toLocaleLowerCase();
        combo.querySelectorAll("[data-hl]").forEach(name => {
            const text = name.textContent;
            const at = query ? text.toLocaleLowerCase().indexOf(query) : -1;
            if (at < 0) {
                name.textContent = text;
                return;
            }
            const mark = document.createElement("mark");
            mark.textContent = text.slice(at, at + query.length);
            name.replaceChildren(text.slice(0, at), mark, text.slice(at + query.length));
        });
        setActive(combo, comboOptions(combo).length ? 0 : -1);
        // Low on a phone screen the list would open under the bottom bar.
        comboParts(combo).panel.scrollIntoView({block: "nearest"});
    }

    document.addEventListener("click", event => {
        const combo = event.target.closest("[data-combo]");
        if (!combo) {
            return;
        }
        const option = event.target.closest("[data-pick]");
        if (option) {
            pickPatient(combo, option);
        } else if (event.target.closest("[data-combo-trigger]")) {
            comboParts(combo).panel.hidden ? openCombo(combo) : closeCombo(combo, true);
        } else if (event.target.closest("[data-combo-clear]")) {
            const {form, trigger} = comboParts(combo);
            showChosen(combo, null);
            form.querySelector("[name=name]").value = "";
            const phone = form.querySelector("[name=phone]");
            phone.value = "";
            phone.dispatchEvent(new Event("input", {bubbles: true}));
            trigger.focus();
        }
    });

    // A family member offered under the phone field: picked like an option of the dropdown.
    document.addEventListener("click", event => {
        const chip = event.target.closest("[data-fill-patient]");
        const form = chip?.closest("form");
        if (!form) {
            return;
        }
        form.querySelector("[name=name]").value = chip.dataset.name;
        form.querySelectorAll("[name=language]").forEach(radio => (radio.checked = radio.value === chip.dataset.language));
        const combo = form.querySelector("[data-combo]");
        if (combo) {
            showChosen(combo, {name: chip.dataset.name, phone: chip.dataset.phone, avatar: chip.querySelector(".avatar")});
        }
        // The name changed, not the phone: htmx only asks again for a field whose value changed.
        form.querySelector("[name=name]").dispatchEvent(new Event("input", {bubbles: true}));
    });

    // "Copy" buttons (the calendar link on an appointment): to the clipboard, and say so for a moment.
    document.addEventListener("click", async event => {
        const button = event.target.closest("[data-copy]");
        if (!button) {
            return;
        }
        try {
            await navigator.clipboard.writeText(button.dataset.copy);
        } catch (e) {
            return; // no clipboard (an insecure page): the link is still there to select
        }
        const label = button.querySelector("span");
        const before = label.textContent;
        label.textContent = button.dataset.copied;
        button.classList.add("copied");
        setTimeout(() => {
            label.textContent = before;
            button.classList.remove("copied");
        }, 2000);
    });

    // Keeps the focus in the search box while an option is clicked (some browsers would move it away).
    document.addEventListener("mousedown", event => {
        if (event.target.closest(".combo-results")) {
            event.preventDefault();
        }
    });

    document.addEventListener("pointerdown", event => {
        document.querySelectorAll("[data-combo]").forEach(combo => {
            if (!combo.contains(event.target)) {
                closeCombo(combo, false);
            }
        });
    });

    document.addEventListener("keydown", event => {
        const combo = event.target.closest?.("[data-combo]");
        if (!combo) {
            return;
        }
        if (event.target.matches("[data-combo-trigger]") && (event.key === "ArrowDown" || event.key === "ArrowUp")) {
            event.preventDefault();
            openCombo(combo);
            return;
        }
        if (!event.target.matches("[data-patient-search]")) {
            return;
        }
        const options = comboOptions(combo);
        const current = options.findIndex(option => option.classList.contains("active"));
        if (event.key === "ArrowDown" || event.key === "ArrowUp") {
            event.preventDefault();
            const step = event.key === "ArrowDown" ? 1 : -1;
            setActive(combo, options.length ? (current + step + options.length) % options.length : -1);
        } else if (event.key === "Enter") {
            event.preventDefault();
            if (options[current]) {
                pickPatient(combo, options[current]);
            }
        } else if (event.key === "Escape") {
            event.preventDefault(); // close the list, not the slide-over around the form
            event.stopPropagation();
            closeCombo(combo, true);
        } else if (event.key === "Tab") {
            closeCombo(combo, false);
        }
    });

    // Escape inside the slide-over: an open list closes first.
    document.addEventListener("cancel", event => {
        const open = [...event.target.querySelectorAll?.("[data-combo]") ?? []].find(combo => !comboParts(combo).panel.hidden);
        if (open) {
            event.preventDefault();
            closeCombo(open, true);
        }
    }, true);

    document.addEventListener("htmx:afterSettle", () => {
        document.querySelectorAll("[data-combo]").forEach(combo => {
            if (!comboParts(combo).panel.hidden) {
                refreshComboResults(combo);
            }
        });
    });

    // Typing a different phone than the chosen patient's means someone else: the field stops showing them.
    document.addEventListener("input", event => {
        const combo = event.target.name === "phone" ? event.target.form?.querySelector("[data-combo]") : null;
        if (!combo || combo.querySelector("[data-combo-chosen]").hidden) {
            return;
        }
        const typed = event.target.value.replace(/\D/g, "").replace(/^0+/, "");
        const chosen = combo.querySelector("[data-chosen-phone]").textContent.replace(/\D/g, "");
        if (typed.length < 6 || !chosen.endsWith(typed)) {
            showChosen(combo, null);
        }
    });

    /* Settings: the WhatsApp Info preview follows the address and hours as they are typed. */
    document.addEventListener("input", event => {
        const field = event.target.closest("[data-preview]");
        if (!field) {
            return;
        }
        const name = field.dataset.preview;
        document.querySelectorAll(`[data-preview-for="${name}"]`).forEach(target => (target.textContent = field.value.trim()));
        document.querySelectorAll(`[data-preview-line="${name}"]`).forEach(line => (line.hidden = !field.value.trim()));
        const anyFilled = [...document.querySelectorAll("[data-preview]")].some(other => other.value.trim());
        document.querySelectorAll("[data-preview-empty]").forEach(empty => (empty.hidden = anyFilled));
        // The bubble answers each change with a small bump.
        document.querySelectorAll(".preview .bubble").forEach(bubble => {
            bubble.classList.remove("bump");
            void bubble.offsetWidth;
            bubble.classList.add("bump");
        });
    });

    /* Sign in: a hint under the password while Caps Lock is on. */
    function capsLock(event) {
        const field = event.target.closest?.("[data-caps-watch]");
        const hint = field?.closest("label")?.querySelector("[data-caps-hint]");
        if (hint && typeof event.getModifierState === "function") {
            hint.hidden = !event.getModifierState("CapsLock");
        }
    }
    document.addEventListener("keydown", capsLock);
    document.addEventListener("keyup", capsLock);

    /*
     * A form with data-dirty-watch (Settings) gets class "dirty" while its fields differ from what the
     * page loaded with, so its Save bar can say there are unsaved changes.
     */
    const formState = form => JSON.stringify([...new FormData(form)].filter(([name]) => name !== "_csrf"));
    function watchDirtyForms() {
        document.querySelectorAll("form[data-dirty-watch]").forEach(form => {
            const loaded = formState(form);
            const mark = () => form.classList.toggle("dirty", formState(form) !== loaded);
            form.addEventListener("input", mark);
            form.addEventListener("change", mark);
        });
    }

    /*
     * The big figures (Report, the classic Today) count up from zero as the page opens. Only plain whole
     * numbers; not where motion is unwanted.
     */
    function countUp() {
        if (matchMedia("(prefers-reduced-motion: reduce)").matches) {
            return;
        }
        document.querySelectorAll(".stat-value").forEach((figure, index) => {
            const text = figure.textContent.trim();
            if (!/^\d{1,6}$/.test(text) || Number(text) === 0) {
                return;
            }
            const value = Number(text);
            const began = performance.now() + 150 + index * 60;
            figure.textContent = "0";
            const tick = now => {
                const progress = Math.min(Math.max((now - began) / 800, 0), 1);
                figure.textContent = String(Math.round(value * (1 - Math.pow(1 - progress, 3))));
                if (progress < 1) {
                    requestAnimationFrame(tick);
                }
            };
            requestAnimationFrame(tick);
        });
    }

    /*
     * Opening another page from a link: if it takes more than a moment, a skeleton of a page takes the
     * place of this one, so nothing looks stuck; the new page replaces it when it arrives. Links that
     * something else already handles (the slide-over, htmx, the day strip) are left alone.
     */
    let navigatingTimer = 0;
    document.addEventListener("click", event => {
        const link = event.target.closest("a[href]");
        if (!link || event.defaultPrevented || event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey
            || event.altKey || link.target || link.hasAttribute("download")) {
            return;
        }
        const url = new URL(link.href, location.href);
        const samePage = url.pathname === location.pathname && url.search === location.search;
        if (url.origin !== location.origin || samePage) {
            return;
        }
        clearTimeout(navigatingTimer);
        navigatingTimer = setTimeout(() => {
            document.body.classList.add("navigating");
            window.scrollTo(0, 0);
        }, 120);
    });
    // Back to a page the browser kept (Back button): it shows as it was.
    window.addEventListener("pageshow", () => {
        clearTimeout(navigatingTimer);
        document.body.classList.remove("navigating");
    });

    /* <button data-dialog-open="id"> opens that <dialog>; a [data-dialog-close] inside, or a click on the backdrop, closes it. */
    document.addEventListener("click", event => {
        const opener = event.target.closest("[data-dialog-open]");
        const dialog = opener ? document.getElementById(opener.dataset.dialogOpen) : null;
        if (dialog && typeof dialog.showModal === "function") {
            dialog.showModal();
            return;
        }
        if (event.target.closest("[data-dialog-close]") || event.target.matches("dialog.modal-form[open]")) {
            event.target.closest("dialog")?.close();
        }
    });

    /*
     * <input data-type-to-confirm="word" data-also="delete">: its form's button stays off until the
     * word is typed. The server checks the word as well.
     */
    function typedToConfirm(input) {
        const typed = input.value.trim().toLowerCase();
        const button = input.form?.querySelector("button[type=submit]");
        if (button) {
            button.disabled = typed !== input.dataset.typeToConfirm.toLowerCase() && typed !== (input.dataset.also || "").toLowerCase();
        }
    }
    document.querySelectorAll("[data-type-to-confirm]").forEach(typedToConfirm);
    document.addEventListener("input", event => {
        if (event.target.matches?.("[data-type-to-confirm]")) {
            typedToConfirm(event.target);
        }
    });

    /* A GET filter form with data-clean-query leaves its empty fields out of the address htmx pushes. */
    document.addEventListener("htmx:configRequest", event => {
        const formData = event.detail.formData;
        if (event.detail.verb === "get" && event.detail.elt.matches?.("[data-clean-query]") && formData) {
            [...formData.keys()].filter(key => formData.get(key) === "").forEach(key => formData.delete(key));
        }
    });

    /* <input type="date" data-navigate-date="/?date=DATE"> goes to that day when picked. */
    document.addEventListener("change", event => {
        const input = event.target.closest("[data-navigate-date]");
        if (input && input.value) {
            window.location.href = input.dataset.navigateDate.replace("DATE", input.value);
        }
    });


    /*
     * The weekly hours chart (a doctor's page): drag on a day to add hours, drag a block to move
     * it, drag its ends to change them; arrow keys move a focused block by 15 minutes, with Shift
     * they move its end. Times snap to 15 minutes, between 6:00 and 22:00, and blocks never
     * overlap. Each change is saved at once and the chart comes back from the server, with its
     * toast. Without this, the chart is a picture and the form under it adds hours.
     */
    const BAR_START = 6 * 60;
    const BAR_END = 22 * 60;
    const STEP = 15;
    let hoursDrag = null;

    const minutesOf = text => {
        const [h, m] = text.split(":").map(Number);
        return h * 60 + m;
    };
    const clockOf = minutes => String(Math.floor(minutes / 60)).padStart(2, "0") + ":" + String(minutes % 60).padStart(2, "0");
    const snap = minutes => Math.round(minutes / STEP) * STEP;

    /* "1:15 PM", or "١:١٥ م" in Arabic with Western digits, as the server writes times. */
    function timeLabel(minutes) {
        const lang = root.lang === "ar" ? "ar-u-nu-latn" : (root.lang || "en");
        const date = new Date(2000, 0, 1, Math.floor(minutes / 60), minutes % 60);
        return new Intl.DateTimeFormat(lang, {hour: "numeric", minute: "2-digit"}).format(date);
    }

    /* The minute under the pointer on a day's bar; the bar runs right to left in Arabic. */
    function minuteAt(track, clientX) {
        const box = track.getBoundingClientRect();
        const rtl = getComputedStyle(track).direction === "rtl";
        const share = Math.min(1, Math.max(0, (rtl ? box.right - clientX : clientX - box.left) / box.width));
        return BAR_START + share * (BAR_END - BAR_START);
    }

    function place(seg, start, end) {
        const span = BAR_END - BAR_START;
        seg.style.insetInlineStart = ((start - BAR_START) / span * 100) + "%";
        seg.style.width = ((end - start) / span * 100) + "%";
        seg.dataset.start = clockOf(start);
        seg.dataset.end = clockOf(end);
        const label = seg.querySelector(".seg-label");
        if (label) {
            label.textContent = timeLabel(start) + " – " + timeLabel(end);
        }
    }

    /* How far a block may go: up to its neighbours on the same day, within the bar. */
    function room(track, seg, start, end) {
        let low = BAR_START;
        let high = BAR_END;
        track.querySelectorAll(".hours-seg").forEach(other => {
            if (other === seg) {
                return;
            }
            const otherStart = minutesOf(other.dataset.start);
            const otherEnd = minutesOf(other.dataset.end);
            if (otherEnd <= start) {
                low = Math.max(low, otherEnd);
            } else if (otherStart >= end) {
                high = Math.min(high, otherStart);
            }
        });
        return {low, high};
    }

    /*
     * Saves with the chart's own CSRF token, then swaps in the chart the server sends back.
     * {@code fields}: an object, or a form's URLSearchParams; "focus" names the block to focus after.
     */
    async function saveHours(url, fields) {
        const card = document.getElementById("weekly-hours");
        const token = card.querySelector("input[name=_csrf]");
        const body = fields instanceof URLSearchParams ? fields : new URLSearchParams(fields);
        if (token && !body.has("_csrf")) {
            body.set("_csrf", token.value);
        }
        const focus = body.get("focus");
        body.delete("focus");
        card.classList.add("saving");
        try {
            const response = await fetch(url, {method: "POST", body, credentials: "same-origin"});
            const page = new DOMParser().parseFromString(await response.text(), "text/html");
            const fresh = page.getElementById("weekly-hours");
            if (!response.ok || !fresh) {
                window.location.reload();
                return;
            }
            card.replaceWith(fresh);
            const toasts = document.querySelector(".toasts");
            page.querySelectorAll(".toasts .toast").forEach(toast => toasts?.appendChild(toast));
            initToasts();
            if (focus) {
                fresh.querySelector(`.hours-seg[data-rule="${focus}"]`)?.focus();
            }
        } catch (failed) {
            window.location.reload();
        }
    }

    function saveSeg(seg, track) {
        const card = document.getElementById("weekly-hours");
        const url = card.dataset.hoursUrl;
        const fields = {start: seg.dataset.start, end: seg.dataset.end};
        if (seg.dataset.rule) {
            saveHours(url + "/" + seg.dataset.rule, {...fields, focus: seg.dataset.rule});
        } else {
            saveHours(url, {...fields, day: track.dataset.day});
        }
    }

    document.addEventListener("pointerdown", event => {
        const track = event.target.closest("#weekly-hours .hours-track");
        if (!track || event.button !== 0 || event.target.closest("form, button")) {
            return;
        }
        const seg = event.target.closest(".hours-seg");
        const at = minuteAt(track, event.clientX);
        if (seg) {
            const start = minutesOf(seg.dataset.start);
            const end = minutesOf(seg.dataset.end);
            const mode = event.target.closest(".seg-start") ? "start" : event.target.closest(".seg-end") ? "end" : "move";
            hoursDrag = {track, seg, mode, start, end, grab: at - start, ...room(track, seg, start, end), moved: false};
        } else {
            // A new block, drawn from where the pointer went down.
            const anchor = snap(at);
            const ghost = document.createElement("div");
            ghost.className = "hours-seg drawing";
            ghost.innerHTML = '<span class="seg-label"></span>';
            track.appendChild(ghost);
            track.querySelector(".closed-label")?.classList.add("hidden");
            hoursDrag = {track, seg: ghost, mode: "draw", anchor, start: anchor, end: anchor, ...room(track, ghost, anchor, anchor),
                moved: false};
        }
        hoursDrag.pointerId = event.pointerId;
        track.setPointerCapture(event.pointerId);
        hoursDrag.seg.classList.add("dragging");
        event.preventDefault();
    });

    document.addEventListener("pointermove", event => {
        if (!hoursDrag || event.pointerId !== hoursDrag.pointerId) {
            return;
        }
        const d = hoursDrag;
        const at = minuteAt(d.track, event.clientX);
        let start = d.start;
        let end = d.end;
        if (d.mode === "move") {
            const length = d.end - d.start;
            start = Math.min(Math.max(snap(at - d.grab), d.low), d.high - length);
            end = start + length;
        } else if (d.mode === "start") {
            start = Math.min(Math.max(snap(at), d.low), d.end - STEP);
        } else if (d.mode === "end") {
            end = Math.max(Math.min(snap(at), d.high), d.start + STEP);
        } else {
            const here = Math.min(Math.max(snap(at), d.low), d.high);
            start = Math.min(d.anchor, here);
            end = Math.max(d.anchor, here);
        }
        if (start !== minutesOf(d.seg.dataset.start ?? "0:0") || end !== minutesOf(d.seg.dataset.end ?? "0:0")) {
            d.moved = true;
            if (end > start) {
                place(d.seg, start, end);
            }
        }
    });

    function endHoursDrag(event, keep) {
        if (!hoursDrag || event.pointerId !== hoursDrag.pointerId) {
            return;
        }
        const {seg, track, mode, moved} = hoursDrag;
        hoursDrag = null;
        seg.classList.remove("dragging");
        const drawn = mode === "draw";
        const start = seg.dataset.start ? minutesOf(seg.dataset.start) : 0;
        const end = seg.dataset.end ? minutesOf(seg.dataset.end) : 0;
        if (keep && moved && end - start >= STEP) {
            saveSeg(seg, track);
        } else if (drawn) {
            seg.remove();
            track.querySelector(".closed-label")?.classList.remove("hidden");
        } else if (moved) {
            window.location.reload();
        }
    }

    document.addEventListener("pointerup", event => endHoursDrag(event, true));
    document.addEventListener("pointercancel", event => endHoursDrag(event, false));

    /* Arrow keys on a focused block: move it 15 minutes; with Shift, move its end. Saved when the keys stop. */
    let keyTimer = 0;
    document.addEventListener("keydown", event => {
        const seg = event.target.closest?.("#weekly-hours .hours-seg[data-rule]");
        if (!seg || !["ArrowLeft", "ArrowRight"].includes(event.key)) {
            return;
        }
        event.preventDefault();
        const track = seg.closest(".hours-track");
        const rtl = getComputedStyle(track).direction === "rtl";
        const later = (event.key === "ArrowRight") !== rtl;
        const delta = later ? STEP : -STEP;
        let start = minutesOf(seg.dataset.start);
        let end = minutesOf(seg.dataset.end);
        const {low, high} = room(track, seg, start, end);
        if (event.shiftKey) {
            end = Math.min(Math.max(end + delta, start + STEP), high);
        } else if (start + delta >= low && end + delta <= high) {
            start += delta;
            end += delta;
        }
        place(seg, start, end);
        clearTimeout(keyTimer);
        keyTimer = setTimeout(() => saveSeg(seg, track), 700);
    });

    /*
     * The hours card's forms (a phone's day list, "Copy to..."): saved in place, like a drag, instead
     * of reloading the page; the button pressed may send the form elsewhere (Delete).
     */
    document.addEventListener("submit", event => {
        const form = event.target.closest("#weekly-hours form[data-hours-form]");
        if (!form) {
            return;
        }
        event.preventDefault();
        const button = event.submitter;
        const url = button?.hasAttribute("formaction") ? button.formAction : form.action;
        saveHours(url, new URLSearchParams(new FormData(form)));
    });

    /* "Morning", "Afternoon"...: fill in the shift's times; Add saves them. */
    document.addEventListener("click", event => {
        const preset = event.target.closest("[data-preset]");
        if (!preset) {
            return;
        }
        const [start, end] = preset.dataset.preset.split("-");
        const form = preset.closest("form");
        form.elements.start.value = start;
        form.elements.end.value = end;
        form.querySelectorAll("[data-preset]").forEach(other => other.classList.toggle("on", other === preset));
    });

    /* One of a day's hours (or "Add hours") open at a time. */
    document.addEventListener("toggle", event => {
        const opened = event.target;
        if (opened.matches?.(".hd-edit") && opened.open) {
            document.querySelectorAll(".hd-edit[open]").forEach(other => other !== opened && (other.open = false));
        }
    }, true);

    /*
     * A doctor's page on a phone: the section tab of the part in view is lit, the last one whose top
     * has passed under the tabs. Looked up on each scroll, as saving the hours swaps their card.
     */
    function watchSectionTabs() {
        const tabs = document.querySelector(".section-tabs");
        if (!tabs) {
            return;
        }
        const links = [...tabs.querySelectorAll("a[href^='#']")];
        let frame = 0;
        const mark = () => {
            frame = 0;
            const line = tabs.getBoundingClientRect().bottom + 24;
            const atEnd = window.innerHeight + window.scrollY >= document.documentElement.scrollHeight - 4;
            let current = links[0];
            links.forEach(link => {
                const section = document.getElementById(link.hash.slice(1));
                if (section && (section.getBoundingClientRect().top <= line || atEnd && link === links.at(-1))) {
                    current = link;
                }
            });
            links.forEach(link => link.classList.toggle("current", link === current));
        };
        window.addEventListener("scroll", () => frame ||= requestAnimationFrame(mark), {passive: true});
        mark();
    }

    /* While the pointer is over a day's bar, a little time label shows where a drag would start. */
    document.addEventListener("pointermove", event => {
        const track = event.target.closest?.("#weekly-hours .hours-track");
        document.querySelectorAll("#weekly-hours .hours-cursor").forEach(cursor => {
            if (cursor.parentElement !== track || hoursDrag || event.target.closest(".hours-seg")) {
                cursor.remove();
            }
        });
        if (!track || hoursDrag || event.target.closest(".hours-seg") || event.pointerType === "touch") {
            return;
        }
        const minute = snap(minuteAt(track, event.clientX));
        let cursor = track.querySelector(".hours-cursor");
        if (!cursor) {
            cursor = document.createElement("span");
            cursor.className = "hours-cursor";
            cursor.setAttribute("aria-hidden", "true");
            track.appendChild(cursor);
        }
        cursor.style.insetInlineStart = ((minute - BAR_START) / (BAR_END - BAR_START) * 100) + "%";
        cursor.dataset.time = timeLabel(minute);
    });

    document.addEventListener("DOMContentLoaded", () => {
        markTheme();
        markSidebar();
        initToasts();
        landOnFocusDay();
        watchSectionTabs();
        watchDirtyForms();
        countUp();
        // The parts of the page have risen into place (app.css): from here on nothing replays its entrance.
        setTimeout(() => root.removeAttribute("data-enter"), 1800);
    });
    document.addEventListener("htmx:afterSettle", initToasts);
})();
