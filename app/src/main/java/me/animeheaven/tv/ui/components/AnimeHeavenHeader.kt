package me.animeheaven.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.animeheaven.tv.ui.theme.LocalAhPalette

enum class HeaderDestination {
    HOME,
    NEW,
    BOOKMARKS,
    POPULAR,
    SEASONAL,
    RANDOM,
    SEARCH,
    SETTINGS
}

@Composable
fun AnimeHeavenHeader(
    activeDestination: HeaderDestination,
    seasonLabel: String?,
    onSelectHome: () -> Unit,
    onSelectSearch: () -> Unit,
    onSelectNew: () -> Unit,
    onSelectBookmarks: () -> Unit,
    onSelectPopular: () -> Unit,
    onSelectSeasonal: () -> Unit,
    onSelectRandom: () -> Unit,
    onSelectSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalAhPalette.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AhOverscanHorizontal)
            .padding(top = AhOverscanVertical, bottom = 8.dp)
            .background(palette.surface, AhShape2)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // AnimeHeaven Logo Button (zero-recomposition focus)
        AhStaticFocusableBox(
            onClick = onSelectHome,
            modifier = Modifier.size(42.dp),
            containerColor = Color.Black,
            focusedContainerColor = Color.Black,
            unfocusedBorderColor = palette.crimson,
            borderWidth = 1.5.dp,
            focusedBorderWidth = 2.5.dp,
            focusScale = 1.06f
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "A",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "H",
                        color = palette.crimson,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }

        // Signature Crimson-Bordered Search Input Pill (opens Custom TV Keyboard)
        AhFocusableBox(
            onClick = onSelectSearch,
            modifier = Modifier
                .width(180.dp)
                .height(40.dp),
            containerColor = if (palette.isLight) Color.White else Color.Black,
            focusedContainerColor = if (palette.isLight) Color(0xFFFFF5F6) else Color(0xFF140004),
            unfocusedBorderColor = palette.crimson,
            focusedBorderColor = Color.White,
            borderWidth = 2.dp,
            focusedBorderWidth = 2.5.dp,
            focusScale = 1.03f
        ) { isFocused ->
            Row(
                modifier = Modifier.matchParentSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Search",
                    color = if (isFocused) palette.textPrimary else palette.textSecondary,
                    fontSize = 15.sp,
                    fontStyle = FontStyle.Italic,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 10.dp)
                )
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(38.dp)
                        .background(palette.crimson),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = "Search",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        // Navigation Items matching animeheaven.me header
        HeaderNavTab(
            label = "Schedule",
            selected = activeDestination == HeaderDestination.HOME,
            onClick = onSelectHome
        )
        HeaderNavTab(
            label = "New",
            selected = activeDestination == HeaderDestination.NEW,
            onClick = onSelectNew
        )
        HeaderNavTab(
            label = "My Bookmarks",
            selected = activeDestination == HeaderDestination.BOOKMARKS,
            onClick = onSelectBookmarks
        )
        HeaderNavTab(
            label = "Popular",
            selected = activeDestination == HeaderDestination.POPULAR,
            onClick = onSelectPopular
        )
        if (!seasonLabel.isNullOrBlank()) {
            HeaderNavTab(
                label = seasonLabel,
                selected = activeDestination == HeaderDestination.SEASONAL,
                onClick = onSelectSeasonal
            )
        }
        HeaderNavTab(
            label = "Random",
            selected = activeDestination == HeaderDestination.RANDOM,
            onClick = onSelectRandom
        )

        Spacer(modifier = Modifier.weight(1f))

        // Settings & Theme Switcher Button (equivalent to AnimeHeaven footer theme picker)
        AhFocusableBox(
            onClick = onSelectSettings,
            modifier = Modifier.size(38.dp),
            shape = CircleShape,
            containerColor = if (activeDestination == HeaderDestination.SETTINGS) {
                palette.crimson
            } else {
                palette.surfaceElevated
            },
            focusedContainerColor = palette.crimson,
            unfocusedBorderColor = palette.crimson,
            focusedBorderColor = Color.White,
            borderWidth = 1.5.dp,
            focusedBorderWidth = 2.dp,
            focusScale = 1.08f
        ) { isFocused ->
            Box(
                modifier = Modifier.matchParentSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = AhIcons.Tune,
                    contentDescription = "Settings & Themes",
                    tint = if (isFocused || activeDestination == HeaderDestination.SETTINGS) {
                        Color.White
                    } else {
                        palette.textPrimary
                    },
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun HeaderNavTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val palette = LocalAhPalette.current

    AhFocusableBox(
        onClick = onClick,
        shape = AhShape3,
        containerColor = Color.Transparent,
        focusedContainerColor = palette.crimson,
        unfocusedBorderColor = if (selected) palette.crimson.copy(alpha = 0.7f) else null,
        focusedBorderColor = Color.White,
        borderWidth = 1.dp,
        focusedBorderWidth = 2.dp,
        focusScale = 1.05f
    ) { isFocused ->
        Text(
            text = label,
            color = when {
                isFocused -> Color.White
                selected -> palette.crimson
                else -> palette.textSecondary
            },
            fontSize = 16.sp,
            fontWeight = if (selected || isFocused) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
        )
    }
}
