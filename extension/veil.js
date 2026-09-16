// ページの中から「消しもの」を消す係。
//
// この拡張の他の部分と同じで、**何を消すかは決めない**。本体が返した veil の
// とおりに消すだけ。判定を持たないので、アプリでは止まるのにブラウザでは
// 止まらない、が起きない。
//
// ## なぜ覆わずに消すのか
//
// 本体の「音だけにする」は全画面を覆うやり方だが、ブラウザではそれが使えない。
// 作業の資料として動画を鳴らしたいのに、検索欄も他のタブも一緒に覆われては
// 用途そのものが潰れる。だからここでは**動画の絵だけ**を消す ── 音は鳴り続け、
// サムネイルも検索結果も見える。
//
// ## 消し方
//
// video を opacity:0 にする。display:none や pause と違い、**再生も音も止まらない**。
// 止めてしまうと「ラジオにする」ではなく「見られなくする」になる。

const STYLE_ID = 'dopa-veil-style';
const NOTE_ID = 'dopa-veil-note';

// 直前の答えを覚えておく置き場。document_start で描かれる前に当てるために使う。
// 古いものを当て続けないよう、時刻も一緒に持つ
const CACHE_KEY = 'veilCache';
const CACHE_FRESH_MS = 5 * 60 * 1000;

let applied = null;

// ---- 消す中身 --------------------------------------------------------

// 映像だけ。サムネイル(静止画)はここに入れない ── 見て選ぶ手がかりは残す
const VIDEO_RULES = `
  video { opacity: 0 !important; }
  /* 進捗バーに出るコマ送りのプレビュー */
  .ytp-tooltip-bg, .ytp-storyboard-framepreview { background-image: none !important; }
  /* 画面の外に滲ませる「シネマティック」。映像の色をそのまま映す */
  #cinematics, .ytp-cinematics-container { display: none !important; }
`;

const SUGGESTION_RULES = `
  /* YouTube: ホームの一覧・横の関連・終わりぎわのカード */
  ytd-browse[page-subtype="home"] ytd-rich-grid-renderer,
  ytd-watch-next-secondary-results-renderer,
  #related,
  ytd-reel-shelf-renderer,
  ytd-rich-shelf-renderer[is-shorts],
  ytd-compact-video-renderer,
  .ytp-endscreen-content,
  .ytp-ce-element,
  .ytp-autonav-endscreen-upnext-container { display: none !important; }

  /* niconico: React の Recommend 一式 */
  [data-name="recommend"],
  [class*="Recommend"] { display: none !important; }
`;

const SEARCH_ONLY_RULES = `
  /* ホーム・急上昇。検索の結果は消さない */
  ytd-browse[page-subtype="home"] #contents,
  ytd-browse[page-subtype="trending"] #contents,
  ytd-browse[page-subtype="explore"] #contents { display: none !important; }

  /* niconico のトップとランキング */
  [class*="RankingMatrix"], [class*="TopPageContainer"] { display: none !important; }
`;

function css(veil) {
  let out = '';
  if (veil.video) out += VIDEO_RULES;
  if (veil.suggestions) out += SUGGESTION_RULES;
  if (veil.searchOnly) out += SEARCH_ONLY_RULES;
  return out;
}

// ---- 当てる / 外す ---------------------------------------------------

function apply(veil) {
  const next = veil && (veil.video || veil.suggestions || veil.searchOnly) ? veil : null;
  applied = next;

  let style = document.getElementById(STYLE_ID);
  if (!next) {
    if (style) style.remove();
    removeNote();
    return;
  }

  if (!style) {
    style = document.createElement('style');
    style.id = STYLE_ID;
    // head がまだ無いこともある(document_start)。あるほうへ挿す
    (document.head || document.documentElement).appendChild(style);
  }
  style.textContent = css(next);

  showNote(next.message);
  if (next.searchOnly) leaveShorts();
}

/**
 * 消したことを小さく知らせる札。
 *
 * 黙って消すと「壊れた」と思って設定を触りに行くことになる。
 * 誰が消したのかが分かれば、そこで一度立ち止まれる。
 */
function showNote(message) {
  if (!document.body) return;
  let note = document.getElementById(NOTE_ID);
  if (!note) {
    note = document.createElement('div');
    note.id = NOTE_ID;
    note.style.cssText = [
      'position:fixed', 'left:12px', 'bottom:12px', 'z-index:2147483647',
      'padding:6px 10px', 'border-radius:8px',
      'background:rgba(20,20,28,.82)', 'color:#e8e8ef',
      'font:12px/1.4 system-ui,sans-serif', 'pointer-events:none',
      'max-width:40vw',
    ].join(';');
    document.body.appendChild(note);
  }
  note.textContent = message ? 'ドパチル: ' + message : 'ドパチル: 音だけにしています';
}

function removeNote() {
  const note = document.getElementById(NOTE_ID);
  if (note) note.remove();
}

/**
 * ショートは同じ動画を普通の再生ページへ移す。
 *
 * 塞がずに移すのは、見ようとしたものを取り上げないため ── 取り上げられるのは
 * **次から次へ流れてくる縦の列**のほうで、そこが無くなれば1本見て終わる。
 */
function leaveShorts() {
  const m = location.pathname.match(/^\/shorts\/([\w-]+)/);
  if (m) location.replace('https://www.youtube.com/watch?v=' + m[1]);
}

// ---- 本体に聞く ------------------------------------------------------

function ask() {
  try {
    chrome.runtime.sendMessage({ type: 'veil?' }, (answer) => {
      if (chrome.runtime.lastError) return; // 背景がまだ寝ている
      if (answer && 'veil' in answer) apply(answer.veil);
    });
  } catch (e) {
    // 拡張が入れ替わった直後。次の報せで直る
  }
}

// 描かれる前に、前回の答えを当てておく。一瞬だけ映像が見えるのを防ぐ
try {
  chrome.storage.local.get([CACHE_KEY], (s) => {
    const cached = s && s[CACHE_KEY];
    if (!applied && cached && Date.now() - (cached.at || 0) < CACHE_FRESH_MS) {
      apply(cached.veil);
    }
    ask();
  });
} catch (e) {
  ask();
}

chrome.runtime.onMessage.addListener((msg) => {
  if (msg && msg.type === 'veil') apply(msg.veil);
});

// body ができてから札を出し直す(document_start では body がまだ無い)
document.addEventListener('DOMContentLoaded', () => {
  if (applied) apply(applied);
});

// YouTube はページを読み直さずに移る。移った先でも当て直す
document.addEventListener('yt-navigate-finish', () => {
  if (applied) apply(applied);
  ask();
});

let lastPath = location.pathname;
setInterval(() => {
  if (location.pathname === lastPath) return;
  lastPath = location.pathname;
  if (applied) apply(applied);
  ask();
}, 1000);
