package lo.naui.ui.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import lo.naui.sys.FileStore
import lo.naui.sys.FsEntry
import lo.naui.sys.PrivLevel
import lo.naui.sys.Privilege
import lo.naui.ui.common.PageHeader
import lo.naui.ui.common.SectionTitle
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val SAF_PREFIX = "saf|"

private fun safKey(treeUri: String, docId: String): String = SAF_PREFIX + treeUri + "|" + docId

private fun parseSaf(key: String): Pair<String, String>? {
    if (!key.startsWith(SAF_PREFIX)) return null
    val rest = key.removePrefix(SAF_PREFIX)
    val i = rest.lastIndexOf('|')
    if (i <= 0) return null
    return rest.substring(0, i) to rest.substring(i + 1)
}

private fun parentOf(path: String): String {
    val t = path.trimEnd('/')
    val i = t.lastIndexOf('/')
    return if (i <= 0) "/" else t.substring(0, i)
}

/** 字节数写成人看的 */
fun readableSize(b: Long): String = when {
    b < 1024L -> b.toString() + " B"
    b < 1024L * 1024L -> (b / 1024.0).let { String.format("%.1f KB", it) }
    b < 1024L * 1024L * 1024L -> (b / 1024.0 / 1024.0).let { String.format("%.1f MB", it) }
    else -> (b / 1024.0 / 1024.0 / 1024.0).let { String.format("%.2f GB", it) }
}

/**
 * 文件管理。
 *
 * 列表按**当前能拿到的最高权限**去读：有 root 就走 su，只有 Shizuku 就走它，
 * 什么都没有就退回 java.io.File（只能看自己有权看的目录）。
 * 想看 `/` 这种地方，得先有 root 或者 Shizuku。
 *
 * 另外可以自己加外部存储目录（SAF），加进来的是持久授权，重启也在。
 */
@Composable
fun FileManagerScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    FileStore.init(ctx)

    var path by remember { mutableStateOf("/") }
    var entries by remember { mutableStateOf<List<FsEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var level by remember { mutableStateOf(PrivLevel.Normal) }
    var note by remember { mutableStateOf<String?>(null) }

    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            FileStore.addSafDir(uri.toString())
        }
    }

    fun load(target: String) {
        path = target
        note = null
        scope.launch {
            loading = true
            level = Privilege.level(ctx)
            val saf = parseSaf(target)
            entries = if (saf != null) {
                runCatching { querySaf(ctx, saf.first, saf.second) }
                    .onFailure { note = "这个目录读不了：" + (it.message ?: "") }
                    .getOrDefault(emptyList())
            } else {
                runCatching { Privilege.listDir(ctx, target) }
                    .onFailure { note = "读不了：" + (it.message ?: "") }
                    .getOrDefault(emptyList())
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { load("/") }

    Column(Modifier.fillMaxSize()) {
        PageHeader(
            title = "文件管理",
            subtitle = level.label + " · " + path,
            action = "加目录",
            onAction = { treePicker.launch(null) },
            onBack = onBack,
        )

        // 外部存储快捷入口
        if (FileStore.safDirs.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FileStore.safDirs.forEach { uri ->
                    val on = path.startsWith(SAF_PREFIX + uri)
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (on) MiuixTheme.colorScheme.primary.copy(alpha = 0.16f)
                                else MiuixTheme.colorScheme.surfaceContainerHigh
                            )
                            .clickable {
                                runCatching {
                                    val tree = Uri.parse(uri)
                                    load(safKey(uri, DocumentsContract.getTreeDocumentId(tree)))
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    ) {
                        Text(
                            FileStore.label(uri),
                            fontSize = 12.5.sp,
                            color = if (on) MiuixTheme.colorScheme.primary
                            else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 28.dp),
            ) {
                if (parseSaf(path) == null && path != "/") {
                    item(key = "__up__") {
                        RowCard {
                            ArrowPreference(
                                title = "..",
                                summary = "回到 " + parentOf(path),
                                onClick = { load(parentOf(path)) },
                            )
                        }
                    }
                }

                if (entries.isEmpty() && !loading) {
                    item(key = "__empty__") {
                        RowCard {
                            ArrowPreference(
                                title = "这里是空的",
                                summary = note ?: "没有条目，或者这个权限读不到",
                            )
                        }
                    }
                }

                items(entries, key = { it.path }) { e ->
                    RowCard {
                        ArrowPreference(
                            title = (if (e.isDir) "📁 " else "📄 ") + e.name,
                            summary = if (e.isDir) "目录" else readableSize(e.size),
                            onClick = {
                                if (e.isDir) {
                                    load(e.path)
                                } else {
                                    note = e.name + " · " + readableSize(e.size)
                                }
                            },
                        )
                    }
                }
            }
        }

        if (loading) {
            Text(
                "读取中…",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 18.dp, bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun RowCard(content: @Composable () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 6.dp),
    ) {
        Column { content() }
    }
}

/** 用系统文档接口列一个 SAF 目录 */
private fun querySaf(ctx: Context, treeUri: String, parentDocId: String): List<FsEntry> {
    val tree = Uri.parse(treeUri)
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
    val out = mutableListOf<FsEntry>()
    ctx.contentResolver.query(
        children,
        arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        ),
        null, null, null,
    )?.use { c ->
        while (c.moveToNext()) {
            val id = c.getString(0) ?: continue
            val name = c.getString(1) ?: continue
            val mime = c.getString(2).orEmpty()
            val size = c.getLong(3)
            val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
            out += FsEntry(name, safKey(treeUri, id), isDir, if (isDir) 0L else size)
        }
    }
    return out.sortedWith(compareByDescending<FsEntry> { it.isDir }.thenBy { it.name.lowercase() })
}
