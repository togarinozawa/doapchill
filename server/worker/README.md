# ドパチル同期サーバー (Cloudflare Workers + D1)

## なぜここに置いたか

前身は自宅の機械で pm2 が回していましたが、**畳まれたことに2週間気づきませんでした。**
「動かし続けなければ死ぬもの」を挟まない置き場所にしてあります。
SSH も pm2 も無く、止めるものがありません。

D1 の実体は SQLite なので、[schema.sql](schema.sql) は Express + better-sqlite3 版から
ほぼそのまま移せています。

## 前提

**同期は設定を配るだけで、取り締まりには関わりません。**
各端末はローカルのルールで判定するので、ここが落ちていても、圏外でも、機内モードでも
制限は効いたままです。ここが落ちて起きるのは「別の端末で足したルールがまだ届かない」だけ。

**逆に言えば、ここに判定を持たせてはいけません。**
ネットが切れれば外れる制限は、機内モードにするだけで抜けられる制限です。

## いまの状態(2026-09-01)

**デプロイ済み・動作確認済みです。**

| | |
|---|---|
| Worker | `dopachiru-sync` |
| アドレス | `https://dopa.togar.dev` |
| 予備のアドレス | `https://dopachiru-sync.snnnsnn3777.workers.dev` |
| D1 | `dopachiru`(APAC) |
| 合言葉 | Workers の secret `DOPA_TOKEN`。手元の控えは `.dopa-token`(gitignore 済み) |

もともと `dopa.togar.dev` には中身の無いトンネルを指す DNS レコードが載っていました。
Cloudflare は外で管理されているレコードを勝手に上書きしないので、
**先にそれを消してから** `wrangler deploy` を打つ必要がありました。同じことが起きたら、
`Hostname ... already has externally managed DNS records` がその合図です。

workers.dev も開けたままにしてあります。DNS をいじった直後に落ちたとき、
切り分ける先が1つも無いと詰むためです。どちらも合言葉が要ります。

## 立ち上げかた

```bash
cd server/worker
npx wrangler login                       # ブラウザで認証(本人が打つ)
npx wrangler d1 create dopachiru         # 出た database_id を wrangler.toml に貼る
npx wrangler d1 execute dopachiru --remote --file=schema.sql
npx wrangler secret put DOPA_TOKEN       # 合言葉。端末にも同じものを入れる
npx wrangler deploy
```

合言葉は自分で決めず、生成したものを貼ってください。

```bash
node -e "console.log(require('crypto').randomBytes(24).toString('hex'))"
```

## 手元で動かす

Cloudflare のアカウントが無くても、ローカルだけで一通り試せます。

```bash
npx wrangler d1 execute dopachiru --local --file=schema.sql
npx wrangler dev --local --var DOPA_TOKEN:testtoken1234567890
```

## エンドポイント

| | |
|---|---|
| `GET /health` | 疎通のみ。**合言葉が要りません** |
| `GET /ping` | 認証つきの疎通。`rev` を返す |
| `POST /sync` | 送信と受信を1往復で |
| `POST /usage` | 自分の端末ぶんの実績を差し替え |
| `GET /usage?from=&to=` | 端末ごとのまま返す(合算はクライアント側) |

`/health` だけ合言葉を要らなくしてあるのは、**繋がらないのか弾かれたのかを
端末側で区別できないと切り分けができない**ためです。

## 設計で意図的にそうしてあるところ

**テーブルは種類ごとに分けず `dopachiru_entities` 1つにまとめてあります。**
`payload` は中身を見ない JSON なので、同期する種類が増えても、ルールの条件が増えても
**スキーマは変わりません**。増やすときは `KINDS` に1つ足すだけです。

**削除は行を消さず `deleted = 1` の墓標にします。** 消してしまうと、オフラインだった端末が
「まだある」と思って復活させてしまいます。

**衝突は `updated_at` が大きいほうが勝ちます。** 同値ならサーバー側を残します。
判定は SQL の中(`ON CONFLICT ... WHERE excluded.updated_at > ...`)にあり、
読んでから書くまでの隙間がありません。

**版数の繰り上げと書き込みは1つの `batch()` に入れてあります。**
D1 に対話的なトランザクションが無いので、各行の `rev` はサブクエリで引かせています。
`since` のカーソルはサーバーが振る `rev` なので、**端末の時計がずれていても
取りこぼしは起きません**(勝ち負けだけが不当になる可能性はあります)。

**使用実績は端末ごとの区画に持ち、合算しません。** 上書きにすると
「スマホ30分 + PC20分」の日の合計が 20分になります。

## 確かめたこと

ローカル(`wrangler dev --local`)で通しで見てあります。

- 合言葉なし・違う合言葉は 401
- 送った端末に自分の変更を返さない
- 別端末が `since=0` で全件受け取る
- 古い `updatedAt` は**負ける**、新しいものは**勝つ**
- 墓標が伝わる
- `since` を進めると差分がゼロになる
- 日本語が壊れずに往復する
- 実績が端末ごとに分かれて返る(30分 + 20分 が 50分として読める)
