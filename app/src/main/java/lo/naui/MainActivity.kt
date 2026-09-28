package lo.naui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import lo.naui.ui.shell.AppShell
import lo.naui.ui.theme.NakourTheme
import lo.naui.ui.theme.Prefs
import lo.naui.ui.theme.ThemePrefs

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val p = remember {
                ThemePrefs(context).also { Prefs.current = it }
            }
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
