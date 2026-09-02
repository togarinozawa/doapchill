package com.dopachiru.core

import com.dopachiru.core.sync.AppInfo
import com.dopachiru.core.sync.Envelope
import com.dopachiru.core.sync.SyncApi
import com.dopachiru.core.sync.SyncKinds
import com.dopachiru.core.sync.SyncRequest
import com.dopachiru.core.sync.UsageDay
import com.dopachiru.core.sync.UsageUpload
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 同期の通信。
 *
 * 失敗のしかたを重点的に見ます。同期の失敗は**異常ではなく日常**なので、
 * 「届かなかった」「断られた」「読めなかった」が混ざると、
 * 画面に出す文言が全部「エラー」になって切り分けができなくなります。
 */
class SyncApiTest {

    private lateinit var server: HttpServer
    private var port = 0
    private var lastBody: String = ""
    private var lastAuth: String = ""

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        port = server.address.port

        server.createContext("/health") { ex ->
            respond(ex, 200, """{"ok":true}""")
        }
        server.createContext("/ping") { ex ->
            lastAuth = ex.requestHeaders.getFirst("Authorization").orEmpty()
            if (lastAuth != "Bearer aikotoba-0123") respond(ex, 401, """{"error":"unauthorized"}""")
            else respond(ex, 200, """{"ok":true,"rev":7,"serverTime":123}""")
        }
        server.createContext("/sync") { ex ->
            lastBody = ex.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            respond(
                ex,
                200,
                """{"rev":9,"serverTime":1,"changes":{"rules":[
                   {"uid":"r1","updatedAt":5,"deleted":false,"payload":{"name":"夜はSNS"}}],
                   "apps":[{"uid":"android:com.x","updatedAt":5,"payload":{"label":"X"}}]}}""",
            )
        }
        server.createContext("/usage") { ex ->
            lastBody = ex.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            if (ex.requestMethod == "GET") {
                respond(
                    ex,
                    200,
                    """{"days":[{"date":"2026-09-02","byDevice":{
                       "phone":{"date":"2026-09-02","totalMinutes":30},
                       "pc":{"date":"2026-09-02","totalMinutes":20}}}]}""",
                )
            } else {
                respond(ex, 200, """{"ok":true,"saved":1}""")
            }
        }
        // 200 を返すのに中身が JSON でない、という壊れかたを作る
        server.createContext("/broken/ping") { ex -> respond(ex, 200, "これはJSONではない") }
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    private fun respond(ex: com.sun.net.httpserver.HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun api(token: String = "aikotoba-0123") = SyncApi("http://127.0.0.1:$port", token)

    // ---- 通る道 -----------------------------------------------------------

    @Test
    fun `疎通できる`() {
        val r = api().ping()
        assertIs<SyncApi.Outcome.Ok<*>>(r)
        assertEquals(7L, (r.value as com.dopachiru.core.sync.PingResponse).rev)
    }

    @Test
    fun `合言葉を見出しに載せる`() {
        api().ping()
        assertEquals("Bearer aikotoba-0123", lastAuth)
    }

    @Test
    fun `送ったものがそのまま本文になる`() {
        api().sync(
            SyncRequest(
                deviceId = "phone",
                since = 3,
                changes = mapOf(
                    SyncKinds.RULES to listOf(
                        Envelope("r1", 5, false, buildJsonObject { put("name", JsonPrimitive("夜はSNS")) }),
                    ),
                ),
            ),
        )
        assertTrue(lastBody.contains("\"deviceId\":\"phone\""), lastBody)
        assertTrue(lastBody.contains("\"since\":3"), lastBody)
        assertTrue(lastBody.contains("夜はSNS"), lastBody)
    }

    @Test
    fun `返ってきた変更を種類ごとに取り出せる`() {
        val r = api().sync(SyncRequest(deviceId = "phone"))
        assertIs<SyncApi.Outcome.Ok<*>>(r)
        val body = r.value as com.dopachiru.core.sync.SyncResponse
        assertEquals(9L, body.rev)
        assertEquals("r1", body.of(SyncKinds.RULES).single().uid)
        // アプリの名札も同じ仕組みで運ばれる
        assertEquals("android:com.x", body.of(SyncKinds.APPS).single().uid)
        // 知らない種類を引いても落ちない
        assertEquals(emptyList(), body.of("いつか増える種類"))
    }

    @Test
    fun `実績は端末ごとに分かれて返る`() {
        val r = api().usage("2026-09-02", "2026-09-02")
        assertIs<SyncApi.Outcome.Ok<*>>(r)
        val day = (r.value as com.dopachiru.core.sync.UsageReport).days.single()
        assertEquals(setOf("phone", "pc"), day.byDevice.keys)
        // 合算は読む側の仕事。上書きにすると 30+20 が 20 になる
        assertEquals(50, day.byDevice.values.sumOf { it.totalMinutes })
    }

    @Test
    fun `実績を送れる`() {
        val r = api().uploadUsage(
            UsageUpload("phone", listOf(UsageDay("2026-09-02", 30, mapOf("com.x" to 30)))),
        )
        assertIs<SyncApi.Outcome.Ok<*>>(r)
        assertTrue(lastBody.contains("com.x"), lastBody)
    }

    // ---- 転ぶ道 -----------------------------------------------------------

    @Test
    fun `合言葉が違えば断られたと分かる`() {
        val r = api(token = "chigau-0123456789")
        val out = r.ping()
        assertIs<SyncApi.Outcome.Rejected>(out)
        assertEquals(401, out.code)
        assertEquals("合言葉が違います", out.message)
    }

    @Test
    fun `届かないのと断られるのは別物`() {
        // ここが混ざると、画面の文言が全部「エラー」になって切り分けができない
        val dead = SyncApi("http://127.0.0.1:1", "aikotoba-0123", connectTimeoutMs = 500, readTimeoutMs = 500)
        assertIs<SyncApi.Outcome.Unreachable>(dead.ping())
    }

    @Test
    fun `住所が違えばそう言う`() {
        val wrong = SyncApi("http://does-not-exist.invalid", "aikotoba-0123", 800, 800)
        val out = wrong.ping()
        assertIs<SyncApi.Outcome.Unreachable>(out)
    }

    @Test
    fun `JSONでない応答は読めなかったと分かる`() {
        // 200 が返ってきても中身が読めないことはある。
        // これを「届かなかった」に混ぜると、電波を疑って延々ハマる
        val broken = SyncApi("http://127.0.0.1:$port/broken", "aikotoba-0123")
        assertIs<SyncApi.Outcome.Malformed>(broken.ping())
    }

    @Test
    fun `健康確認は合言葉が無くても通る`() {
        // 繋がらないのか弾かれたのかを分けるための口
        assertIs<SyncApi.Outcome.Ok<*>>(api(token = "detarame").health())
    }

    // ---- アプリの名札 -----------------------------------------------------

    @Test
    fun `名札の鍵は端末の種類ごとに分かれる`() {
        // 分けないと、両方の端末が同じ鍵に別の名前を書き合って入れ替わり続ける
        val a = AppInfo("chrome.exe", "Chrome", AppInfo.WINDOWS)
        val b = AppInfo("chrome.exe", "べつのなにか", AppInfo.ANDROID)
        assertEquals("windows:chrome.exe", a.uid)
        assertTrue(a.uid != b.uid)
    }

    @Test
    fun `名札の鍵を元に戻せる`() {
        assertEquals(
            AppInfo.WINDOWS to "chrome.exe",
            AppInfo.idOf("windows:chrome.exe"),
        )
        // パッケージ名に点が入っていても壊れない
        assertEquals(
            AppInfo.ANDROID to "com.example.app",
            AppInfo.idOf("android:com.example.app"),
        )
        assertEquals(null, AppInfo.idOf("区切りなし"))
        assertEquals(null, AppInfo.idOf(":先頭が空"))
    }

    @Test
    fun `合言葉に日本語が入っていたら送る前に止める`() {
        // 見出しに載らない文字を黙って送ると 401 が返り、
        // 「合言葉が違う」と「合言葉に使えない字が入っている」が区別できなくなる
        val out = api(token = "にほんごのあいことば").ping()
        assertIs<SyncApi.Outcome.Rejected>(out)
        assertTrue(out.message.contains("使える文字"), out.message)
    }
}
