package lo.naui.term

/** 功能键行里一个键 */
data class ExtraKey(
    val label: String,
    /** 按下去发什么；null 表示这是个"修饰键"，由界面自己处理 */
    val send: String? = null,
    val modifier: ModifierKey? = null,
)

enum class ModifierKey { CTRL, ALT, FN }

/**
 * termux 那两排功能键。
 *
 * 第一排：ESC / / - HOME ↑ END PGUP
 * 第二排：TAB CTRL ALT ← ↓ → PGDN
 */
object ExtraKeys {

    val ROW1 = listOf(
        ExtraKey("ESC", TerminalKeys.ESC),
        ExtraKey("/", "/"),
        ExtraKey("-", "-"),
        ExtraKey("HOME", TerminalKeys.HOME),
        ExtraKey("↑", "\u0000UP"),
        ExtraKey("END", TerminalKeys.END),
        ExtraKey("PGUP", TerminalKeys.PAGE_UP),
    )

    val ROW2 = listOf(
        ExtraKey("TAB", TerminalKeys.TAB),
        ExtraKey("CTRL", modifier = ModifierKey.CTRL),
        ExtraKey("ALT", modifier = ModifierKey.ALT),
        ExtraKey("←", "\u0000LEFT"),
        ExtraKey("↓", "\u0000DOWN"),
        ExtraKey("→", "\u0000RIGHT"),
        ExtraKey("PGDN", TerminalKeys.PAGE_DOWN),
    )

    /** 界面按下的键 → 实际要写进 pty 的字节（光标键要看 DECCKM） */
    fun resolve(key: ExtraKey, app: Boolean): String? = when (key.send) {
        "\u0000UP" -> TerminalKeys.up(app)
        "\u0000DOWN" -> TerminalKeys.down(app)
        "\u0000LEFT" -> TerminalKeys.left(app)
        "\u0000RIGHT" -> TerminalKeys.right(app)
        else -> key.send
    }
}
