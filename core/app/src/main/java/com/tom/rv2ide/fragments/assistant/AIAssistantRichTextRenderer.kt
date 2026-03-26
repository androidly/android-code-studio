package com.tom.rv2ide.fragments.assistant

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.util.LruCache
import android.widget.TextView
import androidx.core.text.HtmlCompat
import com.termux.shared.markdown.MarkdownUtils
import io.noties.markwon.Markwon
import java.util.WeakHashMap
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object AIAssistantRichTextRenderer {

    private const val formattedMarkdownCacheChars = 48_000
    private const val renderedMarkdownCacheChars = 96_000
    private const val defaultMessageId = -1L
    private const val maxCacheableRichTextChars = 12_000
    private const val maxRichRenderedChars = 24_000

    private val formattedMarkdownCache = object : LruCache<String, String>(formattedMarkdownCacheChars) {
        override fun sizeOf(key: String, value: String): Int = value.length.coerceAtLeast(1)
    }

    private val markwonCache = object : LruCache<String, Markwon>(4) {}

    private val renderedMarkdownCache = object : LruCache<String, Spanned>(renderedMarkdownCacheChars) {
        override fun sizeOf(key: String, value: Spanned): Int = (value.length.coerceAtLeast(1) * 4)
    }

    private val renderScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default.limitedParallelism(2)
    )
    private val textViewRenderJobs = WeakHashMap<TextView, Job>()

    fun render(
        textView: TextView,
        rawText: String,
        isStreaming: Boolean = false
    ) {
        render(
            textView = textView,
            rawText = rawText,
            messageId = defaultMessageId,
            isStreaming = isStreaming
        )
    }

    fun render(
        textView: TextView,
        rawText: String,
        messageId: Long,
        isStreaming: Boolean = false
    ) {
        if (rawText.isBlank()) {
            cancel(textView)
            textView.text = ""
            textView.tag = null
            return
        }

        val nightMode = textView.context.applicationContext.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK
        val textSizePx = textView.textSize.roundToInt()
        val contentKey = buildContentKey(
            messageId = messageId,
            rawText = rawText,
            isStreaming = isStreaming
        )
        val renderKey = "$nightMode|${textView.currentTextColor}|$textSizePx|$contentKey"
        if (
            textView.tag == renderKey &&
            textView.text?.isNotEmpty() == true &&
            synchronized(textViewRenderJobs) { textViewRenderJobs[textView] } == null
        ) {
            return
        }

        if (isStreaming && rawText.length > maxStreamingRichRenderedChars) {
            cancel(textView)
            textView.tag = renderKey
            textView.text = buildStreamingDisplayText(rawText)
            return
        }

        if (rawText.length > maxRichRenderedChars) {
            cancel(textView)
            textView.tag = renderKey
            textView.text = rawText
            return
        }

        textView.linksClickable = true
        textView.movementMethod = LinkMovementMethod.getInstance()
        cancel(textView)
        textView.tag = renderKey

        val cacheRichRendering = !isStreaming && rawText.length <= maxCacheableRichTextChars
        val markwon = markwon(
            context = textView.context.applicationContext,
            textSizePx = textSizePx
        )
        val renderedMarkdown = if (cacheRichRendering) {
            synchronized(renderedMarkdownCache) {
                renderedMarkdownCache.get(renderKey)
            }
        } else {
            null
        }
        if (renderedMarkdown != null) {
            synchronized(markwon) {
                markwon.setParsedMarkdown(textView, renderedMarkdown)
            }
            return
        }

        textView.text = if (isStreaming) {
            buildStreamingDisplayText(rawText)
        } else {
            rawText
        }
        var job: Job? = null
        job = renderScope.launch {
            val markdown = if (cacheRichRendering) {
                synchronized(formattedMarkdownCache) {
                    formattedMarkdownCache.get(contentKey)
                        ?: AIAssistantMarkdownFormatter.format(rawText, isStreaming).also {
                            formattedMarkdownCache.put(contentKey, it)
                        }
                }
            } else {
                AIAssistantMarkdownFormatter.format(rawText, isStreaming)
            }
            val rendered = if (cacheRichRendering) {
                synchronized(renderedMarkdownCache) {
                    renderedMarkdownCache.get(renderKey)
                }
            } else {
                null
            } ?: synchronized(markwon) {
                markwon.toMarkdown(markdown)
            }.let(AIAssistantLocalLinkSupport::rewriteLocalLinks)
                .let { localizedSpanned ->
                    if (localizedSpanned is SpannableStringBuilder) {
                        SpannableStringBuilder(localizedSpanned)
                    } else {
                        localizedSpanned
                    }
                }.also { spanned ->
                if (cacheRichRendering) {
                    synchronized(renderedMarkdownCache) {
                        renderedMarkdownCache.put(renderKey, spanned)
                    }
                }
            }

            withContext(Dispatchers.Main.immediate) {
                val activeJob = job ?: return@withContext
                val activeRenderKey = textView.tag as? String
                if (activeRenderKey != renderKey) {
                    clearRenderJob(textView, activeJob)
                    return@withContext
                }
                synchronized(markwon) {
                    markwon.setParsedMarkdown(textView, rendered)
                }
                clearRenderJob(textView, activeJob)
            }
        }
        synchronized(textViewRenderJobs) {
            textViewRenderJobs[textView] = job
        }
    }

    fun cancel(textView: TextView) {
        synchronized(textViewRenderJobs) {
            textViewRenderJobs.remove(textView)?.cancel()
        }
    }

    fun clearCaches(cancelJobs: Boolean = false) {
        if (cancelJobs) {
            val jobsToCancel = synchronized(textViewRenderJobs) {
                textViewRenderJobs.values.toList().also { textViewRenderJobs.clear() }
            }
            jobsToCancel.forEach(Job::cancel)
        }
        synchronized(formattedMarkdownCache) {
            formattedMarkdownCache.evictAll()
        }
        synchronized(renderedMarkdownCache) {
            renderedMarkdownCache.evictAll()
        }
        synchronized(markwonCache) {
            markwonCache.evictAll()
        }
    }

    fun trimMemory(level: Int) {
        val shouldCancelJobs = level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
        val shouldEvictAll = level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        if (shouldEvictAll) {
            clearCaches(cancelJobs = shouldCancelJobs)
            return
        }
        if (shouldCancelJobs) {
            synchronized(textViewRenderJobs) {
                textViewRenderJobs.values.toList().forEach(Job::cancel)
                textViewRenderJobs.clear()
            }
        }
        synchronized(formattedMarkdownCache) {
            formattedMarkdownCache.trimToSize(formattedMarkdownCacheChars / 2)
        }
        synchronized(renderedMarkdownCache) {
            renderedMarkdownCache.trimToSize(renderedMarkdownCacheChars / 2)
        }
        synchronized(markwonCache) {
            markwonCache.trimToSize(2)
        }
    }

    private fun markwon(
        context: android.content.Context,
        textSizePx: Int
    ): Markwon {
        val nightMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val cacheKey = "$nightMode|$textSizePx"
        synchronized(markwonCache) {
            return markwonCache.get(cacheKey)
                ?: MarkdownUtils.getRecyclerMarkwonBuilder(context, textSizePx.toFloat()).also {
                    markwonCache.put(cacheKey, it)
                }
        }
    }

    private fun clearRenderJob(textView: TextView, job: Job) {
        synchronized(textViewRenderJobs) {
            if (textViewRenderJobs[textView] == job) {
                textViewRenderJobs.remove(textView)
            }
        }
    }

    private fun buildContentKey(
        messageId: Long,
        rawText: String,
        isStreaming: Boolean
    ): String {
        return buildString {
            append(messageId)
            append('|')
            append(if (isStreaming) '1' else '0')
            append('|')
            append(rawText.length)
            append('|')
            append(rawText.hashCode())
        }
    }

    private fun buildStreamingDisplayText(rawText: String): CharSequence {
        return if (rawText.isBlank()) {
            ""
        } else {
            "$rawText\n\n▍"
        }
    }

    private const val maxStreamingRichRenderedChars = 12_000
}

