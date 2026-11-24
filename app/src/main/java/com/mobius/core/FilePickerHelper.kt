package com.mobius.core

import android.content.Intent

/**
 * v6 workstream N-core — helper system picker untuk file lokal.
 *
 * Dipakai UI agent untuk membuka `ACTION_OPEN_DOCUMENT` (Storage Access
 * Framework). Pengguna memilih SATU file secara eksplisit; aplikasi tidak
 * pernah memindai storage sendiri — [FileExtractor] hanya menerima Uri
 * hasil picker ini.
 *
 * Daftar MIME mencakup format teks yang bisa diekstrak [FileExtractor]
 * (txt/md/pdf/docx/xlsx) PLUS MIME image/ (semua tipe gambar): gambar BUKAN untuk diekstrak
 * teksnya (extractor menolak gambar dengan "Format tidak didukung"),
 * melainkan untuk workstream foto — foto diteruskan ke sensitive-scan
 * agent, bukan ke extractor teks.
 *
 * Keamanan: konten file adalah DATA YANG TIDAK DIPERCAYA (risiko prompt
 * injection) — lihat KDoc [FileExtractor]. Helper ini hanya membangun
 * intent; ia tidak membaca isi file apa pun.
 */
object FilePickerHelper {

    /** MIME yang ditawarkan di picker. Dipakai bersama type wildcard di intent + daftar ini (lihat [createOpenDocumentIntent]). */
    val SUPPORTED_MIME_TYPES: Array<String> = arrayOf(
        "text/plain",
        "text/markdown",
        "application/pdf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "image/*",
    )

    /** Ekstensi yang teksnya bisa diekstrak [FileExtractor]. */
    val TEXT_EXTENSIONS: Set<String> = setOf("txt", "md", "pdf", "docx", "xlsx")

    /** Ekstensi gambar — didukung picker (untuk workstream foto), BUKAN extractor teks. */
    val IMAGE_EXTENSIONS: Set<String> = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp")

    /**
     * Intent `ACTION_OPEN_DOCUMENT` untuk memilih satu file.
     * `CATEGORY_OPENABLE` memastikan Uri yang kembali bisa dibuka lewat
     * `ContentResolver.openInputStream`. `EXTRA_ALLOW_MULTIPLE` = false:
     * satu file per tugas.
     */
    fun createOpenDocumentIntent(allowMultiple: Boolean = false): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, SUPPORTED_MIME_TYPES)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
        }

    /** True bila MIME ini ada di daftar picker (termasuk MIME image/ untuk semua gambar). */
    fun isSupportedMimeType(mimeType: String?): Boolean {
        if (mimeType == null) return false
        if (mimeType.startsWith("image/")) return true
        return mimeType in SUPPORTED_MIME_TYPES
    }

    /** True bila ekstensi file ini ada di daftar picker (teks + gambar). */
    fun isSupportedExtension(fileName: String?): Boolean {
        if (fileName == null) return false
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in TEXT_EXTENSIONS || ext in IMAGE_EXTENSIONS
    }
}
