package com.dopachiru.block

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/** オーバーレイの出しかた。 */
enum class OverlayMode {
    /** 全画面を塞ぎ、タッチも戻るキーも通さない。 */
    BLOCKING,

    /** 重ねるだけで、下のアプリの操作は妨げない。 */
    PASS_THROUGH,
}

/**
 * AccessibilityService の Context から出すオーバーレイ。
 *
 * `TYPE_ACCESSIBILITY_OVERLAY` を使うので SYSTEM_ALERT_WINDOW 権限が要らず、
 * ステータスバー・ナビゲーションバーの上にも出せる。
 * `TYPE_APPLICATION_OVERLAY` と違い信頼されたウィンドウなので、
 * Android 12 以降のタッチ透過制限も受けない(= 警告表示が意図通り動く)。
 */
class OverlayHost(private val service: AccessibilityService) {

    private val windowManager = service.getSystemService(WindowManager::class.java)

    private var container: BlockingFrameLayout? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var appliedMode: OverlayMode? = null
    private val content = mutableStateOf<(@Composable () -> Unit)?>(null)

    /** いま出しているオーバーレイの識別子。同じものを二重に出さないために使う。 */
    var currentKey: String? = null
        private set

    val isShowing: Boolean get() = container != null

    /** 戻るキーを押されたときに呼ばれる。BLOCKING のときだけ機能する。 */
    var onBackPressed: (() -> Unit)? = null

    fun show(
        key: String,
        mode: OverlayMode,
        coverSystemBars: Boolean = true,
        body: @Composable () -> Unit,
    ) {
        content.value = body

        if (container != null && appliedMode == mode) {
            currentKey = key
            return
        }
        if (container != null) {
            // モードが変わったらフラグを差し替える
            runCatching {
                windowManager.updateViewLayout(container, layoutParams(mode, coverSystemBars))
            }
            appliedMode = mode
            currentKey = key
            return
        }

        val owner = OverlayLifecycleOwner().apply { create() }
        val view = BlockingFrameLayout(service).apply {
            blocking = mode == OverlayMode.BLOCKING
            onBack = { onBackPressed?.invoke() }
        }
        val composeView = ComposeView(service).apply {
            setContent { content.value?.invoke() }
        }
        view.addView(composeView)

        view.setViewTreeLifecycleOwner(owner)
        view.setViewTreeViewModelStoreOwner(owner)
        view.setViewTreeSavedStateRegistryOwner(owner)

        runCatching {
            windowManager.addView(view, layoutParams(mode, coverSystemBars))
        }.onFailure {
            owner.destroy()
            return
        }

        owner.resume()
        container = view
        lifecycleOwner = owner
        appliedMode = mode
        currentKey = key
    }

    fun hide() {
        val view = container ?: return
        container = null
        currentKey = null
        appliedMode = null
        runCatching { windowManager.removeViewImmediate(view) }
        lifecycleOwner?.destroy()
        lifecycleOwner = null
        content.value = null
    }

    private fun layoutParams(mode: OverlayMode, coverSystemBars: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        if (coverSystemBars) {
            flags = flags or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        }
        if (mode == OverlayMode.PASS_THROUGH) {
            flags = flags or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
    }
}

/** 戻るキーを飲み込むためのコンテナ。 */
private class BlockingFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    var blocking: Boolean = true
    var onBack: (() -> Unit)? = null

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (blocking && event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) onBack?.invoke()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

/**
 * Service が持つウィンドウで Compose を動かすための最小の Owner。
 * Activity の外で ComposeView を使うには、この3つを View ツリーに載せる必要がある。
 */
private class OverlayLifecycleOwner :
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore = ViewModelStore()
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    fun create() {
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    fun resume() {
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}