private object AIAssistantMarkdownFormatter {

    fun format(
        rawText: String,
        isStreaming: Boolean
    ): String {
        val normalized = rawText.replace("\r\n", "\n")
        var formatted = normalized
        formatted = transformOutsideCodeFences(formatted, ::rewriteHtmlFragments)
        formatted = transformOutsideCodeFences(formatted, ::rewriteMarkdownImages)
        formatted = transformOutsideCodeFences(formatted, ::rewriteMarkdownTables)
        formatted = transformOutsideCodeFences(formatted, ::rewriteMathSegments)

        return if (isStreaming && formatted.isNotBlank()) {
            "$formatted\n\n▍"
        } else {
            formatted
        }
    }

    private fun transformOutsideCodeFences(
        source: String,
        transformer: (String) -> String
    ): String {
        if (source.isBlank()) {
            return source
        }

        val result = StringBuilder(source.length + 32)
        val plainTextBuffer = StringBuilder()
        var inFence = false

        eachLinePreservingNewline(source) { line ->
            if (line.trimStart().startsWith("```")) {
                if (!inFence && plainTextBuffer.isNotEmpty()) {
                    result.append(transformer(plainTextBuffer.toString()))
                    plainTextBuffer.setLength(0)
                }
                inFence = !inFence
                result.append(line)
            } else if (inFence) {
                result.append(line)
            } else {
                plainTextBuffer.append(line)
            }
        }

        if (plainTextBuffer.isNotEmpty()) {
            result.append(transformer(plainTextBuffer.toString()))
        }

        return result.toString()
    }

