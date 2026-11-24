package me.animeheaven.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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

/**
 * 2025/2026 Netflix TV floating top navigation bar:
 * - Signature red monogram on the left
 * - Centered frosted dark pill navigation dock with pure-white focus pills and red active underline
 * - Profile/Settings avatar button on the right
 */
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
    val topBarScrim = if (palette.isLight) {
        Brush.verticalGradient(listOf(palette.background, palette.background.copy(alpha = 0.85f)))
    } else {
        Brush.verticalGradient(listOf(Color(0xE60B0B0B), Color(0x880B0B0B), Color.Transparent))
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(topBarScrim)
            .padding(horizontal = AhOverscanHorizontal)
            .padding(top = AhOverscanVertical, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Netflix-style Bold Condensed Crimson Monogram
        AhFocusableBox(
            onClick = onSelectHome,
            shape = AhShape20,
            containerColor = Color.Transparent,
            focusedContainerColor = Color(0x26FFFFFF),
            focusedBorderColor = Color(0xCCFFFFFF),
            focusedBorderWidth = 1.5.dp,
            focusScale = 1.04f
        ) { _ ->
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "A",
                    color = palette.crimson,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-1).sp
                )
                Text(
                    text = "HEAVEN",
                    color = palette.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 2.2.sp
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Centered 2025/2026 Netflix TV Frosted Top Nav Pill Dock
        Row(
            modifier = Modifier
                .clip(AhShape20)
                .background(
                    if (palette.isLight) palette.surface.copy(alpha = 0.94f) else Color(0xBF181818)
                )
                .border(
                    width = 1.dp,
                    color = if (palette.isLight) palette.borderSubtle else Color(0x26FFFFFF),
                    shape = AhShape20
                )
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Search shortcut pill (Netflix TV top-left dock slot)
            AhFocusableBox(
                onClick = onSelectSearch,
                shape = AhShape20,
                containerColor = if (activeDestination == HeaderDestination.SEARCH) {
                    Color(0x29FFFFFF)
                } else {
                    Color.Transparent
                },
                focusedContainerColor = Color.White,
                focusedBorderColor = Color.White,
                focusedBorderWidth = 2.dp,
                focusScale = 1.05f
            ) { isFocused ->
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = "Search",
                        tint = if (isFocused) Color.Black else palette.textPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Search",
                        color = if (isFocused) Color.Black else palette.textSecondary,
                        fontSize = 14.sp,
                        fontWeight = if (isFocused) FontWeight.Bold else FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }

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
                label = "My Bookmarks",
                selected = activeDestination == HeaderDestination.BOOKMARKS,
                onClick = onSelectBookmarks
            )
            HeaderNavTab(
                label = "Random",
                selected = activeDestination == HeaderDestination.RANDOM,
                onClick = onSelectRandom
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Netflix-style Profile / Settings Avatar Control
        AhFocusableBox(
            onClick = onSelectSettings,
            modifier = Modifier.size(40.dp),
            shape = CircleShape,
            containerColor = if (activeDestination == HeaderDestination.SETTINGS) {
                palette.crimson
            } else {
                Color(0xFF262626)
            },
            focusedContainerColor = Color.White,
            unfocusedBorderColor = if (activeDestination == HeaderDestination.SETTINGS) {
                Color.White
            } else {
                Color(0x38FFFFFF)
            },
            focusedBorderColor = Color.White,
            borderWidth = 1.5.dp,
            focusedBorderWidth = 2.5.dp,
            focusScale = 1.1f
        ) { isFocused ->
            Box(
                modifier = Modifier.matchParentSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = AhIcons.Tune,
                    contentDescription = "Settings & Themes",
                    tint = when {
                        isFocused -> Color.Black
                        else -> Color.White
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
        shape = AhShape20,
        containerColor = Color.Transparent,
        focusedContainerColor = Color.White,
        focusedBorderColor = Color.White,
        focusedBorderWidth = 2.dp,
        focusScale = 1.05f
    ) { isFocused ->
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                color = when {
                    isFocused -> Color.Black
                    selected -> palette.textPrimary
                    else -> palette.textSecondary
                },
                fontSize = 14.sp,
                fontWeight = if (selected || isFocused) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(3.dp))
            Box(
                modifier = Modifier
                    .width(if (selected && !isFocused) 18.dp else 0.dp)
                    .height(2.5.dp)
                    .clip(CircleShape)
                    .background(palette.crimson)
            )
        }
    }
}
