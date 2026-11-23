package me.animeheaven.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.animeheaven.tv.data.model.KeyboardMode
import me.animeheaven.tv.ui.theme.LocalAhPalette

private val QWERTY_ROWS = listOf(
    listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
    listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
    listOf("z", "x", "c", "v", "b", "n", "m", "-", "'")
)

private val ABC_ROWS = listOf(
    listOf("a", "b", "c", "d", "e", "f", "g"),
    listOf("h", "i", "j", "k", "l", "m", "n"),
    listOf("o", "p", "q", "r", "s", "t", "u"),
    listOf("v", "w", "x", "y", "z", "-", "'")
)

private val SYMBOL_ROWS = listOf(
    listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
    listOf(":", "!", "?", ".", "/", "(", ")", "&", "+", "_"),
    listOf("-", "'", ",", "@", "#", "*")
)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TvCustomKeyboard(
    mode: KeyboardMode,
    firstKeyFocusRequester: FocusRequester,
    resultsFocusRequester: FocusRequester?,
    hasResults: Boolean,
    onChar: (String) -> Unit,
    onSpace: () -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onSearch: () -> Unit,
    onChangeMode: (KeyboardMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalAhPalette.current
    val rows = when (mode) {
        KeyboardMode.QWERTY -> QWERTY_ROWS
        KeyboardMode.ABC -> ABC_ROWS
        KeyboardMode.SYMBOLS -> SYMBOL_ROWS
    }

    Column(
        modifier = modifier
            .background(palette.surface, AhShape4)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        rows.forEachIndexed { rowIndex, rowChars ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                rowChars.forEachIndexed { colIndex, ch ->
                    val isFirst = rowIndex == 0 && colIndex == 0
                    val isLastInRow = colIndex == rowChars.lastIndex
                    val keyModifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .then(if (isFirst) Modifier.focusRequester(firstKeyFocusRequester) else Modifier)
                        .then(
                            if (isLastInRow && hasResults && resultsFocusRequester != null) {
                                Modifier.focusProperties { right = resultsFocusRequester }
                            } else {
                                Modifier
                            }
                        )

                    KeyCap(
                        label = ch.uppercase(),
                        onClick = { onChar(ch) },
                        modifier = keyModifier
                    )
                }
            }
        }

        // Row 4: Mode toggles, Space, Backspace
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            val nextMode = when (mode) {
                KeyboardMode.QWERTY -> KeyboardMode.ABC
                KeyboardMode.ABC -> KeyboardMode.QWERTY
                KeyboardMode.SYMBOLS -> KeyboardMode.QWERTY
            }
            val nextModeLabel = when (mode) {
                KeyboardMode.QWERTY -> "ABC"
                KeyboardMode.ABC -> "QWERTY"
                KeyboardMode.SYMBOLS -> "QWERTY"
            }

            KeyCap(
                label = nextModeLabel,
                onClick = { onChangeMode(nextMode) },
                modifier = Modifier
                    .weight(1.6f)
                    .height(44.dp)
            )
            KeyCap(
                label = if (mode == KeyboardMode.SYMBOLS) "ABC" else "123",
                onClick = {
                    onChangeMode(
                        if (mode == KeyboardMode.SYMBOLS) KeyboardMode.ABC else KeyboardMode.SYMBOLS
                    )
                },
                modifier = Modifier
                    .weight(1.3f)
                    .height(44.dp)
            )
            KeyCap(
                label = "SPACE",
                onClick = onSpace,
                modifier = Modifier
                    .weight(2.8f)
                    .height(44.dp)
            )
            KeyCap(
                label = "⌫",
                onClick = onBackspace,
                modifier = Modifier
                    .weight(1.4f)
                    .height(44.dp)
                    .then(
                        if (hasResults && resultsFocusRequester != null) {
                            Modifier.focusProperties { right = resultsFocusRequester }
                        } else {
                            Modifier
                        }
                    )
            )
        }

        // Row 5: Clear & Search Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            KeyCap(
                label = "CLEAR",
                onClick = onClear,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
            )
            KeyCap(
                label = "SEARCH",
                isPrimary = true,
                onClick = onSearch,
                modifier = Modifier
                    .weight(1.6f)
                    .height(44.dp)
                    .then(
                        if (hasResults && resultsFocusRequester != null) {
                            Modifier.focusProperties { right = resultsFocusRequester }
                        } else {
                            Modifier
                        }
                    )
            )
        }
    }
}

@Composable
private fun KeyCap(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isPrimary: Boolean = false
) {
    val palette = LocalAhPalette.current
    AhFocusableBox(
        onClick = onClick,
        modifier = modifier,
        shape = AhShape3,
        containerColor = if (isPrimary) palette.crimson else palette.surfaceElevated,
        focusedContainerColor = if (isPrimary) Color.White else palette.crimson,
        unfocusedBorderColor = if (isPrimary) palette.crimson else palette.borderSubtle,
        focusedBorderColor = if (isPrimary) palette.crimson else Color.White,
        borderWidth = 1.dp,
        focusedBorderWidth = 2.dp,
        focusScale = 1.07f
    ) { isFocused ->
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = when {
                    isPrimary && isFocused -> palette.crimson
                    isPrimary -> Color.White
                    isFocused -> Color.White
                    else -> palette.textPrimary
                },
                fontSize = if (label.length > 2) 13.sp else 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}
