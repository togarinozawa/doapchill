package com.dopachiru.desktop.data

import com.dopachiru.core.model.FocusSettings
import com.dopachiru.core.model.Lockout
import com.dopachiru.core.model.Rule
import com.dopachiru.core.points.PointEvent
import com.dopachiru.core.points.PointPolicy
import com.dopachiru.desktop.platform.BlockStrength
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** Windows 版だけの設定。ルールそのものは端末で共通なので、ここには入れない。 */
@Serializable
data class DesktopSettings(
    /** ブロックのやり方。Windows には Android のような統一された止め方が無いので選ばせる。 */
    val blockStrength: BlockStrength = BlockStrength.MINIMIZE,

    /** 一時停止中。トレイから切り替える。 */
    val paused: Boolean = false,

    /** Windows と一緒に起動する。 */
    val launchAtLogin: Boolean = false,

    /** 押し切ったあと、何分そのアプリを見逃すか。 */
    val overrideGraceMinutes: Int = 5,

    /**
     * ポイントの使い道と相場。
     *
     * Android と別に持つ。同期でルールが渡ってきても、
     * 「押し切りに代金を取るか」は端末ごとに決めたいことがあるため。
     */
    val pointPolicy: PointPolicy = PointPolicy.DEFAULT,

    /** 解禁券で制限が止まっている期限(秒)。過ぎれば勝手に戻る。 */
    val passUntilSec: Long = 0L,

    /** ブラウザ拡張からの URL 受け口を開けるか。 */
    val bridgeEnabled: Boolean = true,

    /** 集中モードの既定値。Android と同じ形なので、いずれ同期に載せられる。 */
    val focus: FocusSettings = FocusSettings(),

    /**
     * 拡張と分け合う合言葉。
     *
     * 空なら「まだ繋いでいない」。設定から「つなぐ」を押した2分のあいだだけ
     * 配られるので、ここが埋まっている = 一度は自分の手で繋いだ、という意味になる。
     */
    val bridgeToken: String = "",
)

@Serializable
data class RuleFile(
    val rules: List<Rule> = emptyList(),
    /** 次に振る ID。Room の autoGenerate にあたるもの。 */
    val nextId: Long = 1L,
    /** プロセス名 → タグ。 */
    val tags: Map<String, Set<String>> = emptyMap(),
)

@Serializable
data class UsageSession(
    val processName: String,
    val startSec: Long,
    val endSec: Long,
)

@Serializable
data class Declaration(
    val processName: String,
    val budgetMinutes: Int,
    val reason: String = "",
    val declaredAtSec: Long,
    val consumedSec: Long = 0L,
)

object Stores {
    val settings = JsonStore("settings.json", DesktopSettings.serializer()) { DesktopSettings() }
    val rules = JsonStore("rules.json", RuleFile.serializer()) { RuleFile() }
    val usage = JsonStore("usage.json", ListSerializer(UsageSession.serializer())) { emptyList() }
    val declarations =
        JsonStore("declarations.json", ListSerializer(Declaration.serializer())) { emptyList() }

    /**
     * 罰で閉まっているもの。
     *
     * ファイルに落とすのは、再起動で罰が消えては罰にならないため。
     * 逆に期限を過ぎれば勝手に解けるので、閉じ込め続けることもない。
     */
    val lockouts = JsonStore("lockouts.json", ListSerializer(Lockout.serializer())) { emptyList() }

    /** ポイントの増減。残高はこの合計。 */
    val points = JsonStore("points.json", ListSerializer(PointEvent.serializer())) { emptyList() }
}
