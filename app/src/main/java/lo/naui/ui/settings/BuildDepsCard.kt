package lo.naui.ui.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import lo.naui.term.BuildDeps
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 编译依赖的下载与部署。
 *
 * ## 干什么的
 *
 * 用户要的：**"在后台终端内下载本地编译 apk 的包（jar 等）"**。
 *
 * 点一下，就往已经装好的 Termux 环境里装这一套：
 *
 * ```
 * openjdk-17   编 Java/Kotlin 字节码
 * aapt2        编资源
 * apksigner    签名
 * zipalign     对齐
 * zip/unzip    打包解压
 * git/wget     拉代码、下东西
 * ```
 *
 * 装完之后 Agent 就能在那个环境里直接调 `javac` / `aapt2` / `d8` 了 ——
 * **真能在手机上编出 APK 来**。
 *
 * ## 为什么走 Termux 而不是在 App 里塞 JDK
 *
 * JDK + build-tools 加起来几百 MB，塞进 APK 不现实。
 * 而 Termux 那套本来就是"在手机上跑 Linux 环境"，装它有现成的包管理器。
 * 用户也说了"不要求全部使用 Kotlin" —— 这种活儿脚本干更合适。
 */
@Composable
fun BuildDepsCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var running by remember { mutableStateOf(false) }
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var doneCount by remember { mutableStateOf(0) }
    var failCount by remember { mutableStateOf(0) }

    val total = BuildDeps.list.size
    val envReady = remember { BuildDeps.envReady(ctx) }
    val installed = remember { BuildDeps.isInstalled(ctx) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp)) {

        Text(
            "编译 APK 要的那套（JDK / aapt2 / apksigner…），一键装进 Termux 环境。",
            fontSize = 11.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 4.dp).padding(bottom = 8.dp),
        )

        // ---- 环境状态 ----
        if (!envReady) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MiuixTheme.colorScheme.error.copy(alpha = 0.12f))
                    .padding(10.dp),
            ) {
                Text(
                    "Termux 环境还没装 —— 先去「功能 → 终端」把它装上，回来再点这里",
                    fontSize = 11.5.sp,
                    color = MiuixTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(8.dp))
        } else if (installed && !running) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f))
                    .padding(10.dp),
            ) {
                Text(
                    "已经装过了。重装一遍也行（会跳过已装的）",
                    fontSize = 11.5.sp,
                    color = MiuixTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        // ---- 清单 ----
        BuildDeps.list.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { dep ->
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                            .padding(9.dp),
                    ) {
                        Text(
                            dep.label,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            dep.note,
                            fontSize = 9.5.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                // 补空位
                repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(6.dp))
        }

        // ---- 进度 ----
        if (running || lines.isNotEmpty()) {
            val p = if (total > 0) (doneCount + failCount).toFloat() / total else 0f

            Spacer(Modifier.height(4.dp))
            // ⚠️ 颜色必须在 Canvas 外面取好。
            // `DrawScope` 里读不了 `MiuixTheme` —— 它是 @Composable 的，
            // 而 Canvas 的 lambda 不是 composable 上下文。
            // （这条在「工作纪律」里记过，我又犯了一次）
            val barColor = MiuixTheme.colorScheme.primary
            val trackColor = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.18f)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(trackColor),
            ) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                    val w = size.width * p.coerceIn(0f, 1f)
                    if (w > 0f) {
                        drawRoundRect(
                            color = barColor,
                            topLeft = androidx.compose.ui.geometry.Offset(0f, 0f),
                            size = androidx.compose.ui.geometry.Size(w, size.height),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f),
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row {
                Text(
                    "成功 " + doneCount,
                    fontSize = 10.5.sp,
                    color = MiuixTheme.colorScheme.primary,
                )
                if (failCount > 0) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        "失败 " + failCount,
                        fontSize = 10.5.sp,
                        color = MiuixTheme.colorScheme.error,
                    )
                }
            }

            // 最近几行日志
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                    .padding(8.dp),
            ) {
                Column {
                    lines.takeLast(6).forEach { l ->
                        Text(
                            l,
                            fontSize = 9.5.sp,
                            maxLines = 1,
                            color = when {
                                l.startsWith("[FAIL]") || l.contains("CHECK-FAIL") ->
                                    MiuixTheme.colorScheme.error
                                l.startsWith("[OK]") || l.contains("CHECK-OK") ->
                                    MiuixTheme.colorScheme.primary
                                else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                            },
                        )
                    }
                }
            }
        }

        // ---- 按钮 ----
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (running || !envReady) {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f)
                        } else {
                            MiuixTheme.colorScheme.primary
                        }
                    )
                    .clickable(enabled = !running && envReady) {
                        running = true
                        lines = emptyList()
                        doneCount = 0
                        failCount = 0
                        scope.launch {
                            BuildDeps.install(ctx) { line ->
                                lines = lines + line
                                if (line.startsWith("[OK]")) doneCount++
                                if (line.startsWith("[FAIL]")) failCount++
                            }
                            running = false
                        }
                    }
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (running) "正在装…（要几分钟）" else if (installed) "重装一遍" else "开始安装",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (running || !envReady) MiuixTheme.colorScheme.onSurfaceVariantSummary
                    else MiuixTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}
