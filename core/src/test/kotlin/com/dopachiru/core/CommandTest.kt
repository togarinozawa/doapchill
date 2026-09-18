package com.dopachiru.core

import com.dopachiru.core.gate.Gate
import com.dopachiru.core.model.Command
import com.dopachiru.core.model.CommandKind
import com.dopachiru.core.model.CommandState
import com.dopachiru.core.model.CommandVerdict
import com.dopachiru.core.model.Commands
import com.dopachiru.core.model.DeviceScope
import com.dopachiru.core.model.Reservation
import com.dopachiru.core.model.Reservations
import com.dopachiru.core.model.Target
import com.dopachiru.core.param.Params
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 別の端末への頼みごと。
 *
 * いちばん大事なのは **「緩める頼みが関門を素通りしない」** こと。ここが崩れると、
 * PC の閉め出しはスマホを持っているだけで全部無効になり、閉め出しの意味が消えます。
 */
class CommandTest {

    private val now = 1_700_000_000L
    private val pc = "pc"
    private val phone = "phone"
    private val zone = ZoneId.of("Asia/Tokyo")

    private fun command(
        kind: String,
        to: String = pc,
        from: String = phone,
        issuedAt: Long = now - 60,
        expiresAt: Long = now + 3600,
        acceptedAt: Long = 0L,
        reason: String = "",
        cleared: Set<String> = emptySet(),
        state: String = CommandState.PENDING,
    ) = Command(
        uid = "cmd-1",
        to = to,
        from = from,
        kind = kind,
        params = Params.of(CommandKind.KEY_MINUTES to 30),
        reason = reason,
        issuedAtSec = issuedAt,
        expiresAtSec = expiresAt,
        state = state,
        acceptedAtSec = acceptedAt,
        clearedGateKeys = cleared,
    )

    // ---- 締めるほう ----------------------------------------------------

    @Test
    fun `締める頼みは関門があっても素通し`() {
        // 自分を縛るほうに摩擦を足すと、縛るのが面倒になって使わなくなる
        val verdict = Commands.triage(
            command(CommandKind.LOCK_NOW),
            myDeviceId = pc,
            gates = listOf(Gate.Cooldown(60), Gate.Password),
            nowSec = now,
            zone = zone,
        )
        assertEquals(CommandVerdict.Run, verdict)
    }

    @Test
    fun `集中の開始も締めるほう`() {
        assertFalse(CommandKind.loosens(CommandKind.FOCUS_START))
        assertFalse(CommandKind.loosens(CommandKind.RULE_ENABLE))
    }

    // ---- 緩めるほう ----------------------------------------------------

    @Test
    fun `緩める頼みはクールダウンを待つ`() {
        val verdict = Commands.triage(
            command(CommandKind.UNLOCK, acceptedAt = now - 10 * 60),
            myDeviceId = pc,
            gates = listOf(Gate.Cooldown(60)),
            nowSec = now,
            zone = zone,
        )
        assertTrue(verdict is CommandVerdict.Wait, verdict.toString())
        assertEquals(1, (verdict as CommandVerdict.Wait).gates.size)
    }

    @Test
    fun `待ち切ったら通る`() {
        val verdict = Commands.triage(
            command(CommandKind.UNLOCK, acceptedAt = now - 61 * 60),
            myDeviceId = pc,
            gates = listOf(Gate.Cooldown(60)),
            nowSec = now,
            zone = zone,
        )
        assertEquals(CommandVerdict.Run, verdict)
    }

    @Test
    fun `待ちは受け取った時刻から数える`() {
        // 送信時刻から数えると、送る側の時計を1時間戻すだけでクールダウンを飛ばせる。
        // issuedAt は大昔でも、受け取ったのがさっきなら待ちは終わっていない
        val verdict = Commands.triage(
            command(CommandKind.UNLOCK, issuedAt = now - 10 * 3600, acceptedAt = now - 60),
            myDeviceId = pc,
            gates = listOf(Gate.Cooldown(60)),
            nowSec = now,
            zone = zone,
        )
        assertTrue(verdict is CommandVerdict.Wait)
    }

    @Test
    fun `まだ受け取っていなければ、いまから数え始める`() {
        val verdict = Commands.triage(
            command(CommandKind.UNLOCK, acceptedAt = 0L),
            myDeviceId = pc,
            gates = listOf(Gate.Cooldown(60)),
            nowSec = now,
            zone = zone,
        )
        assertTrue(verdict is CommandVerdict.Wait)
    }

