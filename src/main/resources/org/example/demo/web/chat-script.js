const chat = document.getElementById('chat');
const input = document.getElementById('custom-channel');
const sendBtn = document.getElementById('connect-custom');

const SCROLL_THRESHOLD = 50;
let userScrolledUp = false;
const MAX_MESSAGES = 512;

let autocompletePopup = null;
let activeIndex = -1;
let currentItems = [];
let debounceTimer = null;
let colonStart = -1;

chat.addEventListener('scroll', function () {
    const distanceFromBottom = chat.scrollHeight - chat.scrollTop - chat.clientHeight;
    userScrolledUp = distanceFromBottom > SCROLL_THRESHOLD;
});

function sendMessage() {
    const text = input.value.trim();
    if (!text) return;
    notifyJava('message', text);
    input.value = '';
}

sendBtn.addEventListener('click', sendMessage);
input.addEventListener('keyup', function (e) {
    if (e.key === 'Enter') sendMessage();
});

function appendSystemMessage(renderedMessage, isSystem) {
    const div = document.createElement('div');
    div.className = isSystem ? 'msg system-msg' : 'msg';
    div.innerHTML = `<span class="username" style="font-style: italic;">[SYSTEM]:</span> ` + renderedMessage;
    chat.appendChild(div);
    while (chat.childElementCount > MAX_MESSAGES) chat.removeChild(chat.firstElementChild);
    if (!userScrolledUp) chat.scrollTop = chat.scrollHeight;
}

function appendMessage(timestamp, username, userColor, renderedMessage, isSystem, isMod, isVip, isStreamer, isHighlighted) {
    const div = document.createElement('div');
    div.className = (isSystem ? 'msg system-msg' : 'msg') + (isHighlighted ? ' system-msg' : '');

    let badge = '';
    if (isStreamer) badge = `<span style="display:inline-block;width:10px;height:10px;background:rgba(231,76,60,0.6);margin-right:3px;vertical-align:middle;position:relative;top:-1px;"></span>`;
    else if (isMod) badge = `<span style="display:inline-block;width:10px;height:10px;background:rgba(46,204,113,0.6);margin-right:3px;vertical-align:middle;position:relative;top:-1px;"></span>`;
    else if (isVip) badge = `<span style="display:inline-block;width:10px;height:10px;background:rgba(155,89,182,0.6);margin-right:3px;vertical-align:middle;position:relative;top:-1px;"></span>`;

    const userSpan = isSystem
        ? `<span class="username" style="color:${userColor};font-style:italic;">[SYSTEM]:</span> `
        : `${badge}<span class="username" style="color:${userColor};">${username}:</span> `;

    div.innerHTML = `<span class="timestamp">[${timestamp}]</span>` + userSpan + renderedMessage;
    chat.appendChild(div);
    while (chat.childElementCount > MAX_MESSAGES) chat.removeChild(chat.firstElementChild);
    if (!userScrolledUp) chat.scrollTop = chat.scrollHeight;
}

function clearChat() {
    chat.innerHTML = '';
    userScrolledUp = false;
    chat.scrollTop = 0;
}

function notifyJava(type, payload) {
    if (window.javaApp) window.javaApp.onChatEvent(type, payload);
}

function copySelectionToJava() {
    const sel = window.getSelection().toString();
    if (sel) notifyJava('copy', sel);
}

document.addEventListener('copy', function (e) {
    e.clipboardData.setData('text/plain', window.getSelection().toString());
    e.preventDefault();
});

function autocompleteHide() {
    if (!autocompletePopup) return;
    autocompletePopup.style.display = "none";
    autocompletePopup.innerHTML = "";
    activeIndex = -1;
    currentItems = [];
    colonStart = -1;
}

function autocompleteSetActive(idx) {
    if (!autocompletePopup) return;
    autocompletePopup.querySelectorAll(".emote-item").forEach((el, i) =>
        el.classList.toggle("active", i === idx)
    );
    const items = autocompletePopup.querySelectorAll(".emote-item");
    if (items[idx]) items[idx].scrollIntoView({block: "nearest"});
    activeIndex = idx;
}

function autocompleteInsert(name) {
    const before = input.value.slice(0, colonStart);
    const after = input.value.slice(input.selectionStart);
    input.value = before + name + " " + after;
    const pos = (before + name + " ").length;
    input.setSelectionRange(pos, pos);
    autocompleteHide();
    input.focus();
}

