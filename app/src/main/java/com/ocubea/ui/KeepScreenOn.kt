package com.ocubea.ui

import android.view.Window
import android.view.WindowManager

/**
 * The `awake` setting, and the only primitive that can actually honour it.
 *
 * `/settings/awake` answered `okText("ok")` for every value and touched nothing.
 * A flag that means "keep the screen on" only has meaning on a real window, and
 * this app's HTTP server lives in a foreground *service* -- there is no window
 * there to hold awake. Answering "Ok" from a service changed nothing, which is
 * the same defect as `focus_distance` being accepted and dropped.
 *
 * So the setting is split in two, and both halves are reported:
 *
 *  - **requested** — what the client asked for, persisted, and applied to the
 *    next window that appears.
 *  - **applied** — whether a window is on screen right now carrying
 *    FLAG_KEEP_SCREEN_ON because of it.
 *
 * [apply] returns whether it reached a visible window, and the handler turns a
 * false into a 400 with a reason rather than a bare "Ok".
 *
 * ## Why visibility is reported by the Activity rather than queried here
 *
 * `Window` has no `isShowing` -- that is a `View` method -- and asking the decor
 * view instead means *creating* it, which from a service-owned HTTP thread is a
 * main-thread violation on some Android versions. The Activity knows exactly
 * when it is on screen (onResume / onPause), so it reports that here and this
 * object holds no opinion of its own.
 *
 * ## Why the window is registered rather than passed in
 *
 * The HTTP handler runs on a server thread in a service that outlives every
 * Activity, so it cannot be handed one. This holds the *current* window instead,
 * registered by MainActivity in onResume and cleared in onDestroy -- the same
 * shape as any window-token singleton, and it does not outlive the Activity as
 * long as the Activity unregisters.
 */
object KeepScreenOn {

    /** The window currently registered, or null. */
    @Volatile
    private var registered: Window? = null

    /** Whether that window is on screen, as reported by its own Activity. */
    @Volatile
    private var visible = false

    /** True when a registered window is on screen right now. */
    fun isWindowPresent(): Boolean = registered != null && visible

    /**
     * Applies [wanted] to the registered window. True when it landed.
     *
     * False means there was no visible window to carry the flag, and that is
     * what turns the HTTP reply into a 400 naming the reason.
     */
    fun apply(wanted: Boolean): Boolean {
        val w = registered ?: return false
        if (!visible) return false
        return setFlag(w, wanted)
    }

    /**
     * Called by the Activity in onResume (onCreate precedes it and also calls
     * this, so a window exists from the moment it does).
     *
     * Re-registering rather than trusting onCreate alone: the window can be
     * recreated while the Activity instance survives, and the flag has to go
     * onto the *new* one.
     */
    fun attach(window: Window) {
        registered = window
        visible = true
    }

    /** Called by the Activity in onPause: the window is still ours but off screen. */
    fun setVisible(window: Window, isVisible: Boolean) {
        // Identity-checked, so a window that has already been replaced cannot
        // write its own visibility onto the new one's registration.
        if (registered === window) visible = isVisible
    }

    /**
     * Called by the Activity in onDestroy.
     *
     * The identity check is load-bearing: an Activity being replaced must not
     * clear the registration of the one replacing it, or `awake` reports "no
     * window" while a perfectly good one is on screen and the next request is
     * refused for no reason.
     */
    fun detach(window: Window) {
        if (registered === window) {
            registered = null
            visible = false
        }
    }

    /** Sets or clears FLAG_KEEP_SCREEN_ON on one window. Never throws. */
    fun applyTo(wanted: Boolean, window: Window?): Boolean =
        window != null && setFlag(window, wanted)

    private fun setFlag(window: Window, wanted: Boolean): Boolean = try {
        if (wanted) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        true
    } catch (_: Throwable) {
        // A window torn down between the visibility check and this call. False is
        // the honest answer: the flag did not reach a live window.
        false
    }
}