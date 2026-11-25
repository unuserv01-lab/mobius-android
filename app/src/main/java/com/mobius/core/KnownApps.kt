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
        // v8.1-hotfix-4: browser default Tuan (Brave) — package resmi
        // com.brave.browser (terverifikasi via Play Store). Tanpa ini,
        // "buka brave" jatuh ke planning loop dan menebak-nebak package.
        "brave" to "com.brave.browser",
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
        // --- v7.7 bug 4a: system apps. One name = one package, so the
        // variant picked is the MIUI/HyperOS one on Tuan's phone; the
        // Google variant lives under a separate "google ..." key.
        "alarm" to "com.android.deskclock",
        "jam" to "com.android.deskclock",
        "clock" to "com.android.deskclock",
        "google clock" to "com.google.android.deskclock",
        "kalender" to "com.xiaomi.calendar",
        "calendar" to "com.xiaomi.calendar",
        "google calendar" to "com.google.android.calendar",
        "kamera" to "com.android.camera",
        "camera" to "com.android.camera",
        "galeri" to "com.miui.gallery",
        "gallery" to "com.miui.gallery",
        "foto" to "com.google.android.apps.photos",
        "pengaturan" to "com.android.settings",
        "setelan" to "com.android.settings",
        "settings" to "com.android.settings",
        "file" to "com.mi.android.globalFileexplorer",
        "files" to "com.mi.android.globalFileexplorer",
        "google files" to "com.google.android.apps.nbu.files",
        "kalkulator" to "com.miui.calculator",
        "calculator" to "com.miui.calculator",
        "kontak" to "com.android.contacts",
        "contacts" to "com.android.contacts",
        "pesan" to "com.android.mms",
        "sms" to "com.android.mms",
        "messages" to "com.google.android.apps.messaging",
        // "telepon": on MIUI the dialer lives inside the Contacts container
        // (com.android.contacts). ROMs with Google Phone use
        // com.google.android.dialer instead — verify on the device if the
        // launch ever fails. NEVER com.android.phone: that is the telephony
        // service, it has no launcher activity and always fails to open.
        "telepon" to "com.android.contacts",
        "phone" to "com.android.contacts",
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
        "brave" to "Brave",
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
        "alarm" to "Alarm",
        "jam" to "Jam",
        "clock" to "Clock",
        "google clock" to "Google Clock",
        "kalender" to "Kalender",
        "calendar" to "Calendar",
        "google calendar" to "Google Calendar",
        "kamera" to "Kamera",
        "camera" to "Camera",
        "galeri" to "Galeri",
        "gallery" to "Gallery",
        "foto" to "Foto",
        "pengaturan" to "Pengaturan",
        "setelan" to "Setelan",
        "settings" to "Settings",
        "file" to "File",
        "files" to "Files",
        "google files" to "Google Files",
        "kalkulator" to "Kalkulator",
        "calculator" to "Calculator",
        "kontak" to "Kontak",
        "contacts" to "Contacts",
        "pesan" to "Pesan",
        "sms" to "SMS",
        "messages" to "Messages",
        "telepon" to "Telepon",
        "phone" to "Phone",
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
