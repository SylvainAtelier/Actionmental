package com.actionmental.platform

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle

/**
 * Invisible host for the microphone permission prompt. Runtime permissions can only be asked from an
 * Activity; the speech page lives in an overlay, so this one shows nothing but the system dialog and
 * reports the answer back. Not exported: only this app can open it.
 */
class MicPermissionActivity : Activity() {
    private var reported = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) finishWith(true)
        else if (savedInstanceState == null) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST) finishWith(grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
    }

    override fun onDestroy() {
        // Dismissed some other way (home, recents): still answer, so the overlay never waits forever.
        if (!reported) report(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        super.onDestroy()
    }

    private fun finishWith(granted: Boolean) {
        report(granted)
        finish()
    }

    private fun report(granted: Boolean) {
        reported = true
        pending?.invoke(granted)
        pending = null
    }

    companion object {
        private const val REQUEST = 1
        /** Main thread only; one prompt at a time, a newer request replaces an unanswered one. */
        private var pending: ((Boolean) -> Unit)? = null

        fun request(context: Context, done: (Boolean) -> Unit) {
            pending?.invoke(false)
            pending = done
            context.startActivity(Intent(context, MicPermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        }
    }
}