    private fun rewriteHtmlFragments(source: String): String {
        if (!source.contains('<')) {
            return source
        }

        var rewritten = source
        rewritten = htmlImagePattern.replace(rewritten) { match ->
            val src = decodeHtmlEntities(match.groupValues[2]).trim()
            val alt = decodeHtmlEntities(match.groupValues[4]).trim().ifBlank { "image" }
            "[Image: $alt]($src)"
        }
        rewritten = htmlPreCodePattern.replace(rewritten) { match ->
            val language = extractCodeLanguage(match.groupValues[2])
            val code = decodeHtmlEntities(match.groupValues[3]).trim('\n', '\r')
            buildString {
                append("```")
                if (!language.isNullOrBlank()) {
                    append(language)
                }
                append('\n')
                append(code)
                append("\n```")
            }
        }
        rewritten = htmlTablePattern.replace(rewritten) { match ->
            htmlTableToCodeBlock(match.groupValues[1])
        }
        rewritten = htmlHeadingPattern.replace(rewritten) { match ->
            val level = match.groupValues[1].toIntOrNull()?.coerceIn(1, 6) ?: 1
            "${"#".repeat(level)} ${rewriteInlineHtml(match.groupValues[2]).trim()}\n"
        }
        rewritten = htmlBlockquotePattern.replace(rewritten) { match ->
            rewriteInlineHtml(match.groupValues[1])
                .lines()
                .joinToString("\n") { line ->
                    val trimmed = line.trim()
                    if (trimmed.isBlank()) {
                        ">"
                    } else {
                        "> $trimmed"
                    }
                }
        }
        rewritten = htmlBreakPattern.replace(rewritten, "\n")
        rewritten = htmlParagraphClosePattern.replace(rewritten, "\n\n")
        rewritten = htmlParagraphOpenPattern.replace(rewritten, "")
        rewritten = htmlHrPattern.replace(rewritten, "\n---\n")
        rewritten = htmlListOpenClosePattern.replace(rewritten, "\n")
        rewritten = htmlListItemOpenPattern.replace(rewritten, "- ")
        rewritten = htmlListItemClosePattern.replace(rewritten, "\n")
        rewritten = htmlDivOpenPattern.replace(rewritten, "")
        rewritten = htmlDivClosePattern.replace(rewritten, "\n")
        rewritten = rewriteInlineHtml(rewritten)
        rewritten = rewritten.replace(Regex("""[ \t]+\n"""), "\n")
        rewritten = rewritten.replace(Regex("""\n{3,}"""), "\n\n")
        return rewritten
    }

    private fun rewriteMarkdownImages(source: String): String {
        return markdownImagePattern.replace(source) { match ->
            val alt = match.groupValues[1].trim().ifBlank { "image" }
            val url = match.groupValues[2].trim()
            "[Image: $alt]($url)"
        }
    }

    private fun rewriteInlineHtml(source: String): String {
        var rewritten = source
        rewritten = htmlAnchorPattern.replace(rewritten) { match ->
            val url = decodeHtmlEntities(match.groupValues[2]).trim()
            val label = rewriteInlineHtml(match.groupValues[3]).trim().ifBlank { url }
            "[$label]($url)"
        }
        rewritten = htmlInlineCodePattern.replace(rewritten) { match ->
            MarkdownUtils.getMarkdownCodeForString(
                decodeHtmlEntities(stripHtmlTags(match.groupValues[1])).trim(),
                false
            )
        }
        rewritten = htmlBoldPattern.replace(rewritten, "**")
        rewritten = htmlItalicPattern.replace(rewritten, "_")
        rewritten = htmlStrikePattern.replace(rewritten, "~~")
        rewritten = htmlUnderlinePattern.replace(rewritten, "")
        rewritten = htmlTagPattern.replace(rewritten, "")
        return decodeHtmlEntities(rewritten)
    }

