/**
 * ドパチルの端末間同期(Cloudflare Workers + D1)。
 *
 * ## 前提が1つだけあります
 *
 * **同期は設定を配るだけで、取り締まりには関わりません。**
 * 各端末はローカルのルールで判定するので、ここが落ちていても、圏外でも、
 * 機内モードでも制限は効いたままです。ここが落ちて起きるのは
 * 「別の端末で足したルールがまだ届かない」だけ。
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

/** 同期する種類。増やすときはここに足すだけ(スキーマは変わらない)。 */
const KINDS = ['rules', 'tags', 'gates', 'changeRequests'];

/** いまは単一利用者。列だけ先に持たせてある。 */
const USER_ID = 1;

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

    if (!authorized(request, env)) {
      return cors(json({ error: 'unauthorized' }, 401));
    }

    try {
      if (path === '/ping' && request.method === 'GET') return cors(await ping(env));
      if (path === '/sync' && request.method === 'POST') return cors(await sync(request, env));
      if (path === '/usage' && request.method === 'POST') return cors(await putUsage(request, env));
      if (path === '/usage' && request.method === 'GET') return cors(await getUsage(url, env));
      return cors(json({ error: 'not_found' }, 404));
    } catch (err) {
      console.error('dopachiru-sync', path, err && err.message);
      return cors(json({ error: 'server_error' }, 500));
    }
  },
};

// ---- 門番 -----------------------------------------------------------------

/**
 * 合言葉ひとつ。端末が3台の個人用なので、これで足ります。
 *
 * 長さの違いで漏れないよう固定時間で比べます。合言葉を設定していなければ
 * **全部断る** ── 空と空が一致して素通しになるほうが危ない。
 */
function authorized(request, env) {
  const expected = env.DOPA_TOKEN || '';
  if (!expected) return false;
  const given = (request.headers.get('authorization') || '').replace(/^Bearer\s+/i, '');
  return timingSafeEqual(expected, given);
}

function timingSafeEqual(a, b) {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

// ---- 疎通 -----------------------------------------------------------------

async function ping(env) {
  const row = await env.DB.prepare('SELECT rev FROM dopachiru_meta WHERE user_id = ?')
    .bind(USER_ID)
    .first();
  return json({ ok: true, rev: row ? row.rev : 0, serverTime: Date.now() });
}

// ---- 同期 -----------------------------------------------------------------

/**
 * 送信と受信を1往復で。
 *
 * 要求: { deviceId, since, changes: { rules: [ {uid, updatedAt, deleted, payload} ] } }
 * 応答: { rev, serverTime, changes: { rules: [...] } }
 *
 * since は前回受け取った rev。初回は 0(全件)。
 */
async function sync(request, env) {
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
        upsertEntity(env, {
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
      env.DB.prepare('UPDATE dopachiru_meta SET rev = rev + 1 WHERE user_id = ?').bind(USER_ID),
      ...statements,
    ]);
  }

  const read = await env.DB.prepare(
    'SELECT kind, uid, updated_at, deleted, payload FROM dopachiru_entities' +
      ' WHERE user_id = ? AND rev > ? ORDER BY rev',
  )
    .bind(USER_ID, since)
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
    .bind(USER_ID)
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
function upsertEntity(env, e) {
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
  ).bind(USER_ID, e.kind, e.uid, e.updatedAt, e.deleted, e.payload, e.deviceId);
}

// ---- 使用実績 -------------------------------------------------------------

/** 自分の端末ぶんを差し替える。他の端末の行には触りません。 */
async function putUsage(request, env) {
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
        USER_ID,
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
async function getUsage(url, env) {
  const from = String(url.searchParams.get('from') || '0000-01-01');
  const to = String(url.searchParams.get('to') || '9999-12-31');

  const read = await env.DB.prepare(
    'SELECT device_id, date, total_minutes, per_app, block_shown_count, override_count' +
      ' FROM dopachiru_usage WHERE user_id = ? AND date >= ? AND date <= ? ORDER BY date',
  )
    .bind(USER_ID, from, to)
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
