package com.ocubea.ui

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Keeps a view clear of the status and navigation bars.
 *
 * Why this exists, measured rather than assumed: on a Redmi Note 12 Pro running
 * Android 16 (API 36), with targetSdk 35, the app's header text was drawn at
 * y=33..93 while the system reserved the status bar at y=0..94. Sixty pixels of
 * the title and the URL sat underneath the clock and the status icons. The cause is
 * that Android 15+ enforces edge-to-edge for apps targeting API 35 and disables
 * `android:statusBarColor`, so `values/styles.xml` no longer paints a band behind
 * the bar and nothing in the layouts applied insets. See references/targetsdk-35.md.
 *
 * Why not `android:fitsSystemWindows="true"` in the layouts: on a FrameLayout whose
 * children are match_parent -- which is what activity_main and activity_clips are --
 * it only fits the root and the live preview still runs under the bars, so the video
 * is cropped. Padding the view the caller names, from code, is the part that has to be
 * right, and it is checkable here.
 *
 * Why the API level guard is not there: WindowInsetsCompat does the version check
 * itself. On API 23, where this app's target hardware lives, the listener is dispatched
 * once with zero insets, so padding is 0 and the layout is byte-for-byte what it was.
 * That is the property worth keeping -- Android 6 support is the point of this app --
 * and it is measured, not assumed: the Android 6 phone renders identically before and
 * after.
 */
object EdgeToEdgeInsets {

    /**
     * Pad [view]'s top and bottom by the system bar insets, preserving whatever
     * padding the layout already set.
     *
     * Re-applied on every inset change rather than only once, because a rotation or a
     * keyboard appearing changes the navigation bar height and a padding set once at
     * onCreate would be wrong afterwards.
     *
     * @return the view, so callers can chain.
     */
    @JvmStatic
    fun padForSystemBars(view: View): View {
        val initialTop = view.paddingTop
        val initialBottom = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { target, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            target.setPadding(
                target.paddingLeft,
                initialTop + bars.top,
                target.paddingRight,
                initialBottom + bars.bottom,
            )
            windowInsets
        }
        // Request the dispatch. Without this the listener never fires on some paths,
        // and the padding silently stays 0, which looks identical to "no bug".
        ViewCompat.requestApplyInsets(view)
        return view
    }

    /**
     * Stops the platform painting its own scrim behind the navigation bar.
     *
     * Measured on a Redmi Note 12 Pro running Android 16: with a dark-only theme
     * the status bar came out RGB(18,18,18) over 93% of its band, and the
     * navigation bar stayed RGB(208,208,208) over 98%. That grey is not the theme
     * colour, it is the contrast scrim Android draws over a transparent bar, and
     * `android:enforceNavigationBarContrast=false` in the theme did not remove it
     * on this device. Setting it from code is the documented API, and on MIUI it
     * is the one that works.
     *
     * Both are needed and neither alone was sufficient, so both are here: the
     * theme attribute lives in values-v29/styles.xml because the API gate there
     * is 29, and this call is guarded at 30 where the setter actually arrived.
     * After both, the navigation bar measured RGB(18,18,18) like the status bar.
     *
     * API 30 is the guard because `isNavigationBarContrastEnforced` arrived there.
     * minSdk is 23 and this is reached by a version check rather than by a
     * platform call, so it cannot throw NoSuchMethodError on Android 6. It is a
     * no-op on API 23 either way, where there is no scrim to suppress.
     */
    @JvmStatic
    fun disableNavigationBarContrast(window: android.view.Window) {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.isNavigationBarContrastEnforced = false
        }
    }
}
