package com.dopachiru.desktop.bridge

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * ブラウザ拡張とドパチル本体をつなぐ、ループバック専用の小さなサーバ。
 *
 * ## なぜ本体に聞かせるのか
 *
 * 拡張だけで判定すると、ルールが二重帳簿になる。時間帯・連続時間・ポイント・
 * 罰・押し切りの代金は本体にしか無いので、拡張が独自に持つと
 * 「アプリでは止まるがブラウザでは止まらない」がすぐ起きる。
 * 判定は [com.dopachiru.core] 1か所に置き、拡張は目と手だけにする。
 *
 * ## 外に出ないこと
 *
 * `127.0.0.1` にしか bind しない。同じ機械の中からしか触れないので、
 * 「端末の外に何も出さない」は崩れていない。
 *
 * ## 誰でも叩けるわけではない
 *
 * ブラウザで開いた**ただのページも** `fetch("http://127.0.0.1:8787/…")` を投げられる。
 * だから2枚重ねにしてある:
 *
 *  1. `Origin` が `chrome-extension://` で始まるものしか受けない。
 *     この見出しはブラウザが付けるので、ページ側から偽れない。
 *  2. 合言葉。本体側で「つなぐ」を押してから2分のあいだだけ配る。
 *     一度渡せば以降は拡張が保存しておくので、入力の手間は無い。
 */
