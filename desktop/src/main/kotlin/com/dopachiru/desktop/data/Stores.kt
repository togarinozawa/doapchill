package com.dopachiru.desktop.data

import com.dopachiru.core.model.Rule
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
}
