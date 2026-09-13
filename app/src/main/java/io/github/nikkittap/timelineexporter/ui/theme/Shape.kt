package io.github.nikkittap.timelineexporter.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Four corner radii for the whole app, one per kind of thing.
 *
 * The screen used to carry six — 8 dp buttons and chips, 12 dp cards and
 * tiles, 16 dp for the card over the map, 20 dp for the selected-day callout,
 * 28 dp dialogs and fully round segmented buttons — and that mix was most of
 * why it read as parts from different kits. Components now take their shape
 * from here rather than from a local RoundedCornerShape:
 *
 *  - small (12): anything you press — buttons, segmented rows, chips, map buttons;
 *  - medium (16): a panel inside a card, the map tile, the card floating on the map;
 *  - large (24): the step cards;
 *  - extraLarge (28): dialogs and sheets (Material's own default).
 *
 * extraSmall keeps Material's 4 dp: it is only used by menus and tooltips.
 */
val AppShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
