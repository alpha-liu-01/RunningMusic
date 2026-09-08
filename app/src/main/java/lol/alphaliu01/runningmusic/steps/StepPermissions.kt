package lol.alphaliu01.runningmusic.steps

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Whether the step detector will deliver to us.
 *
 * `ACTIVITY_RECOGNITION` only became a runtime permission in API 29. Below that
 * the step detector needs nothing at all, and since `minSdk` is 28 there is a
 * real supported configuration where asking is not merely unnecessary but
 * impossible: the permission does not exist to be granted, so a check against it
 * would fail forever and the recorder would refuse to start on a device that can
 * record perfectly well.
 */
fun Context.hasStepPermission(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true

    return checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) ==
        PackageManager.PERMISSION_GRANTED
}

/**
 * Whether the foreground-service notification will actually be visible.
 *
 * Not required for the service to run, but without it the only evidence a
 * recording is in progress is `adb shell dumpsys`, which is not much use with
 * the phone in a pocket.
 */
fun Context.hasNotificationPermission(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true

    return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
}

/** Everything worth asking for on this API level. Empty below API 29. */
fun stepPermissionsToRequest(): Array<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        add(Manifest.permission.ACTIVITY_RECOGNITION)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}.toTypedArray()
