package com.dopachiru.core.sync

import java.util.UUID

/**
 * 端末をつなぐ。「新しく始める」か「ほかの端末で出したコードで参加する」の2つだけ。
 *
 * ## 押すまで何も送らない
 *
 * 前は起動しただけで持ち主のサーバーに自動で繋がっていた(アプリに鍵を焼いていた)。
 * 配った相手にそれをやると、**持ち主の区画に入ってきて予約や頼みごとに触れる。**
 * 今は本人がどちらかを押したときに初めて繋がり、区画は人ごとに分かれている。
 * 1台しか使わない人は、押さなければ何も送らない。
 *
 * ## つなぎ直すときは版数を0に戻す
 *
 * 別の区画に移ると、前の区画の版数([SyncSettings.since])の続きから貰っても
 * 何も来ない。
 */
object Joining {

    sealed interface Result {
        /** つながった。[settings] をそのまま保存すれば同期が回る。 */
        data class Ok(val settings: SyncSettings) : Result

        /** 駄目だった。[message] はそのまま画面に出す文。 */
        data class Failed(val message: String) : Result
    }

    /** 新しい端末の ID。実績の見出しにもなるので、一度決めたら変えない。 */
    fun newDeviceId(platform: String): String = platform + "-" + UUID.randomUUID().toString().take(8)

    /** 新しい区画を作って、この端末をそこにつなぐ。 */
    fun startNew(
        settings: SyncSettings,
        deviceId: String,
        deviceName: String,
        platform: String,
        baseUrl: String = SyncDefaults.BASE_URL,
        apiFactory: (String) -> SyncApi = { SyncApi(it, "") },
    ): Result {
        val url = baseUrl.trim().ifBlank { SyncDefaults.BASE_URL }
        val out = apiFactory(url).signup(deviceId, deviceName.ifBlank { deviceId }, platform)
        return connected(settings, url, deviceId, out) { code ->
            if (code == 429) "いまは新しく始められません。時間をおいて試してください" else null
        }
    }

    /** ほかの端末で出した短いコードで、その人の区画にこの端末をつなぐ。 */
    fun joinWithCode(
        settings: SyncSettings,
        code: String,
        deviceId: String,
        deviceName: String,
        platform: String,
        baseUrl: String = SyncDefaults.BASE_URL,
        apiFactory: (String) -> SyncApi = { SyncApi(it, "") },
    ): Result {
        val url = baseUrl.trim().ifBlank { SyncDefaults.BASE_URL }
        val out = apiFactory(url).claimInvite(code.trim(), deviceId, deviceName.ifBlank { deviceId }, platform)
        return connected(settings, url, deviceId, out) { status ->
            // 見つからないのと切れたのをサーバーは区別しない(総当たりの手掛かりになる)
            if (status == 404) "そのコードは見つかりません。切れているかもしれません(2分で切れます)" else null
        }
    }

    private fun connected(
        settings: SyncSettings,
        url: String,
        deviceId: String,
        out: SyncApi.Outcome<EnrollResponse>,
        explain: (Int) -> String?,
    ): Result = when (out) {
        is SyncApi.Outcome.Ok ->
            if (out.value.token.isBlank()) {
                Result.Failed("サーバーが合言葉を返しませんでした")
            } else {
                Result.Ok(
                    settings.copy(
                        enabled = true,
                        baseUrl = url,
                        token = out.value.token,
                        deviceId = deviceId,
                        since = 0L,
                        lastError = "",
                    ),
                )
            }

        is SyncApi.Outcome.Rejected -> Result.Failed(
            explain(out.code) ?: when (out.code) {
                403 -> "この端末はサーバーから締め出されています"
                else -> out.message
            },
        )

        is SyncApi.Outcome.Unreachable -> Result.Failed(out.message)
        is SyncApi.Outcome.Malformed -> Result.Failed(out.message)
    }
}
