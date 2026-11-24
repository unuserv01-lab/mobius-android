package com.mobius.core

import com.mobius.BuildConfig

/**
 * Central constants. The product NAME comes from app/build.gradle.kts (`mobiusAppName`),
 * the single place to rename the app; it is surfaced here via BuildConfig.
 */
object AppConfig {
    const val APP_NAME: String = BuildConfig.APP_NAME
    const val CODENAME = "Gembul"
    const val TAGLINE = "Your Intelligence. Everywhere. Owned by You."

    /**
     * Cloud model used by Firebase AI Logic (Gemini Developer API, free tier).
     * Check the Firebase console / docs for the currently available model names and change here.
     * The on-device model also needs a cloud model name to be constructed (SDK requirement),
     * but ONLY_ON_DEVICE mode never calls it.
     *
     * HISTORY: "gemini-2.5-flash" until 24 Nov 2026 00:58 WIB — Google retired it for new
     * users ("no longer available to new users", error seen live on Tuan's phone) and the
     * error itself recommended "gemini-3.8-flash". Same model the AI Studio build uses.
     */
    const val CLOUD_MODEL = "gemini-3.8-flash"

    /** Default TTL for memory + routing log entries (seconds). User-adjustable in Settings. */
    const val DEFAULT_TTL_SECONDS = 86_400L

    /** In-memory notification cache lifetime. Notifications are never written to disk. */
    const val NOTIFICATION_TTL_MS = 10 * 60 * 1000L

    /** Max characters of any external text we put into a prompt (keeps Nano prompts small). */
    const val MAX_PROMPT_FIELD_CHARS = 400

    val WHATSAPP_PACKAGES: Set<String> = setOf("com.whatsapp", "com.whatsapp.w4b")
}
