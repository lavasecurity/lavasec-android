package com.lavasec.app.reactnative

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import com.facebook.react.ReactHost
import com.facebook.react.interfaces.fabric.ReactSurface

/** Start Fabric only after Android has supplied the surface's actual window constraints.
 * ReactSurfaceImpl initially uses AT_MOST display bounds, which permit an empty root to
 * shrink to zero. A retained ReactContext can render against those bounds before the
 * new Activity's first traversal. Never repair child bounds outside the renderer. */
internal class NativeMeasuredSurfaceStart(
    private val root: View,
    private val isCurrent: () -> Boolean,
    private val start: () -> Unit,
) : ViewTreeObserver.OnPreDrawListener, AutoCloseable {
    private var closed = false
    private val observer = root.viewTreeObserver

    init { observer.addOnPreDrawListener(this) }

    override fun onPreDraw(): Boolean {
        if (closed) return true
        if (!isCurrent()) { close(); return true }
        if (!root.isAttachedToWindow || root.isLayoutRequested || root.width <= 0 || root.height <= 0 ||
            root.measuredWidth != root.width || root.measuredHeight != root.height) return true
        close()
        start()
        return true
    }

    override fun close() {
        if (closed) return
        closed = true
        if (observer.isAlive) observer.removeOnPreDrawListener(this)
        // An unattached observer is merged into the window's observer on attachment.
        if (root.viewTreeObserver !== observer) root.viewTreeObserver.removeOnPreDrawListener(this)
    }

    companion object {
        /** Register teardown ownership before attachment, then start this exact measured surface. */
        fun install(
            activity: Activity,
            host: ReactHost,
            moduleName: String,
            initialProps: Bundle?,
            registerSurface: (ReactSurface) -> Unit,
            isCurrent: () -> Boolean,
            onStarted: () -> Unit,
        ): NativeMeasuredSurfaceStart {
            val surface = host.createSurface(activity, moduleName, initialProps)
            registerSurface(surface)
            val root = checkNotNull(surface.view)
            val pending = NativeMeasuredSurfaceStart(root, isCurrent) {
                surface.start()
                onStarted()
            }
            activity.setContentView(root)
            return pending
        }
    }
}
