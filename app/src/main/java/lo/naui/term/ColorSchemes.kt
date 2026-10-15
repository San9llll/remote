package lo.naui.term

/** 一套终端配色：16 色 + 默认前景/背景/光标 */
data class ColorScheme(
    val id: String,
    val name: String,
    val fg: Int,
    val bg: Int,
    val cursor: Int,
    val palette: IntArray,
) {
    override fun equals(other: Any?): Boolean = other is ColorScheme && other.id == id
    override fun hashCode(): Int = id.hashCode()
}

/**
 * 内置配色。
 *
 * 跟 termux 一样按"16 色 + 前景/背景"来定义，用户挑一套，
 * 整个终端（含模拟器里的 ANSI 0-15）立刻跟着换。
 */
object ColorSchemes {

    private fun c(v: Long) = v.toInt()

    val LIST: List<ColorScheme> = listOf(
        ColorScheme(
            "nakour", "Nakour 暗",
            fg = c(0xFFE6E6E6), bg = c(0xFF101014), cursor = c(0xFF7FD1FF),
            palette = intArrayOf(
                c(0xFF000000), c(0xFFCD3131), c(0xFF0DBC79), c(0xFFE5E510),
                c(0xFF2472C8), c(0xFFBC3FBC), c(0xFF11A8CD), c(0xFFE5E5E5),
                c(0xFF666666), c(0xFFF14C4C), c(0xFF23D18B), c(0xFFF5F543),
                c(0xFF3B8EEA), c(0xFFD670D6), c(0xFF29B8DB), c(0xFFFFFFFF),
            ),
        ),
        ColorScheme(
            "termux", "Termux 默认",
            fg = c(0xFFFFFFFF), bg = c(0xFF000000), cursor = c(0xFFFFFFFF),
            palette = intArrayOf(
                c(0xFF000000), c(0xFFCC0000), c(0xFF4E9A06), c(0xFFC4A000),
                c(0xFF3465A4), c(0xFF75507B), c(0xFF06989A), c(0xFFD3D7CF),
                c(0xFF555753), c(0xFFEF2929), c(0xFF8AE234), c(0xFFFCE94F),
                c(0xFF729FCF), c(0xFFAD7FA8), c(0xFF34E2E2), c(0xFFEEEEEC),
            ),
        ),
        ColorScheme(
            "solarized_dark", "Solarized Dark",
            fg = c(0xFF839496), bg = c(0xFF002B36), cursor = c(0xFF839496),
            palette = intArrayOf(
                c(0xFF073642), c(0xFFDC322F), c(0xFF859900), c(0xFFB58900),
                c(0xFF268BD2), c(0xFFD33682), c(0xFF2AA198), c(0xFFEEE8D5),
                c(0xFF002B36), c(0xFFCB4B16), c(0xFF586E75), c(0xFF657B83),
                c(0xFF839496), c(0xFF6C71C4), c(0xFF93A1A1), c(0xFFFDF6E3),
            ),
        ),
        ColorScheme(
            "solarized_light", "Solarized Light",
            fg = c(0xFF657B83), bg = c(0xFFFDF6E3), cursor = c(0xFF657B83),
            palette = intArrayOf(
                c(0xFF073642), c(0xFFDC322F), c(0xFF859900), c(0xFFB58900),
                c(0xFF268BD2), c(0xFFD33682), c(0xFF2AA198), c(0xFFEEE8D5),
                c(0xFF002B36), c(0xFFCB4B16), c(0xFF586E75), c(0xFF657B83),
                c(0xFF839496), c(0xFF6C71C4), c(0xFF93A1A1), c(0xFFFDF6E3),
            ),
        ),
        ColorScheme(
            "gruvbox_dark", "Gruvbox Dark",
            fg = c(0xFFEBDBB2), bg = c(0xFF282828), cursor = c(0xFFEBDBB2),
            palette = intArrayOf(
                c(0xFF282828), c(0xFFCC241D), c(0xFF98971A), c(0xFFD79921),
                c(0xFF458588), c(0xFFB16286), c(0xFF689D6A), c(0xFFA89984),
                c(0xFF928374), c(0xFFFB4934), c(0xFFB8BB26), c(0xFFFABD2F),
                c(0xFF83A598), c(0xFFD3869B), c(0xFF8EC07C), c(0xFFEBDBB2),
            ),
        ),
        ColorScheme(
            "dracula", "Dracula",
            fg = c(0xFFF8F8F2), bg = c(0xFF282A36), cursor = c(0xFFF8F8F2),
            palette = intArrayOf(
                c(0xFF21222C), c(0xFFFF5555), c(0xFF50FA7B), c(0xFFF1FA8C),
                c(0xFFBD93F9), c(0xFFFF79C6), c(0xFF8BE9FD), c(0xFFF8F8F2),
                c(0xFF6272A4), c(0xFFFF6E6E), c(0xFF69FF94), c(0xFFFFFFA5),
                c(0xFFD6ACFF), c(0xFFFF92DF), c(0xFFA4FFFF), c(0xFFFFFFFF),
            ),
        ),
        ColorScheme(
            "nord", "Nord",
            fg = c(0xFFD8DEE9), bg = c(0xFF2E3440), cursor = c(0xFFD8DEE9),
            palette = intArrayOf(
                c(0xFF3B4252), c(0xFFBF616A), c(0xFFA3BE8C), c(0xFFEBCB8B),
                c(0xFF81A1C1), c(0xFFB48EAD), c(0xFF88C0D0), c(0xFFE5E9F0),
                c(0xFF4C566A), c(0xFFBF616A), c(0xFFA3BE8C), c(0xFFEBCB8B),
                c(0xFF81A1C1), c(0xFFB48EAD), c(0xFF8FBCBB), c(0xFFECEFF4),
            ),
        ),
        ColorScheme(
            "one_dark", "One Dark",
            fg = c(0xFFABB2BF), bg = c(0xFF282C34), cursor = c(0xFF528BFF),
            palette = intArrayOf(
                c(0xFF282C34), c(0xFFE06C75), c(0xFF98C379), c(0xFFE5C07B),
                c(0xFF61AFEF), c(0xFFC678DD), c(0xFF56B6C2), c(0xFFABB2BF),
                c(0xFF5C6370), c(0xFFE06C75), c(0xFF98C379), c(0xFFE5C07B),
                c(0xFF61AFEF), c(0xFFC678DD), c(0xFF56B6C2), c(0xFFFFFFFF),
            ),
        ),
        ColorScheme(
            "tokyo_night", "Tokyo Night",
            fg = c(0xFFC0CAF5), bg = c(0xFF1A1B26), cursor = c(0xFFC0CAF5),
            palette = intArrayOf(
                c(0xFF15161E), c(0xFFF7768E), c(0xFF9ECE6A), c(0xFFE0AF68),
                c(0xFF7AA2F7), c(0xFFBB9AF7), c(0xFF7DCFFF), c(0xFFA9B1D6),
                c(0xFF414868), c(0xFFF7768E), c(0xFF9ECE6A), c(0xFFE0AF68),
                c(0xFF7AA2F7), c(0xFFBB9AF7), c(0xFF7DCFFF), c(0xFFC0CAF5),
            ),
        ),
        ColorScheme(
            "material", "Material",
            fg = c(0xFFEEFFFF), bg = c(0xFF263238), cursor = c(0xFFFFCC00),
            palette = intArrayOf(
                c(0xFF000000), c(0xFFFF5370), c(0xFFC3E88D), c(0xFFFFCB6B),
                c(0xFF82AAFF), c(0xFFC792EA), c(0xFF89DDFF), c(0xFFFFFFFF),
                c(0xFF546E7A), c(0xFFFF5370), c(0xFFC3E88D), c(0xFFFFCB6B),
                c(0xFF82AAFF), c(0xFFC792EA), c(0xFF89DDFF), c(0xFFFFFFFF),
            ),
        ),
        ColorScheme(
            "monokai", "Monokai",
            fg = c(0xFFF8F8F2), bg = c(0xFF272822), cursor = c(0xFFF8F8F0),
            palette = intArrayOf(
                c(0xFF272822), c(0xFFF92672), c(0xFFA6E22E), c(0xFFF4BF75),
                c(0xFF66D9EF), c(0xFFAE81FF), c(0xFFA1EFE4), c(0xFFF8F8F2),
                c(0xFF75715E), c(0xFFF92672), c(0xFFA6E22E), c(0xFFF4BF75),
                c(0xFF66D9EF), c(0xFFAE81FF), c(0xFFA1EFE4), c(0xFFF9F8F5),
            ),
        ),
    )

    val DEFAULT: ColorScheme get() = LIST[0]

    fun of(id: String?): ColorScheme = LIST.firstOrNull { it.id == id } ?: DEFAULT
}
