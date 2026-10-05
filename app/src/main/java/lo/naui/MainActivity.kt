package lo.naui

import android.media.AudioManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import lo.naui.sys.Shortcuts
import lo.naui.sys.VolumeChordBus
import lo.naui.ui.shell.AppShell
import lo.naui.ui.theme.NakourTheme
import lo.naui.ui.theme.Prefs
import lo.naui.ui.theme.ThemePrefs

class MainActivity : ComponentActivity() {

    /**
     * 音量上 + 音量下 同时按 = 侧栏快捷方式。
     *
     * 拦截的只有「第二个键」那一下：单独按音量键照常调音量（返回 false 放过去），
     * 两个键凑齐时把这一下吃掉并广播，同时把第一个键已经调掉的那一格补回来。
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!VolumeChordBus.isVolumeKey(keyCode)) return super.onKeyDown(keyCode, event)
        val firstKey = VolumeChordBus.firstKeyHeld()
        val consumed = VolumeChordBus.onKeyDown(keyCode)
        if (consumed) {
            undoVolume(firstKey)
        }
        return consumed
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (!VolumeChordBus.isVolumeKey(keyCode)) return super.onKeyUp(keyCode, event)
        VolumeChordBus.onKeyUp(keyCode)
        return super.onKeyUp(keyCode, event)
    }

    private fun undoVolume(firstKey: Int) {
        runCatching {
            val am = getSystemService(AUDIO_SERVICE) as? AudioManager ?: return
            val step = when (firstKey) {
                KeyEvent.KEYCODE_VOLUME_UP -> AudioManager.ADJUST_LOWER
                KeyEvent.KEYCODE_VOLUME_DOWN -> AudioManager.ADJUST_RAISE
                else -> return
            }
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, step, 0)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val prefs = ThemePrefs(this)
        Prefs.current = prefs
        Shortcuts.init(this)

        setContent {
            val context = LocalContext.current
            val p = remember {
                ThemePrefs(context).also { Prefs.current = it }
            }
            Shortcuts.init(context)
            NakourTheme(p) {
                // 不要开屏了，直接进主界面
                // backdrop 提到这一层：GlassCard 通过 CompositionLocal 自己取，
                // 这样设置页 / Agent 页这些不用一页页往下传，也不会漏
                val backdrop = com.kyant.backdrop.backdrops.rememberLayerBackdrop()

                // ---- 首次进入的准备界面 ----
                //
                // 走完一次就记下来，以后不再出现。
                // 想在已有数据的情况下再看一遍，去
                // 「设置 → 开发者工具 → 重新走一遍准备界面」。
                //
                // ⚠️ 用 remember 而不是 rememberSaveable：
                // 走完之后这一个进程就不再显示了，退出重进由 SetupStore 那个标记管。
                //
                // ⚠️ 不能用 `this` —— 这儿是 composable 的 lambda，
                // `this` 不是 Activity，编译期直接报错。要 LocalContext。
                val setupCtx = LocalContext.current
                val setupDoneFlag = remember {
                    lo.naui.ui.setup.SetupStore.init(setupCtx)
                    androidx.compose.runtime.mutableStateOf(
                        !lo.naui.ui.setup.SetupStore.shouldShow
                    )
                }

                if (!setupDoneFlag.value) {
                    lo.naui.ui.setup.SetupScreen(
                        onFinish = { setupDoneFlag.value = true }
                    )
                } else {
                    androidx.compose.runtime.CompositionLocalProvider(
                        lo.naui.ui.component.LocalGlassBackdrop provides backdrop
                    ) {
                        AppShell(p, backdrop)
                    }
                }
            }
        }
    }
}
