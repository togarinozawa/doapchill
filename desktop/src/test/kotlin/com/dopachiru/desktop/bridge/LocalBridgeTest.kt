package com.dopachiru.desktop.bridge

import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 受け口の門番。
 *
 * ここが緩いと、**ブラウザで開いたただのページから叩ける**ようになる。
 * 判定を覗かれるだけとはいえ、ドパチルが何を止めているかは
 * そのまま「何に困っているか」なので、外に出してよいものではない。
 */
class LocalBridgeTest {

    private lateinit var bridge: LocalBridge
    private var lastUrl: String? = "<未設定>"
    private var token = ""

    private val client: HttpClient = HttpClient.newHttpClient()

    @Before
    fun setUp() {
        token = ""
        lastUrl = "<未設定>"
        bridge = LocalBridge(
            onUrl = { url ->
                lastUrl = url
                LocalBridge.Verdict(blocked = url != null && url.contains("tiktok"), reason = "テスト")
            },
            tokenStore = object : LocalBridge.TokenStore {
                override fun current(): String = token
                override fun save(t: String) { token = t }
            },
        )
        assertTrue(bridge.start(), "ポートを掴めなかった")
    }

    @After
    fun tearDown() {
        bridge.stop()
    }

    private fun post(
        path: String,
        body: String = "{}",
        origin: String? = "chrome-extension://abcdef",
        withToken: String? = null,
    ): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${bridge.port}$path"))
            .POST(HttpRequest.BodyPublishers.ofString(body))
        if (origin != null) b.header("Origin", origin)
        if (withToken != null) b.header("X-Dopa-Token", withToken)
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    // ---- 門番 ------------------------------------------------------------

    @Test
    fun `ページから叩かれても断る`() {
        // ブラウザは Origin を必ず付けるし、ページ側では偽れない
        assertEquals(403, post("/url", origin = "https://evil.example").statusCode())
        assertEquals(403, post("/pair", origin = "https://evil.example").statusCode())
    }

    @Test
    fun `Origin が無い요求も断る`() {
        assertEquals(403, post("/url", origin = null).statusCode())
    }

    @Test
    fun `合言葉が無ければ判定させない`() {
        // HTTP の見出しに非 ASCII は載らない。合言葉が hex なのはそのため
        token = "0123456789abcdef"
        assertEquals(401, post("/url", withToken = null).statusCode())
        assertEquals(401, post("/url", withToken = "ffffffffffffffff").statusCode())
    }

    @Test
    fun `配る合言葉は見出しに載せられる形`() {
        // 非 ASCII が混じると、拡張側が要求を組み立てた時点で落ちる
        bridge.openPairing()
        post("/pair")
        assertTrue(token.matches(Regex("[0-9a-f]{48}")), "hex ではない: $token")
    }

    @Test
    fun `合言葉がまだ無い状態では誰も通さない`() {
        // 空の合言葉と空の見出しが「一致」してしまうと、繋ぐ前から素通しになる
        assertEquals(401, post("/url", withToken = "").statusCode())
    }

    // ---- 合言葉を配る -----------------------------------------------------

    @Test
    fun `窓が閉じているあいだは配らない`() {
        assertFalse(bridge.isPairing)
        assertEquals(409, post("/pair").statusCode())
        assertEquals("", token)
    }

    @Test
    fun `窓が開いていれば配って窓を閉じる`() {
        bridge.openPairing()
        val res = post("/pair")
        assertEquals(200, res.statusCode())
        assertTrue(token.isNotBlank(), "合言葉が保存されていない")
        assertTrue(res.body().contains(token), "配ったものと保存したものが違う")

        // 一度配ったら閉じる。開けっぱなしにすると、あとから来た誰でも貰える
        assertFalse(bridge.isPairing)
        assertEquals(409, post("/pair").statusCode())
    }

    @Test
    fun `繋ぎ直しても同じ合言葉を配る`() {
        bridge.openPairing()
        post("/pair")
        val first = token
        bridge.openPairing()
        post("/pair")
        assertEquals(first, token)
    }

    // ---- 判定 -------------------------------------------------------------

    @Test
    fun `正しく繋がっていれば判定を返す`() {
        bridge.openPairing()
        post("/pair")

        val res = post("/url", body = """{"url":"https://tiktok.com/foryou"}""", withToken = token)
        assertEquals(200, res.statusCode())
        assertTrue(res.body().contains("\"blocked\":true"), res.body())
        assertEquals("https://tiktok.com/foryou", lastUrl)

        val ok = post("/url", body = """{"url":"https://example.com/"}""", withToken = token)
        assertTrue(ok.body().contains("\"blocked\":false"), ok.body())
    }

    @Test
    fun `URLが空なら取れなかったものとして渡す`() {
        // 空文字を素通しすると「取れなかった」と「どれにも当たらない」が混ざる
        bridge.openPairing()
        post("/pair")

        post("/url", body = """{"url":""}""", withToken = token)
        assertNull(lastUrl)

        post("/url", body = """{}""", withToken = token)
        assertNull(lastUrl)
    }

    @Test
    fun `壊れた入力で落ちない`() {
        bridge.openPairing()
        post("/pair")
        assertEquals(400, post("/url", body = "これはJSONではない", withToken = token).statusCode())
        // 落ちていないこと
        assertEquals(200, post("/ping", withToken = token).statusCode())
    }

    @Test
    fun `外向きには開かない`() {
        // ループバック以外に bind していたら、同じ網の誰でも叩ける
        val addresses = java.net.NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .filterNot { it.isLoopbackAddress }
            .filter { it is java.net.Inet4Address }

        addresses.forEach { addr ->
            val reachable = runCatching {
                java.net.Socket().use { it.connect(java.net.InetSocketAddress(addr, bridge.port), 300) }
                true
            }.getOrDefault(false)
            assertFalse(reachable, "$addr から届いてしまう")
        }
    }
}