class LocalBridge(
    private val onUrl: (String?) -> Verdict,
    private val tokenStore: TokenStore,
) {

    /** 拡張に返す判定。 */
    @Serializable
    data class Verdict(
        /** 塞がっているか。拡張はこれを見てタブを退避させる(音を止めるため)。 */
        val blocked: Boolean = false,
        /** 塞いでいる理由。拡張の画面に出す。 */
        val reason: String = "",
    )

    interface TokenStore {
        fun current(): String
        fun save(token: String)
    }

    /**
     * 既定値も必ず書き出す。
     *
     * 省くと `blocked = false` のとき本文から欄ごと消える。拡張側は
     * 「無い = false」と解釈できてしまうので当座は動くが、
     * 「答えが false」と「答えが返ってこなかった」が同じ形になる。
     * 止める側の約束事でそれは危ない。
     */
    private val json = Json { encodeDefaults = true }

    private var server: HttpServer? = null

    /** 実際に掴めたポート。掴めていなければ 0。 */
    @Volatile
    var port: Int = 0
        private set

    /** 合言葉を配ってよい期限(ミリ秒)。既定は閉じている。 */
    @Volatile
    private var pairingUntil: Long = 0L

    val isRunning: Boolean get() = server != null

    /** 拡張が最後に話しかけてきた時刻。つながっているかの表示に使う。 */
    @Volatile
    var lastSeenAtMs: Long = 0L
        private set

    fun start(): Boolean {
        if (server != null) return true
        for (candidate in PORTS) {
            val created = runCatching {
                HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), candidate), 0)
            }.getOrNull() ?: continue

            created.createContext("/ping", ::handlePing)
            created.createContext("/pair", ::handlePair)
            created.createContext("/url", ::handleUrl)
            // 小さく固定する。要求はブラウザ1本からしか来ない
            created.executor = Executors.newFixedThreadPool(2)
            created.start()

            server = created
            port = candidate
            return true
        }
        return false
    }

    fun stop() {
        server?.stop(0)
        server = null
        port = 0
    }

    /** 「つなぐ」を押されたとき。ここから [PAIRING_WINDOW_MS] のあいだだけ合言葉を配る。 */
    fun openPairing() {
        pairingUntil = System.currentTimeMillis() + PAIRING_WINDOW_MS
    }

    fun closePairing() {
        pairingUntil = 0L
    }

    val isPairing: Boolean get() = System.currentTimeMillis() < pairingUntil

    // ---- 各エンドポイント ------------------------------------------------

    private fun handlePing(ex: HttpExchange) = ex.use {
        if (!checkOrigin(ex)) return@use
        if (!checkToken(ex)) return@use
        lastSeenAtMs = System.currentTimeMillis()
        respond(ex, 200, """{"ok":true}""")
    }

    /**
     * 合言葉を配る。窓が開いているあいだだけ。
     *
     * 窓を閉じているときに 403 ではなく 409 を返すのは、拡張側で
     * 「まだ本体で『つなぐ』を押していない」と「合言葉が違う」を
     * 区別して案内するため。
     */
    private fun handlePair(ex: HttpExchange) = ex.use {
        if (!checkOrigin(ex)) return@use
        if (!isPairing) {
            respond(ex, 409, """{"error":"not_pairing"}""")
            return@use
        }
        val token = tokenStore.current().ifBlank { newToken().also(tokenStore::save) }
        closePairing()
        lastSeenAtMs = System.currentTimeMillis()
        respond(ex, 200, json.encodeToString(PairResponse.serializer(), PairResponse(token)))
    }

    private fun handleUrl(ex: HttpExchange) = ex.use {
        if (!checkOrigin(ex)) return@use
        if (!checkToken(ex)) return@use
        lastSeenAtMs = System.currentTimeMillis()

        val body = ex.requestBody.readBytes().toString(StandardCharsets.UTF_8)
        val req = runCatching { json.decodeFromString(UrlRequest.serializer(), body) }.getOrNull()
        if (req == null) {
            respond(ex, 400, """{"error":"bad_json"}""")
            return@use
        }

        // 空文字ではなく null を渡す。「URL が取れなかった」と
        // 「URL は取れたがどれにも当たらない」を混ぜない
        val verdict = runCatching { onUrl(req.url?.takeIf { it.isNotBlank() }) }
            .getOrElse { Verdict() }
        respond(ex, 200, json.encodeToString(Verdict.serializer(), verdict))
    }

    // ---- 門番 ------------------------------------------------------------

    /**
     * ブラウザで開いたページから叩かれるのを防ぐ。
     *
     * `Origin` はブラウザが付ける見出しで、ページ側の JavaScript では書き換えられない。
     * だから「拡張から来た」ことの証明として使える。
     */
    private fun checkOrigin(ex: HttpExchange): Boolean {
        val origin = ex.requestHeaders.getFirst("Origin").orEmpty()
        if (origin.startsWith("chrome-extension://") || origin.startsWith("moz-extension://")) {
            return true
        }
        respond(ex, 403, """{"error":"bad_origin"}""")
        return false
    }

    private fun checkToken(ex: HttpExchange): Boolean {
        val expected = tokenStore.current()
        val given = ex.requestHeaders.getFirst("X-Dopa-Token").orEmpty()
        // 長さの違いで漏れないよう、固定時間で比べる
        if (expected.isNotBlank() && constantTimeEquals(expected, given)) return true
        respond(ex, 401, """{"error":"bad_token"}""")
        return false
    }

    private fun respond(ex: HttpExchange, code: Int, json: String) {
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        // 拡張は host_permissions を持つので CORS の許可は要らないが、
        // 何かの拍子に前段で落とされないよう明示しておく
        ex.responseHeaders.add("Cache-Control", "no-store")
        runCatching {
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.write(bytes)
        }
    }

    @Serializable
    private data class PairResponse(val token: String)

    @Serializable
    private data class UrlRequest(val url: String? = null)

    companion object {
        /**
         * 上から順に試す。1つ目が塞がっていることは珍しくないので数本用意する。
         * 拡張側も同じ並びを試すので、ここを変えるときは両方直すこと。
         */
        val PORTS = listOf(48731, 48732, 48733, 48734)

        private const val PAIRING_WINDOW_MS = 2 * 60 * 1000L

        fun newToken(): String {
            val bytes = ByteArray(24)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        private fun constantTimeEquals(a: String, b: String): Boolean {
            if (a.length != b.length) return false
            var diff = 0
            for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
            return diff == 0
        }
    }
}
