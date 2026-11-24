package com.mobius.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater
import java.util.zip.ZipInputStream

/**
 * v6 workstream N-core — ekstraksi teks dari file yang DIPILIH PENGGUNA.
 *
 * ## Keamanan: konten file adalah DATA YANG TIDAK DIPERCAYA
 * Isi file bisa mengandung teks yang tampak seperti perintah/instruksi
 * (prompt injection). Extractor ini HANYA mengembalikan teks apa adanya —
 * ia tidak pernah mengeksekusi, menafsirkan, atau mengikuti isi file.
 * Pemanggil (UI / Orchestrator / agent) WAJIB memperlakukan [ExtractedFile.text]
 * sebagai data dan membatasinya secara eksplisit (mis. di dalam blok kutip /
 * delimiter) saat diteruskan ke LLM atau ke skill — jangan pernah
 * menyambungnya mentah sebagai instruksi.
 *
 * ## Cakupan
 * HANYA file yang pengguna pilih secara eksplisit lewat system picker
 * ([FilePickerHelper.createOpenDocumentIntent]). Kode ini TIDAK PERNAH
 * memindai storage, TIDAK PERNAH me-list direktori, dan TIDAK PERNAH
 * membuka file tanpa Uri hasil picker.
 *
 * ## Format
 * - `.txt` / `.md`: teks polos (UTF-8). Dijamin bekerja.
 * - `.pdf`: parser ringan best-effort atas content stream PDF (FlateDecode
 *   via [Inflater], string Tj/TJ — literal maupun hex). PdfRenderer TIDAK
 *   dipakai: ia me-render halaman jadi bitmap dan TIDAK mengekstrak teks.
 *   PDF hasil scan / berbasis gambar, PDF terenkripsi, atau PDF dengan
 *   encoding font non-standar kemungkinan besar menghasilkan teks kosong
 *   atau kacau — itu dilaporkan jujur sebagai kegagalan, bukan diisi teks
 *   palsu.
 * - `.docx`: unzip `word/document.xml`, ambil isi `<w:t>`. Dijamin bekerja
 *   untuk dokumen Word standar.
 * - `.xlsx`: unzip `xl/sharedStrings.xml` + `xl/worksheets/sheetN.xml`, gabung
 *   string bersama / inline / angka per baris (dipisah tab).
 * - Gambar (MIME image/ — semua tipe gambar, jpg/png/webp/dll): TIDAK didukung extractor ini —
 *   foto ditangani workstream lain (sensitive scan). Menghasilkan
 *   [FileExtractionException] "Format tidak didukung".
 *
 * ## Kegagalan — jujur, tidak pernah teks palsu
 * Bila format tidak didukung, file rusak, atau tidak ada teks yang berhasil
 * diekstrak, [extractFile] MELEMPAR [FileExtractionException] dengan pesan
 * "Format tidak didukung" atau "Teks tidak dapat diekstrak dari file ini".
 * Fungsi ini tidak pernah mengembalikan placeholder / teks karangan
 * seolah-olah itu isi file.
 *
 * ## Feature flag
 * [extractFile] menerima `localFilesEnabled` (default `true` agar mudah
 * dites tanpa aplikasi). Pemanggil di aplikasi harus mengisinya dari
 * `FeatureFlags.isEnabled(FeatureFlag.LOCAL_FILES).first()`; bila flag OFF,
 * ekstraksi menolak dengan pesan yang jelas.
 *
 * Nol dependensi baru: hanya stdlib Kotlin + `java.util.zip`.
 */

/** Kegagalan ekstraksi yang jujur. Pesan [message] aman ditampilkan ke pengguna. */
class FileExtractionException(message: String) : Exception(message)

/** Hasil ekstraksi satu file. [text] adalah data — bukan instruksi (lihat KDoc file). */
data class ExtractedFile(
    val fileName: String,
    val mimeType: String,
    val text: String,
    val truncated: Boolean,
)

