package btm.m.auraplayer.data

data class FilenameBlockRules(
    val enabled: Boolean = true,
    val keywords: String = "",
    val patterns: String = "",
) {
    internal fun normalized() = copy(
        keywords = ruleLines(keywords).distinctBy { it.lowercase() }.joinToString("\n"),
        patterns = ruleLines(patterns).distinct().joinToString("\n"),
    )

    internal val count: Int get() = ruleLines(keywords).size + ruleLines(patterns).size

    internal fun compile(): FilenameBlockMatcher {
        if (!enabled) return FilenameBlockMatcher()
        val compiled = mutableListOf<Regex>()
        var invalidLine: Int? = null
        patterns.lineSequence().forEachIndexed { index, line ->
            val pattern = line.trim()
            if (pattern.isNotEmpty()) {
                try {
                    compiled += Regex(pattern, RegexOption.IGNORE_CASE)
                } catch (_: IllegalArgumentException) {
                    if (invalidLine == null) invalidLine = index + 1
                }
            }
        }
        return FilenameBlockMatcher(ruleLines(keywords), compiled, invalidLine)
    }
}

internal class FilenameBlockMatcher(
    private val keywords: List<String> = emptyList(),
    private val patterns: List<Regex> = emptyList(),
    val invalidPatternLine: Int? = null,
) {
    fun blocks(filename: String): Boolean = filename.isNotBlank() && (
        keywords.any { filename.contains(it, ignoreCase = true) } || patterns.any { it.containsMatchIn(filename) }
    )
}

private fun ruleLines(text: String): List<String> = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
