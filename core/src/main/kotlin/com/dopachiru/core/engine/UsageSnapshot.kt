package com.dopachiru.core.engine

import com.dopachiru.core.time.ResetPolicy

/**
 * ルール評価に必要な使用実績。
 *
 * core を Android 非依存に保つための境界。実測値の取り方(AccessibilityService の
 * イベント列か UsageStatsManager か)は app 側の都合であって、条件の側は知らなくてよい。
 */
interface UsageSnapshot {
    /** 対象アプリを今この瞬間まで連続して使っている時間(分)。 */
    val currentSessionMinutes: Int

    /** policy が定める現在の集計期間における、合計使用時間(分)。 */
    fun usageMinutesIn(policy: ResetPolicy): Int

    /** policy が定める現在の集計期間に、そのアプリを開いた回数。 */
    fun sessionCountIn(policy: ResetPolicy): Int

    companion object {
        /** 何も使っていない状態。テストと、実績がまだ取れていない初回起動時に使う。 */
        val EMPTY: UsageSnapshot = object : UsageSnapshot {
            override val currentSessionMinutes = 0
            override fun usageMinutesIn(policy: ResetPolicy) = 0
            override fun sessionCountIn(policy: ResetPolicy) = 0
        }
    }
}
