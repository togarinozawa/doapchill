package com.dopachiru.desktop.data

import java.util.concurrent.ConcurrentHashMap

/**
 * 「開く前に宣言させる」で申告した持ち時間の消費。
 *
 * 1アプリにつき有効なものは高々1件。前面にいるあいだだけ減る。
 * 離れて[AWAY_EXPIRY_SEC]以上経ったら、その宣言は終わったものとして捨てる
 * ── そうしないと、朝に宣言した10分が夜まで残る。
 */
class DeclarationTracker {

    private val active = ConcurrentHashMap<String, Declaration>()
    private var lastTickSec = System.currentTimeMillis() / 1000
    private val lastSeenSec = ConcurrentHashMap<String, Long>()

    fun restore(saved: List<Declaration>) {
        val now = System.currentTimeMillis() / 1000
        saved.filter { now - it.declaredAtSec < AWAY_EXPIRY_SEC }
            .forEach { active[it.processName] = it }
    }

    fun snapshotForStorage(): List<Declaration> = active.values.toList()

    fun declare(processName: String, minutes: Int, reason: String) {
        active[processName] = Declaration(
            processName = processName,
            budgetMinutes = minutes,
            reason = reason,
            declaredAtSec = System.currentTimeMillis() / 1000,
        )
        lastSeenSec[processName] = System.currentTimeMillis() / 1000
    }

    /** 残り分数。宣言していなければ null、使い切っていれば 0 以下。 */
    fun remainingMinutes(processName: String): Int? {
        val declaration = active[processName] ?: return null
        val used = (declaration.consumedSec / 60).toInt()
        return declaration.budgetMinutes - used
    }

    fun clear(processName: String) {
        active.remove(processName)
        lastSeenSec.remove(processName)
    }

    /** 前面にいるアプリの宣言を消費させる。 */
    fun tick(foreground: String?, nowSec: Long = System.currentTimeMillis() / 1000) {
        val elapsed = (nowSec - lastTickSec).coerceIn(0, MAX_TICK_DELTA_SEC)
        lastTickSec = nowSec

        if (foreground != null) {
            lastSeenSec[foreground] = nowSec
            val declaration = active[foreground]
            if (declaration != null && elapsed > 0) {
                active[foreground] = declaration.copy(consumedSec = declaration.consumedSec + elapsed)
            }
        }

        // ずっと離れているものは畳む
        active.keys.toList().forEach { process ->
            val seen = lastSeenSec[process] ?: 0L
            if (nowSec - seen > AWAY_EXPIRY_SEC) clear(process)
        }
    }

    private companion object {
        /** スリープ明けに何時間ぶんも一気に消費させない。 */
        const val MAX_TICK_DELTA_SEC = 300L

        /** これだけ離れていたら、その宣言は終わったものとみなす。 */
        const val AWAY_EXPIRY_SEC = 600L
    }
}