    @Test
    fun `書いた理由は相手の「理由を書く」関門を通る`() {
        val long = "あ".repeat(30)
        val verdict = Commands.triage(
            command(CommandKind.RULE_DISABLE, reason = long),
            myDeviceId = pc,
            gates = listOf(Gate.WriteReason(30)),
            nowSec = now,
            zone = zone,
        )
        assertEquals(CommandVerdict.Run, verdict)
    }

    @Test
    fun `短い理由では通らない`() {
        val verdict = Commands.triage(
            command(CommandKind.RULE_DISABLE, reason = "めんどい"),
            myDeviceId = pc,
            gates = listOf(Gate.WriteReason(30)),
            nowSec = now,
            zone = zone,
        )
        assertTrue(verdict is CommandVerdict.Wait)
    }

    @Test
    fun `パスワードとミニゲームは相手の端末でしか通せない`() {
        val verdict = Commands.triage(
            command(CommandKind.PASS),
            myDeviceId = pc,
            gates = listOf(Gate.Password, Gate.MiniGame()),
            nowSec = now,
            zone = zone,
        )
        assertTrue(verdict is CommandVerdict.Wait)
        assertTrue((verdict as CommandVerdict.Wait).needsTargetDevice)
    }

    @Test
    fun `通した関門は覚えておける`() {
        val verdict = Commands.triage(
            command(CommandKind.PASS, cleared = setOf("password")),
            myDeviceId = pc,
            gates = listOf(Gate.Password),
            nowSec = now,
            zone = zone,
        )
        assertEquals(CommandVerdict.Run, verdict)
    }

    // ---- 取りこぼし ----------------------------------------------------

    @Test
    fun `期限を過ぎたものは実行しない`() {
        // PC を3日ぶりに起動した瞬間に「いま30分閉め出す」が走るのは、頼んだ意図と別物
        val verdict = Commands.triage(
            command(CommandKind.LOCK_NOW, expiresAt = now - 1),
            myDeviceId = pc,
            gates = emptyList(),
            nowSec = now,
            zone = zone,
        )
        assertEquals(CommandState.EXPIRED, (verdict as CommandVerdict.Drop).state)
    }

    @Test
    fun `他人宛てには触らない`() {
        // 書き戻すと、本来の宛先が出すはずの答えを踏む
        val verdict = Commands.triage(
            command(CommandKind.LOCK_NOW, to = "other"),
            myDeviceId = pc,
            gates = emptyList(),
            nowSec = now,
            zone = zone,
        )
        assertEquals(CommandVerdict.Ignore, verdict)
    }

    @Test
    fun `済んだものは蒸し返さない`() {
        val verdict = Commands.triage(
            command(CommandKind.LOCK_NOW, state = CommandState.DONE),
            myDeviceId = pc,
            gates = emptyList(),
            nowSec = now,
            zone = zone,
        )
        assertEquals(CommandVerdict.Ignore, verdict)
    }

    @Test
    fun `自分が出した頼みを自分で実行しない`() {
        val verdict = Commands.triage(
            command(CommandKind.LOCK_NOW, to = pc, from = pc),
            myDeviceId = pc,
            gates = emptyList(),
            nowSec = now,
            zone = zone,
        )
        assertEquals(CommandState.REFUSED, (verdict as CommandVerdict.Drop).state)
    }
}

/** 「どの端末で効かせるか」。 */
class DeviceScopeTest {

    @Test
    fun `空ならどの端末でも効く`() {
        assertTrue(DeviceScope.appliesTo(DeviceScope.EVERYWHERE, "pc"))
        assertTrue(DeviceScope.appliesTo(DeviceScope.EVERYWHERE, "phone"))
    }

    @Test
    fun `名指しされていなければ効かない`() {
        assertTrue(DeviceScope.appliesTo(setOf("pc"), "pc"))
        assertFalse(DeviceScope.appliesTo(setOf("pc"), "phone"))
    }

    @Test
    fun `端末名を付けていない端末では効かせる`() {
        // 同期を設定していないだけで縛りが外れるのは、事故の向きが逆
        assertTrue(DeviceScope.appliesTo(setOf("pc"), ""))
    }

    @Test
    fun `予約も端末を選べる`() {
        val forPc = Reservation(
            uid = "r1",
            target = Target(packages = setOf("chrome.exe")),
            startEpochSec = 100,
            endEpochSec = 200,
            devices = setOf("pc"),
        )
        assertTrue(Reservations.covers(listOf(forPc), "chrome.exe", emptySet(), 150, deviceId = "pc"))
        assertFalse(Reservations.covers(listOf(forPc), "chrome.exe", emptySet(), 150, deviceId = "phone"))
    }
}
