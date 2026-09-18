package com.dopachiru.core.model

/**
 * 「どの端末で効かせるか」。
 *
 * ## なぜ要るのか
 *
 * これまで同期したルールは**全端末で同じように効きました**。対象がパッケージ名で
 * 書いてあれば `chrome.exe` は Android に無いので実害は出ませんが、
 * タグや全指定で書いたルールは素通しで両方に効きます ──
 * 「PC だけ 9時から18時は全部塞ぐ」を書くと、スマホまで塞がります。
 *
 * 空なら**どの端末でも**効きます。既定が空なので、これまでのルールは
 * 挙動が変わりません。
 *
 * ## 知らない端末に向けたものは効かない
 *
 * 集合に自分が入っていなければ、そのルールは評価に載りません。**載せない**のが
 * 大事で、載せたうえで無視すると、実績や慣れの数え方が端末ごとにずれます。
 */
object DeviceScope {

    /** どの端末でも効く。 */
    val EVERYWHERE: Set<String> = emptySet()

    /**
     * その端末で効かせるか。
     *
     * [deviceId] が空のとき(同期を設定していない端末)は**効かせます** ──
     * 端末名を付けていないだけで縛りが外れるのは、事故の向きが逆です。
     */
    fun appliesTo(devices: Set<String>, deviceId: String): Boolean =
        devices.isEmpty() || deviceId.isBlank() || deviceId in devices

    /** 画面に出す短い説明。 */
    fun describe(devices: Set<String>, nameOf: (String) -> String): String =
        if (devices.isEmpty()) "すべての端末" else devices.sorted().joinToString("・", transform = nameOf)
}
