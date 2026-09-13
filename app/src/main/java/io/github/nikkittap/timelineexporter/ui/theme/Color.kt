package io.github.nikkittap.timelineexporter.ui.theme

import androidx.compose.ui.graphics.Color

// The app's own palette, taken from the logo's blue (#1976D2) rather than from
// the wallpaper. See Theme.kt for why dynamic colour is off.
//
// The roles are used a little more literally than Material's defaults, and the
// screen depends on that:
//  - background is the ground the step cards sit on, one step darker than
//    surface in light and one step darker again in dark;
//  - surface is the card itself, and also everything floated over the map
//    (the Color by day card, the map buttons). In dark it is exactly the dark
//    basemap's own ground, #191D23 in map_style_dark.json, so the map reads as
//    part of the card rather than a picture pasted into it;
//  - surfaceContainer is a panel inside a card (the file stats, the date
//    field, the "This will write" plaque, the places list);
//  - surfaceContainerHigh is what dialogs and menus are drawn on.
// The neutrals carry a slight blue bias in both themes, so the greys belong to
// the accent instead of sitting beside it.

// ---------- light ----------
internal val LightPrimary = Color(0xFF2A5EA8)
internal val LightOnPrimary = Color(0xFFFFFFFF)
internal val LightPrimaryContainer = Color(0xFFD6E3FF)
internal val LightOnPrimaryContainer = Color(0xFF0A2A57)
internal val LightSecondary = Color(0xFF545F71)
internal val LightOnSecondary = Color(0xFFFFFFFF)
internal val LightSecondaryContainer = Color(0xFFD8E3F8)
internal val LightOnSecondaryContainer = Color(0xFF111C2B)
internal val LightTertiary = Color(0xFF6B5778)
internal val LightOnTertiary = Color(0xFFFFFFFF)
internal val LightTertiaryContainer = Color(0xFFF2DAFF)
internal val LightOnTertiaryContainer = Color(0xFF251431)
internal val LightBackground = Color(0xFFEEF1F6)
internal val LightOnBackground = Color(0xFF191C21)
internal val LightSurface = Color(0xFFFFFFFF)
internal val LightOnSurface = Color(0xFF191C21)
internal val LightSurfaceVariant = Color(0xFFE1E4EB)
internal val LightOnSurfaceVariant = Color(0xFF454A53)
internal val LightOutline = Color(0xFF767B85)
internal val LightOutlineVariant = Color(0xFFC6CAD3)
internal val LightInverseSurface = Color(0xFF2E3036)
internal val LightInverseOnSurface = Color(0xFFEFF0F7)
internal val LightInversePrimary = Color(0xFFA4C8FF)
internal val LightSurfaceDim = Color(0xFFD9DCE3)
internal val LightSurfaceBright = Color(0xFFF8F9FC)
internal val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
internal val LightSurfaceContainerLow = Color(0xFFF6F7FB)
internal val LightSurfaceContainer = Color(0xFFEDF0F6)
internal val LightSurfaceContainerHigh = Color(0xFFE7EAF1)
internal val LightSurfaceContainerHighest = Color(0xFFE1E4EB)

// ---------- dark ----------
internal val DarkPrimary = Color(0xFFA4C8FF)
internal val DarkOnPrimary = Color(0xFF0A305F)
internal val DarkPrimaryContainer = Color(0xFF1F426F)
internal val DarkOnPrimaryContainer = Color(0xFFD6E3FF)
internal val DarkSecondary = Color(0xFFBCC7DB)
internal val DarkOnSecondary = Color(0xFF263141)
internal val DarkSecondaryContainer = Color(0xFF33404F)
internal val DarkOnSecondaryContainer = Color(0xFFD7E3F8)
internal val DarkTertiary = Color(0xFFD6BEE4)
internal val DarkOnTertiary = Color(0xFF3B2948)
internal val DarkTertiaryContainer = Color(0xFF523F5F)
internal val DarkOnTertiaryContainer = Color(0xFFF2DAFF)
internal val DarkBackground = Color(0xFF111418)
internal val DarkOnBackground = Color(0xFFE1E3E8)
internal val DarkSurface = Color(0xFF191D23)
internal val DarkOnSurface = Color(0xFFE1E3E8)
internal val DarkSurfaceVariant = Color(0xFF2F353F)
internal val DarkOnSurfaceVariant = Color(0xFFB9BFC9)
internal val DarkOutline = Color(0xFF8C919B)
internal val DarkOutlineVariant = Color(0xFF40464F)
internal val DarkInverseSurface = Color(0xFFE1E3E8)
internal val DarkInverseOnSurface = Color(0xFF2E3036)
internal val DarkInversePrimary = Color(0xFF2A5EA8)
internal val DarkSurfaceDim = Color(0xFF111418)
internal val DarkSurfaceBright = Color(0xFF353B45)
internal val DarkSurfaceContainerLowest = Color(0xFF0C0F13)
internal val DarkSurfaceContainerLow = Color(0xFF15191F)
internal val DarkSurfaceContainer = Color(0xFF222730)
internal val DarkSurfaceContainerHigh = Color(0xFF272C35)
internal val DarkSurfaceContainerHighest = Color(0xFF2F353F)
