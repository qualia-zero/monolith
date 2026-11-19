package com.monolith.feature.explorer.ai

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class CodeBlock(val code: String) : MdBlock
    data class BulletItem(val text: String) : MdBlock
    data class NumberedItem(val number: String, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data object Divider : MdBlock
}

/**
 * Lightweight, theme-aware Markdown renderer for chat responses.
 * Supports headings, bullet/numbered lists, fenced code blocks, dividers, bold, italic, and inline code.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val blocks = remember(text) { parseMarkdownBlocks(text) }
    val codeBg = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)
    val accent = MaterialTheme.colorScheme.primary

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> {
                    val style = when (block.level) {
                        1 -> MaterialTheme.typography.titleMedium
                        2 -> MaterialTheme.typography.titleSmall
                        else -> MaterialTheme.typography.labelLarge
                    }
                    Text(
                        text = parseInlineMarkdown(block.text, codeBg, accent),
                        style = style.copy(fontWeight = FontWeight.SemiBold),
                        color = color,
                    )
                }

                is MdBlock.Paragraph -> {
                    Text(
                        text = parseInlineMarkdown(block.text, codeBg, accent),
                        style = MaterialTheme.typography.bodyMedium,
                        color = color,
                    )
                }

                is MdBlock.BulletItem -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = accent,
                        )
                        Text(
                            text = parseInlineMarkdown(block.text, codeBg, accent),
                            style = MaterialTheme.typography.bodyMedium,
                            color = color,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                is MdBlock.NumberedItem -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "${block.number}.",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = accent,
                        )
                        Text(
                            text = parseInlineMarkdown(block.text, codeBg, accent),
                            style = MaterialTheme.typography.bodyMedium,
                            color = color,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                is MdBlock.CodeBlock -> {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = codeBg,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = block.code,
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = MaterialTheme.typography.bodySmall.fontSize,
                                lineHeight = MaterialTheme.typography.bodySmall.lineHeight,
                            ),
                            color = color,
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                }

                MdBlock.Divider -> {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
    }
}

private fun parseMarkdownBlocks(input: String): List<MdBlock> {
    val lines = input.replace("\r\n", "\n").split('\n')
    val result = mutableListOf<MdBlock>()
    val paragraphBuffer = mutableListOf<String>()
    var inCodeBlock = false
    val codeBuffer = mutableListOf<String>()

    fun flushParagraph() {
        if (paragraphBuffer.isNotEmpty()) {
            result.add(MdBlock.Paragraph(paragraphBuffer.joinToString("\n")))
            paragraphBuffer.clear()
        }
    }

    for (rawLine in lines) {
        val trimmed = rawLine.trim()
        if (trimmed.startsWith("```")) {
            flushParagraph()
            if (inCodeBlock) {
                result.add(MdBlock.CodeBlock(codeBuffer.joinToString("\n")))
                codeBuffer.clear()
                inCodeBlock = false
            } else {
                inCodeBlock = true
            }
            continue
        }

        if (inCodeBlock) {
            codeBuffer.add(rawLine)
            continue
        }

        when {
            trimmed.isEmpty() -> flushParagraph()
            trimmed == "---" || trimmed == "***" -> {
                flushParagraph()
                result.add(MdBlock.Divider)
            }
            trimmed.startsWith("### ") -> {
                flushParagraph()
                result.add(MdBlock.Heading(3, trimmed.removePrefix("### ").trim()))
            }
            trimmed.startsWith("## ") -> {
                flushParagraph()
                result.add(MdBlock.Heading(2, trimmed.removePrefix("## ").trim()))
            }
            trimmed.startsWith("# ") -> {
                flushParagraph()
                result.add(MdBlock.Heading(1, trimmed.removePrefix("# ").trim()))
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                flushParagraph()
                result.add(MdBlock.BulletItem(trimmed.drop(2).trimStart()))
            }
            else -> {
                val numbered = Regex("^(\\d+)\\.\\s+(.*)$").matchEntire(trimmed)
                if (numbered != null) {
                    flushParagraph()
                    result.add(MdBlock.NumberedItem(numbered.groupValues[1], numbered.groupValues[2]))
                } else {
                    paragraphBuffer.add(rawLine)
                }
            }
        }
    }

    if (inCodeBlock && codeBuffer.isNotEmpty()) {
        result.add(MdBlock.CodeBlock(codeBuffer.joinToString("\n")))
    }
    flushParagraph()
    return result
}

private fun parseInlineMarkdown(
    text: String,
    codeBackground: Color,
    accentColor: Color,
): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        val n = text.length
        while (i < n) {
            when {
                text[i] == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i + 1) {
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                background = codeBackground,
                                color = accentColor,
                            ),
                        ) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append(text[i])
                        i++
                    }
                }

                i + 1 < n && text[i] == '*' && text[i + 1] == '*' -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > i + 2) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        append("**")
                        i += 2
                    }
                }

                text[i] == '*' -> {
                    val end = text.indexOf('*', i + 1)
                    if (end > i + 1) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append('*')
                        i++
                    }
                }

                else -> {
                    append(text[i])
                    i++
                }
            }
        }
    }
}
