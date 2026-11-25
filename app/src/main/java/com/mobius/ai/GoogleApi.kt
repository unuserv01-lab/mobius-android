package com.mobius.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64

/**
 * Thrown when a Google API call fails (no login, expired session, HTTP error, timeout,
 * unparsable response). A NORMAL failure, like [OpenRouterException]: the caller
 * ([com.mobius.core.SkillRunner]) turns it into an honest ToolResult(false, ...) and
 * the user sees the reason instead of a fake success. The message never contains
 * the access token — only the HTTP status / a short reason.
 */
class GoogleApiException(message: String) : Exception(message)

/** One Gmail message header (list view). */
data class GmailMessage(
    val id: String,
    val from: String,
    val subject: String,
    val date: String,
    val snippet: String,
)

/** One Gmail message with its plain-text body. */
data class GmailMessageDetail(
    val id: String,
    val from: String,
    val subject: String,
    val date: String,
    val body: String,
    val snippet: String,
)

/** One calendar event. [start]/[end] are RFC 3339 strings (or a plain date for all-day events). */
data class CalendarEvent(
    val id: String,
    val title: String,
    val start: String,
    val end: String,
    val attendees: List<String> = emptyList(),
    val description: String = "",
)

/** One Drive file. */
data class DriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val modifiedTime: String,
    val size: Long? = null,
)

/** One Google Doc as plain text (tables are skipped — paragraphs only). */
data class DocContent(
    val id: String,
    val title: String,
    val text: String,
)

/**
 * v7.9 — Google API client (Gmail, Calendar, Drive, Docs) over plain REST.
 *
 * Pattern: exactly like [OpenRouterAI] — HttpURLConnection + org.json, stdlib only, no new
 * dependencies. The access token comes from [tokenProvider] (normally
 * `GoogleAuth.getAccessToken`, the contract in ai/GoogleAuth.kt owned by another v7.9
 * builder); the token is sent as a Bearer header and is NEVER logged, never put in any
 * message, never stored anywhere by this class. A null token (not signed in) or a 401
 * after refresh is an honest [GoogleApiException], never a fake success.
 *
 * Endpoints (all v1/v3, Google's public REST APIs):
 *   Gmail:    gmail.googleapis.com/gmail/v1
 *   Calendar: calendar.googleapis.com/calendar/v3 (primary calendar only)
 *   Drive:    drive.googleapis.com/drive/v3
 *   Docs:     docs.googleapis.com/v1
 *
 * [createCalendarEvent] accepts flexible start/end values because the skill-param LLM
 * is only given param names, not a format: RFC 3339 ("2026-11-27T10:00:00+07:00"),
 * "yyyy-MM-dd HH:mm", "yyyy-MM-dd", a day word + time ("besok 10:00", "hari ini jam 9"),
 * or a bare time ("10:00" = today). A blank end defaults to start + 1 hour.
 */
class GoogleApi(private val tokenProvider: suspend () -> String?) {

    // --- Gmail ---

    /** Latest messages first; a blank [query] lists the newest inbox messages. */
    suspend fun searchGmail(query: String, maxResults: Int = 10): List<GmailMessage> {
        val q = if (query.isBlank()) "" else "&q=${URLEncoder.encode(query, "UTF-8")}"
        val list = request("GET", "$GMAIL_BASE/messages?maxResults=${maxResults.coerceIn(1, 20)}$q")
        val arr = list.optJSONArray("messages") ?: return emptyList()
        val out = mutableListOf<GmailMessage>()
        for (i in 0 until arr.length()) {
            val id = arr.optJSONObject(i)?.optString("id").orEmpty()
            if (id.isBlank()) continue
            out += fetchGmailMetadata(id)
        }
        return out
    }

    /** Full message: headers + plain-text body (text/html is stripped only as a fallback). */
    suspend fun readGmail(messageId: String): GmailMessageDetail {
        val json = request("GET", "$GMAIL_BASE/messages/${urlEncode(messageId)}?format=full")
        val headers = headerMap(json.optJSONObject("payload"))
        val payload = json.optJSONObject("payload")
        val body = if (payload != null) extractPlainText(payload) else ""
        return GmailMessageDetail(
            id = json.optString("id"),
            from = headers["from"].orEmpty(),
            subject = headers["subject"].orEmpty(),
            date = headers["date"].orEmpty(),
            body = body.ifBlank { json.optString("snippet") },
            snippet = json.optString("snippet"),
        )
    }

