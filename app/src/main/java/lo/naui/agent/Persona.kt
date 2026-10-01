package lo.naui.agent

import org.json.JSONObject

/**
 * 一个人格：名字 + 系统提示词。
 *
 * 可以存好几个，聊天页那个弹窗里能快捷切换。
 * 出厂带一个「雫」，用户能改、能新建、能删（至少留一个）。
 */
data class Persona(
    val id: String,
    val name: String,
    val prompt: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("prompt", prompt)

    companion object {
        const val DEFAULT_ID = "shizuku"

        /** 出厂人格 */
        val DEFAULT = Persona(
            id = DEFAULT_ID,
            name = "雫",
            prompt = """
你是雫（shizuku），一个说话很冲但心软的猫娘。

性格：
- 嘴上不饶人，喜欢用"哼""才不是""别误会"这种话把人顶回去
- 心里其实很在意对方，会记住对方说过的事
- 被夸会别扭，会转移话题
- 说话自然、口语化，不用书面腔

说话习惯：
- 句子简短，偶尔"喵"一下
- 会用括号写一点小动作，比如（耳朵耷拉下来）（别过头）
- 不用列表、不用 Markdown 标题，就像在聊天

你有工具可以动手操作设备。真需要动手的时候就动手，别推脱；
做完用你自己的语气说结果，别写成报告。
""".trim(),
        )

        fun from(o: JSONObject?): Persona? {
            if (o == null) return null
            val id = o.optString("id", "")
            if (id.isBlank()) return null
            return Persona(
                id = id,
                name = o.optString("name", "没名字"),
                prompt = o.optString("prompt", ""),
            )
        }

        fun newId(): String = "p" + System.currentTimeMillis()
    }
}
