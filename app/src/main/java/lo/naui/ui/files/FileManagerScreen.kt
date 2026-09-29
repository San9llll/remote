@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package lo.naui.ui.files

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.widget.EditText
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlinx.coroutines.launch
import lo.naui.sys.FileStore
import lo.naui.sys.FsEntry
import lo.naui.sys.FsOps
import lo.naui.sys.PrivLevel
import lo.naui.sys.Privilege
import lo.naui.ui.component.GlassCard
import lo.naui.ui.component.glassShape
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

const val DEFAULT_DIR = "/storage/emulated/0/"
private const val DEFAULT_DIR_RIGHT = "/storage/emulated/0/Download"
private const val SAF_PREFIX = "saf|"

private fun safKey(treeUri: String, docId: String): String = SAF_PREFIX + treeUri + "|" + docId

private fun parseSaf(key: String): Pair<String, String>? {
    if (!key.startsWith(SAF_PREFIX)) return null
    val rest = key.removePrefix(SAF_PREFIX)
    val i = rest.lastIndexOf('|')
    if (i <= 0) return null
    return rest.substring(0, i) to rest.substring(i + 1)
}

/** 字节数写成人看的 */
fun readableSize(b: Long): String = when {
    b < 1024L -> b.toString() + " B"
    b < 1024L * 1024L -> (b / 1024.0).let { String.format("%.1f KB", it) }
    b < 1024L * 1024L * 1024L -> (b / 1024.0 / 1024.0).let { String.format("%.1f MB", it) }
    else -> (b / 1024.0 / 1024.0 / 1024.0).let { String.format("%.2f GB", it) }
}

private data class PaneState(
    val path: String = "",
    val entries: List<FsEntry> = emptyList(),
)

/**
 * 文件管理（双栏）。
 *
 * 左右各是一个独立的目录视图，中间一条竖线隔开；顶部靠右那块玻璃卡显示的是
 * **当前操作栏**（点哪栏、在哪栏滑动，哪栏就变成当前操作栏）。
 *
 * 交互：
 *  - 点目录进去，点文件用内置查看器打开（文本 / 图片，不跳别的应用）
 *  - 长按弹操作窗口：复制 / 移动 / 压缩（目标是**另一栏**）、删除 / 重命名 / 属性（作用于自己）
 *  - 卡片左右滑 = 选中；已经在选择模式里再滑另一个，就把两个之间整段全选
 *  - 选择模式里单击 = 单独选 / 取消选
 *  - 选择模式时顶栏目录卡左边会渐显一个「全选」
 *  - 返回键 / 从左往右划 → 退上一级，到 / 再退才回功能页
 */
