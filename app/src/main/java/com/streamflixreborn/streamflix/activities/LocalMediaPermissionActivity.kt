package com.streamflixreborn.streamflix.activities

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle

/**
 * 2026-07-25 — Demande de permission média AUTO-ENCHAÎNÉE.
 *
 * Sur Android TV comme sur mobile, on ne peut pas recevoir le résultat d'une permission depuis un
 * simple dialogue (objet, pas d'Activity/Fragment). Cette activité transparente demande la
 * permission, récupère le résultat, puis rappelle le code appelant → la liste se recharge TOUTE
 * SEULE après « Autoriser », sans avoir à re-taper le bouton.
 */
class LocalMediaPermissionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val perms = intent.getStringArrayExtra(EXTRA_PERMS)
        if (perms.isNullOrEmpty()) { finish(); return }
        requestPermissions(perms, RC)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        val granted = grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        val cb = pendingCallback
        pendingCallback = null
        finish()
        runCatching { cb?.invoke(granted) }
    }

    companion object {
        private const val EXTRA_PERMS = "perms"
        private const val RC = 90

        @Volatile
        private var pendingCallback: ((Boolean) -> Unit)? = null

        /** Demande [perms] ; [onResult] est rappelé sur le thread principal avec le verdict. */
        fun request(context: Context, perms: Array<String>, onResult: (Boolean) -> Unit) {
            pendingCallback = onResult
            context.startActivity(
                Intent(context, LocalMediaPermissionActivity::class.java).apply {
                    putExtra(EXTRA_PERMS, perms)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }
    }
}