function sourceLabel(src) {
    if (src.startsWith("7tv")) return src.includes("global") ? "7TV·G" : "7TV";
    if (src.startsWith("bttv")) return src.includes("global") ? "BTTV·G" : "BTTV";
    if (src.startsWith("ffz")) return src.includes("global") ? "FFZ·G" : "FFZ";
    if (src === "twitch") return "TTW";
    return src.toUpperCase().slice(0, 5);
}

function autocompleteRender(items) {
    if (!autocompletePopup) return;
    autocompletePopup.innerHTML = "";
    if (!items || !items.length) {
        autocompleteHide();
        return;
    }

    currentItems = items;
    activeIndex = 0;

    const header = document.createElement("div");
    header.className = "emote-autocomplete-header";
    header.textContent = `> ${items.length} emote${items.length !== 1 ? "s" : ""}  [↑↓ navigate · Tab/Enter insert · Esc close]`;
    autocompletePopup.appendChild(header);

    items.forEach(function (item, idx) {
        const row = document.createElement("div");
        row.className = "emote-item";

        const img = document.createElement("img");
        img.alt = item.name;
        if (item.filePath) img.src = item.filePath;

        const nameEl = document.createElement("span");
        nameEl.className = "emote-item-name";
        nameEl.textContent = item.name;

        const srcEl = document.createElement("span");
        srcEl.className = "emote-item-source";
        srcEl.textContent = sourceLabel(item.source);

        row.appendChild(img);
        row.appendChild(nameEl);
        row.appendChild(srcEl);
        row.addEventListener("mouseenter", () => autocompleteSetActive(idx));
        row.addEventListener("click", () => autocompleteInsert(item.name));
        autocompletePopup.appendChild(row);
    });

    autocompleteSetActive(0);
    autocompletePopup.style.display = "block";
}

function autocompleteSearch(query) {
    if (!window.javaApp) return;
    try {
        const json = window.javaApp.searchEmotes(
            typeof activeChannelName !== "undefined" ? activeChannelName : "",
            query,
            25
        );
        autocompleteRender(JSON.parse(json) || []);
    } catch (e) {
        console.warn("searchEmotes error:", e);
        autocompleteHide();
    }
}

// Handles input changes, triggers autocomplete when user types ":"
input.addEventListener("input", function (e) {
    const val = input.value;
    const caret = input.selectionStart;

    let ci = -1;
    for (let i = caret - 1; i >= 0; i--) {
        if (val[i] === ":") {
            ci = i;
            break;
        }
        if (val[i] === " ") break;
    }

    if (ci === -1 || (ci > 0 && val[ci - 1] !== " ")) {
        autocompleteHide();
        return;
    }

    colonStart = ci;
    const query = val.slice(ci + 1, caret);
    if (query.length < 1) {
        autocompleteHide();
        return;
    }

    clearTimeout(debounceTimer);
    debounceTimer = setTimeout(() => autocompleteSearch(query), 80);
});

// Single global keydown handler, covers both autocomplete and Enter
document.addEventListener("keydown", function (e) {
    const popupVisible = autocompletePopup && autocompletePopup.style.display !== "none";
    const inputFocused = document.activeElement === input;

    if (!inputFocused) return;

    if (e.key === "Enter") {
        if (popupVisible) {
            if (currentItems.length && activeIndex >= 0) {
                e.preventDefault();
                e.stopImmediatePropagation();
                autocompleteInsert(currentItems[activeIndex].name);
            }
        } else {
            e.preventDefault();
            sendMessage();
        }
        return;
    }

    if (popupVisible) {
        switch (e.key) {
            case "ArrowDown":
                e.preventDefault();
                autocompleteSetActive((activeIndex + 1) % currentItems.length);
                return;
            case "ArrowUp":
                e.preventDefault();
                autocompleteSetActive((activeIndex - 1 + currentItems.length) % currentItems.length);
                return;
            case "Tab":
                e.preventDefault();
                if (currentItems.length && activeIndex >= 0) {
                    autocompleteInsert(currentItems[activeIndex].name);
                }
                break;
            case "Escape":
                e.preventDefault();
                autocompleteHide();
                return;
        }
    }
}, true);

input.addEventListener("blur", () => {
    setTimeout(() => {
        if (autocompletePopup && !autocompletePopup.matches(":hover")) autocompleteHide();
    }, 150);
});

window.onload = () => {
    // Create and attach popup
    autocompletePopup = document.createElement("div");
    autocompletePopup.id = "emote-autocomplete";
    const container = document.querySelector(".chat-input-container");
    if (container) container.appendChild(autocompletePopup);

    appendSystemMessage("-- CONNECTING TO CHANNELS --");
};