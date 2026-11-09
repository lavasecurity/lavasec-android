package com.lavasec.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

/**
 * Process-wide current-Activity holder for platform surfaces that must be hosted by an Activity
 * (the biometric [android.hardware.biometrics.BiometricPrompt] takes a `FragmentActivity`).
 *
 * Registered as an [Application.ActivityLifecycleCallbacks] by [LavasecApplication]. The reference
 * is [WeakReference] and is cleared on pause/destroy, so a retired Activity is never strongly
 * retained and [current] never hands back a leaked host.
 */
internal class CurrentActivityHolder : Application.ActivityLifecycleCallbacks {
    private var resumed = WeakReference<Activity>(null)

    /** The most recently resumed Activity, or `null` while none is in the foreground. */
    fun current(): Activity? = resumed.get()

    override fun onActivityResumed(activity: Activity) {
        resumed = WeakReference(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumed.get() === activity) resumed.clear()
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (resumed.get() === activity) resumed.clear()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
