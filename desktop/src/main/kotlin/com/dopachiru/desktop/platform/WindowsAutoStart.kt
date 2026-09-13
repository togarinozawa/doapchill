package com.dopachiru.desktop.platform

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg

/**
 * Windows にログインしたら、ドパチルも一緒に立ち上がるようにする。
 *
 * ## なぜ Run キーなのか
 * 自動起動の入れ方は3つある。
 *  - **HKCU の Run キー**(これ): 管理者権限が要らない。自分のログインだけに効く。
 *    タスクマネージャの「スタートアップ アプリ」に並ぶので、**本人が必ず切れる**。
 *  - スタートアップ フォルダ: ショートカット(.lnk)を作る必要があり、
 *    JVM から作るには COM を叩くか別の道具が要る。得るものが無い。
 *  - タスク スケジューラ: 管理者権限を求められることがあり、
 *    しかも一覧に出ないので**切りかたが分かりにくい**。
 *
 * 自分で入れた見張りを自分で外せないのは抑止ではなく事故なので、
 * いちばん外しやすい Run キーを採る。ドパチル自身の設定からも、
 * Windows のタスクマネージャからも切れる状態にしておく。
 *
 * ## 書き込む先
 * `HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Run`
 * に `Dopachiru` という名前で、インストールされた exe のパスを書く。
 * 管理者権限は要らない。HKLM(端末全体)には**書かない**。
 */
object WindowsAutoStart {

    /** Run キーに並ぶときの名前。変えると古い行が残るので変えない。 */
    const val VALUE_NAME = "Dopachiru"

    /** 自動起動で立ち上がったときに付ける印。窓を出さず、トレイだけで始まる。 */
    const val STARTUP_FLAG = "--startup"

    private const val RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"

    /**
     * タスクマネージャの「スタートアップ アプリ」で切ったときに立つ印の置き場。
     *
     * Windows は切っても Run キーの行を消さない。代わりにここへ
     * 「切った」という印を書く。これを見ないと、切られているのに
     * 設定画面では「入っています」と表示し続けることになる。
     */
    private const val APPROVED_KEY =
        "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\StartupApproved\\Run"

    /** Windows 以外では何もしない。開発中に Mac や Linux で動かしても落ちないように。 */
    private val isWindows: Boolean
        get() = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    /**
     * いま動いている起動 exe の在り処。開発中に Gradle から動かしていれば null。
     *
     * jpackage が起動時に渡してくる値をそのまま使う。`gradlew :desktop:run` では
     * 渡ってこない ── そのときは java.exe を指してしまうので、**登録できないことにする**。
     * 意味の無い行を Run に残すほうが厄介なので。
     *
     * 持ち運び版(zip を展開しただけ)でも同じ exe なので、そちらでも登録できる。
     * フォルダを動かすと前のパスは効かなくなるが、動かした先で一度起動すれば
     * [reconcile] が書き直す。
     */
    fun launcherPath(): String? {
        if (!isWindows) return null
        val fromJpackage = System.getProperty("jpackage.app-path")
        if (!fromJpackage.isNullOrBlank()) return fromJpackage
        val command = ProcessHandle.current().info().command().orElse(null) ?: return null
        return command.takeIf { it.endsWith("$VALUE_NAME.exe", ignoreCase = true) }
    }

    /** この端末で自動起動を設定できるか。Gradle から動かしているときだけ false。 */
    val supported: Boolean get() = launcherPath() != null

    /** Run キーに登録してあるか。 */
    fun isEnabled(): Boolean = runCatching {
        isWindows && Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME)
    }.getOrDefault(false)

    /**
     * 登録はしてあるが、Windows 側で切られているか。
     *
     * 印は12バイトの塊で、先頭バイトが 2 や 6 なら有効、3 なら無効。
     * 文書化されていないので、**読めなかったら「切られていない」に倒す** ──
     * 読み違えて「切られています」と出すほうが、黙っているより悪い。
     */
    fun blockedByWindows(): Boolean = runCatching {
        if (!isWindows) return false
        if (!Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, APPROVED_KEY, VALUE_NAME)) {
            return false
        }
        val bytes = Advapi32Util.registryGetBinaryValue(WinReg.HKEY_CURRENT_USER, APPROVED_KEY, VALUE_NAME)
        bytes.isNotEmpty() && (bytes[0].toInt() and 0x01) != 0
    }.getOrDefault(false)

    /**
     * 登録する。
     *
     * パスは毎回書き直す。入れ直したり別の場所に入れ直したりすると、
     * 前のパスは**黙って効かなくなる**(消えたexeを指した行はエラーも出ない)。
     *
     * @return 登録できたら true。インストール版でなければ false。
     */
    fun enable(): Boolean {
        val path = launcherPath() ?: return false
        return runCatching {
            // 空白を含むパス(Program Files など)のために必ず引用符で囲む
            Advapi32Util.registrySetStringValue(
                WinReg.HKEY_CURRENT_USER,
                RUN_KEY,
                VALUE_NAME,
                "\"$path\" $STARTUP_FLAG",
            )
            true
        }.getOrDefault(false)
    }

    /** 登録を消す。もともと無ければ何もしない。 */
    fun disable(): Boolean = runCatching {
        if (!isWindows) return false
        if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME)) {
            Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME)
        }
        true
    }.getOrDefault(false)

    /**
     * 設定に書いてある望みと、実際の登録を合わせる。起動のたびに呼ぶ。
     *
     * 入れ直してパスが変わっていても、ここで書き直される。
     * 「入れたはずなのに立ち上がらない」の大半はこれで消える。
     *
     * @return いま実際に登録されているか。
     */
    fun reconcile(desired: Boolean): Boolean {
        if (!supported) return isEnabled()
        if (desired) enable() else disable()
        return isEnabled()
    }
}
