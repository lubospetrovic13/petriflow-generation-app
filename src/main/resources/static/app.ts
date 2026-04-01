// Fallback inline marked.js polyfill if CDN fails
if (typeof marked === 'undefined') {
  var marked = (function() {
    function escHtmlMd(s) {
      return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
    }
    function inlineParse(text) {
      return text
          .replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')
          .replace(/`([^`]+)`/g, function(_,c){return '<code>'+c+'</code>';})
          .replace(/\*\*\*(.+?)\*\*\*/g,'<strong><em>$1</em></strong>')
          .replace(/\*\*(.+?)\*\*/g,'<strong>$1</strong>')
          .replace(/\*(.+?)\*/g,'<em>$1</em>')
          .replace(/__(.+?)__/g,'<strong>$1</strong>')
          .replace(/_(.+?)_/g,'<em>$1</em>')
          .replace(/\[([^\]]+)\]\(([^)]+)\)/g, function(_, text, href) {
            if (href.startsWith('#')) {
              return '<a href="' + href + '" onclick="event.preventDefault(); document.getElementById(\'' + href.slice(1) + '\')?.scrollIntoView({behavior:\'smooth\'});">' + text + '</a>';
            }
            return '<a href="' + href + '" target="_blank" rel="noopener">' + text + '</a>';
          });
    }
    function parse(md) {
      var lines = md.split('\n');
      var html = '', i = 0;
      var inPre = false, preLang = '', preBuf = '';
      var inUl = false, inOl = false, inTable = false, tableHdrDone = false;
      var listStack = []; // track nested list levels
      function closeLists() {
        while (listStack.length > 0) {
          var type = listStack.pop();
          html += '</' + type + '>\n';
        }
        inUl = false; inOl = false;
      }
      function closeTable() {
        if (inTable) { html += '</tbody></table>\n'; inTable = false; tableHdrDone = false; }
      }
      for (; i < lines.length; i++) {
        var line = lines[i];
        // Code fence
        if (!inPre && /^```/.test(line)) {
          closeLists(); closeTable();
          preLang = escHtmlMd(line.slice(3).trim());
          preBuf = ''; inPre = true; continue;
        }
        if (inPre) {
          if (/^```/.test(line)) {
            html += '<pre><code' + (preLang ? ' class="language-'+preLang+'"' : '') + '>' + preBuf + '</code></pre>\n';
            inPre = false;
          } else {
            preBuf += escHtmlMd(line) + '\n';
          }
          continue;
        }
        // Headings
        var hm = line.match(/^(#{1,4}) (.+)/);
        if (hm) {
          closeLists(); closeTable();
          var lvl = hm[1].length;
          var headingText = hm[2];
          // Generate ID from heading text, preserving numbers and periods for subsections like "3.2"
          var id = headingText.toLowerCase()
              .replace(/[^a-z0-9.\s-]/g, '')
              .replace(/\s+/g, '-')
              .replace(/-+/g, '-')
              .replace(/^-|-$/g, '');
          html += '<h'+lvl+' id="'+id+'">' + inlineParse(headingText) + '</h'+lvl+'>\n'; continue;
        }
        // HR
        if (/^(-{3,}|\*{3,}|={3,})$/.test(line.trim())) {
          closeLists(); closeTable(); html += '<hr>\n'; continue;
        }
        // Blockquote
        if (/^> /.test(line)) {
          closeLists(); closeTable();
          html += '<blockquote>' + inlineParse(line.slice(2)) + '</blockquote>\n'; continue;
        }
        // Table
        if (/^\|/.test(line)) {
          if (!inTable) { html += '<table><thead>\n'; inTable = true; tableHdrDone = false; }
          if (/^\|[-| :]+\|$/.test(line.trim())) {
            if (!tableHdrDone) { html += '</thead><tbody>\n'; tableHdrDone = true; }
            continue;
          }
          var cells = line.split('|').slice(1,-1).map(function(c){return c.trim();});
          var tag = tableHdrDone ? 'td' : 'th';
          html += '<tr>' + cells.map(function(c){return '<'+tag+'>'+inlineParse(c)+'</'+tag+'>';}).join('') + '</tr>\n';
          continue;
        }
        if (inTable && !/^\|/.test(line)) { closeTable(); }
        // Unordered list (supports indent)
        var ulMatch = line.match(/^(\s*)[-*] (.+)/);
        if (ulMatch) {
          var indent = Math.floor(ulMatch[1].length / 2);
          while (listStack.length > indent + 1) {
            html += '</' + listStack.pop() + '>\n';
          }
          if (listStack.length === indent && listStack[listStack.length - 1] === 'ol') {
            html += '</ol>\n'; listStack.pop();
          }
          if (listStack.length === indent) {
            html += '<ul>\n'; listStack.push('ul'); inUl = true;
          }
          html += '<li>' + inlineParse(ulMatch[2]) + '</li>\n';
          continue;
        }
        // Ordered list (supports indent)
        var olMatch = line.match(/^(\s*)(\d+)\. (.+)/);
        if (olMatch) {
          var indent = Math.floor(olMatch[1].length / 2);
          var num = parseInt(olMatch[2]);
          while (listStack.length > indent + 1) {
            html += '</' + listStack.pop() + '>\n';
          }
          if (listStack.length === indent && listStack[listStack.length - 1] === 'ul') {
            html += '</ul>\n'; listStack.pop();
          }
          if (listStack.length === indent) {
            html += '<ol start="' + num + '">\n'; listStack.push('ol'); inOl = true;
          }
          html += '<li>' + inlineParse(olMatch[3]) + '</li>\n';
          continue;
        }
        // Empty line
        if (line.trim() === '') { closeLists(); closeTable(); html += '\n'; continue; }
        // Paragraph
        closeLists(); closeTable();
        html += '<p>' + inlineParse(line) + '</p>\n';
      }
      if (inPre) html += '<pre><code>' + preBuf + '</code></pre>\n';
      closeLists(); closeTable();
      return html;
    }
    function setOptions() {}
    return { parse: parse, setOptions: setOptions };
  })();
}
// --- Config -------------------------------------------------------------------
const BACKEND_URL = 'http://localhost:8080/api/chat/stream';

// --- State -------------------------------------------------------------------
let history = [];          // current conversation messages
let isLoading = false;
let sidebarOpen = true;
let chats = [];            // [{id, title, history, createdAt}]
let activeChatId = null;   // null = unsaved new chat
let activeStreams = new Map();  // chatId -> { history, bubble, fullText, reader, finalized }
const welcomeTemplate = document.querySelector('#welcome')?.cloneNode(true);

// --- Init --------------------------------------------------------------------
checkBackend();
loadChatsFromStorage().then(() => renderSidebar());
applyTheme(getSavedTheme());

// --- Theme -------------------------------------------------------------------
function getSavedTheme() {
  return localStorage.getItem('pf-theme') || 'light';
}
function applyTheme(theme) {
  document.documentElement.setAttribute('data-theme', theme);
  document.getElementById('theme-btn').textContent = theme === 'dark' ? '☀️' : '🌙';
  localStorage.setItem('pf-theme', theme);
}
function toggleTheme() {
  const current = document.documentElement.getAttribute('data-theme');
  applyTheme(current === 'dark' ? 'light' : 'dark');
}

// --- Sidebar toggle -----------------------------------------------------------
function toggleSidebar() {
  sidebarOpen = !sidebarOpen;
  document.getElementById('sidebar').classList.toggle('collapsed', !sidebarOpen);
  const overlay = document.getElementById('sidebar-overlay');
  if (overlay) overlay.classList.toggle('visible', sidebarOpen && window.innerWidth <= 768);
}

// --- Chat storage -------------------------------------------------------------
async function loadChatsFromStorage() {
  try {
    const resp = await fetch('http://localhost:8080/api/chats');
    if (resp.ok) {
      chats = await resp.json();
    } else {
      chats = [];
    }
  } catch(e) {
    console.error('Failed to load chats from backend:', e);
    chats = [];
  }
}

async function saveChatsToStorage() {
  try {
    await fetch('http://localhost:8080/api/chats', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(chats)
    });
  } catch(e) {
    console.error('Failed to save chats to backend:', e);
  }
}

function saveCurrent() {
  if (history.length === 0) return;
  const inp = document.getElementById('user-input');
  if (activeChatId && inp) {
    const draft = inp.value;
    const idx = chats.findIndex(c => c.id === activeChatId);
    if (idx !== -1) chats[idx].draft = draft;
  }

  if (activeChatId) {
    const idx = chats.findIndex(c => c.id === activeChatId);
    if (idx !== -1) {
      chats[idx].history = [...history];
      chats[idx].updatedAt = Date.now();
    }
  } else {
    // Extract title from XML if available (prefer <title>, fallback to <id>)
    let title = 'Untitled chat';
    const lastAssistant = [...history].reverse().find(m => m.role === 'assistant');

    if (lastAssistant) {
      // Find all XML documents in the response
      const xmlDocRegex = /<document[\s\S]*?<\/document>/g;
      const xmlDocs = lastAssistant.content.match(xmlDocRegex) || [];
      const processNames = [];

      for (const doc of xmlDocs) {
        const xmlTitleMatch = doc.match(/<title>([^<]+)<\/title>/);
        const xmlIdMatch = doc.match(/<id>([^<]+)<\/id>/);

        if (xmlTitleMatch) {
          processNames.push(xmlTitleMatch[1].trim());
        } else if (xmlIdMatch) {
          processNames.push(xmlIdMatch[1].trim());
        }
      }

      if (processNames.length > 0) {
        title = processNames.join(' & ');
      }
    }

    // Fallback to first user message if no XML found
    if (title === 'Untitled chat') {
      const firstUser = history.find(m => m.role === 'user');
      if (firstUser) {
        title = firstUser.content.replace(/\s+/g, ' ').trim().slice(0, 55) + (firstUser.content.length > 55 ? '…' : '');
      }
    }

    const provider = document.getElementById('llm-provider').value;
    const mode = document.getElementById('context-mode').value;
    activeChatId = 'chat_' + Date.now();
    chats.unshift({
      id: activeChatId,
      title,
      history: [...history],
      provider,
      mode,
      createdAt: Date.now(),
      updatedAt: Date.now()
    });
  }
  saveChatsToStorage();
}

function loadChat(id) {
  const chat = chats.find(c => c.id === id);
  if (!chat) return;

  if (activeChatId && activeChatId !== id) {
    const inp = document.getElementById('user-input');
    const idx = chats.findIndex(c => c.id === activeChatId);
    if (idx !== -1 && inp) { chats[idx].draft = inp.value; saveChatsToStorage(); }
  }
  if (history.length > 0 && activeChatId !== id) saveCurrent();

  const streamState = activeStreams.get(id);
  isLoading = !!streamState;

  activeChatId = id;
  history = [...chat.history];

  const msgs = document.getElementById('messages');
  msgs.innerHTML = '';
  history.forEach(m => {
    const el = addMessage(m.role === 'assistant' ? 'ai' : m.role, m.content);
  });

  if (streamState && streamState.bubble) {
    msgs.appendChild(streamState.bubble);
  }

  msgs.scrollTop = msgs.scrollHeight;

  const inp2 = document.getElementById('user-input');
  if (inp2) {
    inp2.value = chat.draft || '';
    inp2.style.height = 'auto';
    if (inp2.value) inp2.style.height = Math.min(inp2.scrollHeight, 180) + 'px';
  }

  const providerSelect = document.getElementById('llm-provider');
  const modeSelect = document.getElementById('context-mode');
  const selector = document.getElementById('llm-selector');
  if (chat.provider && chat.mode) {
    providerSelect.value = chat.provider;
    modeSelect.value = chat.mode;
    selector.classList.remove('hidden');
    providerSelect.disabled = true;
    modeSelect.disabled = true;

    fetch('http://localhost:8080/api/config/update', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ provider: chat.provider, mode: chat.mode })
    }).catch(e => console.error('Failed to update config:', e));
  } else {
    selector.classList.add('hidden');
  }

  updateSendBtn();
  renderSidebar();
  if (window.innerWidth <= 768) toggleSidebar();
}

function renameChat(id, e) {
  e.stopPropagation();
  const chat = chats.find(c => c.id === id);
  if (!chat) return;
  const newTitle = prompt('Rename chat:', chat.title);
  if (newTitle && newTitle.trim()) {
    chat.title = newTitle.trim();
    chat.updatedAt = Date.now();
    saveChatsToStorage();
    renderSidebar();
  }
}

function deleteChat(id, e) {
  e.stopPropagation();
  chats = chats.filter(c => c.id !== id);
  saveChatsToStorage();
  if (activeChatId === id) {
    activeChatId = null;
    history = [];
    showWelcome();
  }
  renderSidebar();
}

function renderSidebar() {
  const list = document.getElementById('sidebar-list');

  if (chats.length === 0) {
    list.innerHTML = '<div class="sidebar-empty">No saved chats yet.<br>Start a conversation to see it here.</div>';
    return;
  }

  list.innerHTML = '';
  chats.forEach(chat => {
    const msgCount = chat.history.filter(m => m.role === 'user').length;
    const when = timeAgo(chat.updatedAt || chat.createdAt);
    const isActive = chat.id === activeChatId ? ' active' : '';

    const item = document.createElement('div');
    item.className = 'chat-item' + isActive;
    item.addEventListener('click', function() { loadChat(chat.id); });

    const icon = document.createElement('div');
    icon.className = 'chat-item-icon';
    icon.textContent = '';

    const contentDiv = document.createElement('div');
    contentDiv.className = 'chat-item-content';

    const titleDiv = document.createElement('div');
    titleDiv.className = 'chat-item-title';
    titleDiv.textContent = chat.title;
    titleDiv.title = chat.title;

    const metaDiv = document.createElement('div');
    metaDiv.className = 'chat-item-meta';
    metaDiv.textContent = msgCount + ' message' + (msgCount !== 1 ? 's' : '') + ' · ' + when;

    contentDiv.appendChild(titleDiv);
    contentDiv.appendChild(metaDiv);

    const renameBtn = document.createElement('button');
    renameBtn.className = 'chat-item-rename';
    renameBtn.title = 'Rename';
    renameBtn.textContent = '✎';
    renameBtn.addEventListener('click', function(e) { renameChat(chat.id, e); });

    const delBtn = document.createElement('button');
    delBtn.className = 'chat-item-del';
    delBtn.title = 'Delete';
    delBtn.textContent = '✕';
    delBtn.addEventListener('click', function(e) { deleteChat(chat.id, e); });

    item.appendChild(icon);
    item.appendChild(contentDiv);
    item.appendChild(renameBtn);
    item.appendChild(delBtn);
    list.appendChild(item);
  });

}

function timeAgo(ts) {
  const s = Math.floor((Date.now() - ts) / 1000);
  if (s < 60) return 'just now';
  if (s < 3600) return Math.floor(s/60) + 'm ago';
  if (s < 86400) return Math.floor(s/3600) + 'h ago';
  return Math.floor(s/86400) + 'd ago';
}

// --- New chat -----------------------------------------------------------------
function newChat() {
  if (activeChatId) {
    const inp = document.getElementById('user-input');
    const idx = chats.findIndex(c => c.id === activeChatId);
    if (idx !== -1 && inp) { chats[idx].draft = inp.value; saveChatsToStorage(); }
  }
  if (history.length > 0) saveCurrent();
  activeChatId = null;
  history = [];
  const inp = document.getElementById('user-input');
  if (inp) { inp.value = ''; inp.style.height = 'auto'; }
  updateSendBtn();

  const selector = document.getElementById('llm-selector');
  const providerSelect = document.getElementById('llm-provider');
  const modeSelect = document.getElementById('context-mode');
  if (selector) {
    selector.classList.remove('hidden');
    providerSelect.disabled = false;
    modeSelect.disabled = false;
  }

  showWelcome();
}



function showWelcome() {
  const msgs = document.getElementById('messages');
  msgs.innerHTML = '';
  if (welcomeTemplate) {
    const clone = welcomeTemplate.cloneNode(true);
    clone.querySelectorAll('.suggestion-chip').forEach(chip =>
        chip.addEventListener('click', () => fillSuggestion(chip))
    );
    msgs.appendChild(clone);
  }
  renderSidebar();
}

// --- Backend health -----------------------------------------------------------
async function checkBackend() {
  try {
    const r = await fetch('http://localhost:8080/api/health');
    if (r.ok) showBackendStatus('ok');
    else showBackendStatus('err');
  } catch(e) {
    showBackendStatus('err');
  }
}

function showBackendStatus(state) {
  const el = document.getElementById('backend-status');
  if (!el) return;
  if (state === 'ok') {
    el.innerHTML = '<span class="bs-dot bs-ok"></span><span>Backend connected</span>';
    setTimeout(() => el.style.display = 'none', 3000);
  } else {
    el.innerHTML = '<span class="bs-dot bs-err"></span><span>Backend not running -- start the Java app on port 8080</span>';
  }
}

// --- UI helpers ---------------------------------------------------------------
function updateSendBtn() {
  document.getElementById('send-btn').disabled =
      isLoading || !document.getElementById('user-input').value.trim();
}
document.getElementById('user-input').addEventListener('input', updateSendBtn);
updateSendBtn();

function autoResize(el) { el.style.height = 'auto'; el.style.height = Math.min(el.scrollHeight, 180) + 'px'; }
function handleKey(e) { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendMessage(); } }
function fillSuggestion(el) {
  const inp = document.getElementById('user-input');
  const chip = el.closest ? (el.closest('.suggestion-chip') || el) : el;

  const shortPrompt = chip.dataset.prompt || '';

  const title   = chip.querySelector('.sc-header') ? chip.querySelector('.sc-header').innerText.trim() : '';
  const desc    = chip.querySelector('.sc-desc') ? chip.querySelector('.sc-desc').innerText.trim() : '';
  const bulletEls = chip.querySelectorAll('.sc-bullet');
  const bullets = Array.from(bulletEls).map(function(b) { return '- ' + b.innerText.trim(); }).join('\n');
  const footer  = chip.querySelector('.sc-footer') ? chip.querySelector('.sc-footer').innerText.trim() : '';
  const fullDisplay = [title, desc, bullets, footer].filter(Boolean).join('\n\n');

  inp.value = fullDisplay;
  inp.dataset.backendPrompt = shortPrompt;

  inp.style.height = 'auto';
  inp.style.height = Math.min(inp.scrollHeight, 180) + 'px';
  updateSendBtn();
  inp.focus();
}

// --- Send ---------------------------------------------------------------------
async function sendMessage() {
  const inp = document.getElementById('user-input');
  const text = inp.value.trim();
  if (!text || isLoading) return;

  const welcome = document.getElementById('welcome');
  if (welcome) welcome.remove();

  const providerSelect = document.getElementById('llm-provider');
  const modeSelect = document.getElementById('context-mode');
  const selector = document.getElementById('llm-selector');

  if (!selector.classList.contains('hidden')) {
    const provider = providerSelect.value;
    const mode = modeSelect.value;

    await fetch('http://localhost:8080/api/config/update', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ provider, mode })
    }).catch(e => console.error('Failed to update config:', e));

    selector.classList.add('hidden');
    providerSelect.disabled = true;
    modeSelect.disabled = true;
  }

  const backendText = inp.dataset.backendPrompt || text;
  delete inp.dataset.backendPrompt;

  addMessage('user', text);  // show full text in chat
  history.push({ role: 'user', content: backendText });  // send short prompt to LLM
  inp.value = ''; inp.style.height = 'auto'; updateSendBtn();

  const thinking = addThinking();
  isLoading = true;

  try {
    const response = await fetch(BACKEND_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ messages: history })
    });

    thinking.remove();

    if (!response.ok) {
      const err = await response.json().catch(() => ({}));
      throw new Error(err.error || `HTTP ${response.status}`);
    }

    const { bubble, textContainer, xmlContainer, cursor } = createStreamBubble();
    let fullText = '';
    let finalized = false;  // guard against duplicate done events
    let receivedAnyData = false;  // track if we got any response from backend

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';

    const streamChatId = activeChatId || 'temp_' + Date.now();

    activeStreams.set(streamChatId, {
      bubble,
      textContainer,
      xmlContainer,
      cursor,
      fullText,
      finalized,
      history: history  // reference to current history array
    });

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split('\n');
      buffer = lines.pop();

      let currentEvent = null;
      for (const line of lines) {
        const trimmed = line.trim();
        if (!trimmed) {
          // Empty line resets event
          currentEvent = null;
          continue;
        }

        if (trimmed.startsWith('event:')) {
          currentEvent = trimmed.slice(6).trim();
          continue;
        }

        if (!trimmed.startsWith('data:')) continue;
        const dataStr = trimmed.slice(5).trim();
        if (!dataStr) continue;

        try {
          if (currentEvent === 'error') {
            receivedAnyData = true;
            throw new Error(dataStr);
          }

          const data = JSON.parse(dataStr);
          if (data.text !== undefined) {
            receivedAnyData = true;
            fullText += data.text;

            const streamState = activeStreams.get(streamChatId);
            if (streamState) streamState.fullText = fullText;

            if (activeChatId === streamChatId || (activeChatId === null && streamChatId.startsWith('temp_'))) {
              updateStreamDisplay(bubble, fullText);
            }
          } else if (data.full !== undefined) {
            receivedAnyData = true;
            if (!finalized) {
              finalized = true;

              const streamState = activeStreams.get(streamChatId);
              const targetHistory = streamState ? streamState.history : history;

              if (cursor.parentNode) cursor.parentNode.removeChild(cursor);

              if (activeChatId === streamChatId || (activeChatId === null && streamChatId.startsWith('temp_'))) {
                finalizeStreamBubble(bubble, textContainer, xmlContainer, data.full);
              }

              targetHistory.push({ role: 'assistant', content: data.full });

              if (streamChatId.startsWith('temp_') && activeChatId === null) {
                saveCurrent();
              } else {
                const chatIdx = chats.findIndex(c => c.id === streamChatId);
                if (chatIdx !== -1) {
                  chats[chatIdx].history = targetHistory;
                  chats[chatIdx].updatedAt = Date.now();
                  saveChatsToStorage();
                }
              }

              activeStreams.delete(streamChatId);

              renderSidebar();
            }
          } else if (data.error !== undefined) {
            receivedAnyData = true;
            throw new Error(data.error);
          }
        } catch(e) {
          // Only rethrow real errors, not JSON parse failures on malformed SSE lines
          if (e.message && !e.message.includes('JSON') && !e.message.includes('Unexpected')) throw e;
        }
      }
    }

    // If stream ended without receiving any data or finalization, something went wrong
    if (!receivedAnyData || !finalized) {
      throw new Error('Stream ended unexpectedly without a response');
    }

  } catch (e) {
    const streamChatId = activeChatId || 'temp_' + Date.now();
    activeStreams.delete(streamChatId);

    try { document.querySelector('.streaming-bubble')?.remove(); } catch(_) {}

    if (history.length > 0 && history[history.length - 1].role === 'user') {
      history.pop();
      const messages = document.getElementById('messages');
      const userMsgs = messages.querySelectorAll('.msg.user');
      if (userMsgs.length > 0) {
        userMsgs[userMsgs.length - 1].remove();
      }
    }

    addError(e.message.includes('fetch') ?
        'Cannot reach backend -- is the Java app running on port 8080?' : e.message);

    saveCurrent();
    renderSidebar();
  }
  isLoading = false; updateSendBtn();
}

// --- Streaming render ---------------------------------------------------------
function createStreamBubble() {
  const msgs = document.getElementById('messages');
  const div = document.createElement('div');
  div.className = 'msg ai streaming-bubble';

  const avatar = document.createElement('div');
  avatar.className = 'avatar ai';
  avatar.textContent = 'PF';

  const bubble = document.createElement('div');
  bubble.className = 'bubble';

  const textContainer = document.createElement('div');
  textContainer.className = 'stream-text';

  const xmlContainer = document.createElement('div');
  xmlContainer.style.display = 'none';

  const cursor = document.createElement('span');
  cursor.className = 'streaming-cursor';

  bubble.appendChild(textContainer);
  bubble.appendChild(xmlContainer);
  bubble.appendChild(cursor);
  div.appendChild(avatar);
  div.appendChild(bubble);
  msgs.appendChild(div);

  // Auto-scroll only if user is near bottom
  const isNearBottom = msgs.scrollHeight - msgs.scrollTop - msgs.clientHeight < 200;
  if (isNearBottom) {
    msgs.scrollTop = msgs.scrollHeight;
  }

  return { bubble, textContainer, xmlContainer, cursor };
}

function finalizeStreamBubble(bubble, textContainer, xmlContainer, fullText) {
  textContainer.innerHTML = '';
  xmlContainer.innerHTML = '';
  xmlContainer.style.display = '';

  const parsed = parseContent(fullText);
  bubble.innerHTML = parsed.html;

  if (parsed.xmlBlocks.length > 0) {
    bubble.appendChild(buildXmlActions(parsed.xmlBlocks));
  }

  bubble.closest('.msg')?.classList.remove('streaming-bubble');
  document.getElementById('messages').scrollTop = 99999;
}

function updateStreamDisplay(bubble, fullText) {
  const msgs = document.getElementById('messages');
  const xmlStartIdx = fullText.indexOf('```xml');

  if (xmlStartIdx === -1) {
    const textDiv = bubble.querySelector('.stream-text') || bubble;
    textDiv.innerHTML = renderMarkdown(fullText);
  } else {
    const beforeXml = fullText.slice(0, xmlStartIdx);
    const xmlContent = fullText.slice(xmlStartIdx + 6);
    const xmlEndIdx = xmlContent.indexOf('\n```');
    const xmlBody = xmlEndIdx !== -1 ? xmlContent.slice(0, xmlEndIdx) : xmlContent;
    const afterXml = xmlEndIdx !== -1 ? xmlContent.slice(xmlEndIdx + 4) : '';

    const textDiv = bubble.querySelector('.stream-text');
    if (textDiv) textDiv.innerHTML = renderMarkdown(beforeXml);

    let xmlDiv = bubble.querySelector('.xml-block-streaming');
    if (!xmlDiv) {
      xmlDiv = document.createElement('div');
      xmlDiv.className = 'xml-block-streaming';
      const cursor = bubble.querySelector('.streaming-cursor');
      if (cursor) bubble.insertBefore(xmlDiv, cursor);
      else bubble.appendChild(xmlDiv);
    }
    xmlDiv.textContent = xmlBody;
    xmlDiv.scrollTop = xmlDiv.scrollHeight;

    if (afterXml) {
      let afterDiv = bubble.querySelector('.stream-after');
      if (!afterDiv) {
        afterDiv = document.createElement('div');
        afterDiv.className = 'stream-after';
        bubble.appendChild(afterDiv);
      }
      afterDiv.innerHTML = renderMarkdown(afterXml);
    }
  }

  // Auto-scroll only if user is near bottom (within 200px)
  const isNearBottom = msgs.scrollHeight - msgs.scrollTop - msgs.clientHeight < 200;
  if (isNearBottom) {
    msgs.scrollTop = msgs.scrollHeight;
  }
}

function renderMarkdown(text) {
  let h = escHtml(text);

  // Process block elements first
  const lines = h.split('\n');
  let result = [];
  let inList = false;
  let listItems = [];

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];

    // Handle h3 headings (###)
    if (line.match(/^###\s+(.+)$/)) {
      if (inList) {
        result.push('<ol style="margin:12px 0 16px;padding-left:24px;list-style:decimal;color:var(--text)">' + listItems.join('') + '</ol>');
        listItems = [];
        inList = false;
      }
      const headingText = line.replace(/^###\s+/, '');
      result.push(`<h3 style="font-weight:600;font-size:15px;margin:20px 0 10px;color:var(--text);border-bottom:1px solid var(--border);padding-bottom:6px">${headingText}</h3>`);
    }
    // Handle h4 headings (####) - used for sub-questions
    else if (line.match(/^####\s+(.+)$/)) {
      if (inList) {
        result.push('<ol style="margin:12px 0 16px;padding-left:24px;list-style:decimal;color:var(--text)">' + listItems.join('') + '</ol>');
        listItems = [];
        inList = false;
      }
      const headingText = line.replace(/^####\s+/, '');
      result.push(`<h4 style="font-weight:600;font-size:14px;margin:14px 0 8px;color:var(--blue);letter-spacing:0.3px">${headingText}</h4>`);
    }
    // Handle numbered lists (including sub-items with dashes)
    else if (line.match(/^\d+\.\s+(.+)$/)) {
      const content = line.replace(/^\d+\.\s+/, '');
      listItems.push(`<li style="margin-bottom:10px;line-height:1.6;color:var(--text);font-weight:500">${content}</li>`);
      inList = true;
    }
    // Handle sub-items with dashes/bullets
    else if (line.match(/^\s*[-•]\s+(.+)$/) && inList) {
      const content = line.replace(/^\s*[-•]\s+/, '');
      // Add sub-item as nested ul inside last li
      if (listItems.length > 0) {
        const lastItem = listItems[listItems.length - 1];
        if (!lastItem.includes('<ul')) {
          listItems[listItems.length - 1] = lastItem.replace('</li>', '<ul style="margin:6px 0 0;padding-left:20px;list-style:disc"></ul></li>');
        }
        listItems[listItems.length - 1] = listItems[listItems.length - 1].replace('</ul>', `<li style="margin:4px 0;line-height:1.5;color:var(--text-muted);font-weight:400">${content}</li></ul>`);
      }
    }
    // Empty line between list items - preserve spacing
    else if (line.trim() === '' && inList && i + 1 < lines.length && lines[i + 1].match(/^\d+\.\s+/)) {
      // Continue list, add spacing
      continue;
    }
    // Close list if we hit a non-list line
    else if (inList && line.trim() !== '' && !line.match(/^\s*[-•]\s+/)) {
      result.push('<ol style="margin:12px 0 16px;padding-left:24px;list-style:decimal;color:var(--text)">' + listItems.join('') + '</ol>');
      listItems = [];
      inList = false;
      result.push(line ? `<p style="margin:8px 0;line-height:1.6">${line}</p>` : '');
    }
    // Regular line
    else if (!inList && line.trim()) {
      result.push(`<p style="margin:8px 0;line-height:1.6">${line}</p>`);
    }
  }

  // Close any remaining list
  if (inList) {
    result.push('<ol style="margin:12px 0 16px;padding-left:24px;list-style:decimal;color:var(--text)">' + listItems.join('') + '</ol>');
  }

  // Process inline formatting
  let output = result.join('');
  output = output.replace(/\*\*(.+?)\*\*/g, '<strong style="font-weight:600;color:var(--text)">$1</strong>');
  output = output.replace(/\*(.+?)\*/g, '<em>$1</em>');
  output = output.replace(/`([^`\n]+)`/g, '<code style="background:var(--blue-light);padding:2px 6px;border-radius:4px;font-family:var(--font-mono);font-size:12px;color:var(--blue)">$1</code>');

  return output;
}

// --- DOM helpers --------------------------------------------------------------
function addMessage(role, text) {
  const msgs = document.getElementById('messages');
  const div = document.createElement('div');
  div.className = `msg ${role}`;

  const avatar = document.createElement('div');
  avatar.className = `avatar ${role}`;
  avatar.textContent = role === 'ai' ? 'PF' : 'U';

  const bubble = document.createElement('div');
  bubble.className = 'bubble';

  const parsed = parseContent(text);
  bubble.innerHTML = parsed.html;

  if (parsed.xmlBlocks.length > 0) {
    bubble.appendChild(buildXmlActions(parsed.xmlBlocks));
  }

  div.appendChild(avatar);
  div.appendChild(bubble);
  msgs.appendChild(div);
  msgs.scrollTop = msgs.scrollHeight;
  return div;
}

function addThinking() {
  const msgs = document.getElementById('messages');
  const div = document.createElement('div');
  div.className = 'thinking';
  div.innerHTML = `<div class="avatar ai">PF</div><div class="thinking-dots"><span></span><span></span><span></span></div>`;
  msgs.appendChild(div);
  msgs.scrollTop = msgs.scrollHeight;
  return div;
}

function addError(msg) {
  const msgs = document.getElementById('messages');
  const div = document.createElement('div');
  div.className = 'msg ai';
  div.innerHTML = `<div class="avatar ai">PF</div><div class="bubble"><div class="error-bubble">⚠ ${escHtml(msg)}</div></div>`;
  msgs.appendChild(div);
  msgs.scrollTop = msgs.scrollHeight;
}

// --- Content parser -----------------------------------------------------------
function parseContent(text) {
  const xmlBlocks = [];

  const FENCE_RE = /^```([\w]*)\n([\s\S]*?)^```/gm;
  const segments = [];   // { type: 'text'|'code', content, lang }
  let lastIdx = 0;
  let m;

  while ((m = FENCE_RE.exec(text)) !== null) {
    // prose before this fence
    if (m.index > lastIdx) {
      segments.push({ type: 'text', content: text.slice(lastIdx, m.index) });
    }
    segments.push({ type: 'code', lang: m[1], content: m[2] });
    lastIdx = m.index + m[0].length;
  }
  if (lastIdx < text.length) {
    segments.push({ type: 'text', content: text.slice(lastIdx) });
  }

  let html = '';
  for (const seg of segments) {
    if (seg.type === 'text') {
      html += renderMarkdown(seg.content);
    } else {
      const decoded = seg.content.replace(/&lt;/g,'<').replace(/&gt;/g,'>').replace(/&amp;/g,'&');
      const isXml = (seg.lang === 'xml' || seg.lang === '') &&
          (decoded.includes('<document') || decoded.includes('petriflow') ||
              decoded.trimStart().startsWith('<'));
      if (isXml) {
        const docMatches = [...decoded.matchAll(/<document[\s\S]*?<\/document>/g)];
        if (docMatches.length > 1) {
          docMatches.forEach(dm => xmlBlocks.push(formatXml(dm[0])));
        } else {
          xmlBlocks.push(formatXml(decoded));
        }
      }
      const id = 'cb_' + Math.random().toString(36).slice(2, 7);
      const escapedCode = escHtml(seg.content.trimEnd());
      if (isXml) {
        const processId = seg.content.match(/<id>([^<]+)<\/id>/)?.[1]?.trim() || 'XML';
        const lines = escapedCode.split('\n');
        const previewLines = lines.slice(0, 5).join('\n');
        const restLines = lines.length > 5 ? lines.slice(5).join('\n') : '';
        const hasMore = restLines.length > 0;
        html += `<div class="xml-collapsible">
  <div class="xml-collapsible-header">
    <span class="xml-collapse-label">📄 ${escHtml(processId)}</span>
    <button class="copy-btn inline" onclick="copyCode('${id}',this)">copy</button>
  </div>
  <pre id="${id}" class="xml-pre-preview"><code class="xml-preview-code">${previewLines}</code>${hasMore ? `<code class="xml-rest-code" style="display:none">
${restLines}</code>` : ''}</pre>
  ${hasMore ? `<button class="xml-show-more" onclick="toggleXmlRest(this)">Show full XML ▾</button>` : ''}
</div>`;
      } else {
        html += `<pre id="${id}"><button class="copy-btn" onclick="copyCode('${id}',this)">copy</button><code>${escapedCode}</code></pre>`;
      }
    }
  }

  // Deduplicate: if multiple XML blocks share the same <id>, keep only the last.
  // This handles the common LLM pattern of emitting a draft then a corrected version.
  const seen = new Map(); // processId -> index in xmlBlocks
  xmlBlocks.forEach((xml, i) => {
    const m = xml.match(/<id>([^<]+)<\/id>/);
    if (m) seen.set(m[1].trim(), i);
  });
  const dedupedXmlBlocks = xmlBlocks.filter((_, i) =>
      [...seen.values()].includes(i)
  );

  return { html, xmlBlocks: dedupedXmlBlocks };
}

function escHtml(s) { return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;'); }

function formatXml(xml) {
  try {
    const doc = new DOMParser().parseFromString(xml.trim(), 'application/xml');
    if (doc.querySelector('parsererror')) return xml;
    return serializeXml(doc.documentElement, 0);
  } catch(e) { return xml; }
}

function serializeXml(node, depth) {
  const I = '  ', pad = I.repeat(depth);
  if (node.nodeType === Node.TEXT_NODE) { const t = node.textContent.trim(); return t ? pad + t : ''; }
  if (node.nodeType === Node.CDATA_SECTION_NODE) {
    const lines = node.nodeValue.split('\n').map((l,i) => i===0 ? l.trimEnd() : pad+I+l.trim()).join('\n');
    return `<![CDATA[${lines}]]>`;
  }
  if (node.nodeType === Node.COMMENT_NODE) return `${pad}<!-- ${node.nodeValue.trim()} -->`;
  if (node.nodeType !== Node.ELEMENT_NODE) return '';
  const tag = node.tagName;
  const attrs = Array.from(node.attributes).map(a => ` ${a.name}="${a.value}"`).join('');
  const children = Array.from(node.childNodes);
  const hasEl = children.some(c => c.nodeType === Node.ELEMENT_NODE);
  const hasCdata = children.some(c => c.nodeType === Node.CDATA_SECTION_NODE);
  if (!hasEl && !hasCdata) {
    const text = node.textContent.trim();
    if (!text) return `${pad}<${tag}${attrs}/>`;
    return `${pad}<${tag}${attrs}>${text.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')}</${tag}>`;
  }
  let inner = '';
  for (const child of children) { const r = serializeXml(child, depth+1); if (r) inner += '\n' + r; }
  return `${pad}<${tag}${attrs}>${inner}\n${pad}</${tag}>`;
}

function copyCode(id, btn) {
  const pre = document.getElementById(id);
  const allCode = pre.querySelectorAll('code');
  let fullText = '';
  allCode.forEach(code => fullText += code.textContent);

  navigator.clipboard.writeText(fullText).then(() => {
    btn.textContent = '✓ copied'; btn.classList.add('copied');
    setTimeout(() => { btn.textContent = 'copy'; btn.classList.remove('copied'); }, 2000);
  });
}

// --- Docs panel ---------------------------------------------------------------
let docsLoaded = false;
let docsContent = '';

function openDocs() {
  const overlay = document.getElementById('docs-overlay');
  overlay.classList.add('open');
  document.body.style.overflow = 'hidden';
  if (!docsLoaded) loadDocs();
}

function closeDocs() {
  document.getElementById('docs-overlay').classList.remove('open');
  document.body.style.overflow = '';
}

function handleDocsOverlayClick(e) {
  if (e.target === document.getElementById('docs-overlay')) closeDocs();
}

document.addEventListener('keydown', e => { if (e.key === 'Escape') closeDocs(); });

async function loadDocs() {
  const body = document.getElementById('docs-body');
  try {
    const resp = await fetch('http://localhost:8080/api/guide');
    if (!resp.ok) throw new Error('HTTP ' + resp.status);
    const md = await resp.text();
    docsContent = md;
    docsLoaded = true;

    const renderer = new marked.Renderer();

    renderer.heading = function(text, level, raw) {
      const id = raw
          .toLowerCase()
          .replace(/(\d+)\.(\d+)\s+/g, '$1.$2-')  // "3.1 Roles" -> "3.1-roles" (keep dot in subsection)
          .replace(/^(\d+)\.\s+/g, '$1-')         // "2. Something" -> "2-something" (single digit at start)
          .replace(/[^\w\s.-]/g, '')              // Remove special chars except spaces, dashes, dots
          .replace(/\s+/g, '-')                   // Spaces to dashes
          .replace(/-+/g, '-')                    // Multiple dashes to single
          .replace(/^-|-$/g, '');                 // Trim dashes

      return `<h${level} id="${id}">${text}</h${level}>\n`;
    };

    marked.setOptions({
      breaks: false,
      gfm: true,
      headerIds: false,  // Disable default, use custom renderer
      mangle: false,
      renderer: renderer
    });

    body.innerHTML = marked.parse(md);

    body.querySelectorAll('li').forEach(li => {
      const text = li.textContent;
      const match = text.match(/^(\d+\.\d+)\s+(.+?)(?:\s+—|\s+-|$)/);
      if (match) {
        const [, number, title] = match;
        const id = number + '-' + title.toLowerCase()
            .replace(/[^\w\s-]/g, '')
            .replace(/\s+/g, '-')
            .replace(/-+/g, '-');
        li.innerHTML = `<a href="#${id}" class="anchor-link">${text}</a>`;
      }
    });

    body.querySelectorAll('a[href^="#"]').forEach(link => {
      link.addEventListener('click', function(e) {
        e.preventDefault();
        const href = this.getAttribute('href');
        const targetId = href.slice(1);

        let target = body.querySelector('#' + CSS.escape(targetId));

        if (target) {
          const container = body;
          const containerRect = container.getBoundingClientRect();
          const targetRect = target.getBoundingClientRect();
          const scrollTop = container.scrollTop;
          const offset = targetRect.top - containerRect.top + scrollTop - 20;

          container.scrollTo({ top: offset, behavior: 'smooth' });
        } else {
          console.warn('Anchor target not found:', targetId, 'Available IDs:',
              Array.from(body.querySelectorAll('[id]')).map(el => el.id).slice(0, 10));
        }
      });
    });

    setupBackToTop();
  } catch(e) {
    body.innerHTML = '<div class="docs-error">⚠ Could not load guide from backend.<br><small>' + escHtml(e.message) + '</small><br><br>Make sure the Java backend is running on port 8080.</div>';
  }
}

// --- Upload XML to builder -----------------------------------------------------
async function openInBuilder(xmlContent) {
  const formatted = '<?xml version="1.0" encoding="UTF-8"?>\n' + formatXml(xmlContent);

  try {
    const resp = await fetch('http://localhost:8080/api/upload-xml', {
      method: 'POST',
      headers: { 'Content-Type': 'text/plain' },
      body: formatted
    });

    if (!resp.ok) {
      const err = await resp.json();
      throw new Error(err.error || 'Upload failed');
    }

    const data = await resp.json();
    const builderUrl = 'https://builder.netgrif.cloud/modeler?modelUrl=' + data.url;
    window.open(builderUrl, '_blank');
  } catch(e) {
    downloadXml(xmlContent);
    alert('Could not upload XML for builder (' + e.message + ').\nXML downloaded instead -- you can import it manually in the builder.');
  }
}

// --- XML action buttons builder -----------------------------------------------
function getXmlProcessName(xmlContent) {
  const idMatch = xmlContent.match(/<id>([^<]+)<\/id>/);
  const titleMatch = xmlContent.match(/<title>([^<]+)<\/title>/);
  if (titleMatch) return titleMatch[1].trim();
  if (idMatch) return idMatch[1].trim();
  return 'Process';
}

function buildXmlActions(xmlBlocks) {
  const wrapper = document.createElement('div');
  wrapper.className = 'xml-actions-wrapper';

  xmlBlocks.forEach((xmlContent) => {
    const row = document.createElement('div');
    row.className = 'xml-actions-row';

    const label = document.createElement('span');
    label.className = 'xml-process-label';
    label.textContent = getXmlProcessName(xmlContent);

    const dlBtn = document.createElement('button');
    dlBtn.className = 'xml-btn primary';
    dlBtn.innerHTML = '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg> Download XML';
    dlBtn.onclick = () => downloadXml(xmlContent);

    const builderBtn = document.createElement('button');
    builderBtn.className = 'xml-btn open-builder';
    builderBtn.innerHTML = '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/><polyline points="15 3 21 3 21 9"/><line x1="10" y1="14" x2="21" y2="3"/></svg> Open in Builder';
    builderBtn.onclick = async () => {
      builderBtn.disabled = true;
      builderBtn.innerHTML = '⏳ Uploading…';
      await openInBuilder(xmlContent);
      builderBtn.disabled = false;
      builderBtn.innerHTML = '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/><polyline points="15 3 21 3 21 9"/><line x1="10" y1="14" x2="21" y2="3"/></svg> Open in Builder';
    };

    const etaskBtn = document.createElement('button');
    etaskBtn.className = 'xml-btn secondary';
    etaskBtn.innerHTML = '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polygon points="5 3 19 12 5 21 5 3"/></svg> Open in eTask';
    etaskBtn.onclick = async function() {
      etaskBtn.disabled = true;
      etaskBtn.innerHTML = '⏳ Uploading…';
      await uploadAndOpenEtask(xmlContent, etaskBtn);
    };

    var btns = document.createElement('div');
    btns.className = 'xml-actions-btns';
    btns.appendChild(dlBtn);
    btns.appendChild(builderBtn);
    btns.appendChild(etaskBtn);
    row.appendChild(label);
    row.appendChild(btns);
    wrapper.appendChild(row);
  });

  return wrapper;
}

function downloadXml(xmlContent) {
  const match = xmlContent.match(/<id>([^<]+)<\/id>/);
  const filename = (match ? match[1].trim() : 'petriflow_process') + '.xml';
  const blob = new Blob([`<?xml version="1.0" encoding="UTF-8"?>\n` + formatXml(xmlContent)], { type: 'application/xml' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a'); a.href = url; a.download = filename; a.click();
  URL.revokeObjectURL(url);
}

// --- Back to top button -------------------------------------------------------
function setupBackToTop() {
  const docsBody = document.getElementById('docs-body');
  const btn = document.getElementById('back-to-top-btn');
  if (!docsBody || !btn) return;

  docsBody.addEventListener('scroll', function() {
    if (docsBody.scrollTop > 300) {
      btn.classList.add('visible');
    } else {
      btn.classList.remove('visible');
    }
  });

  btn.addEventListener('click', function() {
    docsBody.scrollTo({ top: 0, behavior: 'smooth' });
  });
}

// --- Scroll to top/bottom buttons -------------------------------------------
function scrollToTop() {
  const msgs = document.getElementById('messages');
  msgs.scrollTo({ top: 0, behavior: 'smooth' });
}

function scrollToBottom() {
  const msgs = document.getElementById('messages');
  msgs.scrollTo({ top: msgs.scrollHeight, behavior: 'smooth' });
}

// Show/hide scroll buttons based on scroll position
function updateScrollButtons() {
  const msgs = document.getElementById('messages');
  const scrollTop = msgs.scrollTop;
  const scrollHeight = msgs.scrollHeight;
  const clientHeight = msgs.clientHeight;

  const topBtn = document.getElementById('scroll-top');
  const bottomBtn = document.getElementById('scroll-bottom');

  if (scrollTop > 300) {
    topBtn.classList.add('visible');
  } else {
    topBtn.classList.remove('visible');
  }

  if (scrollHeight - scrollTop - clientHeight > 300) {
    bottomBtn.classList.add('visible');
  } else {
    bottomBtn.classList.remove('visible');
  }
}

// Toggle full XML visibility
function toggleXmlRest(btn) {
  const pre = btn.previousElementSibling;
  const rest = pre.querySelector('.xml-rest-code');
  if (!rest) return;
  const isHidden = rest.style.display === 'none';
  rest.style.display = isHidden ? 'inline' : 'none';
  btn.textContent = isHidden ? 'Collapse ▴' : 'Show full XML ▾';

  if (isHidden) {
    // Expanding - show scrollbar
    pre.style.maxHeight = '500px';
    pre.style.overflowY = 'auto';
  } else {
    // Collapsing - hide scrollbar
    pre.style.maxHeight = '7em';
    pre.style.overflowY = 'hidden';
  }
}

// Initialize scroll button listeners
document.addEventListener('DOMContentLoaded', function() {
  const msgs = document.getElementById('messages');
  if (msgs) {
    msgs.addEventListener('scroll', updateScrollButtons);
    updateScrollButtons();
  }
});

// ── Settings modal ─────────────────────────────────────────────────────────

var settingsLoaded = false;

function openSettings() {
  document.getElementById('settings-overlay').classList.add('open');
  document.body.style.overflow = 'hidden';
  if (!settingsLoaded) loadSettings();
}

function closeSettings() {
  document.getElementById('settings-overlay').classList.remove('open');
  document.body.style.overflow = '';
}

function handleSettingsOverlayClick(e) {
  if (e.target.id === 'settings-overlay') closeSettings();
}

async function loadSettings() {
  try {
    var resp = await fetch('http://localhost:8080/api/settings');
    if (!resp.ok) return;
    var s = await resp.json();
    fillSettingsForm(s);
    settingsLoaded = true;
  } catch (e) {
    console.error('Failed to load settings:', e);
  }
}

function fillSettingsForm(s) {
  setInput('s-anthropicApiKey', s.anthropicApiKey || '');
  setInput('s-openaiApiKey',    s.openaiApiKey    || '');
  setInput('s-geminiApiKey',    s.geminiApiKey    || '');
  setInput('s-githubToken',     s.githubToken     || '');
  setInput('s-githubUsername',  s.githubUsername  || '');
  setInput('s-githubRepo',      s.githubRepo      || '');
  setInput('s-eTaskEmail',    s.eTaskEmail    || '');
  setInput('s-eTaskPassword', s.eTaskPassword ? '••••••••' : '');
  setInput('s-claudeModel',     s.claudeModel     || '');
  setInput('s-openaiModel',     s.openaiModel     || '');
  setInput('s-geminiModel',     s.geminiModel     || '');
  setInput('s-claudeMaxTokens',      s.claudeMaxTokens      != null ? s.claudeMaxTokens      : '');
  setInput('s-openaiMaxTokens',      s.openaiMaxTokens      != null ? s.openaiMaxTokens      : '');
  setInput('s-geminiMaxTokens',      s.geminiMaxTokens      != null ? s.geminiMaxTokens      : '');
  setInput('s-claudeThinkingBudget', s.claudeThinkingBudget != null ? s.claudeThinkingBudget : '');
  setInput('s-geminiThinkingBudget', s.geminiThinkingBudget != null ? s.geminiThinkingBudget : '');
  setInput('s-ragTopK',              s.ragTopK              != null ? s.ragTopK              : '');
  setInput('s-ragAlwaysInclude',     s.ragAlwaysInclude     || '');

  var thinking = document.getElementById('s-claudeThinkingEnabled');
  if (thinking) thinking.checked = !!s.claudeThinkingEnabled;

  var embed = document.getElementById('s-embedProvider');
  if (embed && s.embedProvider) embed.value = s.embedProvider;

  updateKeyStatus('ks-claude',  s.anthropicApiKey || '');
  updateKeyStatus('ks-openai',  s.openaiApiKey    || '');
  updateKeyStatus('ks-gemini',  s.geminiApiKey    || '');
  updateKeyStatus('ks-github',  s.githubToken     || '');
  // eTask: email is plain text (not masked), so synthesize a masked-style string for updateKeyStatus
  updateKeyStatus('ks-etask', s.eTaskEmail && s.eTaskEmail.length > 0 ? 'configured***' : '');

  updateSettingsBadge(s);
}

function setInput(id, value) {
  var el = document.getElementById(id);
  if (el) el.value = String(value);
}

function updateKeyStatus(id, value) {
  var el = document.getElementById(id);
  if (!el) return;
  var isSet = value && value.indexOf('***') !== -1;
  el.className = 'key-status ' + (isSet ? 'set' : 'unset');
  el.title = isSet ? 'Key is configured' : 'Not configured';
}

function updateSettingsBadge(s) {
  var badge = document.getElementById('settings-badge');
  if (!badge) return;
  var hasAny = (s.anthropicApiKey && s.anthropicApiKey.indexOf('***') !== -1) ||
      (s.openaiApiKey    && s.openaiApiKey.indexOf('***')    !== -1) ||
      (s.geminiApiKey    && s.geminiApiKey.indexOf('***')    !== -1);
  badge.style.display = hasAny ? 'none' : 'block';
}

async function saveSettings() {
  var btn = document.querySelector('.btn-settings-save');
  var status = document.getElementById('settings-save-status');
  btn.disabled = true;
  status.textContent = 'Saving…';
  status.className = 'settings-save-status';

  var payload = {};
  var fields = [
    's-anthropicApiKey', 's-openaiApiKey', 's-geminiApiKey',
    's-githubToken', 's-githubUsername', 's-githubRepo',
    's-claudeModel', 's-openaiModel', 's-geminiModel',
    's-ragAlwaysInclude', 's-embedProvider',
    's-eTaskEmail'
  ];
  var keyMap = {
    's-anthropicApiKey': 'anthropicApiKey', 's-openaiApiKey': 'openaiApiKey',
    's-geminiApiKey': 'geminiApiKey', 's-githubToken': 'githubToken',
    's-githubUsername': 'githubUsername', 's-githubRepo': 'githubRepo',
    's-claudeModel': 'claudeModel', 's-openaiModel': 'openaiModel',
    's-geminiModel': 'geminiModel', 's-ragAlwaysInclude': 'ragAlwaysInclude',
    's-embedProvider': 'embedProvider',
    's-eTaskEmail': 'eTaskEmail'
  };
  fields.forEach(function(id) {
    var el = document.getElementById(id);
    if (el && el.value !== '') payload[keyMap[id]] = el.value;
  });

  // eTask password — only send if user typed something new (not placeholder dots)
  var eTaskPwEl = document.getElementById('s-eTaskPassword');
  if (eTaskPwEl && eTaskPwEl.value !== '' && eTaskPwEl.value.indexOf('•') === -1) {
    payload['eTaskPassword'] = eTaskPwEl.value;
  }

  var numFields = {
    's-claudeMaxTokens': 'claudeMaxTokens', 's-openaiMaxTokens': 'openaiMaxTokens',
    's-geminiMaxTokens': 'geminiMaxTokens', 's-claudeThinkingBudget': 'claudeThinkingBudget',
    's-geminiThinkingBudget': 'geminiThinkingBudget', 's-ragTopK': 'ragTopK'
  };
  Object.keys(numFields).forEach(function(id) {
    var el = document.getElementById(id);
    if (el && el.value !== '') payload[numFields[id]] = Number(el.value);
  });

  var thinkingEl = document.getElementById('s-claudeThinkingEnabled');
  if (thinkingEl) payload['claudeThinkingEnabled'] = thinkingEl.checked;

  try {
    var resp = await fetch('http://localhost:8080/api/settings', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    });
    if (!resp.ok) {
      var err = await resp.json().catch(function() { return {}; });
      throw new Error(err.error || ('HTTP ' + resp.status));
    }
    settingsLoaded = false;
    await loadSettings();
    status.textContent = '✓ Saved successfully';
    status.className = 'settings-save-status ok';
    setTimeout(function() { status.textContent = ''; status.className = 'settings-save-status'; }, 3000);
  } catch (e) {
    status.textContent = '✗ ' + e.message;
    status.className = 'settings-save-status err';
  }
  btn.disabled = false;
}

// ── Upload & Open in eTask ────────────────────────────────────────────────

async function uploadAndOpenEtask(xmlContent, btn) {
  var formatted = '<?xml version="1.0" encoding="UTF-8"?>\n' + formatXml(xmlContent);
  try {
    var resp = await fetch('http://localhost:8080/api/upload-etask', {
      method: 'POST',
      headers: { 'Content-Type': 'text/plain' },
      body: formatted
    });
    var data = await resp.json().catch(function() { return {}; });
    if (!resp.ok) {
      var msg = data.error || ('HTTP ' + resp.status);
      alert('eTask upload failed: ' + msg);
      btn.disabled = false;
      btn.innerHTML = '▶ Upload & Open in eTask';
      return;
    }
    // Open eTask cases page directly — if not logged in, eTask will redirect to login
    // and return to /portal/cases after successful authentication.
    window.open('https://etask.netgrif.cloud/portal/cases', '_blank');
    btn.disabled = false;
    btn.innerHTML = '▶ Upload & Open in eTask';
  } catch (e) {
    alert('eTask upload error: ' + e.message);
    btn.disabled = false;
    btn.innerHTML = '▶ Upload & Open in eTask';
  }
}

// Load settings on startup to show/hide badge
(async function initSettings() {
  try {
    var resp = await fetch('http://localhost:8080/api/settings');
    if (resp.ok) {
      var s = await resp.json();
      updateSettingsBadge(s);
    }
  } catch (_) {}
})();