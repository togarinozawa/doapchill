# ドパチル (dopachiru)

個人用のアプリ使用制限アプリ。Kotlin + Compose Multiplatform。

## 構成

| モジュール | 中身 |
|---|---|
| `:core` | ルールエンジン。条件・措置・判定・同期の型。**UI も端末 API も持たない** |
| `:app` | Android。AccessibilityService + オーバーレイ + Room |
| `:desktop` | Windows。Compose Desktop + トレイ + JSON ファイル保存 |
| `extension/` | Chrome 拡張。URL とページ内要素を見る |
| `server/worker/` | Cloudflare Workers + D1。同期と `/version` |

## 機能の足しかた

**条件と措置はレジストリに登録する。** `DopaCore.registerAll()` に1行足せば、
両OSの画面に自動で出る。分岐を書き足す場所は無い。

- 条件 = `ConditionType`(`core/condition/types/`)
- 措置 = `ActionType`(`core/action/types/`)
- パラメータは `Params`(JSON)。欄の定義は `ParamSpec`。
  `visibleWhen` で他の欄の値に応じて畳める
- `available = false` にすると**凍結**(既存ルールは読めるが、新規に選べない)

判定の要は `RuleEngine.decide` ── 成立した組のうち `severity` がいちばん高い
措置を1つだけ採る。重ねられるのは `stackable` な覚え書きだけ。

## 守ること

- **制限の実行をネットワークに依存させない。** 圏外・機内モードで外れる制限は、
  機内モードにするだけで抜けられる制限になる。同期は上乗せであって土台ではない
- **このリポジトリは公開。** 鍵・トークン・個人的な記録を置かない
  (`dopa.md` `dopa_design_doc.md` `dist/` `local.properties` は gitignore 済み)
- **ASCII パスで作業する。** 非 ASCII のパスに置くと Gradle のテストが全滅する
- コメントは**なぜそう書いたか**を残す。何をしているかはコードが言う

## 確かめかた

```bash
./gradlew :core:test :app:testDebugUnitTest
./gradlew :desktop:compileKotlin
```

Room のマイグレーションは、手書きの SQL と生成されたスキーマ JSON を
突き合わせる `MigrationSqlTest` が見張っている。列を足したら必ず通すこと。

## ビルドとリリース

**ビルド・コミット・GitHub リリース・サーバーのデプロイで1セット。**
バラバラにやらない。

```bash
./gradlew :app:dist :desktop:distMsi :desktop:packagePortable
```

3つとも版つきの名前で `dist/` に出る。**リリースにはこの3つを上げる。**

**`dist/` の名前のまま上げること。** `/version`(自動更新)が名前から版を読むので、
名前が合わないと更新が届かない。`build/` の中の jpackage 生の
`Dopachiru-x.y.z.msi` を上げると**この事故が起きる**:

```
dopachiru-<app版>.apk              例 dopachiru-0.31.0.apk
dopachiru-windows-<desktop版>.msi  例 dopachiru-windows-1.25.0.msi
dopachiru-windows-<desktop版>.zip  持ち運び版
```

版は2本立て ── `app/build.gradle.kts` の `versionName`/`versionCode` と、
`desktop/build.gradle.kts` の `desktopVersion`。両方上げる。

MSI には WiX 3 が要る(PATH に通す)。無い環境では `packageSetup`(zip + install.cmd)。

サーバーは `server/worker/` で `npm run deploy`。差分が無ければ触らない。
