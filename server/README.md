# ドパチル同期 — スキマス側への取り付け手順(**役目を終えました**)

> **⚠ この手順はもう使えません。**
> 取り付け先だった dynasched のサーバーは 2026-08-18 に畳まれ、
> `/home/tf/projects/dynasched` は DB ごと削除されています。
> 相乗りする先がもう無いので、**同期サーバーは [worker/](worker/) に作り直しました**
> (Cloudflare Workers + D1)。
>
> こちらのファイルは、ルート・スキーマ・衝突解決の考え方の記録として残してあります。
> 中身の設計は worker 版にそのまま引き継いでいます。


`ANSWER-SUKIMASU.md` でもらった流儀(Express / better-sqlite3 / `CREATE TABLE IF NOT EXISTS`
だけ / `dopachiru_` 接頭辞 / `index.js` は末尾に1行)に合わせて書いてあります。

**既存のルートには一切触りません。** `/schedule` などの重複登録の並び順も変わりません。

---

## 置くもの

| ファイル | 置き場所 |
|---|---|
| `routes/dopachiru.js` | `src/routes/dopachiru.js` |
| `dopachiru-schema.js` | `src/dopachiru-schema.js` |

```bash
scp routes/dopachiru.js  tf@<host>:/home/tf/projects/dynasched/src/routes/
scp dopachiru-schema.js  tf@<host>:/home/tf/projects/dynasched/src/
```

## 足す1行

`src/index.js` の**末尾**に:

```js
app.use('/dopachiru', require('./routes/dopachiru'));
```

## 再起動

```bash
pm2 restart dynasched
```

テーブルは `routes/dopachiru.js` が読み込み時に自分で作ります。
`src/db.js` に貼りたい場合は `dopachiru-schema.js` の SQL をそのまま移してください。
二重に実行しても `IF NOT EXISTS` なので害はありません。

---

## 確認

```bash
node verify.js <JWT> http://localhost:3000
```

認証・送受信・衝突解決・端末ごとの実績まで通しで見ます。**全部OK と出れば取り付け成功**です。
テスト用の `verify-*` という行が残るので、気になれば消してください(消し方は verify.js の先頭)。

手っ取り早く見るだけなら:

```bash
curl -s -H "Authorization: Bearer <JWT>" http://localhost:3000/dopachiru/ping
# → {"ok":true,"rev":0,"serverTime":...}
```

> これらは手元で **Express + better-sqlite3 を実際に立てて15項目通してあります**
> (認証、初回同期、差分、衝突の勝ち負け、墓標、実績の端末分け、壊れた入力)。
> 未検証なのはスキマスの実際の `db.js` / `middleware/auth.js` との噛み合わせだけです。

---

## 直してもらう可能性がある1箇所

`middleware/auth` の export の形が分からなかったので、
`routes/dopachiru.js` の冒頭で**関数・`default`・`requireAuth`・`authenticate`・`verifyToken`
のどれでも拾える**ように書いてあります。どれでもなければ起動時に

```
dopachiru: middleware/auth からミドルウェア関数を取り出せませんでした。
```

で落ちます。そのときは該当行を実際の名前に直してください。それ以外の依存はありません
(express と既存の `db` だけ)。

---

## エンドポイント

| | |
|---|---|
| `GET /dopachiru/ping` | 疎通と認証の確認。`rev` を返す |
| `POST /dopachiru/sync` | 送信と受信を1往復で。ルール・タグ・ゲート・変更リクエスト |
| `POST /dopachiru/usage` | 自分の端末ぶんの実績を差し替え |
| `GET /dopachiru/usage?from=&to=` | 端末ごとのまま返す(合算はクライアント側) |

詳しい形は [SYNC.md](../SYNC.md)。

---

## 設計で意図的にそうしてあるところ

**テーブルは4つに分けず、`dopachiru_entities` 1つにまとめました。**
`payload` は中身を見ない JSON なので、同期する種類が増えても、ルールの条件が
増えても**スキーマは変わりません**。ドパチル本体が条件を JSON 1本で持っているのと
同じ理由です。増やすときは `routes/dopachiru.js` の `KINDS` に1つ足すだけです。

**削除は行を消さず `deleted = 1` の墓標にします。** 消してしまうと、
オフラインだった端末が「まだある」と思って復活させてしまいます。

**衝突は `updated_at` が大きいほうが勝ちます。** 同値ならサーバー側を残します。
ただし **`since` のカーソルはサーバーが振る `rev`** なので、端末の時計がずれていても
取りこぼしは起きません(勝ち負けだけが不当になる可能性はあります)。

**使用実績は端末ごとの区画に持ち、合算しません。** 上書きにすると
「スマホ30分 + PC20分」の日の合計が 20分になります。読む側で足せば、
「全端末合わせて1日30分」も「PC だけで1日2時間」も同じデータから出せます。

**この同期が落ちても制限は効いたままです。** 各端末はローカルのルールで判定します。
自宅サーバーで可用性の保証が無いという前提と噛み合っています ──
逆に言えば、**ここに判定を持たせてはいけません。**