    private fun rewriteMarkdownTables(source: String): String {
        if (!source.contains('|')) {
            return source
        }

        val trailingNewline = source.endsWith('\n')
        val lines = source.split('\n')
        val renderedLines = mutableListOf<String>()
        var index = 0

        while (index < lines.size) {
            if (index + 1 < lines.size &&
                looksLikeMarkdownTableHeader(lines[index], lines[index + 1])
            ) {
                val block = mutableListOf(lines[index])
                block += lines[index + 1]
                var rowIndex = index + 2
                while (rowIndex < lines.size && looksLikeMarkdownTableRow(lines[rowIndex])) {
                    block += lines[rowIndex]
                    rowIndex += 1
                }
                renderedLines += renderMarkdownTableBlock(block)
                index = rowIndex
                continue
            }

            renderedLines += lines[index]
            index += 1
        }

        return renderedLines.joinToString("\n").let { joined ->
            if (trailingNewline) {
                "$joined\n"
            } else {
                joined
            }
        }
    }

    private fun rewriteMathSegments(source: String): String {
        val result = StringBuilder(source.length + 32)
        var index = 0
        var inlineCodeFenceLength = 0

        while (index < source.length) {
            val current = source[index]
            if (current == '`') {
                val backtickCount = source.countRepeated(index, '`')
                if (inlineCodeFenceLength == 0) {
                    inlineCodeFenceLength = backtickCount
                } else if (backtickCount == inlineCodeFenceLength) {
                    inlineCodeFenceLength = 0
                }
                result.append(source, index, index + backtickCount)
                index += backtickCount
                continue
            }

            if (inlineCodeFenceLength > 0) {
                result.append(current)
                index += 1
                continue
            }

            if (source.startsWith("\\[", index)) {
                val closingIndex = findDelimitedMathEnd(source, startIndex = index + 2, closingToken = "\\]")
                if (closingIndex >= 0) {
                    val formula = source.substring(index + 2, closingIndex).trim()
                    if (formula.isNotBlank()) {
                        appendBlockMath(result, formula)
                        val nextIndex = closingIndex + 2
                        if (nextIndex < source.length && source[nextIndex] != '\n') {
                            result.append('\n')
                        }
                        index = nextIndex
                        continue
                    }
                }
            }

            if (source.startsWith("\\(", index)) {
                val closingIndex = findDelimitedMathEnd(source, startIndex = index + 2, closingToken = "\\)")
                if (closingIndex >= 0) {
                    val formula = source.substring(index + 2, closingIndex).trim()
                    if (formula.isNotBlank()) {
                        appendInlineMath(result, formula)
                        index = closingIndex + 2
                        continue
                    }
                }
            }

            if (current == '$' && !source.isEscaped(index)) {
                val closingIndex = findInlineFormulaEnd(source, startIndex = index + 1)
                if (closingIndex >= 0) {
                    val formula = source.substring(index + 1, closingIndex).trim()
                    if (looksLikeInlineFormula(formula)) {
                        appendInlineMath(result, formula)
                        index = closingIndex + 1
                        continue
                    }
                }
            }

            result.append(current)
            index += 1
        }

        return result.toString()
    }

    private fun looksLikeInlineFormula(formula: String): Boolean {
        if (formula.isBlank() || '\n' in formula) {
            return false
        }
        if (formula.matches(Regex("""\d+(?:[.,]\d+)?"""))) {
            return false
        }
        if (formula.contains('\\')) {
            return true
        }
        if (formula.any { it in charArrayOf('=', '^', '_', '{', '}', '(', ')', '[', ']', '+', '-', '*', '/', '<', '>') }) {
            return true
        }
        return formula.any(Char::isDigit) && formula.any(Char::isLetter)
    }

    private fun findInlineFormulaEnd(source: String, startIndex: Int): Int {
        var index = startIndex
        while (index < source.length) {
            val current = source[index]
            if (current == '\n') {
                return -1
            }
            if (current == '$' && !source.isEscaped(index)) {
                return index
            }
            index += 1
        }
        return -1
    }

