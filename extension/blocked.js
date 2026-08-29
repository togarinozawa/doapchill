// 退避先のページ。
//
// ここには押し切るボタンを置かない。続ける判断は本体の画面で
// (反省文・待ち時間・ポイントの代金つきで)させる。
// ここに簡単な出口を作ると、そちらが使われて本体の仕組みが素通りになる。

const params = new URLSearchParams(location.search);
const original = params.get('u') || '';
const reason = params.get('r') || '';

document.getElementById('reason').textContent = reason;
document.getElementById('url').textContent = original;

// 本体で押し切られた/時間が来た、を拾って元の場所へ戻す
async function poll() {
  if (!original) return;
  try {
    const answer = await chrome.runtime.sendMessage({ type: 'recheck', url: original });
    if (answer && answer.ok && !answer.blocked) {
      location.replace(original);
      return;
    }
    document.getElementById('waiting').textContent =
      answer && answer.ok ? '本体の判定を待っています…' : '本体につながりません';
  } catch (e) {
    document.getElementById('waiting').textContent = '本体につながりません';
  }
  setTimeout(poll, 2000);
}

poll();
