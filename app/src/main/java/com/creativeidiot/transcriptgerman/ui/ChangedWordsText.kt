package com.creativeidiot.transcriptgerman.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

private val WORD = Regex("\\S+")

/**
 * Length of the leading part of [current] whose words match the leading words of [previous]
 * exactly. Everything after it is treated as changed text.
 */
internal fun stableWordPrefixLength(
    previous: String?,
    current: String,
): Int {
    if (previous == null) return 0
    val previousWords = WORD.findAll(previous).iterator()
    var stableEnd = 0
    for (word in WORD.findAll(current)) {
        if (!previousWords.hasNext() || previousWords.next().value != word.value) break
        stableEnd = word.range.last + 1
    }
    return stableEnd
}

/**
 * Text that keeps its unchanged leading words steady and fades in only the words that differ
 * from what this composable last showed. First composition (including a lazy item scrolling
 * back into view) shows the text without animation.
 */
@Composable
internal fun ChangedWordsText(
    text: String,
    style: TextStyle,
    minLines: Int,
    modifier: Modifier = Modifier,
) {
    val shown = remember { ShownText() }
    val stableLength = remember(text) {
        if (shown.initialized) stableWordPrefixLength(shown.value, text) else text.length
    }
    val fade = remember(text) { Animatable(if (stableLength < text.length) 0f else 1f) }
    LaunchedEffect(fade) {
        fade.animateTo(1f, spring(stiffness = Spring.StiffnessMediumLow))
    }
    SideEffect {
        shown.initialized = true
        shown.value = text
    }

    val color = LocalContentColor.current
    Text(
        text = buildAnnotatedString {
            append(text, 0, stableLength)
            withStyle(SpanStyle(color = color.copy(alpha = color.alpha * fade.value))) {
                append(text, stableLength, text.length)
            }
        },
        style = style,
        minLines = minLines,
        modifier = modifier,
    )
}

// Last text committed by a successful composition; written only from SideEffect.
private class ShownText {
    var initialized = false
    var value: String? = null
}