    private fun htmlTableToCodeBlock(tableBody: String): String {
        val rows = htmlRowPattern.findAll(tableBody).mapNotNull { rowMatch ->
            val rawCells = htmlCellPattern.findAll(rowMatch.groupValues[1]).map { cellMatch ->
                normalizeTableCell(rewriteInlineHtml(cellMatch.groupValues[1]))
            }.toList()
            rawCells.takeIf { it.isNotEmpty() }
        }.toList()

        if (rows.isEmpty()) {
            return decodeHtmlEntities(stripHtmlTags(tableBody))
        }

        val hasHeader = htmlTableHeaderCellPattern.containsMatchIn(tableBody)
        return renderTableCodeBlock(rows, hasHeader = hasHeader)
    }

    private fun renderMarkdownTableBlock(lines: List<String>): String {
        val header = parseMarkdownTableCells(lines.first())
        val bodyRows = lines.drop(2).map(::parseMarkdownTableCells)
        val rows = buildList {
            add(header)
            addAll(bodyRows)
        }
        return renderTableCodeBlock(rows, hasHeader = true)
    }

    private fun renderTableCodeBlock(
        rows: List<List<String>>,
        hasHeader: Boolean
    ): String {
        if (rows.isEmpty()) {
            return ""
        }

        val columnCount = rows.maxOf { it.size }
        val normalizedRows = rows.map { row ->
            List(columnCount) { index -> row.getOrNull(index).orEmpty() }
        }
        val widths = IntArray(columnCount)
        normalizedRows.forEach { row ->
            row.forEachIndexed { index, cell ->
                widths[index] = maxOf(widths[index], cell.length)
            }
        }

        fun renderRow(row: List<String>): String {
            return buildString {
                append("| ")
                row.forEachIndexed { index, cell ->
                    append(cell.padEnd(widths[index]))
                    append(" |")
                    if (index != row.lastIndex) {
                        append(' ')
                    }
                }
            }
        }

        val rendered = buildList {
            add(renderRow(normalizedRows.first()))
            if (hasHeader) {
                add(
                    buildString {
                        append("| ")
                        widths.forEachIndexed { index, width ->
                            append("-".repeat(maxOf(3, width)))
                            append(" |")
                            if (index != widths.lastIndex) {
                                append(' ')
                            }
                        }
                    }
                )
            }
            normalizedRows.drop(1).forEach { add(renderRow(it)) }
        }

        return buildString {
            append("```text\n")
            append(rendered.joinToString("\n"))
            append("\n```")
        }
    }

    private fun parseMarkdownTableCells(line: String): List<String> {
        return line.trim()
            .trim('|')
            .split('|')
            .map { normalizeTableCell(it) }
    }

    private fun looksLikeMarkdownTableHeader(
        headerLine: String,
        separatorLine: String
    ): Boolean {
        if (!headerLine.contains('|') || !separatorLine.contains('-')) {
            return false
        }
        if (!markdownTableSeparatorPattern.matches(separatorLine.trim())) {
            return false
        }
        return parseMarkdownTableCells(headerLine).size >= 2
    }

    private fun looksLikeMarkdownTableRow(line: String): Boolean {
        if (line.isBlank() || !line.contains('|')) {
            return false
        }
        return parseMarkdownTableCells(line).size >= 2
    }