/**
 * Cara kanonis memanggil dari UI: `FileExtractor.extractFile(context, uri, enabled)`.
 * Ada juga fungsi top-level [extractFile] di bawah sebagai delegasi, jadi pemanggil
 * boleh memakai salah satu — keduanya memanggil implementasi yang sama.
 */
object FileExtractor {

    /** Batas isi yang dikembalikan. Lebih dari ini dipotong dan [ExtractedFile.truncated] = true. */
    const val MAX_CHARS = 8000

    /** Batas ukuran file yang dibaca (20 MB) — pelindung OOM untuk file raksasa. */
    const val MAX_BYTES = 20_000_000

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp")

    /**
     * Ekstrak teks dari file yang dipilih pengguna.
     *
     * @param localFilesEnabled isi dari `FeatureFlag.LOCAL_FILES` (default true
     *   supaya fungsi ini bisa dites tanpa aplikasi). Bila false, lempar
     *   [FileExtractionException] sebelum file disentuh.
     * @throws FileExtractionException "Format tidak didukung" /
     *   "Teks tidak dapat diekstrak dari file ini" / "Fitur file lokal nonaktif" /
     *   "File terlalu besar (maks 20 MB)".
     */
    suspend fun extractFile(
        context: Context,
        uri: Uri,
        localFilesEnabled: Boolean = true,
    ): ExtractedFile {
        if (!localFilesEnabled) {
            throw FileExtractionException("Fitur file lokal nonaktif — aktifkan LOCAL_FILES di Pengaturan")
        }
        val fileName = queryDisplayName(context, uri) ?: "file"
        val extension = extensionOf(fileName)
        val mimeType = context.contentResolver.getType(uri)
            ?: mimeTypeForExtension(extension)
            ?: "application/octet-stream"
        val bytes = readAllBytes(context, uri)

        val rawText = when {
            extension == "txt" || extension == "md" ||
                mimeType == "text/plain" || mimeType == "text/markdown" ->
                extractPlainText(bytes)
            extension == "pdf" || mimeType == "application/pdf" ->
                extractPdfText(bytes)
            extension == "docx" ||
                mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ->
                extractDocxText(bytes)
            extension == "xlsx" ||
                mimeType == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" ->
                extractXlsxText(bytes)
            mimeType.startsWith("image/") || extension in IMAGE_EXTENSIONS ->
                throw FileExtractionException("Format tidak didukung")
            else -> throw FileExtractionException("Format tidak didukung")
        }

        val clean = rawText.trim()
        if (clean.isEmpty()) {
            throw FileExtractionException("Teks tidak dapat diekstrak dari file ini")
        }
        val (text, truncated) = truncateText(clean)
        return ExtractedFile(fileName = fileName, mimeType = mimeType, text = text, truncated = truncated)
    }

    /** True bila nama/mime ini bisa diekstrak teksnya (gambar -> false). */
    fun isSupportedFile(fileName: String?, mimeType: String?): Boolean {
        val ext = fileName?.let { extensionOf(it) }.orEmpty()
        return ext in setOf("txt", "md", "pdf", "docx", "xlsx") ||
            mimeType in setOf(
                "text/plain",
                "text/markdown",
                "application/pdf",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            )
    }

    /** Potong ke [MAX_CHARS]; kembalikan (teks, apakahDipotong). */
    fun truncateText(text: String): Pair<String, Boolean> =
        if (text.length > MAX_CHARS) text.substring(0, MAX_CHARS) to true else text to false

    // ------------------------------------------------------------------
    // Parser per format — fungsi murni atas ByteArray (bisa dites di JVM
    // tanpa Android). Semuanya TIDAK PERNAH throw untuk input rusak:
    // mereka mengembalikan "" dan extractFile mengubahnya menjadi
    // FileExtractionException yang jujur.
    // ------------------------------------------------------------------