@Composable
fun FileManagerScreen(
    onBack: () -> Unit,
    backdrop: com.kyant.backdrop.Backdrop? = null,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cardShape = glassShape()
    FileStore.init(ctx)

    var left by remember { mutableStateOf(PaneState(DEFAULT_DIR)) }
    var right by remember { mutableStateOf(PaneState(DEFAULT_DIR_RIGHT)) }
    var active by remember { mutableStateOf(0) }              // 0 = 左，1 = 右
    var selection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var anchor by remember { mutableStateOf<String?>(null) }
    var level by remember { mutableStateOf(PrivLevel.Normal) }
    var loading by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var remarkTarget by remember { mutableStateOf<String?>(null) }
    var actionTarget by remember { mutableStateOf<FsEntry?>(null) }
    var propsText by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var viewer by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var pathEditor by remember { mutableStateOf<Int?>(null) }

    val selectMode = selection.isNotEmpty()

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

    fun load(pane: Int, target: String) {
        scope.launch {
            loading = true
            note = null
            level = Privilege.level(ctx)
            val saf = parseSaf(target)
            val list = if (saf != null) {
                runCatching { querySaf(ctx, saf.first, saf.second) }
                    .onFailure { note = "这个目录读不了：" + (it.message ?: "") }
                    .getOrDefault(emptyList())
            } else {
                runCatching { Privilege.listDir(ctx, target) }
                    .onFailure { note = "读不了：" + (it.message ?: "") }
                    .getOrDefault(emptyList())
            }
            if (pane == 0) left = PaneState(target, list) else right = PaneState(target, list)
            loading = false
        }
    }

    fun goUp(pane: Int) {
        val st = if (pane == 0) left else right
        val saf = parseSaf(st.path)
        if (saf != null) {
            runCatching {
                load(pane, safKey(saf.first, DocumentsContract.getTreeDocumentId(Uri.parse(saf.first))))
            }
            return
        }
        if (st.path.isBlank() || st.path == "/") {
            if (selectMode) {
                selection = emptySet()
            } else {
                onBack()
            }
        } else {
            load(pane, FsOps.parentOf(st.path))
        }
    }

    LaunchedEffect(Unit) {
        load(0, DEFAULT_DIR)
        load(1, DEFAULT_DIR_RIGHT)
    }

    BackHandler(enabled = true) {
        when {
            viewer != null -> viewer = null
            propsText != null -> propsText = null
            selectMode -> {
                selection = emptySet()
                anchor = null
            }
            else -> goUp(active)
        }
    }

    /** 滑动选中：第一次滑 = 进选择模式，已经在里面就整段全选 */
    fun swipeSelect(pane: Int, e: FsEntry, list: List<FsEntry>) {
        active = pane
        if (selection.isEmpty()) {
            selection = setOf(e.path)
            anchor = e.path
            return
        }
        val a = anchor ?: e.path
        val i1 = list.indexOfFirst { it.path == a }
        val i2 = list.indexOfFirst { it.path == e.path }
        selection = if (i1 >= 0 && i2 >= 0) {
            val r = if (i1 <= i2) i1..i2 else i2..i1
            selection + r.map { list[it].path }
        } else {
            selection + e.path
        }
        anchor = e.path
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            /* ---------------- 顶栏 ---------------- */
            Row(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .clickable { menuOpen = !menuOpen },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("⋮", fontSize = 20.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }

                Spacer(Modifier.width(6.dp))

                // 选择模式才渐显出来的「全选」
                AnimatedVisibility(
                    visible = selectMode,
                    enter = fadeIn(tween(260, easing = FastOutSlowInEasing)),
                    exit = fadeOut(tween(160)),
                ) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.16f))
                            .clickable {
                                val list = if (active == 0) left.entries else right.entries
                                selection = list.map { it.path }.toSet()
                            }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    ) {
                        Text(
                            "全选",
                            fontSize = 12.5.sp,
                            color = MiuixTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }

                Spacer(Modifier.weight(1f))

                // 当前操作栏 —— 顶在最右边的液态玻璃卡
                GlassCard(
                    backdrop = backdrop,
                    modifier = Modifier.widthIn(max = 200.dp),
                    contentPadding = 12.dp,
                ) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            (if (active == 0) left.path else right.path).ifBlank { "/" },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            level.label + " · " +
                                (if (active == 0) left.entries.size else right.entries.size) + " 项" +
                                if (selectMode) " · 已选 " + selection.size else "",
                            fontSize = 10.5.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                        )
                    }
                }
            }

            note?.let {
                Text(
                    it,
                    fontSize = 11.5.sp,
                    color = MiuixTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }

            /* ---------------- 双栏 ---------------- */
            Row(Modifier.weight(1f).fillMaxWidth()) {
                FilePane(
                    pane = 0,
                    state = left,
                    active = active == 0,
                    selection = selection,
                    backdrop = backdrop,
                    cardShape = cardShape,
                    onActivate = { active = 0 },
                    onOpenDir = { load(0, it) },
                    onOpenFile = { e -> openFile(ctx, e) { viewer = it } },
                    onLongPress = { actionTarget = it },
                    onSwipe = { e, list -> swipeSelect(0, e, list) },
                    onClickItem = { e, list ->
                        active = 0
                        if (selectMode) {
                            selection = if (selection.contains(e.path)) selection - e.path else selection + e.path
                            anchor = e.path
                        } else if (e.isDir) {
                            load(0, e.path)
                        } else {
                            openFile(ctx, e) { viewer = it }
                        }
                    },
                    onEditPath = { pathEditor = 0 },
                    onSwipeBack = { goUp(0) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )

                // 中间那条分隔线
                Box(
                    Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(MiuixTheme.colorScheme.dividerLine)
                )

                FilePane(
                    pane = 1,
                    state = right,
                    active = active == 1,
                    selection = selection,
                    backdrop = backdrop,
                    cardShape = cardShape,
                    onActivate = { active = 1 },
                    onOpenDir = { load(1, it) },
                    onOpenFile = { e -> openFile(ctx, e) { viewer = it } },
                    onLongPress = { actionTarget = it },
                    onSwipe = { e, list -> swipeSelect(1, e, list) },
                    onClickItem = { e, list ->
                        active = 1
                        if (selectMode) {
                            selection = if (selection.contains(e.path)) selection - e.path else selection + e.path
                            anchor = e.path
                        } else if (e.isDir) {
                            load(1, e.path)
                        } else {
                            openFile(ctx, e) { viewer = it }
                        }
                    },
                    onEditPath = { pathEditor = 1 },
                    onSwipeBack = { goUp(1) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }

        /* ---------------- ⋮ 菜单 ---------------- */
        if (menuOpen) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.30f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { menuOpen = false }
            )
            GlassCard(
                backdrop = backdrop,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 14.dp, top = 56.dp)
                    .widthIn(max = 320.dp),
                contentPadding = 14.dp,
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                menuOpen = false
                                openAllFilesAccess(ctx)
                            }
                            .padding(vertical = 10.dp, horizontal = 6.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("所有文件访问权限", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(
                                if (hasAllFilesAccess()) "已开启"
                                else "没开的话 /Android/data 这类地方看不了",
                                fontSize = 11.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }

                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                menuOpen = false
                                treePicker.launch(null)
                            }
                            .padding(vertical = 10.dp, horizontal = 6.dp),
                    ) {
                        Text("加目录", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }

                    if (FileStore.safDirs.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "已添加（长按改备注 / 删除）",
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(start = 6.dp, bottom = 4.dp),
                        )
                        FileStore.safDirs.forEach { uri ->
                            val remark = FileStore.remarkOf(uri)
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .combinedClickable(
                                        onClick = {
                                            menuOpen = false
                                            runCatching {
                                                load(
                                                    active,
                                                    safKey(uri, DocumentsContract.getTreeDocumentId(Uri.parse(uri))),
                                                )
                                            }
                                        },
                                        onLongClick = { remarkTarget = uri },
                                    )
                                    .padding(vertical = 9.dp, horizontal = 6.dp),
                            ) {
                                Text(
                                    remark.ifBlank { FileStore.label(uri) },
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (remark.isNotBlank()) {
                                    Text(
                                        FileStore.label(uri),
                                        fontSize = 11.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        /* ---------------- 长按操作窗口 ---------------- */
        actionTarget?.let { target ->
            val destDir = if (active == 0) right.path else left.path
            ActionSheet(
                target = target,
                destDir = destDir,
                backdrop = backdrop,
                onDismiss = { actionTarget = null },
                onRun = { label ->
                    actionTarget = null
                    scope.launch {
                        val paths = if (selection.contains(target.path)) selection.toList() else listOf(target.path)
                        val ok = when (label) {
                            "复制" -> paths.all { FsOps.copy(ctx, it, destDir) }
                            "移动" -> paths.all { FsOps.move(ctx, it, destDir) }
                            "压缩" -> FsOps.archive(ctx, paths, destDir)
                            "删除" -> paths.all { FsOps.delete(ctx, it) }
                            else -> false
                        }
                        note = if (ok) label + " 完成 → " + destDir else label + " 失败（权限不够？）"
                        selection = emptySet()
                        anchor = null
                        load(0, left.path)
                        load(1, right.path)
                    }
                },
                onRename = {
                    val t = actionTarget
                    actionTarget = null
                    if (t != null) {
                        val input = EditText(ctx).apply { setText(t.name); setSelection(t.name.length) }
                        AlertDialog.Builder(ctx)
                            .setTitle("重命名")
                            .setView(input)
                            .setPositiveButton("确定") { _, _ ->
                                scope.launch {
                                    val ok = FsOps.rename(ctx, t.path, input.text.toString().trim())
                                    note = if (ok) "已重命名" else "重命名失败"
                                    load(0, left.path)
                                    load(1, right.path)
                                }
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                },
                onProps = {
                    val t = actionTarget
                    actionTarget = null
                    if (t != null) {
                        scope.launch { propsText = FsOps.stat(ctx, t.path) }
                    }
                },
            )
        }

        /* ---------------- 属性 ---------------- */
        propsText?.let { rows ->
            OverlayCard(backdrop) {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Text("属性", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    rows.forEach { (k, v) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(k, fontSize = 12.5.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                v,
                                fontSize = 12.5.sp,
                                modifier = Modifier.weight(1f),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(50))
                            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.14f))
                            .clickable { propsText = null }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("关闭", fontSize = 13.sp, color = MiuixTheme.colorScheme.primary)
                    }
                }
            }
        }

        /* ---------------- 改路径 ---------------- */
        pathEditor?.let { pane ->
            val cur = if (pane == 0) left.path else right.path
            val input = remember(pane, cur) {
                EditText(ctx).apply { setText(cur); setSelection(cur.length) }
            }
            AlertDialog.Builder(ctx)
                .setTitle("去哪")
                .setView(input)
                .setPositiveButton("确定") { _, _ ->
                    val p = input.text.toString().trim()
                    if (p.isNotBlank()) load(pane, p)
                    pathEditor = null
                }
                .setNegativeButton("取消") { _, _ -> pathEditor = null }
                .show()
        }
    }

    // 长按已添加目录 → 备注 / 删除
    val target = remarkTarget
    if (target != null) {
        AlertDialog.Builder(ctx)
            .setTitle(FileStore.remarkOf(target).ifBlank { FileStore.label(target) })
            .setItems(arrayOf("设置备注", "删除这个目录")) { _, which ->
                if (which == 0) {
                    val input = EditText(ctx).apply {
                        setText(FileStore.remarkOf(target))
                        setSelection(FileStore.remarkOf(target).length)
                    }
                    AlertDialog.Builder(ctx)
                        .setTitle("备注")
                        .setView(input)
                        .setPositiveButton("保存") { _, _ ->
                            FileStore.setRemark(target, input.text.toString())
                        }
                        .setNegativeButton("取消", null)
                        .show()
                } else {
                    FileStore.removeSafDir(target)
                    runCatching {
                        ctx.contentResolver.releasePersistableUriPermission(
                            Uri.parse(target), Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                }
                remarkTarget = null
            }
            .setNegativeButton("取消") { _, _ -> remarkTarget = null }
            .show()
    }

    // 内置查看器
    viewer?.let { (path, isImage) ->
        ImageViewer(path, isImage) { viewer = null }
    }
}

/* ---------------- 一栏 ---------------- */

@Composable
private fun FilePane(
    pane: Int,
    state: PaneState,
    active: Boolean,
    selection: Set<String>,
    backdrop: com.kyant.backdrop.Backdrop?,
    cardShape: androidx.compose.ui.graphics.Shape,
    onActivate: () -> Unit,
    onOpenDir: (String) -> Unit,
    onOpenFile: (FsEntry) -> Unit,
    onLongPress: (FsEntry) -> Unit,
    onSwipe: (FsEntry, List<FsEntry>) -> Unit,
    onClickItem: (FsEntry, List<FsEntry>) -> Unit,
    onEditPath: () -> Unit,
    onSwipeBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .pointerInput(state.path) {
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = { if (total > 150f) onSwipeBack() },
                    onHorizontalDrag = { _, drag -> total += drag },
                )
            }
    ) {
        // 这一栏的路径条
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onActivate(); onEditPath() }
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(
                        if (active) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.35f)
                    )
            )
            Spacer(Modifier.width(6.dp))
            Text(
                state.path.ifBlank { "/" },
                fontSize = 10.5.sp,
                color = if (active) MiuixTheme.colorScheme.primary
                else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.entries.isEmpty()) {
                item(key = "__empty__") {
                    Text(
                        "空",
                        fontSize = 11.5.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
            items(state.entries, key = { it.path }) { e ->
                FileRow(
                    entry = e,
                    selected = selection.contains(e.path),
                    backdrop = backdrop,
                    cardShape = cardShape,
                    onLongPress = { onLongPress(e) },
                    onSwipe = { onSwipe(e, state.entries) },
                    onClick = { onClickItem(e, state.entries) },
                )
            }
        }
    }
}

/* ---------------- 一个文件卡 ---------------- */

@Composable
private fun FileRow(
    entry: FsEntry,
    selected: Boolean,
    backdrop: com.kyant.backdrop.Backdrop?,
    cardShape: androidx.compose.ui.graphics.Shape,
    onLongPress: () -> Unit,
    onSwipe: () -> Unit,
    onClick: () -> Unit,
) {
    val ctx = LocalContext.current
    val visual = rememberVisual(ctx, entry.path, entry.name, entry.isDir)
    val isPic = visual.kind == PreviewKind.Image || visual.kind == PreviewKind.Video

    Box(
        Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .then(
                if (selected) {
                    Modifier.background(MiuixTheme.colorScheme.primary).padding(1.5.dp)
                } else {
                    Modifier
                }
            )
    ) {
        GlassCard(
            backdrop = backdrop,
            modifier = Modifier
                .fillMaxWidth()
                .clip(cardShape)
                .pointerInput(entry.path) {
                    var dx = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dx = 0f },
                        onDragEnd = { if (abs(dx) > 55f) onSwipe() },
                        onHorizontalDrag = { _, d -> dx += d },
                    )
                }
                .combinedClickable(onClick = onClick, onLongClick = onLongPress),
            contentPadding = 9.dp,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 左边那个小方框
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .then(
                            // 图片 / 视频直接铺缩略图，不吃那层玻璃；
                            // 其它类型才是「60% 透明度的 2D 玻璃」上写类型
                            if (isPic && visual.image != null) {
                                Modifier.background(Color.Black.copy(alpha = 0.25f))
                            } else {
                                Modifier.background(MiuixTheme.colorScheme.surface.copy(alpha = 0.60f))
                            }
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    val img = visual.image
                    if (img != null) {
                        Image(
                            bitmap = img,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Text(
                            visual.label,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        entry.name,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (entry.isDir) "目录" else readableSize(entry.size),
                        fontSize = 10.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                    )
                }

                if (selected) {
                    Text("✓", fontSize = 13.sp, color = MiuixTheme.colorScheme.primary)
                }
            }
        }
    }
}

/* ---------------- 长按弹出来的操作窗口 ---------------- */

@Composable
private fun ActionSheet(
    target: FsEntry,
    destDir: String,
    backdrop: com.kyant.backdrop.Backdrop?,
    onDismiss: () -> Unit,
    onRun: (String) -> Unit,
    onRename: () -> Unit,
    onProps: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.34f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        GlassCard(
            backdrop = backdrop,
            modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 380.dp),
            contentPadding = 18.dp,
        ) {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    target.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "复制 / 移动 / 压缩 的目标栏：" + destDir,
                    fontSize = 10.5.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 2,
                )
                Spacer(Modifier.height(12.dp))

                // 左右两排
                val actions = listOf(
                    "复制" to { onRun("复制") },
                    "移动" to { onRun("移动") },
                    "压缩" to { onRun("压缩") },
                    "删除" to { onRun("删除") },
                    "重命名" to { onRename() },
                    "属性" to { onProps() },
                )
                actions.chunked(2).forEach { rowItems ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        rowItems.forEach { (label, act) ->
                            val danger = label == "删除"
                            Box(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (danger) MiuixTheme.colorScheme.error.copy(alpha = 0.14f)
                                        else MiuixTheme.colorScheme.surfaceContainerHigh
                                    )
                                    .clickable { act() }
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    label,
                                    fontSize = 13.sp,
                                    color = if (danger) MiuixTheme.colorScheme.error
                                    else MiuixTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OverlayCard(backdrop: com.kyant.backdrop.Backdrop?, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.34f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { },
        contentAlignment = Alignment.Center,
    ) {
        GlassCard(
            backdrop = backdrop,
            modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 400.dp),
        ) { content() }
    }
}

/* ---------------- 内置查看器 ---------------- */

private fun openFile(ctx: Context, e: FsEntry, open: (Pair<String, Boolean>) -> Unit) {
    when (kindOf(e.name)) {
        PreviewKind.Image -> open(e.path to true)
        PreviewKind.Text -> open(e.path to false)
        PreviewKind.Video -> open(e.path to true)
        PreviewKind.App -> open(e.path to false)
    }
}

@Composable
private fun ImageViewer(path: String, isImage: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var text by remember(path) { mutableStateOf<String?>(null) }
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(path) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (isImage) {
                bitmap = runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
            } else {
                text = runCatching {
                    val f = java.io.File(path)
                    if (f.canRead() && f.length() < 512 * 1024) {
                        f.readText()
                    } else {
                        val before = text
                        before
                    }
                }.getOrNull() ?: "这个文件打不开（太大、是二进制，或者没权限）"
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.88f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClose() },
    ) {
        val img = bitmap
        when {
            isImage && img != null -> Image(
                bitmap = img,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(16.dp),
            )
            isImage -> Text(
                "图片读不出来",
                fontSize = 14.sp,
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
            else -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    path,
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.6f),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text ?: "读取中…",
                    fontSize = 13.sp,
                    color = Color.White,
                )
            }
        }

        Text(
            "点任意处关闭",
            fontSize = 11.sp,
            color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.align(Alignment.BottomCenter).padding(20.dp),
        )
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

/** Android 11+ 的「所有文件访问权限」开没开 */
private fun hasAllFilesAccess(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
    } else {
        true
    }

/** 跳到系统那个「所有文件访问权限」开关（老系统就跳应用详情页） */
private fun openAllFilesAccess(ctx: Context) {
    runCatching {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + ctx.packageName),
            )
        } else {
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + ctx.packageName),
            )
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }
}
