package com.lavasec.app.reactnative

import android.content.Context
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.lavasec.app.foundation.security.ProtectedSurface
import com.lavasec.app.security.SecurityAvailability
import com.lavasec.app.security.SecurityOwnerState
import com.lavasec.app.security.SecurityPrivacyPolicy
import com.lavasec.app.presentation.R

/** Native cover: locked React content cannot draw, receive touches, or enter accessibility. */
internal class ReactAppLockView(
    context: Context,
    private val content: ViewGroup,
    private val unlock: () -> Unit,
    recovery: () -> Unit,
) : LinearLayout(context) {
    private val contentPadding = (24 * resources.displayMetrics.density).toInt()
    private val title = TextView(context)
    private val action = Button(context).apply {
        setText(R.string.security_unlock_action)
        setOnClickListener { unlock() }
    }
    private val recover = Button(context).apply {
        setText(R.string.settings_security_title)
        setOnClickListener { recovery() }
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        isClickable = true
        setPadding(contentPadding, contentPadding, contentPadding, contentPadding)
        addView(title)
        addView(action)
        addView(recover)
    }

    /** Padding confines controls to the chosen pane; the opaque/touch cover stays full-window. */
    fun usePane(pane: Rect, windowWidth: Int, windowHeight: Int) {
        setPadding(contentPadding + pane.left, contentPadding + pane.top,
            contentPadding + (windowWidth - pane.right), contentPadding + (windowHeight - pane.bottom))
    }

    fun render(
        foreground: Boolean,
        security: SecurityOwnerState?,
        authenticating: Boolean,
        surface: ProtectedSurface? = null,
        presentationReady: Boolean = true,
        credentials: Boolean = false,
        appCoverRequired: Boolean = SecurityPrivacyPolicy.requiresBackgroundCover(security),
        retainSessionFrame: Boolean = false,
    ) {
        val admitted = admits(foreground, security, surface, presentationReady, credentials)
        val retained = retainSessionFrame && !appCoverRequired && security?.availability == SecurityAvailability.Available &&
            !security.recoveryRequired
        for (index in 0 until content.childCount) {
            val child = content.getChildAt(index)
            if (child !== this) {
                child.visibility = if (admitted || retained) VISIBLE else INVISIBLE
                child.importantForAccessibility = if (admitted && foreground) IMPORTANT_FOR_ACCESSIBILITY_AUTO
                    else IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
        }
        visibility = if (admitted) GONE else VISIBLE
        title.setText(if (security?.recoveryRequired == true) R.string.security_recovery_failure_title
            else if (security?.availability == SecurityAvailability.Available) R.string.security_unlock_hint
            else R.string.security_unavailable_title)
        val unavailable = security == null || security.availability != SecurityAvailability.Available || security.recoveryRequired
        val needsUnlock = security != null && (!security.isAuthorized(ProtectedSurface.APP_UNLOCK) ||
            surface != null && !security.isAuthorized(surface) || credentials && !security.credentialsAuthenticated)
        title.visibility = if (retained || !unavailable && !needsUnlock) GONE else VISIBLE
        action.setText(if (unavailable) R.string.onboarding_retry else R.string.security_unlock_action)
        action.visibility = if (!retained && (unavailable || needsUnlock)) VISIBLE else GONE
        action.isEnabled = foreground && !authenticating
        recover.visibility = if (security != null && (security.availability != SecurityAvailability.Available || security.recoveryRequired)) VISIBLE else GONE
        recover.isEnabled = foreground && !authenticating
        if (!admitted) bringToFront()
    }

    companion object {
        /** Window privacy and current-page admission are separate native facts. */
        internal fun admits(
            foreground: Boolean,
            security: SecurityOwnerState?,
            surface: ProtectedSurface?,
            presentationReady: Boolean,
            credentials: Boolean = false,
        ): Boolean {
            if (security == null || security.availability != SecurityAvailability.Available || security.recoveryRequired) return false
            if (credentials && (security.enabled && !security.credentialsAuthenticated || !foreground || !presentationReady)) return false
            // Scoped page gates remain enforced even with the whole-app cover OFF.
            if (surface != null && surface in security.protectedSurfaces &&
                (!foreground || !presentationReady || !security.isAuthorized(surface))) return false
            if (!security.isAuthorized(ProtectedSurface.APP_UNLOCK)) return false
            return !SecurityPrivacyPolicy.requiresBackgroundCover(security) || foreground && presentationReady
        }
    }
}
