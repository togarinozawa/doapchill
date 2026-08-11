# ドパチル 技術リスク事前検証レポート

対象設計書: `dopa_design_doc.md` v0.1
調査日: 2026-08-06
調査時点の Android: 16 (API 36) が最新安定、17 (API 37) は QPR2 ベータ

---

## 結論

**設計の根幹は成立する。** Device Owner なし・Accessibility + オーバーレイの範囲で、設計書の狙う「面倒さによる抑止」は実装可能。

ただし以下の対応が必要:

- **設計変更を推奨: 3件**(うち1件はブロック強度が大きく変わる重要な変更)
- **設計書に追記が必要: 3件**
- **フェーズ0で実機検証が必須: 5件**

---

## A. 設計変更の推奨

### A-1. 【重要】オーバーレイは `SYSTEM_ALERT_WINDOW` ではなく `TYPE_ACCESSIBILITY_OVERLAY` を使う

設計書では「ブロック画面表示 → SYSTEM_ALERT_WINDOW (オーバーレイ)」となっているが、**AccessibilityService を使う前提なら、より強力な選択肢がある。**

| | `TYPE_APPLICATION_OVERLAY`<br>(SYSTEM_ALERT_WINDOW) | `TYPE_ACCESSIBILITY_OVERLAY` |
|---|---|---|
| 必要な権限 | SYSTEM_ALERT_WINDOW(ユーザー許可が要る) | **不要**(AccessibilityService 自体が権限として機能) |
| ステータスバー/ナビバーの上に出せるか | **出せない** | **出せる** |
| システムダイアログの上 | 出せない | 出せる |
| 信頼されたウィンドウか | いいえ(untrusted) | **はい(trusted)** |
| Android 12+ のタッチ透過制限 | 受ける | **免除される** |

**この差がなぜ効くか:**

1. **ナビゲーションバーを覆える** — 設計書の「ホーム画面にも一時的にオーバーレイを被せる」という抑止策が、ナビバーごと覆えるかどうかで体感の強度が全く変わる。抑止力を最大化したいこのアプリでは決定的。

2. **「警告表示」アクションが正しく動く** — 設計書の Action には「警告表示 / 完全封印」の2段階がある。半透明の警告を出しつつ下のアプリを使わせ続けたい場合、`TYPE_APPLICATION_OVERLAY` だと Android 12 の untrusted touch 制限に引っかかる。不透明度 0.8 以上のオーバーレイを通したタッチは**下のアプリに配送されない**ため、「警告は出るが操作不能」という中途半端な状態になる。`TYPE_ACCESSIBILITY_OVERLAY` は trusted なのでこの制限を受けず、意図通り動く。

3. **権限要求が1つ減る** — 初期セットアップの手間が減る。

**実装方法:** AccessibilityService の Context から `WindowManager` を取得してウィンドウを追加する。Android 14 (API 34) 以降は `attachAccessibilityOverlayToDisplay()` / `attachAccessibilityOverlayToWindow()` という公式 API もあるが、min SDK が Android 12 なら従来の WindowManager 経由が必要。

> なお SYSTEM_ALERT_WINDOW も併用する価値はある(Accessibility が OFF にされた時のフォールバック、および後述の FGS バックグラウンド起動免除)。「主: Accessibility overlay / 副: SYSTEM_ALERT_WINDOW」の二段構えを推奨。

---

### A-2. Foreground Service の位置づけを「検知の維持」から「プロセス優先度の確保」に改める

設計書では「常時監視の維持 → Foreground Service」となっているが、**AccessibilityService の生存に FGS は必須ではない。**

- AccessibilityService はシステムがバインドするサービスで、ユーザーが設定で有効化した時点で開始される
- **再起動をまたいで有効状態が維持される**(BOOT_COMPLETED レシーバも不要)
- アプリ側から start/stop するものではない

つまり FGS がなくても検知自体は動く。それでも FGS を置く価値はあるが、理由は別にある:

- プロセス優先度が上がり、OEM 独自のタスクキラーに耐えやすくなる
- **バッテリー最適化を除外したアプリは、FGS のバックグラウンド起動制限を免除される**(後述 B-3 に効く)

→ FGS は「検知が死なないための保険」であって「検知の前提」ではない、と整理し直す。

---

### A-3. Windows 11 版は同一設計の流用が効かないため、構想から切り離す

設計書に「Windows11版は後日検討」とあるが、Accessibility Service / オーバーレイ / UsageStats はすべて Android 固有 API で、共有できるのはルールエンジンのデータモデル程度。Kotlin Multiplatform で `core` を切り出す構成にしておくと将来効くが、フェーズ0でそこまでやる必要はない。**将来のためにルールエンジンを Android 依存のないピュア Kotlin モジュールに分離しておく**のが低コストな備え。

---

## B. 設計書への追記が必要な項目

### B-1. 【最大の落とし穴】Restricted Settings — サイドロードでは Accessibility を有効化できない

**Android 13 以降、サイドロードしたアプリは設定画面から Accessibility を有効化できない。** トグルがグレーアウトし「制限された設定」と表示される。Android 14・15・16 でも継続。

これは設計書の「個人用サイドロード」という配布方針を直撃する。回避手順が必要:

**方法1: 端末上の操作**
1. まず Accessibility の有効化を試みて**ブロックされる**(これをやらないと次のメニューが出ない)
2. 設定 → アプリ → ドパチル → 右上 ⋮ → 「制限された設定を許可」
3. 画面ロック認証

**方法2: adb(こちらが確実)**
```bash
adb shell appops set <package_name> ACCESS_RESTRICTED_SETTINGS allow
```

