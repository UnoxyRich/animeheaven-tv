package me.animeheaven.tv.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.animeheaven.tv.data.local.AppSettingsStore
import me.animeheaven.tv.data.model.AhThemeId
import me.animeheaven.tv.data.model.FeedViewMode
import me.animeheaven.tv.data.model.KeyboardMode
import me.animeheaven.tv.data.model.PreloadMode
import me.animeheaven.tv.data.repository.AnimeHeavenRepository
import me.animeheaven.tv.ui.components.AhFocusableBox
import me.animeheaven.tv.ui.components.AhIcons
import me.animeheaven.tv.ui.components.AhOverscanHorizontal
import me.animeheaven.tv.ui.components.AhShape20
import me.animeheaven.tv.ui.components.AhShape3
import me.animeheaven.tv.ui.components.AhShape4
import me.animeheaven.tv.ui.components.AhStaticFocusableBox
import me.animeheaven.tv.ui.theme.LocalAhPalette
import me.animeheaven.tv.ui.theme.paletteFor

private val THEME_ROWS = AhThemeId.entries.chunked(4)
private val SKIP_OP_SECONDS_OPTIONS = listOf(60, 85, 90)
private enum class ClearTarget { HISTORY, CACHE }

@Composable
fun SettingsScreen(
    settingsStore: AppSettingsStore,
    repository: AnimeHeavenRepository,
    onGetCacheSummary: suspend () -> String = { "" },
    onClearAllCaches: suspend () -> Unit = {},
    onBack: () -> Unit
) {
    val settings by settingsStore.settings.collectAsStateWithLifecycle()
    val palette = LocalAhPalette.current
    val scope = rememberCoroutineScope()
    var statusBanner by remember { mutableStateOf<String?>(null) }
    var cacheSummary by remember { mutableStateOf("Calculating...") }
    var pendingClear by remember { mutableStateOf<ClearTarget?>(null) }
    val themeFocusRequesters = remember { AhThemeId.entries.associateWith { FocusRequester() } }
    var isInitialThemeFocused by remember { mutableStateOf(false) }

    pendingClear?.let { target ->
        val history = target == ClearTarget.HISTORY
        AlertDialog(
            onDismissRequest = { pendingClear = null },
            containerColor = palette.surface,
            titleContentColor = palette.textPrimary,
            textContentColor = palette.textSecondary,
            title = {
                Text(
                    text = if (history) "Clear watch history?" else "Clear offline caches?",
                    color = palette.textPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = if (history) {
                        "Saved progress and Continue Watching will be removed."
                    } else {
                        "Cached pages, images, and video will be removed. Watch history and bookmarks will stay."
                    },
                    color = palette.textSecondary
                )
            },
            dismissButton = {
                AhFocusableBox(
                    onClick = { pendingClear = null },
                    shape = AhShape4,
                    containerColor = palette.surfaceElevated,
                    focusedContainerColor = palette.focusContainer,
                    unfocusedBorderColor = palette.borderSubtle,
                    focusedBorderColor = palette.focusBorder
                ) { isFocused ->
                    Text(
                        text = "Cancel",
                        color = if (isFocused) palette.onFocusContainer else palette.textPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            },
            confirmButton = {
                AhFocusableBox(
                    onClick = {
                        pendingClear = null
                        scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            withContext(NonCancellable) {
                                if (history) repository.clearWatchHistory() else onClearAllCaches()
                            }
                            if (!history) cacheSummary = onGetCacheSummary()
                            statusBanner = if (history) "Watch history cleared" else "Offline caches cleared"
                        }
                    },
                    shape = AhShape4,
                    containerColor = palette.crimson,
                    focusedContainerColor = palette.focusContainer,
                    unfocusedBorderColor = palette.crimson,
                    focusedBorderColor = palette.crimson
                ) { isFocused ->
                    Text(
                        text = "Clear",
                        color = if (isFocused) palette.onFocusContainer else Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        )
    }

    LaunchedEffect(Unit) {
        cacheSummary = onGetCacheSummary()
    }

    LaunchedEffect(Unit) {
        val initialRequester = themeFocusRequesters[settings.themeId] ?: return@LaunchedEffect
        repeat(12) {
            if (isInitialThemeFocused) return@LaunchedEffect
            delay(120L)
            if (isInitialThemeFocused) return@LaunchedEffect
            runCatching { initialRequester.requestFocus() }
        }
    }

    LaunchedEffect(statusBanner) {
        if (statusBanner != null) {
            delay(1800L)
            statusBanner = null
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = AhOverscanHorizontal, end = AhOverscanHorizontal, top = 14.dp)
        ) {
            // Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.surface, AhShape4)
                    .border(1.dp, palette.borderSubtle, AhShape4)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AhFocusableBox(
                    onClick = onBack,
                    modifier = Modifier.size(38.dp),
                    shape = CircleShape,
                    containerColor = palette.surfaceElevated,
                    focusedContainerColor = palette.focusContainer,
                    unfocusedBorderColor = palette.borderSubtle,
                    focusedBorderColor = palette.focusBorder
                ) { isFocused ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                            tint = if (isFocused) palette.onFocusContainer else palette.textPrimary
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Settings & Themes",
                        color = palette.textPrimary,
                        fontSize = 20.sp,
                        lineHeight = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Customize AnimeHeaven TV theme, keyboard, playback & history",
                        color = palette.textSecondary,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Theme Picker Section
                item(key = "theme_section") {
                    SettingsCard(title = "AnimeHeaven Theme") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            THEME_ROWS.forEach { rowThemes ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    rowThemes.forEach { theme ->
                                        val preview = paletteFor(theme)
                                        val selected = settings.themeId == theme
                                        val cardFocusRequester = themeFocusRequesters.getValue(theme)
                                        AhStaticFocusableBox(
                                            onClick = {
                                                settingsStore.setTheme(theme)
                                                runCatching { cardFocusRequester.requestFocus() }
                                            },
                                            modifier = Modifier
                                                .weight(1f)
                                                .semantics { this.selected = selected }
                                                .focusRequester(cardFocusRequester),
                                            shape = AhShape3,
                                            containerColor = palette.surfaceElevated,
                                            focusedContainerColor = palette.surfaceFocused,
                                            unfocusedBorderColor = if (selected) palette.crimson else palette.borderSubtle,
                                            borderWidth = if (selected) 2.dp else 1.dp,
                                            focusedBorderColor = if (selected) palette.crimson else palette.focusBorder,
                                            focusedBorderWidth = 2.5.dp,
                                            onFocusStateChanged = { focused ->
                                                if (focused && selected) isInitialThemeFocused = true
                                            }
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(34.dp)
                                                        .clip(CircleShape)
                                                        .then(
                                                            if (preview.swatchBrush != null) {
                                                                Modifier.background(preview.swatchBrush)
                                                            } else {
                                                                Modifier.background(preview.background)
                                                            }
                                                        )
                                                        .border(2.dp, preview.crimson, CircleShape),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    if (selected) {
                                                        Icon(
                                                            imageVector = Icons.Rounded.Check,
                                                            contentDescription = "Selected",
                                                            tint = if (preview.isLight) Color(0xFF1A1A1A) else Color.White,
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }
                                                }
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = theme.displayName,
                                                        color = palette.textPrimary,
                                                        fontSize = 14.sp,
                                                        lineHeight = 17.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        maxLines = 1
                                                    )
                                                    Text(
                                                        text = theme.subtitle,
                                                        color = palette.textMuted,
                                                        fontSize = 11.sp,
                                                        lineHeight = 14.sp,
                                                        maxLines = 1
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    repeat(4 - rowThemes.size) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }

                // Default Keyboard Layout + View Mode
                item(key = "ui_preferences") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        SettingsCard(
                            title = "Default Search Keyboard",
                            modifier = Modifier.weight(1f)
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OptionChip(
                                    label = "QWERTY (Default)",
                                    selected = settings.defaultKeyboardMode == KeyboardMode.QWERTY,
                                    onClick = {
                                        settingsStore.setDefaultKeyboardMode(KeyboardMode.QWERTY)
                                        statusBanner = "Keyboard: QWERTY"
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                                OptionChip(
                                    label = "ABC Grid",
                                    selected = settings.defaultKeyboardMode == KeyboardMode.ABC,
                                    onClick = {
                                        settingsStore.setDefaultKeyboardMode(KeyboardMode.ABC)
                                        statusBanner = "Keyboard: ABC"
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        SettingsCard(
                            title = "Default Catalog View",
                            modifier = Modifier.weight(1f)
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OptionChip(
                                    label = "List View",
                                    selected = settings.defaultViewMode == FeedViewMode.LIST,
                                    onClick = {
                                        settingsStore.setDefaultViewMode(FeedViewMode.LIST)
                                        statusBanner = "Default View: List"
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                                OptionChip(
                                    label = "Grid View",
                                    selected = settings.defaultViewMode == FeedViewMode.GRID,
                                    onClick = {
                                        settingsStore.setDefaultViewMode(FeedViewMode.GRID)
                                        statusBanner = "Default View: Grid"
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                // Playback Preferences
                item(key = "playback_preferences") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            SettingsCard(
                                title = "Auto-Play Next Episode",
                                modifier = Modifier.weight(1f)
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OptionChip(
                                        label = "On (Recommended)",
                                        selected = settings.autoPlayNext,
                                        onClick = {
                                            settingsStore.setAutoPlayNext(true)
                                            statusBanner = "Auto-Play Next: On"
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                    OptionChip(
                                        label = "Off",
                                        selected = !settings.autoPlayNext,
                                        onClick = {
                                            settingsStore.setAutoPlayNext(false)
                                            statusBanner = "Auto-Play Next: Off"
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }

                            SettingsCard(
                                title = "Skip Opening (OP) Duration",
                                modifier = Modifier.weight(1f)
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SKIP_OP_SECONDS_OPTIONS.forEach { seconds ->
                                        OptionChip(
                                            label = "${seconds}s",
                                            selected = settings.skipOpSeconds == seconds,
                                            onClick = {
                                                settingsStore.setSkipOpSeconds(seconds)
                                                statusBanner = "Skip OP: ${seconds}s"
                                            },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }

                        SettingsCard(title = "Skip OP & ED Prompt (7s OK Button)") {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OptionChip(
                                    label = "On (Default)",
                                    selected = settings.skipPromptEnabled,
                                    onClick = {
                                        settingsStore.setSkipPromptEnabled(true)
                                        statusBanner = "Skip OP/ED Prompt: On"
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                                OptionChip(
                                    label = "Off",
                                    selected = !settings.skipPromptEnabled,
                                    onClick = {
                                        settingsStore.setSkipPromptEnabled(false)
                                        statusBanner = "Skip OP/ED Prompt: Off"
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        SettingsCard(title = "Preload & Prefetch Strategy") {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    PreloadMode.entries.forEach { mode ->
                                        OptionChip(
                                            label = mode.displayName,
                                            selected = settings.preloadMode == mode,
                                            onClick = {
                                                settingsStore.setPreloadMode(mode)
                                                statusBanner = "Preload: ${mode.displayName}"
                                            },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                                Text(
                                    text = settings.preloadMode.subtitle,
                                    color = palette.textMuted,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }

                // Data & History Management
                item(key = "data_management") {
                    SettingsCard(title = "Local History & Storage") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (cacheSummary.isNotBlank()) {
                                Text(
                                    text = "Offline Cache: $cacheSummary",
                                    color = palette.textSecondary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                AhFocusableBox(
                                    onClick = { pendingClear = ClearTarget.HISTORY },
                                    containerColor = palette.surfaceElevated,
                                    focusedContainerColor = palette.crimson,
                                    unfocusedBorderColor = palette.borderSubtle,
                                    focusedBorderColor = palette.focusBorder
                                ) { isFocused ->
                                    Row(
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = AhIcons.DeleteSweep,
                                            contentDescription = null,
                                            tint = if (isFocused) Color.White else palette.textPrimary
                                        )
                                        Text(
                                            text = "Clear Continue Watching & Progress",
                                            color = if (isFocused) Color.White else palette.textPrimary,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }

                                AhFocusableBox(
                                    onClick = {
                                        scope.launch {
                                            repository.clearSearchHistory()
                                            statusBanner = "Search history cleared"
                                        }
                                    },
                                    containerColor = palette.surfaceElevated,
                                    focusedContainerColor = palette.crimson,
                                    unfocusedBorderColor = palette.borderSubtle,
                                    focusedBorderColor = palette.focusBorder
                                ) { isFocused ->
                                    Row(
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = AhIcons.DeleteSweep,
                                            contentDescription = null,
                                            tint = if (isFocused) Color.White else palette.textPrimary
                                        )
                                        Text(
                                            text = "Clear Recent Searches",
                                            color = if (isFocused) Color.White else palette.textPrimary,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }

                            AhFocusableBox(
                                onClick = { pendingClear = ClearTarget.CACHE },
                                containerColor = palette.surfaceElevated,
                                focusedContainerColor = palette.crimson,
                                unfocusedBorderColor = palette.borderSubtle,
                                focusedBorderColor = palette.focusBorder
                            ) { isFocused ->
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = AhIcons.DeleteSweep,
                                        contentDescription = null,
                                        tint = if (isFocused) Color.White else palette.textPrimary
                                    )
                                    Text(
                                        text = "Clear Offline Cache (Web • Images • Video)",
                                        color = if (isFocused) Color.White else palette.textPrimary,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Auto-dismissing floating status toast pill
        AnimatedVisibility(
            visible = statusBanner != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 20.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(AhShape20)
                    .background(palette.focusContainer)
                    .border(1.dp, palette.borderSubtle, AhShape20)
                    .padding(horizontal = 18.dp, vertical = 8.dp)
            ) {
                Text(
                    text = statusBanner.orEmpty(),
                    color = palette.onFocusContainer,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val palette = LocalAhPalette.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(palette.surface, AhShape4)
            .border(1.dp, palette.borderSubtle, AhShape4)
            .padding(11.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            color = palette.textPrimary,
            fontSize = 16.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Bold
        )
        content()
    }
}

@Composable
private fun OptionChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalAhPalette.current
    AhFocusableBox(
        onClick = onClick,
        modifier = modifier,
        shape = AhShape4,
        containerColor = if (selected) palette.crimson else palette.surfaceElevated,
        focusedContainerColor = palette.focusContainer,
        unfocusedBorderColor = if (selected) palette.crimson else palette.borderSubtle,
        focusedBorderColor = palette.focusBorder
    ) { isFocused ->
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp, horizontal = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = when {
                    isFocused -> palette.onFocusContainer
                    selected -> Color.White
                    else -> palette.textSecondary
                },
                fontSize = 13.sp,
                fontWeight = if (selected || isFocused) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}