    private fun normalizeTableCell(cell: String): String {
        return decodeHtmlEntities(stripHtmlTags(cell))
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun appendInlineMath(
        result: StringBuilder,
        formula: String
    ) {
        result.append("$$")
        result.append(formula.trim())
        result.append("$$")
    }

    private fun appendBlockMath(
        result: StringBuilder,
        formula: String
    ) {
        if (result.isNotEmpty() && result.last() != '\n') {
            result.append('\n')
        }
        result.append("$$\n")
        result.append(formula.trim())
        result.append("\n$$")
    }

    private fun findDelimitedMathEnd(
        source: String,
        startIndex: Int,
        closingToken: String
    ): Int {
        var index = source.indexOf(closingToken, startIndex)
        while (index >= 0) {
            if (!source.isEscaped(index)) {
                return index
            }
            index = source.indexOf(closingToken, index + closingToken.length)
        }
        return -1
    }

    private fun eachLinePreservingNewline(
        source: String,
        consumer: (String) -> Unit
    ) {
        if (source.isEmpty()) {
            return
        }

        var startIndex = 0
        while (startIndex < source.length) {
            val newlineIndex = source.indexOf('\n', startIndex)
            if (newlineIndex < 0) {
                consumer(source.substring(startIndex))
                return
            }
            consumer(source.substring(startIndex, newlineIndex + 1))
            startIndex = newlineIndex + 1
        }
    }

    private fun String.isEscaped(index: Int): Boolean {
        var backslashCount = 0
        var cursor = index - 1
        while (cursor >= 0 && this[cursor] == '\\') {
            backslashCount += 1
            cursor -= 1
        }
        return backslashCount % 2 == 1
    }

    private fun String.countRepeated(
        startIndex: Int,
        value: Char
    ): Int {
        var cursor = startIndex
        while (cursor < length && this[cursor] == value) {
            cursor += 1
        }
        return cursor - startIndex
    }

    private fun extractCodeLanguage(rawClassName: String): String? {
        if (rawClassName.isBlank()) {
            return null
        }
        return rawClassName
            .split(' ', '\t', '\n')
            .firstNotNullOfOrNull { token ->
                when {
                    token.startsWith("language-") -> token.removePrefix("language-")
                    token.startsWith("lang-") -> token.removePrefix("lang-")
                    else -> null
                }
            }
    }

    private fun stripHtmlTags(source: String): String {
        return htmlTagPattern.replace(source, "")
    }

    private fun decodeHtmlEntities(source: String): String {
        if (!source.contains('&')) {
            return source
        }
        return HtmlCompat.fromHtml(source, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
    }

    private val htmlPreCodePattern = Regex(
        pattern = """<pre\b[^>]*>\s*<code(?:\s+class\s*=\s*(['"])(.*?)\1)?[^>]*>(.*?)</code>\s*</pre>""",
        options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val htmlImagePattern = Regex(
        pattern = """<img\b(?=[^>]*\bsrc\s*=\s*(['"])(.*?)\1)(?:(?=[^>]*\balt\s*=\s*(['"])(.*?)\3))?[^>]*>""",
        options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val htmlInlineCodePattern = Regex(
        pattern = """<code\b[^>]*>(.*?)</code>""",
        options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val htmlAnchorPattern = Regex(
        pattern = """<a\b[^>]*href\s*=\s*(['"])(.*?)\1[^>]*>(.*?)</a>""",
        options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val htmlHeadingPattern = Regex(
        pattern = """<h([1-6])\b[^>]*>(.*?)</h\1>""",
        options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val htmlBlockquotePattern = Regex(
        pattern = """<blockquote\b[^>]*>(.*?)</blockquote>""",
        options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val htmlTablePattern = Regex(
        pattern = """<table\b[^>]*>(.*?)</table>""",
        options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val htmlRowPattern = Regex(
        pattern = """<tr\b[^>]*>(.*?)</tr>""",
        options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val htmlCellPattern = Regex(
        pattern = """<t[hd]\b[^>]*>(.*?)</t[hd]>""",
        options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val htmlTableHeaderCellPattern = Regex("""<th\b""", RegexOption.IGNORE_CASE)
    private val htmlBreakPattern = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val htmlParagraphOpenPattern = Regex("""<p\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val htmlParagraphClosePattern = Regex("""</p\s*>""", RegexOption.IGNORE_CASE)
    private val htmlDivOpenPattern = Regex("""<div\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val htmlDivClosePattern = Regex("""</div\s*>""", RegexOption.IGNORE_CASE)
    private val htmlHrPattern = Regex("""<hr\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val htmlListOpenClosePattern = Regex("""</?(ul|ol)\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val htmlListItemOpenPattern = Regex("""<li\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val htmlListItemClosePattern = Regex("""</li\s*>""", RegexOption.IGNORE_CASE)
    private val htmlBoldPattern = Regex("""</?(strong|b)\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val htmlItalicPattern = Regex("""</?(em|i)\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val htmlStrikePattern = Regex("""</?(s|del)\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val htmlUnderlinePattern = Regex("""</?u\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val htmlTagPattern = Regex("""</?[A-Za-z][^>]*>""", RegexOption.IGNORE_CASE)
    private val markdownImagePattern = Regex("""!\[([^\]]*)]\(([^)\s]+(?:\s+"[^"]*")?)\)""")
    private val markdownTableSeparatorPattern = Regex(
        """^\|?(?:\s*:?-{3,}:?\s*\|)+\s*:?-{3,}:?\s*\|?$"""
    )
}