→ **セットアップ手順書として残すこと。** 個人用なので許容範囲だが、知らないと「なぜか有効化できない」で詰まる。

### B-2. Android 13+ の `POST_NOTIFICATIONS` 権限

FGS の常駐通知を出すためにランタイム権限が必要。設計書の権限表に未記載。

### B-3. Android 14 以降の `foregroundServiceType` 必須化

Android 14 (API 34) 以降、FGS には型の宣言が必須。このユースケースに該当する既存の型はないため **`specialUse`** を使う。

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />

<service
    android:name=".MonitorService"
    android:foregroundServiceType="specialUse">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="Self-imposed app usage restriction enforcement" />
</service>
```

`specialUse` は **Google Play 配布時のみ審査対象**。サイドロードなら審査は発生せず、宣言するだけで動く。設計書の「Play 非公開のため審査制約なし」という前提はここでも正しい。

---

## C. フェーズ0で実機検証すべき項目(優先順)

設計書の「既知のリスク・要検証事項」を、調査結果を踏まえて具体化・優先順位付けしたもの。

| # | 検証項目 | なぜ重要か | 判定基準 |
|---|---|---|---|
| 1 | `TYPE_ACCESSIBILITY_OVERLAY` でナビバー・ステータスバーまで覆えるか | 抑止力の強度が決まる。A-1 の前提 | ナビバーが完全に隠れ、タップも通らない |
| 2 | ブロック対象アプリを開いてからオーバーレイ表示までのレイテンシ | 一瞬でも中身が見えると抑止として機能しない | 体感で「見えなかった」と言えるか(目安 200ms 以下) |
| 3 | OEM のバッテリー最適化耐性 | **実運用最大のリスク**。数日放置して検証が必要 | 3日放置後も検知が生きているか |
| 4 | `setHideOverlayWindows` を呼ぶアプリでオーバーレイが消えないか | 未確定事項(下記) | 銀行アプリ等を開いてブロックが効くか |
| 5 | Accessibility が勝手に OFF にされないか | OEM によっては起きる報告あり | 3日放置後も有効か |

### 検証4について(公式ドキュメントで確定できなかった点)

Android 12 で `Window.setHideOverlayWindows(true)` が追加され、アプリ側が**非システムオーバーレイを一括で隠せる**ようになった。銀行アプリなどがオーバーレイ攻撃対策で使う。

`TYPE_ACCESSIBILITY_OVERLAY` がこの対象外かどうか、公式ドキュメントで明記を確認できなかった。アクセシビリティ機能を壊さないため対象外である可能性が高いが、**推測に留まるため実機検証項目とする。**

もし対象だった場合の影響: `setHideOverlayWindows` を使うアプリはブロックできない。ただし対象アプリは主にSNS・動画アプリで、これらが使っている可能性は低い。実害は限定的と見込む。

---

## D. 中長期のリスク: Google のサイドロード規制

配布方針そのものに関わるため記載。

- Google が **developer verification** を導入。未検証の開発者のアプリは通常の方法でインストールできなくなる
- **2026年9月30日**: ブラジル・インドネシア・シンガポール・タイで開始
- **2027年**: グローバル展開(日本もこの段階で対象)
- **ADB 経由のインストールは維持される**。加えて「advanced flow」(24時間の待機を伴う上級者向け導線)も用意される

→ 個人用アプリを adb で入れる分には当面問題ない。ただし PC を用意しないとインストール・更新ができなくなる可能性がある。**皮肉にもこれ自体が「アプリを消しにくくする」方向に働く**ため、このアプリの用途とは相性が良い。

---

## E. 参考になる既存実装

**Curbox**(旧 DigiPaws) — GPL v3、Kotlin、F-Droid 配布のオープンソースアプリブロッカー。

- リポジトリ: https://github.com/curbox-app/curbox-android
- F-Droid: https://f-droid.org/en/packages/neth.iecal.curbox/

AccessibilityService ベースの検知 + オーバーレイ、アプリ内の特定機能(Reels / Shorts)だけをブロック、解除の「摩擦(friction)」をユーザーが選べる設計 — と、ドパチルの構想とかなり重なる。**フェーズ0を書く前に、この実装のオーバーレイ周りを読むのが最も効率的。**

---

## 出典

- [Behavior changes: Apps targeting Android 15 or higher](https://developer.android.com/about/versions/15/behavior-changes-15)
- [Behavior changes: Apps targeting Android 17 or higher](https://developer.android.com/about/versions/17/behavior-changes-17)
- [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Restrictions on starting a foreground service from the background](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [AccessibilityService リファレンス](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)
- [Behavior changes: all apps (Android 12)](https://developer.android.com/about/versions/12/behavior-changes-all)
- [Untrusted Touch Events in Android](https://medium.com/androiddevelopers/untrusted-touch-events-2c0e0b9c374c)
- [Android developer verification](https://developer.android.com/developer-verification)
- [Android 13's New Sideloading Restriction Makes it Harder for Malware to Abuse Accessibility APIs (Esper)](https://www.esper.io/blog/android-13-sideloading-restriction-harder-malware-abuse-accessibility-apis)
- [Enables Accessibility Features for Sideloaded Apps on Android 15](https://dev.moe/en/3030)
- [Android's AccessibilityService: A Single Toggle to Total Device Control](https://chocapikk.com/posts/2026/android-a11y-god-mode/)
- [Don't kill my app! — Xiaomi](https://dontkillmyapp.com/xiaomi) / [Samsung](https://dontkillmyapp.com/samsung)
- [Secure sensitive activities (Fraud prevention)](https://developer.android.com/security/fraud-prevention/activities)
