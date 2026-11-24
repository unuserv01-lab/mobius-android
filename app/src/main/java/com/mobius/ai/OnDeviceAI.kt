@file:OptIn(PublicPreviewAPI::class)

package com.mobius.ai

// ---------------------------------------------------------------------------------------------
// Imports VERIFIED against the real SDK artifacts (23 Nov 2026, VM build):
//   firebase-ai-ondevice 16.0.0-beta08 AAR -> com.google.firebase.ai.ondevice.*
//     (FirebaseAIOnDevice, OnDeviceModelStatus, DownloadStatus + subclasses)
//   firebase-ai 18.2.0 AAR -> com.google.firebase.ai.type.*
//     (GenerativeBackend, PublicPreviewAPI)
//   firebase-ai 18.2.0 AAR -> com.google.firebase.ai  (TOP-LEVEL package, NOT .type.*)
//     (InferenceMode, OnDeviceConfig) — these two were the original bug: the wildcard
//     import com.google.firebase.ai.type.* does NOT cover them.
//   Verified by javap: InferenceMode.ONLY_ON_DEVICE, OnDeviceConfig(mode=...),
//   FirebaseAI.generativeModel(modelName, onDeviceConfig=...),
//   FirebaseAIOnDevice.checkStatus() suspend -> OnDeviceModelStatus,
//   FirebaseAIOnDevice.download() -> Flow<DownloadStatus>,
//   DownloadStarted.bytesToDownload, DownloadInProgress.totalBytesDownloaded.
// ---------------------------------------------------------------------------------------------
import com.google.firebase.Firebase
import com.google.firebase.ai.InferenceMode
import com.google.firebase.ai.OnDeviceConfig
import com.google.firebase.ai.ai
import com.google.firebase.ai.ondevice.*
import com.google.firebase.ai.type.*
import com.mobius.core.AppConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class NanoStatus(val label: String) {
    AVAILABLE("available"),
    DOWNLOADABLE("downloadable (not downloaded yet)"),
    DOWNLOADING("downloading"),
    UNAVAILABLE("unavailable on this device"),
}

/**
 * Gemini Nano on-device inference through Firebase AI Logic (P3: on-device is the default).
 *
 * We use InferenceMode.ONLY_ON_DEVICE so that THIS class can never reach the cloud; the decision
 * to use the cloud belongs exclusively to [AIRouter], which enforces the "Allow cloud AI" gate (P4).
 * On-device inference is single-turn text (plus at most one image); no multi-turn chat, no tools.
 */
class OnDeviceAI {

    private val model by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = AppConfig.CLOUD_MODEL, // required by the SDK; never called in ONLY_ON_DEVICE mode
            onDeviceConfig = OnDeviceConfig(mode = InferenceMode.ONLY_ON_DEVICE),
        )
    }

    /** Whether Gemini Nano can serve a request right now. */
    suspend fun status(): NanoStatus = try {
        when (FirebaseAIOnDevice.checkStatus()) {
            OnDeviceModelStatus.AVAILABLE -> NanoStatus.AVAILABLE
            OnDeviceModelStatus.DOWNLOADABLE -> NanoStatus.DOWNLOADABLE
            OnDeviceModelStatus.DOWNLOADING -> NanoStatus.DOWNLOADING
            else -> NanoStatus.UNAVAILABLE
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        NanoStatus.UNAVAILABLE
    }

    /** Runs the prompt fully on the device. Throws if Nano cannot answer. */
    suspend fun generate(prompt: String): String {
        val text = model.generateContent(prompt).text
        require(!text.isNullOrBlank()) { "Gemini Nano returned an empty response" }
        return text
    }

    /** Downloads the Nano model (user-initiated from Settings; the SDK never downloads silently). */
    fun download(): Flow<String> = FirebaseAIOnDevice.download().map { status ->
        when (status) {
            is DownloadStatus.DownloadStarted -> "Download started (${status.bytesToDownload} bytes)"
            is DownloadStatus.DownloadInProgress -> "Downloading... ${status.totalBytesDownloaded} bytes"
            is DownloadStatus.DownloadCompleted -> "Download complete"
            is DownloadStatus.DownloadFailed -> "Download failed"
            else -> status.toString()
        }
    }
}
