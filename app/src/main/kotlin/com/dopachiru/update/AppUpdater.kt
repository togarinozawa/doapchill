package com.dopachiru.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.dopachiru.core.sync.SyncApi
import com.dopachiru.core.update.Downloader
import com.dopachiru.core.update.ReleaseBuild
import com.dopachiru.core.update.UpdateEndpoint
import com.dopachiru.core.update.Versions
import com.dopachiru.runtime.DopaRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 新しい版を自分で入れ替える。
 *
 * ## 押したときだけ通信します
 *
 * 裏で見に行くようにはしていません。**取り締まりがネットに依存していない**という
 * このアプリの前提を、更新のためだけに崩したくないからです。裏で毎日聞きに行く
 * 仕組みを足すと、「ネットが要るアプリ」に見えるようになり、機内モードで
 * 何かが変わるのではないかという疑いが立ちます。実際には変わらないのに。
 *
 * ## 最後の一押しは必ず手で
 *
 * 無音でインストールできるのは端末所有者アプリだけです。ここができるのは
 * 「落として、インストーラに渡す」まで。**そこは諦めずに割り切っています** ──
 * 落とし終わってさえいれば、残りは数秒で終わるので。
 */
object AppUpdater {

    sealed interface State {
        /** まだ聞いていない。 */
        data object Idle : State

        data object Checking : State

        /** 聞いたが、いまのが最新だった。 */
        data class UpToDate(val current: String) : State

        data class Available(val build: ReleaseBuild, val notes: String) : State

        data class Downloading(val build: ReleaseBuild, val percent: Int) : State

        /** 落とし終わった。あとはインストーラに渡すだけ。 */
        data class Ready(val build: ReleaseBuild, val file: File) : State

        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** 落とし途中に画面を離れても続けたいので、やめる合図は別に持つ。 */
    @Volatile
    private var cancelled = false

    /** 聞きに行く。押したときだけ呼ばれる。 */
    suspend fun check(context: Context) {
        _state.value = State.Checking
        val current = currentVersion(context)
        val baseUrl = UpdateEndpoint.resolve(
            runCatching { DopaRuntime.settings.syncSettings.first().baseUrl }.getOrDefault(""),
        )

        val latest = withContext(Dispatchers.IO) { SyncApi(baseUrl, "").latest() }
        _state.value = when (latest) {
            is SyncApi.Outcome.Ok -> {
                val build = latest.value.android
                when {
                    build == null || !build.isUsable -> State.Failed("配っている版が見つかりません")
                    Versions.isNewer(current, build.version) -> State.Available(build, latest.value.notes)
                    else -> State.UpToDate(current)
                }
            }
            is SyncApi.Outcome.Unreachable -> State.Failed(latest.message)
            is SyncApi.Outcome.Rejected -> State.Failed(latest.message)
            is SyncApi.Outcome.Malformed -> State.Failed(latest.message)
        }
    }

    /** 落とす。落とし終えても勝手にはインストーラを開かない。 */
    suspend fun download(context: Context, build: ReleaseBuild) {
        cancelled = false
        _state.value = State.Downloading(build, 0)
        val dest = File(updatesDir(context), build.fileName.ifBlank { "dopachiru-update.apk" })

        val result = withContext(Dispatchers.IO) {
            Downloader.download(
                url = build.url,
                dest = dest,
                expectedBytes = build.sizeBytes,
                onProgress = { percent ->
                    // 落とし途中に他の状態へ移っていたら、そこを塗り潰さない
                    if (_state.value is State.Downloading) {
                        _state.value = State.Downloading(build, percent)
                    }
                },
                isCancelled = { cancelled },
            )
        }

        _state.value = when (result) {
            is Downloader.Result.Ok -> State.Ready(build, result.file)
            is Downloader.Result.Failed -> State.Failed(result.message)
        }
    }

    fun cancel() {
        cancelled = true
        _state.value = State.Idle
    }

    fun reset() {
        _state.value = State.Idle
    }

    /**
     * インストーラに渡す。
     *
     * 「不明なアプリのインストール」が許されていなければ、その設定画面へ送ります。
     * ここで黙って失敗すると、押しても何も起きないアプリに見える。
     */
    fun install(context: Context, file: File): Boolean {
        if (!canInstall(context)) {
            openInstallPermission(context)
            return false
        }
        val uri: Uri = FileProvider.getUriForFile(context, context.packageName + ".updates", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }

    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:" + context.packageName),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /**
     * いま入っている版。**gradle の値ではなくパッケージから読みます** ──
     * 焼き込むと、渡した APK と端末に入っているものが食い違ったときに嘘をつく。
     */
    fun currentVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    /**
     * 落とし場所。`cache` の下なので、容量が要るときに OS が消せる。
     *
     * 消えて困るものではありません ── 入れ終わったあとの APK は用済みだし、
     * 入れる前に消えたらもう一度落とせばいい。
     */
    private fun updatesDir(context: Context): File =
        File(context.cacheDir, "updates").apply { mkdirs() }

    /** 古い APK を片付ける。入れ終わったあとに呼びます。 */
    fun cleanUp(context: Context) {
        runCatching { updatesDir(context).listFiles()?.forEach { it.delete() } }
    }
}
