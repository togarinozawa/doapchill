/**
 * ドパチルの端末間同期(Cloudflare Workers + D1)。
 *
 * ## 前提が1つだけあります
 *
 * **同期は設定を配るだけで、取り締まりには関わりません。**
 * 各端末はローカルのルールで判定するので、ここが落ちていても、圏外でも、
 * 機内モードでも制限は効いたままです。ここが落ちて起きるのは
 * 「別の端末の予約や連動がまだ届かない」だけ。
 *
 * **逆に言えば、ここに判定を持たせてはいけません。**
 * ネットが切れれば外れる制限は、機内モードにするだけで抜けられる制限です。
 *
 * ## なぜ Workers なのか
 *
 * 前身は自宅の機械で pm2 が回していましたが、畳まれたことに2週間気づきませんでした。
 * 「動かし続けなければ死ぬもの」を挟まない置き場所にしてあります。
 *
 * ## Express 版との違い
 *
 * D1 には対話的なトランザクションがありません。代わりに batch() が
 * ひとまとまりで順に実行されるので、**版数の繰り上げと書き込みを1つの batch に入れて**、
 * 各行の rev はサブクエリで引かせています。事前の読み出しが要らなくなったぶん、
 * 元の実装より競合に強くなっています。
 *
 * 衝突解決も SQL 側に移しました。読んでから書くまでの隙間が無くなります。
 */

/**
 * 同期する種類。増やすときはここに足すだけ(スキーマは変わらない)。
 *
 * `apps` は「識別子 → 人が読む名前」の対応表です。実績には
 * `com.twitter.android` や `chrome.exe` しか入っていないので、
 * **これが無いと別の端末の実績を読める形で出せません。**
 * 実績の中に名前を埋めないのは、同じ名前を日数ぶん繰り返すことになるためです。
 */
const KINDS = [
  'tags',
  'gates',
  'changeRequests',
  'apps',
  // 端末の名簿。どの端末があるかを知るため(「PC に頼む」の PC を選べるように)
  'devices',
  // 予約。決める端末と使う端末が別でよいので、端末をまたいで配る
  'reservations',
  // 別の端末への頼みごと。**配るのは頼みであって実行ではない** ──
  // やるかどうかは受け取った端末が自分の関門で決める
  'commands',
  // 「そのルールが、その端末で、いま効いているか」。
  // ルールを配っても**使った時間は配られない**ので、スマホで持ち時間を
  // 使い切っても PC では数え直しになる。効いているという事実のほうを配る
  'ruleStates',
  // 端末ごとのルールの名札。ルールそのものは配らないので、ほかの端末のルールを
  // 予約や連動で指すにはこれが要る。鍵は deviceId で、1台ぶんをまるごと置き換える
  'ruleCatalogs',
];

/**
 * 最初の利用者(持ち主)。元の合言葉(DOPA_TOKEN)はこの利用者として扱う。
 *
 * 利用者は複数いる。**どの行も user_id で分けてあり、合言葉から引いた利用者の行しか
 * 読み書きしない** ── 配った相手の端末が、持ち主の予約や頼みごとに触れないように。
 */
const OWNER_USER_ID = 1;

/**
 * 1日に作れる新しい利用者の数。荒らしで D1 が埋まるのを止めるだけの上限。
 * IP は覚えない(配った相手の居場所を記録したくない)ので、全体の数で絞る。
 */