    /** `.txt` / `.md`: UTF-8 polos (BOM dihapus). */
    fun extractPlainText(bytes: ByteArray): String =
        String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")

    /**
     * `.pdf`: parser minimal best-effort.
     * Cari tiap segmen `stream`…`endstream`, coba FlateDecode (zlib, lalu
     * raw-deflate sebagai fallback), lalu ambil string dari operator Tj/TJ.
     * Stream gambar (`/Subtype /Image`, DCT/JPX/dll) dilewati. PDF terenkripsi
     * / hasil scan / tanpa teks -> "" (pemanggil melaporkan kegagalan jujur).
     * Keterbatasan yang diketahui: encoding font kustom (tanpa ToUnicode)
     * bisa menghasilkan karakter kacau; Predictor pada FlateDecode tidak
     * ditangani sehingga sebagian PDF seperti itu bisa gagal.
     */
    fun extractPdfText(bytes: ByteArray): String {
        val raw = String(bytes, Charsets.ISO_8859_1) // Latin-1: 1 char = 1 byte, indeks tetap selaras
        val out = StringBuilder()
        var searchFrom = 0
        while (true) {
            val streamIdx = raw.indexOf("stream", searchFrom)
            if (streamIdx < 0) break
            var dataStart = streamIdx + "stream".length
            // "stream" harus diikuti CRLF / LF / CR (spesifikasi PDF).
            dataStart = when {
                raw.startsWith("\r\n", dataStart) -> dataStart + 2
                dataStart < raw.length && (raw[dataStart] == '\n' || raw[dataStart] == '\r') -> dataStart + 1
                else -> {
                    searchFrom = streamIdx + "stream".length
                    continue
                }
            }
            val endIdx = raw.indexOf("endstream", dataStart)
            if (endIdx < 0) break
            // Lewati stream gambar — isinya biner, bukan content stream teks.
            val dictHead = raw.substring(maxOf(0, streamIdx - 1000), streamIdx)
            if ("/Subtype /Image" in dictHead || "/Subtype/Image" in dictHead ||
                "/DCTDecode" in dictHead || "/JPXDecode" in dictHead ||
                "/CCITTFaxDecode" in dictHead || "/JBIG2Decode" in dictHead
            ) {
                searchFrom = endIdx + "endstream".length
                continue
            }
            val streamBytes = bytes.copyOfRange(dataStart, endIdx)
            val contentBytes = tryInflate(streamBytes) ?: streamBytes
            val content = String(contentBytes, Charsets.ISO_8859_1)
            val text = extractTextFromContentStream(content)
            if (text.isNotBlank()) {
                if (out.isNotEmpty()) out.append('\n')
                out.append(text)
            }
            searchFrom = endIdx + "endstream".length
        }
        return out.toString()
    }

    /**
     * `.docx`: ambil `<w:t>` per paragraf dari `word/document.xml`.
     * `<w:tab/>` -> tab, `<w:br/>` -> baris baru, paragraf dipisah baris baru.
     */
    fun extractDocxText(bytes: ByteArray): String {
        val xml = readZipEntries(bytes) { it == "word/document.xml" }["word/document.xml"] ?: return ""
        val out = StringBuilder()
        // Satu regex untuk ketiganya, sesuai urutan dokumen: teks <w:t>, tab, dan
        // line-break. (Mengganti <w:tab/> jadi "\t" SEBELUM ekstraksi tidak
        // bekerja — tab itu berada DI LUAR elemen <w:t> sehingga ikut terbuang.)
        val tokenRegex = Regex("<w:t[^>]*>([^<]*)</w:t>|<w:tab[^>]*/>|<w:br[^>]*/>")
        for (paragraph in xml.split("</w:p>")) {
            val text = tokenRegex.findAll(paragraph).joinToString("") { match ->
                // Urutan penting: "<w:tab/>" juga diawali "<w:t" — cek tab/br DULU.
                when {
                    match.value.startsWith("<w:tab") -> "\t"
                    match.value.startsWith("<w:br") -> "\n" // <w:br/>
                    else -> xmlUnescape(match.groupValues[1]) // <w:t>
                }
            }
            if (text.isNotBlank()) {
                if (out.isNotEmpty()) out.append('\n')
                out.append(text)
            }
        }
        return out.toString()
    }

