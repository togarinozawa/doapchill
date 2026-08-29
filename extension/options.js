const statusEl = document.getElementById('status');

function show(text, cls) {
  statusEl.textContent = text;
  statusEl.className = 'status ' + (cls || '');
}

document.getElementById('pair').addEventListener('click', async () => {
  show('つないでいます…');
  const res = await chrome.runtime.sendMessage({ type: 'pair' });
  if (res && res.ok) {
    show('つながりました(ポート ' + res.port + ')', 'ok');
    return;
  }
  const why = {
    not_pairing: '本体は動いていますが、まだ「つなぐ」が押されていません。本体の設定で押してから2分以内にもう一度。',
    not_found: 'ドパチル本体が見つかりません。起動しているか確認してください。',
    unreachable: '本体に届きませんでした。',
  };
  show((res && why[res.error]) || '失敗しました', 'ng');
});

document.getElementById('check').addEventListener('click', async () => {
  const res = await chrome.runtime.sendMessage({ type: 'status' });
  if (!res || !res.online) {
    show('本体が見つかりません', 'ng');
  } else if (!res.paired) {
    show('本体は動いていますが、まだ繋いでいません', 'ng');
  } else {
    show('つながっています(ポート ' + res.port + ')', 'ok');
  }
});

const offline = document.getElementById('offline');
chrome.storage.local.get(['blockWhenOffline']).then((s) => {
  offline.checked = !!s.blockWhenOffline;
});
offline.addEventListener('change', () => {
  chrome.storage.local.set({ blockWhenOffline: offline.checked });
});
