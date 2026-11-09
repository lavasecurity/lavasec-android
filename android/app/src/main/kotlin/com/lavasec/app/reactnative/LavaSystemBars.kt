package com.lavasec.app.reactnative

import android.app.Activity
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.lavasec.app.R

/** Keep both the icon contrast and its surface in the Activity's confirmed palette.
 * Android 15+ draws transparent system bars over the window background. Earlier
 * versions still need explicit bar colors; changing only icon style hides light-
 * mode status icons against AppCompat's default black status bar. */
@Suppress("DEPRECATION")
internal fun Activity.applyLavaSystemBars() {
    val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val background = ContextCompat.getColor(this, R.color.lava_rn_groupedbackground)
    val navigation = ContextCompat.getColor(this, R.color.lava_rn_cardbackground)
    window.setBackgroundDrawable(ColorDrawable(background))
    if (Build.VERSION.SDK_INT < 35) {
        window.statusBarColor = background
        window.navigationBarColor = navigation
    }
    WindowInsetsControllerCompat(window, window.decorView).apply {
        isAppearanceLightStatusBars = !dark
        isAppearanceLightNavigationBars = !dark
    }
}
