package com.dopachiru.core.sync

/**
 * 端末を名簿に載せて、その端末ぶんの合言葉を受け取る。
 *
 * ## なぜ自動なのか
 *
 * 使うのが一人なので、端末を足すたびに48文字を写す手間のほうが重い。
 * 入れた直後から同期が効いているほうが、実際に使う形に近い。
 *
 * ## 何を引き受けているか
 *
 * **入口の鍵は配っているアプリの中にあります。** APK も MSI も公開の場に
 * 置いてあるので、中を開けた人はここを叩けます。防げるのは
 * 「住所を知っているだけの人」まで。
 *
 * 引き受けたうえで、次の2つで受けています。
 *
 *  1. サーバーが配るのは**端末ごとに別の合言葉**。怪しい1台だけ止められる
 *  2. 名乗った名前が名簿に出る。見慣れない名前が増えれば気づける
 *
 * 気づいてから止めるまでのあいだ、相手はルールと1日ごとの使用時間を
 * 読めます。**その瞬間に何を見ていたかは出ません**(元から送っていない)。
 *
 * ## 手で繋ぐ道は残してあります
 *
 * 鍵が無いビルド(クローンしただけの人)では [needed] が偽になり、
 * 今までどおり合言葉を手で入れる形になります。
 */
object Enrollment {

    sealed interface Result {
        /** 載った。[settings] をそのまま保存すれば繋がる。 */
        data class Ok(val settings: SyncSettings) : Result

        /** やることが無かった。すでに繋がっている、鍵を焼いていない、など。 */
        data class Skipped(val reason: String) : Result

        /** 試したが駄目だった。次の起動でまた試します。 */
        data class Failed(val message: String) : Result
    }

    /**
     * 自動で繋ぎにいくべきか。
     *
     * **すでに設定があるなら触りません。** 手で入れた合言葉を
     * 起動のたびに上書きされては、設定した意味がない。
     */
    fun needed(settings: SyncSettings, enrollKey: String): Boolean =
        enrollKey.isNotBlank() && !settings.isConfigured

    /**
     * 名簿に載せてもらう。**呼ぶ側が別スレッドに逃がしてください。**
     *
     * @param deviceId この端末の ID。無ければ呼ぶ側で作って渡す。
     * @param deviceName 人が読む名前。名簿に出るので、見分けのつくものを。
     * @param platform `android` か `windows`。
     */
    fun run(
        settings: SyncSettings,
        enrollKey: String,
        deviceId: String,
        deviceName: String,
        platform: String,
        baseUrl: String = SyncDefaults.BASE_URL,
        apiFactory: (String) -> SyncApi = { SyncApi(it, "") },
    ): Result {
        if (enrollKey.isBlank()) return Result.Skipped("鍵を焼いていないビルドです")
        if (settings.isConfigured) return Result.Skipped("すでに繋がっています")
        if (deviceId.isBlank()) return Result.Skipped("端末の ID がありません")

        val url = baseUrl.trim().ifBlank { SyncDefaults.BASE_URL }
        val name = deviceName.ifBlank { deviceId }

        return when (val out = apiFactory(url).enroll(enrollKey, deviceId, name, platform)) {
            is SyncApi.Outcome.Ok -> {
                val token = out.value.token
                if (token.isBlank()) {
                    Result.Failed("サーバーが合言葉を返しませんでした")
                } else {
                    Result.Ok(
                        settings.copy(
                            enabled = true,
                            baseUrl = url,
                            token = token,
                            deviceId = deviceId,
                            lastError = "",
                        ),
                    )
                }
            }

            is SyncApi.Outcome.Rejected -> Result.Failed(
                when (out.code) {
                    // 鍵ごと閉じたか、この端末を締め出したか。どちらも
                    // 「もう入れない」なので、繰り返し試しても意味はない
                    403 -> "この端末はサーバーから締め出されています"
                    401 -> "入口の鍵が合いません"
                    else -> out.message
                },
            )

            is SyncApi.Outcome.Unreachable -> Result.Failed(out.message)
            is SyncApi.Outcome.Malformed -> Result.Failed(out.message)
        }
    }
}
