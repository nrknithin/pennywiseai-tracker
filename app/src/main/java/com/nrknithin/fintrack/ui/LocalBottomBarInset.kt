package com.nrknithin.fintrack.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.isSpecified
import com.nrknithin.fintrack.ui.theme.Dimensions

/**
 * How much of the bottom of the screen the overlaid bottom navigation covers, as
 * measured by MainScreen. It already includes the system navigation bar and reflects the
 * chosen style (floating toolbar or fixed bar). [Dp.Unspecified] until first measured.
 */
val LocalBottomBarInset = compositionLocalOf { Dp.Unspecified }

/**
 * The height content on a bottom-nav tab must stay clear of. Before the bar has been
 * measured, falls back to the fixed bar height plus the system navigation bar.
 */
@Composable
fun bottomBarClearance(): Dp {
    val measured = LocalBottomBarInset.current
    return if (measured.isSpecified) {
        measured
    } else {
        Dimensions.Component.bottomBarHeight +
            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    }
}