const SIGNUPS_PER_DAY = 20;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, '') || '/';

    if (request.method === 'OPTIONS') return cors(new Response(null, { status: 204 }));

    // 疎通だけは合言葉なしで答える。繋がらないのか弾かれたのかを、
    // 端末側で区別できないと切り分けができない
    if (path === '/health') {
      return cors(json({ ok: true, service: 'dopachiru-sync' }));
    }

    // 短い合言葉を引き換える口。**合言葉を持っていない端末が叩く**ので、
    // ここだけは認証の前に置く。詳しくは claimInvite を見ること
    if (path === '/invite/claim' && request.method === 'POST') {
      return cors(await claimInvite(request, env));
    }

    // 新しい版があるかを答える口。**繋いでいない端末も叩く**ので認証の前。
    // 版を知るのに合言葉が要ると、まだ繋いでいない端末が置き去りになる
    if (path === '/version' && request.method === 'GET') {
      return cors(await latestVersions(request, env));
    }

    // 新しい利用者として始める口。**合言葉をまだ持っていない端末が叩く**ので認証の前。
    // 誰でも叩けるが、作られるのは空の区画で、ほかの利用者の行には届かない
    if (path === '/signup' && request.method === 'POST') {
      return cors(await signup(request, env));
    }

    const auth = await authorized(request, env);
    if (!auth) {
      return cors(json({ error: 'unauthorized' }, 401));
    }

    if (path === '/invite/new' && request.method === 'POST') {
      return cors(await newInvite(env, auth));
    }

    try {
      if (path === '/ping' && request.method === 'GET') return cors(await ping(env, auth));
      if (path === '/sync' && request.method === 'POST') return cors(await sync(request, env, auth));
      if (path === '/usage' && request.method === 'POST') return cors(await putUsage(request, env, auth));
      if (path === '/usage' && request.method === 'GET') return cors(await getUsage(url, env, auth));
      if (path === '/account/delete' && request.method === 'POST') {
        return cors(await deleteAccount(env, auth));
      }
      return cors(json({ error: 'not_found' }, 404));
    } catch (err) {
      console.error('dopachiru-sync', path, err && err.message);
      return cors(json({ error: 'server_error' }, 500));
    }
  },
};

// ---- 門番 -----------------------------------------------------------------

/**
 * 合言葉から「誰の、どの端末か」を引く。引けなければ null。
 *
 * 返した userId で**すべての読み書きを絞る**。ここを通さずに行を触る口を作ると、
 * 配った相手が持ち主の行を読めるようになる。
 *
 * 長さの違いで漏れないよう固定時間で比べます。合言葉を設定していなければ
 * **全部断る** ── 空と空が一致して素通しになるほうが危ない。
 */
async function authorized(request, env) {
  const given = (request.headers.get('authorization') || '').replace(/^Bearer\s+/i, '');
  if (!given) return null;

  // 元の合言葉。持ち主が手で入れた端末のためだけに残してある
  const expected = env.DOPA_TOKEN || '';
  if (expected && timingSafeEqual(expected, given)) return { userId: OWNER_USER_ID, deviceId: '' };

  // 端末ごとに配った合言葉。**止められる**のが元の合言葉との違い。
  // 見慣れない名前が名簿に出たら、その行の revoked を立てれば締め出せる
  const row = await env.DB.prepare(
    'SELECT user_id, device_id FROM dopachiru_device_tokens WHERE token = ? AND revoked = 0',
  )
    .bind(given)
    .first();
  if (!row) return null;

  // 最後に来た時刻。使われていない行を見分けるため(消す判断は手で)
  await env.DB.prepare(
    'UPDATE dopachiru_device_tokens SET last_seen_at = ? WHERE user_id = ? AND token = ?',
  )
    .bind(Date.now(), row.user_id, given)
    .run();
  return { userId: row.user_id, deviceId: row.device_id };
}

