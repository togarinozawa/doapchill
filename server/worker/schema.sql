-- ドパチル同期のテーブル(D1)。
--
-- Express + better-sqlite3 版から、ほぼそのまま移してあります。
-- D1 の実体は SQLite なので、型も ON CONFLICT の書き方も同じものが通ります。
--
-- 流しかた:
--   npx wrangler d1 execute dopachiru --remote --file=schema.sql

-- サーバーが振る版数。同期のカーソルに使う。
-- 端末の時計に依存させないための土台なので、必ずサーバー側で進める。
CREATE TABLE IF NOT EXISTS dopachiru_meta (
  user_id INTEGER NOT NULL PRIMARY KEY DEFAULT 1,
  rev     INTEGER NOT NULL DEFAULT 0
);

-- 同期する実体を1つのテーブルにまとめて置く。
-- payload は中身を見ない JSON。ルールの条件が増えても、同期する種類が増えても、
-- ここのスキーマは変わらない(ドパチル本体が条件を JSON 1本で持っているのと同じ考え)。
CREATE TABLE IF NOT EXISTS dopachiru_entities (
  user_id    INTEGER NOT NULL DEFAULT 1,
  kind       TEXT    NOT NULL,              -- 'rules' | 'tags' | 'gates' | 'changeRequests' | 'lockouts'
  uid        TEXT    NOT NULL,              -- 端末をまたいで一意な ID
  updated_at INTEGER NOT NULL,              -- 端末が付けた時刻(ミリ秒)。衝突解決はこれで
  deleted    INTEGER NOT NULL DEFAULT 0,    -- 墓標。行は消さない
  payload    TEXT    NOT NULL,
  rev        INTEGER NOT NULL,              -- サーバーが振る。since のカーソル
  device_id  TEXT    NOT NULL DEFAULT '',   -- 最後に書いた端末
  PRIMARY KEY (user_id, kind, uid)
);

CREATE INDEX IF NOT EXISTS idx_dopachiru_entities_rev
  ON dopachiru_entities (user_id, rev);

-- 使用実績。端末ごとの区画に持つ。
-- 上書きにすると「スマホ30分 + PC20分」の日が 20分になってしまうので、
-- 合算は読む側でやる。
CREATE TABLE IF NOT EXISTS dopachiru_usage (
  user_id           INTEGER NOT NULL DEFAULT 1,
  device_id         TEXT    NOT NULL,
  date              TEXT    NOT NULL,        -- 'YYYY-MM-DD'
  total_minutes     INTEGER NOT NULL DEFAULT 0,
  per_app           TEXT    NOT NULL DEFAULT '{}',
  block_shown_count INTEGER NOT NULL DEFAULT 0,
  override_count    INTEGER NOT NULL DEFAULT 0,
  updated_at        INTEGER NOT NULL,
  PRIMARY KEY (user_id, device_id, date)
);

CREATE INDEX IF NOT EXISTS idx_dopachiru_usage_date
  ON dopachiru_usage (user_id, date);

-- 版数の行が無ければ作る
INSERT OR IGNORE INTO dopachiru_meta (user_id, rev) VALUES (1, 0);
