package com.actionmental.core.action

/**
 * Termux 执行完回传的结果，以及它在通知里怎么说。
 *
 * 字段对应 Termux `RUN_COMMAND` 结果包里的同名键。[err] 是 Termux 自己的错误码：
 * [ERR_OK]（-1）表示命令确实跑了，结论看 [exitCode]；其余值说明 Termux 根本没执行
 * （最常见的是没在 termux.properties 里打开 allow-external-apps），原因在 [errmsg]。
 */
data class TermuxOutcome(
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int? = null,
    val err: Int = ERR_OK,
    val errmsg: String = "",
) {
    val executed: Boolean get() = err == ERR_OK
    val succeeded: Boolean get() = executed && exitCode == 0

    /** 通知标题：名字在前，结论在后，一眼扫过去先认出是哪一条。 */
    fun title(name: String, translate: (String) -> String): String = when {
        !executed -> name + " · " + translate("Termux 拒绝执行")
        succeeded -> name + " · " + translate("执行完成")
        else -> name + " · " + translate("退出码") + " " + (exitCode ?: "?")
    }

    /**
     * 通知正文。只留最后几行：命令的结论几乎总在输出末尾，前面是过程。
     * 失败时优先给 stderr；两边都空就明说没有输出，而不是留一条空通知。
     */
    fun body(translate: (String) -> String): String {
        val text = when {
            !executed -> errmsg.ifBlank { translate("未知错误") }
            succeeded -> stdout.ifBlank { stderr }
            else -> stderr.ifBlank { stdout }
        }
        return tail(text).ifBlank { translate("没有输出") }
    }

    /** 折叠态那一行：正文的最后一行。 */
    fun summary(translate: (String) -> String): String = body(translate).lineSequence().last()

    companion object {
        const val ERR_OK = -1
        const val MAX_LINES = 12
        const val MAX_CHARS = 1_200

        /** 行数与字数两道上限：一行几千字的 JSON 也不该把通知撑爆。 */
        fun tail(text: String): String {
            val lines = text.trimEnd().lines().takeLast(MAX_LINES).joinToString("\n")
            return if (lines.length <= MAX_CHARS) lines else "…" + lines.takeLast(MAX_CHARS)
        }
    }
}
