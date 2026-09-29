package lo.naui

import android.media.AudioManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
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
                // 先走开屏，加载完再进主界面
                // 不用 by 委托 —— 省得还要 import getValue/setValue
                val booted = remember { mutableStateOf(false) }
                if (!booted.value) {
                    lo.naui.ui.splash.SplashScreen(onDone = { booted.value = true })
                } else {
                    AppShell(p)
                }
            }
        }
    }
}
