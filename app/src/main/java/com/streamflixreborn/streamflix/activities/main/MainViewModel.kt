package com.streamflixreborn.streamflix.activities.main

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.utils.GitHub
import com.streamflixreborn.streamflix.utils.InAppUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import com.streamflixreborn.streamflix.utils.UserPreferences
import java.io.File

class MainViewModel : ViewModel() {

    private val _state = MutableStateFlow<State>(State.CheckingUpdate)
    val state: Flow<State> = _state

    sealed class State {
        data object CheckingUpdate : State()
        data class SuccessCheckingUpdate(val newReleases: List<GitHub.Release>, val asset: GitHub.Release.Asset) : State()

        data object DownloadingUpdate : State()
        data class SuccessDownloadingUpdate(val apk: File) : State()

        data object InstallingUpdate : State()

        data class FailedUpdate(val error: Exception) : State()
    }


    private var updateChecked = false
    var updateDismissed = false

    fun checkUpdate() = viewModelScope.launch(Dispatchers.IO) {
        if (!UserPreferences.updateCheckEnabled) return@launch
        if (updateChecked || updateDismissed) return@launch
        updateChecked = true
        _state.emit(State.CheckingUpdate)

        try {
            val newReleases = InAppUpdater.getNewReleases()
            if (newReleases.isEmpty()) return@launch

            // 2026-07-17 : sélection d'APK selon LAYOUT + ABI de l'appareil.
            // Assets d'une release : onyx-<tag>-universal.apk (défaut mobile+TV,
            // ARM), onyx-<tag>-tv.apk (TV-only, ARM), onyx-<tag>-x86.apk (défaut,
            // x86 émulateurs/TV Intel).
            // BUG CORRIGÉ : l'ancienne logique matchait « -mobile.apk » (inexistant)
            // et sa branche else tombait sur « -x86.apk » (car l'ARM s'appelait
            // « -mobile-tv.apk » et finit par « -tv.apk » → exclu) → x86 installé
            // sur ARM = INSTALL_FAILED_NO_MATCHING_ABIS (« config du package »).
            // Le rename ARM → « -universal.apk » (1er asset uploadé) fait que même
            // les vieilles apps cassées reprennent l'ARM via leur branche else.
            val apkAssets = newReleases.first().assets
                .filter {
                    it.contentType == "application/vnd.android.package-archive" ||
                        it.name.endsWith(".apk", ignoreCase = true)
                }

            // ABI primaire de l'appareil : x86/x86_64 = émulateur ou TV Intel Atom.
            val isX86Device = Build.SUPPORTED_ABIS.firstOrNull()?.let {
                it.equals("x86", ignoreCase = true) || it.equals("x86_64", ignoreCase = true)
            } == true

            val asset = when {
                // Appareil x86/x86_64 → APK x86 dédié (émulateur, TV Intel).
                isX86Device ->
                    apkAssets.firstOrNull { it.name.endsWith("-x86.apk", ignoreCase = true) }

                // Build TV-only ARM → l'APK -tv.apk.
                BuildConfig.APP_LAYOUT == "tv" ->
                    apkAssets.firstOrNull { it.name.endsWith("-tv.apk", ignoreCase = true) }

                // Défaut (mobile + TV, ARM).
                else ->
                    apkAssets.firstOrNull { it.name.endsWith("-universal.apk", ignoreCase = true) }
            }
                // Filet de sécurité : n'importe quel APK NON-x86 (jamais x86 sur ARM).
                ?: apkAssets.firstOrNull { !it.name.endsWith("-x86.apk", ignoreCase = true) }
                ?: throw Exception("Can't find update APK")

            _state.emit(State.SuccessCheckingUpdate(newReleases, asset))
        } catch (e: Exception) {
            // Update check is a background task — failures should be silent.
            // Emitting FailedUpdate here caused a persistent toast (e.g. "HTTP 404")
            // on every provider opening because StateFlow replays the last value.
            Log.w("MainViewModel", "checkUpdate failed (silent): ${e.message}")
        }
    }

    fun downloadUpdate(
        context: Context,
        asset: GitHub.Release.Asset,
    ) = viewModelScope.launch(Dispatchers.IO) {
        _state.emit(State.DownloadingUpdate)

        try {
            val apk = InAppUpdater.downloadApk(context, asset)

            _state.emit(State.SuccessDownloadingUpdate(apk))
        } catch (e: Exception) {
            Log.e("MainViewModel", "downloadUpdate: ", e)
            _state.emit(State.FailedUpdate(e))
        }
    }

    fun installUpdate(
        context: Context,
        apk: File,
    ) = viewModelScope.launch(Dispatchers.IO) {
        _state.emit(State.InstallingUpdate)

        try {
            InAppUpdater.installApk(context, Uri.fromFile(apk))
        } catch (e: Exception) {
            Log.e("MainViewModel", "installUpdate: ", e)
            _state.emit(State.FailedUpdate(e))
        }
    }
}