function timingSafeEqual(a, b) {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

// ---- 短い合言葉(招待コード) -----------------------------------------------
//
// 本物の合言葉は48文字ある。端末を増やすたびにこれを打ち込むのは現実的でないので、
// **すでに繋がっている端末**が短いコードを1つ登録し、新しい端末はそれと引き換えに
// 本物を受け取る。QR と同じことを、カメラも権限も使わずにやる。
//
// 危ないのは、引き換えの口が合言葉なしで叩けること。3つで抑えている。
//
//  1. 短命。2分で死ぬ
//  2. 使い切り。引き換えた瞬間に消える
//  3. 紛らわしい字を抜いた32文字から8文字 = 約1兆通り。2分では総当たりできない
//
// それでも「合言葉そのものを配る口」であることに変わりはないので、
// 窓を開けるのは**すでに繋がっている端末から頼まれたときだけ**にしてある。

/** 紛らわしい字(0/O/1/I/L)を抜いてある。口頭でも打ち間違えない。 */
const INVITE_ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
const INVITE_TTL_MS = 2 * 60 * 1000;

function newCode() {
  const bytes = crypto.getRandomValues(new Uint8Array(8));
  return [...bytes].map((b) => INVITE_ALPHABET[b % INVITE_ALPHABET.length]).join('');
}

async function newInvite(env, auth) {
  const code = newCode();
  const expires = Date.now() + INVITE_TTL_MS;
  await env.DB.batch([
    // 古いものを掃除してから入れる。溜めても使い道が無い
    env.DB.prepare('DELETE FROM dopachiru_invites WHERE expires_at < ?').bind(Date.now()),
    env.DB.prepare(
      'INSERT INTO dopachiru_invites (user_id, code, expires_at) VALUES (?1, ?2, ?3)',
    ).bind(auth.userId, code, expires),
  ]);
  return json({ code, expiresAt: expires, ttlSeconds: Math.floor(INVITE_TTL_MS / 1000) });
}

/**
 * コードと引き換えに、**コードを出した人の区画に**この端末ぶんの合言葉を作って渡す。
 *
 * **引き換えは1回だけ。** 先に消してから渡すので、同じコードで2台は繋がらない。
 * D1 の DELETE ... RETURNING が使えるので、読んでから消すまでの隙間が無い。
 *
 * 前は元の合言葉(DOPA_TOKEN)をそのまま渡していた。利用者が1人のうちはそれで
 * よかったが、今それをやると**コードを出したのが誰でも持ち主の区画に入れてしまう。**
 */
async function claimInvite(request, env) {
  const body = await readJson(request);
  const code = String(body.code || '').toUpperCase().replace(/[^A-Z0-9]/g, '');
  if (code.length < 6) return json({ error: 'bad_code' }, 400);
  const device = deviceOf(body);
  // 古いアプリは端末を名乗らずに来る。元の合言葉はもう渡さないので、更新してもらう
  if (!device.deviceId) return json({ error: 'update_required' }, 426);

  const row = await env.DB.prepare(
    'DELETE FROM dopachiru_invites WHERE code = ?1 AND expires_at > ?2 RETURNING user_id',
  )
    .bind(code, Date.now())
    .first();

  // 見つからないのと期限切れを区別しない。区別すると総当たりの手掛かりになる
  if (!row) return json({ error: 'not_found' }, 404);

  const issued = await issueDeviceToken(env, row.user_id, device);
  if (issued.error) return json({ error: issued.error }, 403);
  return json({ token: issued.token, deviceId: device.deviceId });
}

// ---- 新しく始める -----------------------------------------------------------

/**
 * 新しい利用者を作り、この端末ぶんの合言葉を渡す。
 *
 * 誰でも叩ける。それで困らないのは、**作られるのが空の区画で、合言葉がその区画しか
 * 開けない**から。荒らしで D1 が埋まるのだけは困るので、1日の数に上限を置く。
 */
async function signup(request, env) {
  const device = deviceOf(await readJson(request));
  if (!device.deviceId) return json({ error: 'device_id_required' }, 400);

  const since = Date.now() - 24 * 60 * 60 * 1000;
  const recent = await env.DB.prepare('SELECT COUNT(*) AS n FROM dopachiru_users WHERE created_at > ?')
    .bind(since)
    .first();
  if (recent && recent.n >= SIGNUPS_PER_DAY) return json({ error: 'busy' }, 429);

  const user = await env.DB.prepare('INSERT INTO dopachiru_users (created_at) VALUES (?) RETURNING user_id')
    .bind(Date.now())
    .first();
  const userId = user.user_id;
  await env.DB.prepare('INSERT OR IGNORE INTO dopachiru_meta (user_id, rev) VALUES (?, 0)').bind(userId).run();

  const issued = await issueDeviceToken(env, userId, device);
  if (issued.error) return json({ error: issued.error }, 403);
  return json({ token: issued.token, deviceId: device.deviceId });
}

/** 本文から、端末が名乗ったものを取り出す。長さは切り詰める。 */
function deviceOf(body) {
  return {
    deviceId: String((body && body.deviceId) || '').slice(0, 64),
    name: String((body && body.name) || '').slice(0, 64),
    platform: String((body && body.platform) || '').slice(0, 32),
  };
}

/**
 * その利用者の区画に、この端末の合言葉を作る。
 *
 * すでに載っている端末なら同じ合言葉を返す。**入れ直すたびに行が増えると、
 * 名簿が使い物にならなくなる** ── 見慣れない名前に気づけるのが要なので。
 */
async function issueDeviceToken(env, userId, device) {
  const existing = await env.DB.prepare(
    'SELECT token, revoked FROM dopachiru_device_tokens WHERE user_id = ? AND device_id = ? ORDER BY created_at DESC LIMIT 1',
  )
    .bind(userId, device.deviceId)
    .first();
  if (existing) {
    if (existing.revoked) return { error: 'revoked' };
    return { token: existing.token };
  }

  const token = newToken();
  await env.DB.prepare(
    'INSERT INTO dopachiru_device_tokens (user_id, token, device_id, name, platform, created_at) VALUES (?, ?, ?, ?, ?, ?)',
  )
    .bind(userId, token, device.deviceId, device.name, device.platform, Date.now())
    .run();
  return { token };
}

// ---- 消す -----------------------------------------------------------------

/**
 * その利用者の行を**全部**消す。すべての端末の連携が切れる。
 *
 * 墓標も残さない。配った相手が「やめたい」と言ったとき、サーバーに何も残らないことを
 * 約束できるようにするため。端末の中のルールや記録には触らない(ここには元から無い)。
 */
async function deleteAccount(env, auth) {
  const id = auth.userId;
  await env.DB.batch([
    env.DB.prepare('DELETE FROM dopachiru_entities WHERE user_id = ?').bind(id),
    env.DB.prepare('DELETE FROM dopachiru_usage WHERE user_id = ?').bind(id),
    env.DB.prepare('DELETE FROM dopachiru_invites WHERE user_id = ?').bind(id),
    env.DB.prepare('DELETE FROM dopachiru_device_tokens WHERE user_id = ?').bind(id),
    env.DB.prepare('DELETE FROM dopachiru_meta WHERE user_id = ?').bind(id),
    env.DB.prepare('DELETE FROM dopachiru_users WHERE user_id = ?').bind(id),
  ]);
  return json({ ok: true });
}

// ---- 疎通 -----------------------------------------------------------------

async function ping(env, auth) {
  const row = await env.DB.prepare('SELECT rev FROM dopachiru_meta WHERE user_id = ?')
    .bind(auth.userId)
    .first();
  return json({ ok: true, rev: row ? row.rev : 0, serverTime: Date.now() });
}

// ---- 同期 -----------------------------------------------------------------

/**
 * 送信と受信を1往復で。
 *
 * 要求: { deviceId, since, changes: { tags: [ {uid, updatedAt, deleted, payload} ] } }
 * 応答: { rev, serverTime, changes: { tags: [...] } }
 *
 * since は前回受け取った rev。初回は 0(全件)。
 */
async function sync(request, env, auth) {
  const body = await readJson(request);
  const deviceId = String(body.deviceId || '').slice(0, 64);
  const since = Number(body.since) || 0;
  const incoming = body.changes || {};

  if (!deviceId) return json({ error: 'deviceId が要ります' }, 400);

  // この往復で自分が書いたものは返さない。送り主に送り返しても意味がなく、
  // 端末側で「自分の変更が他所からの変更として戻ってくる」ことになる
  const writtenKeys = new Set();
  const statements = [];

  for (const kind of KINDS) {
    const list = Array.isArray(incoming[kind]) ? incoming[kind] : [];
    for (const item of list) {
      if (!item || typeof item.uid !== 'string' || !item.uid) continue;
      statements.push(
        upsertEntity(env, auth.userId, {
          kind,
          uid: item.uid,
          updatedAt: Number(item.updatedAt) || 0,
          deleted: item.deleted ? 1 : 0,
          payload: JSON.stringify(item.payload == null ? {} : item.payload),
          deviceId,
        }),
      );
      writtenKeys.add(kind + ' ' + item.uid);
    }
  }

  if (statements.length > 0) {
    // 版数の繰り上げを先頭に置く。batch はひとまとまりで順に走るので、
    // 続く INSERT のサブクエリは繰り上げ後の値を読む
    await env.DB.batch([
      // 版数の行が無い利用者(区画を消したあと元の合言葉で来た持ち主など)にも書けるように
      env.DB.prepare('INSERT OR IGNORE INTO dopachiru_meta (user_id, rev) VALUES (?, 0)').bind(auth.userId),
      env.DB.prepare('UPDATE dopachiru_meta SET rev = rev + 1 WHERE user_id = ?').bind(auth.userId),
      ...statements,
    ]);
  }

  const read = await env.DB.prepare(
    'SELECT kind, uid, updated_at, deleted, payload FROM dopachiru_entities' +
      ' WHERE user_id = ? AND rev > ? ORDER BY rev',
  )
    .bind(auth.userId, since)
    .all();

  const changes = {};
  for (const kind of KINDS) changes[kind] = [];

  for (const row of read.results || []) {
    if (writtenKeys.has(row.kind + ' ' + row.uid)) continue;
    if (!changes[row.kind]) continue; // 知らない種類は黙って捨てる(前方互換)
    changes[row.kind].push({
      uid: row.uid,
      updatedAt: row.updated_at,
      deleted: !!row.deleted,
      payload: safeParse(row.payload),
    });
  }

  const revRow = await env.DB.prepare('SELECT rev FROM dopachiru_meta WHERE user_id = ?')
    .bind(auth.userId)
    .first();

  return json({ rev: revRow ? revRow.rev : 0, serverTime: Date.now(), changes });
}

/**
 * 1件ぶんの書き込み。
 *
 * 後に書かれたほうが勝ち、同値ならサーバー側を残します。**判定は SQL の中**です ──
 * 読んでから書くまでに他の端末が割り込む隙間を作らないため。
 * rev もサブクエリで引くので、値を先に知る必要がありません。
 */
function upsertEntity(env, userId, e) {
  return env.DB.prepare(
    `INSERT INTO dopachiru_entities
       (user_id, kind, uid, updated_at, deleted, payload, rev, device_id)
     VALUES (?1, ?2, ?3, ?4, ?5, ?6,
             (SELECT rev FROM dopachiru_meta WHERE user_id = ?1), ?7)
     ON CONFLICT (user_id, kind, uid) DO UPDATE SET
       updated_at = excluded.updated_at,
       deleted    = excluded.deleted,
       payload    = excluded.payload,
       rev        = excluded.rev,
       device_id  = excluded.device_id
     WHERE excluded.updated_at > dopachiru_entities.updated_at`,
  ).bind(userId, e.kind, e.uid, e.updatedAt, e.deleted, e.payload, e.deviceId);
}

// ---- 使用実績 -------------------------------------------------------------

/** 自分の端末ぶんを差し替える。他の端末の行には触りません。 */
async function putUsage(request, env, auth) {
  const body = await readJson(request);
  const deviceId = String(body.deviceId || '').slice(0, 64);
  const days = Array.isArray(body.days) ? body.days : [];

  if (!deviceId) return json({ error: 'deviceId が要ります' }, 400);

  const now = Date.now();
  const statements = days
    .filter((d) => d && typeof d.date === 'string')
    .map((d) =>
      env.DB.prepare(
        `INSERT INTO dopachiru_usage
           (user_id, device_id, date, total_minutes, per_app,
            block_shown_count, override_count, updated_at)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)
         ON CONFLICT (user_id, device_id, date) DO UPDATE SET
           total_minutes     = excluded.total_minutes,
           per_app           = excluded.per_app,
           block_shown_count = excluded.block_shown_count,
           override_count    = excluded.override_count,
           updated_at        = excluded.updated_at`,
      ).bind(
        auth.userId,
        deviceId,
        d.date,
        Number(d.totalMinutes) || 0,
        JSON.stringify(d.perApp || {}),
        Number(d.blockShownCount) || 0,
        Number(d.overrideCount) || 0,
        now,
      ),
    );

  if (statements.length > 0) await env.DB.batch(statements);
  return json({ ok: true, saved: statements.length });
}

/**
 * 端末ごとのまま返します。合算はクライアントの仕事です。
 *
 * 上書きで合算にすると「スマホ30分 + PC20分」の日が 20分になります。
 * 分けて持てば、「全端末で1日30分」も「PC だけで1日2時間」も同じデータから出せます。
 */
async function getUsage(url, env, auth) {
  const from = String(url.searchParams.get('from') || '0000-01-01');
  const to = String(url.searchParams.get('to') || '9999-12-31');

  const read = await env.DB.prepare(
    'SELECT device_id, date, total_minutes, per_app, block_shown_count, override_count' +
      ' FROM dopachiru_usage WHERE user_id = ? AND date >= ? AND date <= ? ORDER BY date',
  )
    .bind(auth.userId, from, to)
    .all();

  const byDate = new Map();
  for (const row of read.results || []) {
    if (!byDate.has(row.date)) byDate.set(row.date, {});
    byDate.get(row.date)[row.device_id] = {
      totalMinutes: row.total_minutes,
      perApp: safeParse(row.per_app),
      blockShownCount: row.block_shown_count,
      overrideCount: row.override_count,
    };
  }

  return json({
    days: [...byDate.entries()].map(([date, byDevice]) => ({ date, byDevice })),
  });
}

// ---- 新しい版 -------------------------------------------------------------

/**
 * 配っている中でいちばん新しい版を答える。
 *
 * ## なぜ GitHub を端末から直に見させないのか
 *
 * 端末が見に来る先を1か所にしておくと、**後から引っ込められる**。
 * 出してしまった版に致命的な不具合があったとき、[PINNED] に一つ前を書いて
 * deploy すれば、まだ落としていない端末はそこで止まる。GitHub を直に
 * 見せていると、消すまで配り続けることになる。
 *
 * ## 版はファイル名から読む
 *
 * どこにも版を書き写さないため。書き写す場所を作ると、リリースのたびに
 * そこを直す作業が増え、**忘れたときに「最新です」と嘘をつく**。
 * `dopachiru-0.24.0.apk` から 0.24.0 を読めば、書き写す場所は無くなる。
 */
const REPO = 'togarinozawa/doapchill';

/** 版ごとのファイル名の形。ここから版を読む。 */
const ASSETS = {
  android: /^dopachiru-(\d+(?:\.\d+)*)\.apk$/,
  windows: /^dopachiru-windows-(\d+(?:\.\d+)*)\.msi$/,
};

/**
 * ここに版を書くと、**それより新しいものを配らない**。
 * 出した版を引っ込めたいときに使う。ふだんは空。
 *
 * 例: `{ android: '0.23.0' }` と書くと 0.24.0 は出てこなくなる。
 */
const PINNED = {};

/** GitHub に聞き直す間隔。押すたびに聞きに行くと、時間あたりの上限に当たる。 */
const VERSION_TTL_SEC = 600;

async function latestVersions(request, env) {
  const cache = caches.default;
  const key = new Request(new URL('/version', request.url).toString(), { method: 'GET' });
  const hit = await cache.match(key);
  if (hit) return new Response(hit.body, hit);

  let releases;
  try {
    const res = await fetch(
      'https://api.github.com/repos/' + REPO + '/releases?per_page=10',
      {
        headers: {
          // GitHub は見出しが無いと 403 を返す
          'user-agent': 'dopachiru-sync',
          accept: 'application/vnd.github+json',
        },
      },
    );
    if (!res.ok) return json({ error: 'upstream', status: res.status }, 502);
    releases = await res.json();
  } catch (err) {
    console.error('dopachiru-sync /version', err && err.message);
    return json({ error: 'upstream' }, 502);
  }

  const body = { android: null, windows: null, notes: '', publishedAt: '' };
  if (Array.isArray(releases)) {
    // 新しいほうから見て、最初に見つかったものを採る。**platform ごとに別々に探す**
    // ── APK だけ出した版があると、MSI が道連れで見えなくなるため
    for (const release of releases) {
      if (!release || release.draft || release.prerelease) continue;
      // 一覧の中の assets が空のまま戻らないことがある(v0.33.0 で25分以上続いた。
      // 個別に引くと入っている)。空なら取り直す ── 取り直さないと一つ前の版を配り続ける
      let assets = release.assets || [];
      if (assets.length === 0 && release.assets_url) {
        try {
          const res = await fetch(release.assets_url, {
            headers: { 'user-agent': 'dopachiru-sync', accept: 'application/vnd.github+json' },
          });
          if (res.ok) assets = (await res.json()) || [];
        } catch (_) {
          // 取れなければ空のまま。一つ前の版に落ちるだけで、壊れはしない
        }
      }
      for (const [platform, pattern] of Object.entries(ASSETS)) {
        if (body[platform]) continue;
        const asset = assets.find((a) => pattern.test(a.name || ''));
        if (!asset) continue;
        const version = asset.name.match(pattern)[1];
        if (PINNED[platform] && compareVersions(version, PINNED[platform]) > 0) continue;
        body[platform] = {
          version,
          fileName: asset.name,
          url: asset.browser_download_url,
          sizeBytes: asset.size || 0,
        };
        if (!body.notes) {
          body.notes = String(release.body || '').slice(0, 2000);
          body.publishedAt = release.published_at || '';
        }
      }
      if (body.android && body.windows) break;
    }
  }

  const response = json(body);
  response.headers.set('cache-control', 'public, max-age=' + VERSION_TTL_SEC);
  await cache.put(key, response.clone());
  return response;
}

/** `1.2.10` と `1.2.9` を数として比べる。文字として比べると 10 < 9 になる。 */
function compareVersions(a, b) {
  const left = String(a).split('.').map(Number);
  const right = String(b).split('.').map(Number);
  for (let i = 0; i < Math.max(left.length, right.length); i++) {
    const diff = (left[i] || 0) - (right[i] || 0);
    if (diff) return diff > 0 ? 1 : -1;
  }
  return 0;
}

/** 端末ごとの合言葉。元の合言葉と同じ長さ(48文字)にしてある。 */
function newToken() {
  const bytes = crypto.getRandomValues(new Uint8Array(24));
  return [...bytes].map((b) => b.toString(16).padStart(2, '0')).join('');
}

// ---- 小物 -----------------------------------------------------------------

async function readJson(request) {
  try {
    return (await request.json()) || {};
  } catch (_) {
    return {};
  }
}

function safeParse(text) {
  try {
    return JSON.parse(text);
  } catch (_) {
    return {};
  }
}

function json(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json; charset=utf-8' },
  });
}

/** 端末のアプリからしか叩かないので、CORS は最小限。 */
function cors(response) {
  response.headers.set('access-control-allow-origin', '*');
  response.headers.set('access-control-allow-headers', 'authorization, content-type');
  response.headers.set('access-control-allow-methods', 'GET, POST, OPTIONS');
  return response;
}
