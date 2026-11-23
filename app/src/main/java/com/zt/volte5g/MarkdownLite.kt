package com.zt.volte5g

/**
 * 把 Release 说明的 Markdown 精简成适合对话框/小卡片阅读的纯文本。
 *
 * 只做“去掉不好读的标记”，不做完整渲染：外部文本不走 WebView，也不会被当作指令。
 *  - 标题：`### xxx` → `xxx`
 *  - 列表：`- ` / `* ` → `•`
 *  - 行内代码反引号、加粗/斜体星号去掉
 *  - 链接 `[文字](url)` → `文字`，裸 URL 保留（系统会识别为可点）
 *  - 分隔线 `---` 去掉，连续空行压成一行
 */
object MarkdownLite {

    private val heading = Regex("^#{1,6}\\s*")
    private val link = Regex("\\[([^\\]]+)]\\(([^)]+)\\)")
    private val boldItalic = Regex("(\\*\\*|__|\\*|_)([^*_]+)\\1")
    private val hr = Regex("^\\s*([-*_])\\1{2,}\\s*$")

    fun toPlain(markdown: String, maxLines: Int = 40): String {
        if (markdown.isBlank()) return ""
        val out = StringBuilder()
        var blank = false
        for (raw in markdown.lineSequence()) {
            val line = raw.trimEnd()
            if (line.isBlank()) {
                // 连续空行只保留一个
                if (!blank && out.isNotEmpty()) out.append('\n')
                blank = true
                continue
            }
            blank = false
            if (hr.matches(line)) continue

            var text = line
            val indent = text.length - text.trimStart().length
            text = text.trimStart()

            // 引用块
            text = text.removePrefix(">").trimStart()
            // 列表符号
            text = when {
                text.startsWith("- ") || text.startsWith("* ") || text.startsWith("+ ") ->
                    "• " + text.substring(2)
                Regex("^\\d+[.)]\\s+").containsMatchIn(text) ->
                    text.replaceFirst(Regex("^(\\d+)[.)]\\s+"), "$1. ")
                else -> text
            }
            if (indent >= 2 && out.isNotEmpty()) text = "  $text"

            text = heading.replace(text, "")
            text = link.replace(text) { it.groupValues[1] }
            text = boldItalic.replace(text) { it.groupValues[2] }
            text = text.replace("`", "")
            out.append(text).append('\n')
        }

        val lines = out.toString().trim().lines().take(maxLines).toMutableList()
        if (out.toString().trim().lines().size > maxLines) lines[lines.size - 1] = "…"
        return lines.joinToString("\n")
    }
}
