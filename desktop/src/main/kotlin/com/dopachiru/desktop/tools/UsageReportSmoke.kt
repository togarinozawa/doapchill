package com.dopachiru.desktop.tools

import com.dopachiru.core.DopaCore
import com.dopachiru.core.io.UsageReport
import com.dopachiru.core.io.UsageSpan
import com.dopachiru.core.model.ConditionNode
import com.dopachiru.core.model.Rule
import com.dopachiru.core.model.Target
import com.dopachiru.core.param.Params
import com.dopachiru.core.action.types.BlockAction
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 書き出しの見た目を目で見るための道具。
 * `gradlew :desktop:usageReportSmoke`
 *
 * 中身の計算は単体テストが見ているので、ここで確かめるのは
 * **1枚の紙として読めるか**だけ。作り物の記録を流し込む。
 */
fun main() {
    DopaCore.registerAll()
    val zone = ZoneId.systemDefault()
    val now = LocalDateTime.now()
    val base = now.toLocalDate().atStartOfDay(zone).toEpochSecond()

    // それらしい癖を作る: 夜に YouTube、昼に Chrome、たまに War Thunder
    val spans = ArrayList<UsageSpan>()
    for (day in 0 until 14) {
        val d = base - day * 24L * 3600
        spans += UsageSpan("chrome.exe", d + 10 * 3600, d + 12 * 3600)
        spans += UsageSpan("chrome.exe", d + 14 * 3600, d + 15 * 3600 + 1800)
        spans += UsageSpan("youtube.exe", d + 22 * 3600 + 2400, d + 24 * 3600 + 1200)
        if (day % 3 == 0) spans += UsageSpan("aces.exe", d + 20 * 3600, d + 22 * 3600)
        spans += UsageSpan("code.exe", d + 13 * 3600, d + 13 * 3600 + 900)
    }

    val rule = Rule(
        id = 1,
        name = "夜はYouTubeを使えなくする",
        target = Target(packages = setOf("youtube.exe")),
        condition = ConditionNode.AllOf(emptyList()),
        actionId = BlockAction.id,
        actionParams = Params.defaultsOf(BlockAction.params),
    )

    println(
        UsageReport.build(
            spans = spans,
            labelOf = { it.removeSuffix(".exe") },
            rules = listOf(rule),
            tags = mapOf("youtube.exe" to setOf("動画"), "aces.exe" to setOf("ゲーム")),
            now = now,
            days = 14,
            deviceName = "しごと用PC",
        )
    )
}
