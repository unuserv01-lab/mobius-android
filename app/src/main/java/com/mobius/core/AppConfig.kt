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

    /**
     * v7.2 — Default fallback model on OpenRouter (free tier), used only when Gemini
     * cloud fails (timeout or error) AND Tuan stored an OpenRouter key. User-changeable
     * in Settings — never the only option, because free models rotate.
     *
     * PICKED 24 Nov 2026 from the LIVE list (curl openrouter.ai/api/v1/models:
     * 473 models, 12 ":free"): "google/gemma-4-26b-a4b-it:free". Why this one:
     * Google Gemma 4, instruct-tuned (good at the short classification/JSON prompts
     * MOBIUS sends), NON-reasoning (reasoning ":free" models burn the whole token
     * budget on reasoning tokens and return null content — verified trap, AGENTS.md),
     * mid-size 26B (sane latency on the free tier). Rejected: reasoning models
     * (ling-3.2-flash, nemotron-3-nano-omni reasoning), previews (ernie-5.5-flash-preview,
     * dots-3-note-preview), unknown vendors (particle-1, flint-v1.0, lfm-3-1.7b),
     * the 550B ultra MoE (free-tier latency risk), VL-only variants.
     * Re-check the live list when this stops working — free models come and go.
     */
    const val DEFAULT_OPENROUTER_MODEL = "google/gemma-4-26b-a4b-it:free"

    /** Default TTL for memory + routing log entries (seconds). User-adjustable in Settings. */
    const val DEFAULT_TTL_SECONDS = 86_400L

    /** In-memory notification cache lifetime. Notifications are never written to disk. */
    const val NOTIFICATION_TTL_MS = 10 * 60 * 1000L

    /** Max characters of any external text we put into a prompt (keeps Nano prompts small). */
    const val MAX_PROMPT_FIELD_CHARS = 400

    val WHATSAPP_PACKAGES: Set<String> = setOf("com.whatsapp", "com.whatsapp.w4b")
}