    /**
     * `.xlsx`: string bersama dari `xl/sharedStrings.xml` + tiap
     * `xl/worksheets/sheet*.xml`. Sel dipisah tab, baris dipisah baris baru.
     * Sel `t="s"` -> shared string; `t="inlineStr"` -> `<t>` inline;
     * selain itu -> nilai `<v>` (angka / hasil formula).
     */
    fun extractXlsxText(bytes: ByteArray): String {
        val entries = readZipEntries(bytes) {
            it == "xl/sharedStrings.xml" ||
                (it.startsWith("xl/worksheets/") && it.endsWith(".xml"))
        }
        val sharedStrings = entries["xl/sharedStrings.xml"]?.let { xml ->
            Regex("<t[^>]*>([^<]*)</t>").findAll(xml)
                .map { xmlUnescape(it.groupValues[1]) }.toList()
        } ?: emptyList()
        val sheets = entries.keys.filter { it.startsWith("xl/worksheets/") }.sorted()
        if (sheets.isEmpty()) return ""
        val out = StringBuilder()
        for (sheet in sheets) {
            val xml = entries[sheet] ?: continue
            for (row in xml.split("<row").drop(1)) {
                val cells = Regex("<c\\b[^>]*>(.*?)</c>", RegexOption.DOT_MATCHES_ALL).findAll(row)
                val values = cells.map { match ->
                    val cellXml = match.value
                    val inner = match.groupValues[1]
                    val type = Regex("t=\"([^\"]*)\"").find(cellXml)?.groupValues?.get(1)
                    when (type) {
                        "s" -> {
                            val idx = Regex("<v>([^<]*)</v>").find(inner)
                                ?.groupValues?.get(1)?.toIntOrNull()
                            if (idx != null) sharedStrings.getOrNull(idx).orEmpty() else ""
                        }
                        "inlineStr" -> Regex("<t[^>]*>([^<]*)</t>").findAll(inner)
                            .joinToString("") { xmlUnescape(it.groupValues[1]) }
                        else -> Regex("<v>([^<]*)</v>").find(inner)
                            ?.groupValues?.get(1)?.let { xmlUnescape(it) }.orEmpty()
                    }
                }.toList()
                val line = values.joinToString("\t").trimEnd('\t', ' ')
                if (line.isNotBlank()) {
                    if (out.isNotEmpty()) out.append('\n')
                    out.append(line)
                }
            }
        }
        return out.toString()
    }

    // ------------------------------------------------------------------
    // Util internal
    // ------------------------------------------------------------------

    private fun extensionOf(fileName: String): String =
        fileName.substringAfterLast('.', "").lowercase()

