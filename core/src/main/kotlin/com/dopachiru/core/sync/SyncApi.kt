package com.dopachiru.core.sync

import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * 同期サーバーとの通信。
 *
 * `HttpURLConnection` で書いてあるのは、**Android と Windows の両方で動く唯一の選択肢**
 * だからです(`java.net.http` は Android に無く、OkHttp を入れると core が
 * Android 寄りの依存を持つことになる)。おかげでこの1本を両方で使い回せます。
 *
 * **呼ぶ側が別スレッドに逃がしてください。** ここは素直に待ちます。
 *
 * ## 失敗は例外にせず [Outcome] で返します
 *
 * 同期の失敗は**異常ではなく日常**です(圏外・サーバー再起動・寝ている端末)。
 * 例外にすると呼ぶ側が握りつぶしがちになり、握りつぶすと
 * 「同期しているつもりで何年も止まっていた」が起きます。
 * 理由を値で返して、画面に出させます。
 */
class SyncApi(
    baseUrl: String,
    private val token: String,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 20_000,
) {
    private val base = baseUrl.trimEnd('/')

    sealed interface Outcome<out T> {
        data class Ok<T>(val value: T) : Outcome<T>

        /** 届かなかった。圏外・サーバーが寝ている・URL が違う。 */
        data class Unreachable(val message: String) : Outcome<Nothing>

        /** 届いたが断られた。合言葉違いなど。 */
        data class Rejected(val code: Int, val message: String) : Outcome<Nothing>

        /** 届いて返ってきたが、読めなかった。 */
        data class Malformed(val message: String) : Outcome<Nothing>
    }

    fun ping(): Outcome<PingResponse> =
        request("GET", "/ping", null) { JSON.decodeFromString(PingResponse.serializer(), it) }

    fun sync(body: SyncRequest): Outcome<SyncResponse> = request(
        "POST",
        "/sync",
        JSON.encodeToString(SyncRequest.serializer(), body),
    ) { JSON.decodeFromString(SyncResponse.serializer(), it) }

    fun uploadUsage(body: UsageUpload): Outcome<Unit> = request(
        "POST",
        "/usage",
        JSON.encodeToString(UsageUpload.serializer(), body),
    ) { }

    fun usage(from: String, to: String): Outcome<UsageReport> = request(
        "GET",
        "/usage?from=" + enc(from) + "&to=" + enc(to),
        null,
    ) { JSON.decodeFromString(UsageReport.serializer(), it) }

    /**
     * 合言葉を要らない口。**繋がらないのか弾かれたのかを分けるため**にあります。
     * ここが通って [ping] が 401 なら、合言葉だけが違うと分かります。
     */
    fun health(): Outcome<Unit> = request("GET", "/health", null, withToken = false) { }

    // ------------------------------------------------------------------

    private fun <T> request(
        method: String,
        path: String,
        body: String?,
        withToken: Boolean = true,
        parse: (String) -> T,
    ): Outcome<T> {
        // HTTP の見出しに非 ASCII は載りません。ここで弾かないと、
        // 文字化けした合言葉が送られて **401 が返り、原因が合言葉の中身だと分からない**。
        // 「違う合言葉」と「載せられない合言葉」は直しかたが別物です。
        if (withToken && !token.all { it.code in 0x21..0x7E }) {
            return Outcome.Rejected(0, "合言葉に使える文字は英数字と記号だけです")
        }

        val connection = try {
            (URL(base + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                if (withToken) setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/json")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
        } catch (e: Exception) {
            return Outcome.Unreachable(reasonOf(e))
        }

        return try {
            if (body != null) {
                connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            }

            val code = connection.responseCode
            if (code !in 200..299) {
                val detail = connection.errorStream?.use {
                    it.readBytes().toString(StandardCharsets.UTF_8)
                }.orEmpty().take(200)
                return Outcome.Rejected(code, describe(code, detail))
            }

            val text = connection.inputStream.use {
                it.readBytes().toString(StandardCharsets.UTF_8)
            }
            try {
                Outcome.Ok(parse(text))
            } catch (e: Exception) {
                Outcome.Malformed("返ってきたものを読めませんでした: " + (e.message ?: "").take(120))
            }
        } catch (e: Exception) {
            Outcome.Unreachable(reasonOf(e))
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun describe(code: Int, detail: String): String = when (code) {
        401 -> "合言葉が違います"
        404 -> "その住所にサーバーが居ません"
        in 500..599 -> "サーバー側で失敗しました($code)"
        else -> "断られました($code)" + if (detail.isBlank()) "" else " $detail"
    }

    private fun reasonOf(e: Exception): String = when (e) {
        is java.net.SocketTimeoutException -> "応答がありません"
        is java.net.UnknownHostException -> "その住所が見つかりません"
        is IOException -> "つながりません" + (e.message?.let { ": " + it.take(80) } ?: "")
        else -> (e.message ?: e::class.simpleName.orEmpty()).take(120)
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        internal val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