    /** Sends a plain-text email. Returns the Gmail message id of the sent message. */
    suspend fun sendGmail(to: String, subject: String, body: String): String {
        if (!to.contains("@")) {
            throw GoogleApiException("Alamat email tujuan tidak valid: \"$to\".")
        }
        if (subject.isBlank() && body.isBlank()) {
            throw GoogleApiException("Email kosong: isi subjek atau badan email dulu.")
        }
        val mime = buildString {
            append("To: $to\r\n")
            if (subject.isNotBlank()) append("Subject: ${encodeHeader(subject)}\r\n")
            append("Content-Type: text/plain; charset=UTF-8\r\n")
            append("Content-Transfer-Encoding: 8bit\r\n")
            append("\r\n")
            append(body)
        }
        val raw = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(mime.toByteArray(Charsets.UTF_8))
        val resp = request("POST", "$GMAIL_BASE/messages/send", JSONObject().put("raw", raw))
        return resp.optString("id").takeIf { it.isNotBlank() }
            ?: throw GoogleApiException("Gmail: email terkirim tapi respons tidak berisi id pesan.")
    }

    // --- Calendar (primary calendar) ---

    /** Events between two RFC 3339 timestamps, ordered by start time. */
    suspend fun listCalendarEvents(
        timeMin: String,
        timeMax: String,
        maxResults: Int = 20,
    ): List<CalendarEvent> {
        val url = "$CALENDAR_BASE/calendars/primary/events" +
            "?timeMin=${urlEncode(timeMin)}&timeMax=${urlEncode(timeMax)}" +
            "&singleEvents=true&orderBy=startTime&maxResults=${maxResults.coerceIn(1, 50)}"
        val resp = request("GET", url)
        val items = resp.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            items.optJSONObject(i)?.let { parseCalendarEvent(it) }
        }
    }

    /**
     * Events for one day, named in Indonesian: "hari ini" (default), "besok", "lusa",
     * "kemarin", or an ISO date ("2026-11-27"). Day boundaries use Asia/Jakarta.
     */
    suspend fun listCalendarDay(day: String): List<CalendarEvent> {
        val date = parseDayWord(day)
        val start = OffsetDateTime.of(date, LocalTime.MIDNIGHT, JAKARTA_OFFSET)
        return listCalendarEvents(start.toString(), start.plusDays(1).toString())
    }

    /**
     * Creates an event on the primary calendar. [start]/[end] accept the flexible
     * formats described on the class; a blank [end] means start + 1 hour. Returns
     * the created event (with its Google id).
     */
    suspend fun createCalendarEvent(
        title: String,
        start: String,
        end: String,
        description: String? = null,
        attendees: List<String> = emptyList(),
        timeZone: String = "Asia/Jakarta",
    ): CalendarEvent {
        if (title.isBlank()) throw GoogleApiException("Judul acara kosong.")
        val today = LocalDate.now(JAKARTA_ZONE)
        val startIso = normalizeDateTime(start, today)
            ?: throw GoogleApiException(
                "Waktu mulai tidak bisa dibaca: \"$start\". " +
                    "Contoh: \"besok 10:00\" atau \"2026-11-27T10:00:00+07:00\".",
            )
        val parsedEnd = normalizeDateTime(end, startIso.toLocalDate())
        val endIso = if (parsedEnd == null || !parsedEnd.isAfter(startIso)) startIso.plusHours(1) else parsedEnd
        val body = JSONObject().apply {
            put("summary", title)
            put("start", JSONObject().apply {
                put("dateTime", startIso.toString())
                put("timeZone", timeZone)
            })
            put("end", JSONObject().apply {
                put("dateTime", endIso.toString())
                put("timeZone", timeZone)
            })
            if (!description.isNullOrBlank()) put("description", description)
            if (attendees.isNotEmpty()) {
                put("attendees", JSONArray().apply {
                    attendees.filter { it.contains("@") }.forEach { email ->
                        put(JSONObject().put("email", email))
                    }
                })
            }
        }
        val resp = request("POST", "$CALENDAR_BASE/calendars/primary/events", body)
        return parseCalendarEvent(resp)
    }

    // --- Drive + Docs ---

    /**
     * Files whose name contains [query] (case-insensitive), newest activity first.
     * [mimeType] optionally restricts the result, e.g. the Google Docs MIME type
     * for the "baca dokumen" skill.
     */
    suspend fun searchDrive(
        query: String,
        maxResults: Int = 10,
        mimeType: String? = null,
    ): List<DriveFile> {
        if (query.isBlank()) throw GoogleApiException("Kata kunci pencarian Drive kosong.")
        val escaped = query.replace("\\", "\\\\").replace("'", "\\'")
        val clauses = mutableListOf("name contains '$escaped'", "trashed = false")
        if (!mimeType.isNullOrBlank()) clauses += "mimeType = '$mimeType'"
        val q = urlEncode(clauses.joinToString(" and "))
        val fields = urlEncode("files(id,name,mimeType,modifiedTime,size)")
        val resp = request(
            "GET",
            "$DRIVE_BASE/files?q=$q&fields=$fields&pageSize=${maxResults.coerceIn(1, 20)}",
        )
        val files = resp.optJSONArray("files") ?: return emptyList()
        return (0 until files.length()).mapNotNull { i ->
            val f = files.optJSONObject(i) ?: return@mapNotNull null
            DriveFile(
                id = f.optString("id"),
                name = f.optString("name"),
                mimeType = f.optString("mimeType"),
                modifiedTime = f.optString("modifiedTime"),
                size = f.optString("size").toLongOrNull(),
            )
        }
    }

    /** Reads a Google Doc as plain text (paragraphs only; tables are skipped). */
    suspend fun readDoc(docId: String): DocContent {
        val doc = request("GET", "$DOCS_BASE/documents/${urlEncode(docId)}")
        return DocContent(
            id = doc.optString("documentId", docId),
            title = doc.optString("title"),
            text = docText(doc),
        )
    }

    // --- HTTP plumbing (same shape as OpenRouterAI) ---

    /**
     * One authenticated request. The token is fetched fresh per call (the provider
     * refreshes it when needed). Null token = not signed in; 401 = the session died
     * even after refresh. Both are honest failures, never silent successes.
     */
    private suspend fun request(method: String, url: String, body: JSONObject? = null): JSONObject {
        val token = tokenProvider() ?: throw GoogleApiException(NOT_SIGNED_IN)
        val raw = try {
            withTimeout(GOOGLE_TIMEOUT_MS) {
                withContext(Dispatchers.IO) { requestBlocking(method, url, body, token) }
            }
        } catch (e: TimeoutCancellationException) {
            // Same rule as OpenRouterAI: a timeout is a normal failure, not a coroutine
            // cancellation — it must reach the outcome path, never strand the bubble.
            throw GoogleApiException("Google API timeout ${GOOGLE_TIMEOUT_MS / 1000} detik — coba lagi.")
        }
        if (raw.isBlank()) return JSONObject()
        return try {
            JSONObject(raw)
        } catch (e: Exception) {
            throw GoogleApiException("Google API: respons tidak bisa dibaca.")
        }
    }

    private fun requestBlocking(method: String, url: String, body: JSONObject?, token: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = GOOGLE_TIMEOUT_MS.toInt()
            doOutput = body != null
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
            if (body != null) setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        }
        try {
            if (body != null) {
                conn.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
            }
            val code = conn.responseCode
            val raw = readBody(conn)
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED) throw GoogleApiException(SESSION_EXPIRED)
            if (code < 200 || code >= 300) {
                throw GoogleApiException("Google API HTTP $code${errorDetail(raw)}")
            }
            return raw
        } finally {
            conn.disconnect()
        }
    }

    private fun readBody(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode < HttpURLConnection.HTTP_BAD_REQUEST) {
            conn.inputStream
        } else {
            conn.errorStream
        } ?: return ""
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    /** Short "…: <google error message>" suffix, truncated; never contains the token. */
    private fun errorDetail(raw: String): String {
        val msg = try {
            JSONObject(raw).optJSONObject("error")?.optString("message").orEmpty()
        } catch (e: Exception) {
            ""
        }
        return if (msg.isBlank()) "" else ": ${msg.take(200)}"
    }

    // --- Gmail parsing ---

    private suspend fun fetchGmailMetadata(id: String): GmailMessage {
        val json = request(
            "GET",
            "$GMAIL_BASE/messages/${urlEncode(id)}" +
                "?format=metadata&metadataHeaders=From&metadataHeaders=Subject&metadataHeaders=Date",
        )
        val headers = headerMap(json.optJSONObject("payload"))
        return GmailMessage(
            id = id,
            from = headers["from"].orEmpty(),
            subject = headers["subject"].orEmpty(),
            date = headers["date"].orEmpty(),
            snippet = json.optString("snippet"),
        )
    }

    private fun headerMap(payload: JSONObject?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val headers = payload?.optJSONArray("headers") ?: return out
        for (i in 0 until headers.length()) {
            val h = headers.optJSONObject(i) ?: continue
            out[h.optString("name").lowercase()] = h.optString("value")
        }
        return out
    }

    /** Prefers text/plain; falls back to text/html with tags stripped; else "". */
    private fun extractPlainText(payload: JSONObject): String {
        findPartText(payload, "text/plain")?.let { return it }
        findPartText(payload, "text/html")?.let { return stripHtml(it) }
        return ""
    }

    private fun findPartText(part: JSONObject, mime: String): String? {
        if (part.optString("mimeType") == mime) {
            val data = part.optJSONObject("body")?.optString("data").orEmpty()
            if (data.isNotBlank()) return decodeBase64Url(data)
        }
        val parts = part.optJSONArray("parts") ?: return null
        for (i in 0 until parts.length()) {
            findPartText(parts.optJSONObject(i) ?: continue, mime)?.let { return it }
        }
        return null
    }

    private fun decodeBase64Url(data: String): String = try {
        val padded = data + "=".repeat((4 - data.length % 4) % 4)
        String(Base64.getUrlDecoder().decode(padded), Charsets.UTF_8)
    } catch (e: Exception) {
        ""
    }

    private fun stripHtml(html: String): String =
        html.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()

    /** RFC 2047 encoded-word for non-ASCII subjects; plain ASCII passes through untouched. */
    private fun encodeHeader(value: String): String {
        if (value.all { it.code < 128 }) return value
        val encoded = Base64.getEncoder().withoutPadding()
            .encodeToString(value.toByteArray(Charsets.UTF_8))
        return "=?UTF-8?B?$encoded?="
    }

    // --- Calendar parsing + flexible time input ---

    private fun parseCalendarEvent(json: JSONObject): CalendarEvent {
        val startObj = json.optJSONObject("start")
        val endObj = json.optJSONObject("end")
        val attendees = json.optJSONArray("attendees")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.optString("email")?.takeIf { it.isNotBlank() }
            }
        } ?: emptyList()
        return CalendarEvent(
            id = json.optString("id"),
            title = json.optString("summary", "(tanpa judul)"),
            start = startObj?.optString("dateTime")?.takeIf { it.isNotBlank() }
                ?: startObj?.optString("date").orEmpty(),
            end = endObj?.optString("dateTime")?.takeIf { it.isNotBlank() }
                ?: endObj?.optString("date").orEmpty(),
            attendees = attendees,
            description = json.optString("description"),
        )
    }

    private fun parseDayWord(day: String): LocalDate {
        val today = LocalDate.now(JAKARTA_ZONE)
        return when (day.trim().lowercase()) {
            "", "hari ini", "today", "ini" -> today
            "besok", "tomorrow" -> today.plusDays(1)
            "lusa" -> today.plusDays(2)
            "kemarin", "yesterday" -> today.minusDays(1)
            else -> runCatching {
                LocalDate.parse(day.trim(), DateTimeFormatter.ISO_LOCAL_DATE)
            }.getOrDefault(today)
        }
    }

    /**
     * Parses the flexible start/end values described on the class. Returns null when
     * nothing in [value] can be read as a date/time.
     */
    private fun normalizeDateTime(value: String, refDate: LocalDate): OffsetDateTime? {
        val v = value.trim()
        if (v.isEmpty()) return null
        if ('T' in v) {
            runCatching { OffsetDateTime.parse(v) }.getOrNull()?.let { return it }
            runCatching { LocalDateTime.parse(v).atOffset(JAKARTA_OFFSET) }.getOrNull()?.let { return it }
        }
        val time = Regex("""(\d{1,2})[:.](\d{2})""").find(v)?.let { m ->
            val hour = m.groupValues[1].toIntOrNull() ?: return@let null
            val minute = m.groupValues[2].toIntOrNull() ?: return@let null
            if (hour !in 0..23 || minute !in 0..59) return@let null
            LocalTime.of(hour, minute)
        } ?: Regex("""\bjam\s+(\d{1,2})\b""").find(v.lowercase())?.let { m ->
            val hour = m.groupValues[1].toIntOrNull() ?: return@let null
            if (hour !in 0..23) return@let null
            LocalTime.of(hour, 0)
        }
        val date = Regex("""(\d{4}-\d{2}-\d{2})""").find(v)?.let { m ->
            runCatching { LocalDate.parse(m.groupValues[1]) }.getOrNull()
        } ?: when {
            Regex("""\bbesok\b""").containsMatchIn(v.lowercase()) -> refDate.plusDays(1)
            Regex("""\blusa\b""").containsMatchIn(v.lowercase()) -> refDate.plusDays(2)
            Regex("""\bkemarin\b""").containsMatchIn(v.lowercase()) -> refDate.minusDays(1)
            Regex("""\b(hari ini|today)\b""").containsMatchIn(v.lowercase()) -> refDate
            time != null -> refDate
            else -> null
        } ?: return null
        return OffsetDateTime.of(date, time ?: LocalTime.of(9, 0), JAKARTA_OFFSET)
    }

    // --- Docs parsing ---

    private fun docText(doc: JSONObject): String {
        val sb = StringBuilder()
        val content = doc.optJSONObject("body")?.optJSONArray("content") ?: return ""
        for (i in 0 until content.length()) {
            val paragraph = content.optJSONObject(i)?.optJSONObject("paragraph") ?: continue
            val elements = paragraph.optJSONArray("elements") ?: continue
            for (j in 0 until elements.length()) {
                sb.append(elements.optJSONObject(j)?.optJSONObject("textRun")?.optString("content").orEmpty())
            }
        }
        return sb.toString().trim()
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        const val GMAIL_BASE = "https://gmail.googleapis.com/gmail/v1/users/me"
        const val CALENDAR_BASE = "https://calendar.googleapis.com/calendar/v3"
        const val DRIVE_BASE = "https://drive.googleapis.com/drive/v3"
        const val DOCS_BASE = "https://docs.googleapis.com/v1"

        /** Same 30 s as the OpenRouter timeout: fail fast on a bad connection. */
        const val GOOGLE_TIMEOUT_MS = 30_000L

        /** Shown when no Google account is connected (token provider returned null). */
        const val NOT_SIGNED_IN =
            "Belum login Google — buka Settings → Akun Google untuk menghubungkan akunmu."

        /** Shown when Google answers 401 even after the provider refreshed the token. */
        const val SESSION_EXPIRED =
            "Sesi Google kedaluwarsa — login ulang di Settings → Akun Google."

        /** Google Docs MIME type, used to restrict Drive search for the "baca dokumen" skill. */
        const val MIME_GOOGLE_DOC = "application/vnd.google-apps.document"

        val JAKARTA_ZONE: ZoneId = ZoneId.of("Asia/Jakarta")
        val JAKARTA_OFFSET: ZoneOffset = ZoneOffset.ofHours(7)
    }
}
