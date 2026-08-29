// ドパチル拡張の本体。
//
// この拡張は「何を止めるか」を持たない。見ているページを本体に伝え、
// 返ってきた答えのとおりに動くだけ。ルールを二重に持つと、
// アプリでは止まるのにブラウザでは止まらない、がすぐ起きる。
//
// 本体がしてくれないことが1つだけある ── 全画面を被せても
// 裏のタブの音は止まらないので、そこだけこちらでタブを退避させる。

const PORTS = [48731, 48732, 48733, 48734]; // LocalBridge.PORTS と揃えること
const HEARTBEAT_MINUTES = 0.5;
const BLOCKED_PAGE = chrome.runtime.getURL('blocked.html');

let state = {
  token: '',
  port: 0,
  online: false,
  blockWhenOffline: false,
};

// 塞がれて退避させたタブ。開放されたら元の場所へ戻す
const parked = new Map(); // tabId -> original url

async function loadState() {
  const s = await chrome.storage.local.get(['token', 'port', 'blockWhenOffline']);
  state.token = s.token || '';
  state.port = s.port || 0;
  state.blockWhenOffline = !!s.blockWhenOffline;
}

function endpoint(path, port) {
  return 'http://127.0.0.1:' + (port || state.port) + path;
}

async function call(path, body, port) {
  const res = await fetch(endpoint(path, port), {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Dopa-Token': state.token,
    },
    body: JSON.stringify(body || {}),
  });
  if (!res.ok) throw new Error('http ' + res.status);
  return res.json();
}

// 本体がどのポートに居るかを探す。掴めたポートは覚えておく
async function findPort() {
  const order = state.port ? [state.port, ...PORTS.filter((p) => p !== state.port)] : PORTS;
  for (const p of order) {
    try {
      await call('/ping', {}, p);
      if (p !== state.port) {
        state.port = p;
        await chrome.storage.local.set({ port: p });
      }
      return p;
    } catch (e) {
      // 次のポートへ
    }
  }
  return 0;
}

// ---- 判定を頼む ------------------------------------------------------

async function askAbout(url) {
  if (!state.token) return { ok: false };
  try {
    const verdict = await call('/url', { url });
    state.online = true;
    return { ok: true, blocked: !!verdict.blocked, reason: verdict.reason || '' };
  } catch (e) {
    // ポートが変わったのかもしれない。一度だけ探し直す
    const p = await findPort();
    if (!p) {
      state.online = false;
      return { ok: false };
    }
    try {
      const verdict = await call('/url', { url });
      state.online = true;
      return { ok: true, blocked: !!verdict.blocked, reason: verdict.reason || '' };
    } catch (e2) {
      state.online = false;
      return { ok: false };
    }
  }
}

function isWatchable(url) {
  return typeof url === 'string' && (url.startsWith('http://') || url.startsWith('https://'));
}

async function park(tabId, url, reason) {
  if (parked.has(tabId)) return;
  parked.set(tabId, url);
  const to = BLOCKED_PAGE + '?u=' + encodeURIComponent(url) + '&r=' + encodeURIComponent(reason || '');
  try {
    await chrome.tabs.update(tabId, { url: to });
  } catch (e) {
    parked.delete(tabId);
  }
}

async function unpark(tabId) {
  const original = parked.get(tabId);
  if (!original) return;
  parked.delete(tabId);
  try {
    await chrome.tabs.update(tabId, { url: original });
  } catch (e) {
    // タブが閉じられていた
  }
}

// いま前面にあるタブの URL を本体に伝える
async function reportActiveTab() {
  let tab;
  try {
    const tabs = await chrome.tabs.query({ active: true, lastFocusedWindow: true });
    tab = tabs[0];
  } catch (e) {
    return;
  }

  // 退避ページを見ている最中は、元の URL について聞き続ける。
  // そうしないと「退避ページは対象外なので開放」と判断されて往復する
  let url = tab && tab.url;
  if (tab && parked.has(tab.id)) url = parked.get(tab.id);

  if (!isWatchable(url)) {
    // ブラウザの内部ページ。本体には「URL は無い」と伝える
    await askAbout(null);
    return;
  }

  const answer = await askAbout(url);

  if (!answer.ok) {
    // 本体に届かない。既定は通す ── 本体が落ちただけで
    // ブラウザが使えなくなるほうが、取り返しがつかない
    if (state.blockWhenOffline && tab && !parked.has(tab.id)) {
      await park(tab.id, url, '本体につながりません');
    }
    return;
  }

  if (answer.blocked) {
    if (tab) await park(tab.id, url, answer.reason);
  } else if (tab && parked.has(tab.id)) {
    await unpark(tab.id);
  }
}

// ---- きっかけ --------------------------------------------------------

chrome.runtime.onStartup.addListener(init);
chrome.runtime.onInstalled.addListener(init);

async function init() {
  await loadState();
  await findPort();
  chrome.alarms.create('heartbeat', { periodInMinutes: HEARTBEAT_MINUTES });
  reportActiveTab();
}

chrome.alarms.onAlarm.addListener((a) => {
  if (a.name === 'heartbeat') reportActiveTab();
});

chrome.tabs.onActivated.addListener(() => reportActiveTab());
chrome.windows.onFocusChanged.addListener(() => reportActiveTab());

chrome.tabs.onUpdated.addListener((tabId, info, tab) => {
  if (!info.url && info.status !== 'complete') return;
  if (!tab.active) return;
  reportActiveTab();
});

chrome.tabs.onRemoved.addListener((tabId) => parked.delete(tabId));

// 退避ページと設定画面からの問い合わせ
chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  (async () => {
    await loadState();
    if (msg.type === 'recheck') {
      const answer = await askAbout(msg.url);
      // 開放されたら退避を解いて元の場所へ戻す
      if (answer.ok && !answer.blocked && sender.tab) {
        parked.delete(sender.tab.id);
      }
      sendResponse(answer);
      return;
    }
    if (msg.type === 'pair') {
      const port = await findPortForPairing();
      sendResponse(await pair(port));
      return;
    }
    if (msg.type === 'status') {
      const port = await findPort();
      sendResponse({ port, paired: !!state.token, online: !!port });
      return;
    }
    sendResponse({});
  })();
  return true; // 非同期で返す
});

// つなぐときは合言葉をまだ持っていないので、/ping ではなく /pair で当たりを探す
async function findPortForPairing() {
  for (const p of PORTS) {
    try {
      const res = await fetch(endpoint('/pair', p), { method: 'POST' });
      // 409 = 本体は居るが窓が閉じている。それも「見つかった」に数える
      if (res.ok || res.status === 409) return p;
    } catch (e) {
      // 次へ
    }
  }
  return 0;
}

async function pair(port) {
  if (!port) return { ok: false, error: 'not_found' };
  try {
    const res = await fetch(endpoint('/pair', port), { method: 'POST' });
    if (res.status === 409) return { ok: false, error: 'not_pairing' };
    if (!res.ok) return { ok: false, error: 'http_' + res.status };
    const body = await res.json();
    state.token = body.token;
    state.port = port;
    await chrome.storage.local.set({ token: body.token, port });
    reportActiveTab();
    return { ok: true, port };
  } catch (e) {
    return { ok: false, error: 'unreachable' };
  }
}

init();