    private fun mimeTypeForExtension(extension: String): String? = when (extension) {
        "txt" -> "text/plain"
        "md" -> "text/markdown"
        "pdf" -> "application/pdf"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        else -> null
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx < 0) null else cursor.getString(idx)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readAllBytes(context: Context, uri: Uri): ByteArray {
        val input = try {
            context.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            throw FileExtractionException("Teks tidak dapat diekstrak dari file ini")
        } ?: throw FileExtractionException("Teks tidak dapat diekstrak dari file ini")
        val out = ByteArrayOutputStream()
        input.use {
            val buf = ByteArray(8192)
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_BYTES) {
                    throw FileExtractionException("File terlalu besar (maks 20 MB)")
                }
            }
        }
        return out.toByteArray()
    }

    /** Baca entri zip yang cocok [wanted] sebagai string UTF-8. Entri rusak -> diabaikan. */
    private fun readZipEntries(bytes: ByteArray, wanted: (String) -> Boolean): Map<String, String> {
        val result = mutableMapOf<String, String>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && wanted(entry.name)) {
                        result[entry.name] = String(zin.readBytes(), Charsets.UTF_8)
                    }
                    entry = zin.nextEntry
                }
            }
        } catch (_: Exception) {
            // Zip rusak -> peta (mungkin kosong); pemanggil mengembalikan "".
        }
        return result
    }

    private fun xmlUnescape(text: String): String {
        var result = text
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&") // &amp; TERAKHIR agar "&amp;lt;" tetap "&lt;" literal
        result = Regex("&#x([0-9a-fA-F]+);").replace(result) { match ->
            match.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: match.value
        }
        result = Regex("&#([0-9]+);").replace(result) { match ->
            match.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: match.value
        }
        return result
    }

    /** Coba FlateDecode: zlib dulu, lalu raw-deflate. Gagal -> null (pakai byte mentah). */
    private fun tryInflate(data: ByteArray): ByteArray? {
        for (nowrap in listOf(false, true)) {
            try {
                val inflater = Inflater(nowrap)
                inflater.setInput(data)
                val out = ByteArrayOutputStream()
                val tmp = ByteArray(8192)
                while (!inflater.finished()) {
                    val n = inflater.inflate(tmp)
                    if (n == 0) break // butuh input lagi / selesai / data tidak valid
                    out.write(tmp, 0, n)
                    if (out.size() > 5_000_000) break // batas per stream: 5 MB
                }
                inflater.end()
                if (out.size() > 0) return out.toByteArray()
            } catch (_: Exception) {
                // Coba varian berikutnya; keduanya gagal -> null.
            }
        }
        return null
    }

    /**
     * Ambil teks dari SATU content stream PDF (sudah di-inflate bila perlu).
     * Tokenisasi sederhana: string literal `(…)` / hex `<…>`, lalu operator
     * `Tj` (tampilkan string), `TJ` (tampilkan array string), `Td`/`TD`/`T*`
     * (pindah baris). Angka kerning di array TJ diabaikan.
     */
    private fun extractTextFromContentStream(content: String): String {
        val out = StringBuilder()
        val pendingStrings = mutableListOf<String>() // string di luar array
        val arrayStrings = mutableListOf<String>() // string di dalam [...]
        var inArray = false
        var i = 0
        val n = content.length
        while (i < n) {
            val c = content[i]
            when {
                c == '%' -> {
                    while (i < n && content[i] != '\n' && content[i] != '\r') i++
                }
                c == '(' -> {
                    val (text, next) = readLiteralString(content, i)
                    i = next
                    if (inArray) arrayStrings.add(text) else pendingStrings.add(text)
                }
                c == '<' && i + 1 < n && content[i + 1] != '<' -> {
                    val (text, next) = readHexString(content, i)
                    i = next
                    if (inArray) arrayStrings.add(text) else pendingStrings.add(text)
                }
                c == '<' -> i += 2 // "<<": awal dictionary
                c == '>' -> i += if (i + 1 < n && content[i + 1] == '>') 2 else 1
                c == '[' -> {
                    inArray = true
                    arrayStrings.clear()
                    i++
                }
                c == ']' -> {
                    inArray = false
                    i++
                }
                c.isLetter() -> {
                    val start = i
                    while (i < n && content[i].isLetter()) i++
                    when (content.substring(start, i)) {
                        "Tj" -> {
                            val text = pendingStrings.joinToString("")
                            if (text.isNotEmpty()) {
                                out.append(text).append(' ')
                            }
                            pendingStrings.clear()
                        }
                        "TJ" -> {
                            val text = arrayStrings.joinToString("")
                            if (text.isNotEmpty()) {
                                out.append(text).append(' ')
                            }
                            arrayStrings.clear()
                            pendingStrings.clear()
                        }
                        "Td", "TD", "T*" -> {
                            out.append('\n')
                            pendingStrings.clear()
                            arrayStrings.clear()
                        }
                        else -> {
                            pendingStrings.clear()
                            if (!inArray) arrayStrings.clear()
                        }
                    }
                }
                else -> i++
            }
        }
        return out.toString().replace(Regex("[ \\t]+"), " ").replace(Regex(" *\\n *"), "\n").trim()
    }

    /** Baca string literal PDF `(…)` dari [start]; kembalikan (teks, indeks setelah ')'). */
    private fun readLiteralString(s: String, start: Int): Pair<String, Int> {
        val bytes = mutableListOf<Byte>()
        var i = start + 1
        var depth = 1
        while (i < s.length && depth > 0) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length -> {
                    val e = s[i + 1]
                    when (e) {
                        'n' -> {
                            bytes.add('\n'.code.toByte())
                            i += 2
                        }
                        'r' -> {
                            bytes.add('\r'.code.toByte())
                            i += 2
                        }
                        't' -> {
                            bytes.add('\t'.code.toByte())
                            i += 2
                        }
                        'b' -> {
                            bytes.add('\b'.code.toByte())
                            i += 2
                        }
                        'f' -> {
                            bytes.add(0x0C.toByte())
                            i += 2
                        }
                        '(', ')', '\\' -> {
                            bytes.add(e.code.toByte())
                            i += 2
                        }
                        '\n' -> i += 2 // line continuation
                        '\r' -> {
                            i += 2
                            if (i < s.length && s[i] == '\n') i++
                        }
                        in '0'..'7' -> {
                            var j = i + 1
                            var value = 0
                            var count = 0
                            while (j < s.length && count < 3 && s[j] in '0'..'7') {
                                value = value * 8 + (s[j] - '0')
                                j++
                                count++
                            }
                            bytes.add(value.toByte())
                            i = j
                        }
                        else -> {
                            bytes.add(e.code.toByte())
                            i += 2
                        }
                    }
                }
                c == '(' -> {
                    depth++
                    bytes.add('('.code.toByte())
                    i++
                }
                c == ')' -> {
                    depth--
                    if (depth > 0) bytes.add(')'.code.toByte())
                    i++
                }
                else -> {
                    bytes.add(c.code.toByte())
                    i++
                }
            }
        }
        return decodePdfStringBytes(bytes.toByteArray()) to i
    }

    /** Baca string hex PDF `<…>` dari [start]; kembalikan (teks, indeks setelah '>'). */
    private fun readHexString(s: String, start: Int): Pair<String, Int> {
        val digits = StringBuilder()
        var i = start + 1
        while (i < s.length && s[i] != '>') {
            if (s[i].isLetterOrDigit()) digits.append(s[i])
            i++
        }
        if (i < s.length) i++ // lewati '>'
        if (digits.length % 2 == 1) digits.append('0')
        val bytes = ByteArray(digits.length / 2) { k ->
            digits.substring(k * 2, k * 2 + 2).toIntOrNull(16)?.toByte() ?: 0
        }
        return decodePdfStringBytes(bytes) to i
    }

    /** String PDF: BOM FE FF -> UTF-16BE, selain itu Latin-1. */
    private fun decodePdfStringBytes(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        return String(bytes, Charsets.ISO_8859_1)
    }
}

/**
 * Delegasi top-level ke [FileExtractor.extractFile] — pemanggil boleh memakai
 * `extractFile(context, uri)` langsung atau `FileExtractor.extractFile(...)`.
 * Implementasi kanonis ada di [FileExtractor].
 */
suspend fun extractFile(
    context: Context,
    uri: Uri,
    localFilesEnabled: Boolean = true,
): ExtractedFile = FileExtractor.extractFile(context, uri, localFilesEnabled)
