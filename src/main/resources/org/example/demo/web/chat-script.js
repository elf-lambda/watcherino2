const chat = document.getElementById('chat');
const input = document.getElementById('custom-channel');
const sendBtn = document.getElementById('connect-custom');

const SCROLL_THRESHOLD = 50;
let userScrolledUp = false;
const MAX_MESSAGES = 512;

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

    const userSpan = `<span class="username" style="font-style: italic;">[SYSTEM]:</span> `;

    div.innerHTML = userSpan + renderedMessage;

    chat.appendChild(div);

    while (chat.childElementCount > MAX_MESSAGES) {
        chat.removeChild(chat.firstElementChild);
    }

    if (!userScrolledUp) {
        chat.scrollTop = chat.scrollHeight;
    }
}

function appendMessage(timestamp, username, userColor, renderedMessage, isSystem) {
    const div = document.createElement('div');

    div.className = isSystem ? 'msg system-msg' : 'msg';

    const userSpan = isSystem
        ? `<span class="username" style="color: ${userColor}; font-style: italic;">[SYSTEM]:</span> `
        : `<span class="username" style="color: ${userColor};">${username}:</span> `;

    div.innerHTML = `<span class="timestamp">[${timestamp}]</span>`
        + userSpan
        + renderedMessage;

    chat.appendChild(div);

    while (chat.childElementCount > MAX_MESSAGES) {
        chat.removeChild(chat.firstElementChild);
    }

    if (!userScrolledUp) {
        chat.scrollTop = chat.scrollHeight;
    }
}

function clearChat() {
    chat.innerHTML = '';
    userScrolledUp = false;
    chat.scrollTop = 0;
}

function notifyJava(type, payload) {
    if (window.javaApp) {
        window.javaApp.onChatEvent(type, payload);
    }
}

function copySelectionToJava() {
    const sel = window.getSelection().toString();
    if (sel) notifyJava('copy', sel);
}

document.addEventListener('copy', function (e) {
    e.clipboardData.setData('text/plain', window.getSelection().toString());
    e.preventDefault();
});

window.onload = () => {
    appendSystemMessage("<b>-- CONNECTING TO CHANNELS --</b>");
}
