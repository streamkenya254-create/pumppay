package ke.tafiti.pumppay

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight

/**
 * Space Grotesk, one variable file covering every weight.
 *
 * A geometric grotesque rather than a neutral UI face: tight apertures, flat
 * terminals, and numerals that stay unambiguous at a glance - which matters
 * more here than anywhere, since most of what this app shows IS a number.
 *
 * ONE FILE, FIVE WEIGHTS. The ttf carries a weight axis, so each entry below
 * points at the same resource and pins the axis to a different value. That is
 * 134 KB for the whole range instead of five separate static faces, and it
 * avoids the other trap: without real weights registered, Compose fakes bold by
 * smearing the glyphs, which looks exactly as bad as it sounds.
 *
 * Variable axes need API 26, which is this app's minimum anyway.
 */
@OptIn(ExperimentalTextApi::class)
val AppFont = FontFamily(
    Font(
        R.font.space_grotesk,
        weight = FontWeight.Light,
        variationSettings = FontVariation.Settings(FontVariation.weight(300))
    ),
    Font(
        R.font.space_grotesk,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400))
    ),
    Font(
        R.font.space_grotesk,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500))
    ),
    Font(
        R.font.space_grotesk,
        weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600))
    ),
    Font(
        R.font.space_grotesk,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700))
    )
)
