package com.mobius.core

/**
 * v7.5 — ONE known-apps map (name -> official package id), used by the planning
 * loop to resolve an app NAME to the real package before launching it.
 *
 * Why: the planner's launchApp argument used to come 100% from the model's JSON
 * guess, with no resolution anywhere — the model could pass "youtube" (a name,
 * not a package) or invent an id like "com.youtube.app", and the launch then
 * failed 3 times in a row ("open YouTube app", 24 Nov 15:49). Only well-known
 * official package ids live here; an app we are unsure about is left out on
 * purpose, and then the model's guess is used exactly as before.
 */
object KnownApps {
    val PACKAGES: Map<String, String> = mapOf(
        "youtube" to "com.google.android.youtube",
        "whatsapp" to "com.whatsapp",
        "chrome" to "com.android.chrome",
        "gmail" to "com.google.android.gm",
        "maps" to "com.google.android.apps.maps",
        "instagram" to "com.instagram.android",
        "tiktok" to "com.zhiliaoapp.musically",
        "shopee" to "com.shopee.id",
        "tokopedia" to "com.tokopedia.tkpd",
        "gojek" to "com.gojek.app",
        "grab" to "com.grabtaxi.passenger",
        "dana" to "id.dana",
        "ovo" to "ovo.id",
    )

    /** Short aliases people actually type (arguments only, never text scanning). */
    private val ALIASES: Map<String, String> = mapOf(
        "yt" to "youtube",
        "wa" to "whatsapp",
        "ig" to "instagram",
    )

    /** Proper display spelling per app name ("youtube" -> "YouTube"). */
    private val DISPLAY_NAMES: Map<String, String> = mapOf(
        "youtube" to "YouTube",
        "whatsapp" to "WhatsApp",
        "chrome" to "Chrome",
        "gmail" to "Gmail",
        "maps" to "Maps",
        "instagram" to "Instagram",
        "tiktok" to "TikTok",
        "shopee" to "Shopee",
        "tokopedia" to "Tokopedia",
        "gojek" to "Gojek",
        "grab" to "Grab",
        "dana" to "DANA",
        "ovo" to "OVO",
    )

    /** Display name for a package ("YouTube"), or null when the package is unknown. */
    fun displayName(packageName: String): String? =
        PACKAGES.entries.firstOrNull { it.value == packageName }?.key
            ?.let { DISPLAY_NAMES[it] ?: it.replaceFirstChar { c -> c.uppercaseChar() } }

    /**
     * Resolves a launchApp argument to a real package id, or null when no
     * resolution applies (the caller then uses the raw argument as-is).
     * - A known app name / alias ("YouTube", "yt") -> the map's package.
     * - The task text mentions a known app and the argument is NOT that app's
     *   package -> the mentioned app's package (the map beats a wrong guess
     *   like "com.youtube.app"). An argument that already IS a mentioned app's
     *   package is kept, so multi-app tasks still work step by step.
     */
    fun resolvePackage(argument: String, taskText: String? = null): String? {
        val v = argument.trim()
        if (v.isEmpty()) return null
        val key = ALIASES[v.lowercase()] ?: v.lowercase()
        PACKAGES[key]?.let { return it }
        val mentioned = findInText(taskText ?: return null)
        if (mentioned.isEmpty()) return null
        if (mentioned.any { it.second == v }) return v
        return mentioned.first().second
    }

    /** Known apps mentioned in free text, in order of appearance. */
    fun findInText(text: String): List<Pair<String, String>> {
        val t = " ${text.lowercase()} "
        return PACKAGES.entries
            .filter { (name, _) -> " $name " in t }
            .map { (name, pkg) -> name to pkg }
            .sortedBy { t.indexOf(" ${it.first} ") }
    }
}
