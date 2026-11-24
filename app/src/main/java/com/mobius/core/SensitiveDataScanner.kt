package com.mobius.core

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
// Kelas options Latin (DEFAULT_OPTIONS) TIDAK ada di com.google.mlkit.vision.text —
// ia ada di com.google.mlkit.vision.text.latin, di artifact transitif
// play-services-mlkit-text-recognition (terverifikasi via javap pada AAR asli,
// bukan dari docs). TextRecognition.getClient(...) menerima
// TextRecognizerOptionsInterface, yang diimplementasikan kelas Latin ini.
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * SensitiveDataScanner (v6, workstream O-scan) — pemindai data sensitif LOKAL dan
 * DETERMINISTIK, dijalankan SEBELUM file/foto yang dilampirkan dipakai agent atau
 * dikirim ke mana pun (terutama ke cloud).
 *
 * KEPUTUSAN DESAIN (dicatat agar Tuan bisa mengoreksi — lihat BUILD-LOG-v6-sensitive.md):
 * pemindaian utama adalah pola deterministik (regex/kata kunci) di atas teks hasil
 * ekstraksi, BUKAN AI. Alasannya: (1) Nano adalah model TEXT-only — ia tidak bisa
 * melihat gambar sama sekali, jadi tidak bisa memindai foto; (2) Nano TIDAK TERSEDIA
 * di HP Tuan (Xiaomi, tanpa AICore), jadi tidak bisa jalan di sana; (3) privasi:
 * memeriksa data sensitif tidak boleh mengharuskan data itu dikirim ke cloud.
 * Nano (bila tersedia di perangkat yang memilikinya) hanya boleh menjadi classifier
 * opini-kedua OPSIONAL atas teks hasil ekstraksi, di balik flag yang sama, dan hanya
 * bila pengguna sudah mengizinkan cloud — TIDAK PERNAH menjadi gate utama.
 * Opini-kedua Nano itu TIDAK diimplementasikan di build ini.
 *
 * PRIVASI — finding TIDAK PERNAH berisi nilai yang cocok (bahkan sebagian):
 * [SensitiveFinding] hanya membawa label tipe berbahasa Indonesia. Kode ini juga
 * tidak pernah me-log teks yang dipindai atau nilai yang cocok.
 *
 * KONTRAK ALERT (UI agent yang memasang; scanner ini hanya mendefinisikan kontrak):
 * scan jalan saat attach -> bila findings tidak kosong, UI menampilkan dialog alert
 * yang menyebut HANYA tipe yang ditemukan, dengan tombol "Tetap lanjut" / "Batal" ->
 * file TIDAK diteruskan sampai pengguna menekan "Tetap lanjut".
 *
 * FLAG — pemanggil WAJIB memeriksa [FeatureFlag.SENSITIVE_ALERT] sebelum memanggil
 * scan (mis. `app.featureFlags.isEnabled(FeatureFlag.SENSITIVE_ALERT).first()`).
 * Bila flag OFF, scan dilewati (perilaku v5: tanpa scan, tanpa alert). Scanner ini
 * sendiri murni (tanpa Context/DataStore) supaya mudah dites; gating flag adalah
 * tanggung jawab pemanggil, bukan scanner.
 *
 * AMBIGUITAS YANG DIKETAHUI (heuristik, bukan kepastian):
 * - Angka 16 digit berurutan dilabeli "nomor KTP" — angka 16 digit yang sama
 *   bentuknya juga bisa berupa nomor kartu/rekening. Label yang dipilih mengikuti
 *   spek (KTP = 16 digit); pengguna yang menekan "Tetap lanjut" memutuskan.
 * - Angka 10-15 digit berurutan yang bukan nomor HP dilabeli "nomor rekening" —
 *   nomor urut/order ID sepanjang itu ikut kena. Ini sengaja fail-safe (alert lalu
 *   pengguna memutuskan), bukan bukti bahwa angka itu memang rekening.
 * - [scanImage] hanya memindai teks yang berhasil dibaca OCR; foto yang teksnya
 *   tidak terbaca (buram, tulisan tangan, sudut ekstrem) TIDAK menghasilkan finding
 *   — tidak adanya finding dari foto TIDAK berarti foto itu bersih. UI tidak boleh
 *   mengklaim isi gambar sudah dipindai bila OCR gagal / tidak tersedia.
 */
data class SensitiveFinding(val type: String)

object SensitiveDataScanner {

    const val TYPE_KTP = "nomor KTP"
    const val TYPE_HP = "nomor HP"
    const val TYPE_REKENING = "nomor rekening"
    const val TYPE_KATA_SANDI = "kata sandi"
    const val TYPE_EMAIL = "email"

    /** Urutan keluaran yang stabil — satu finding per tipe, tanpa duplikat. */
    private val TYPE_ORDER = listOf(TYPE_KTP, TYPE_HP, TYPE_REKENING, TYPE_EMAIL, TYPE_KATA_SANDI)

    /** Angka berurutan 8-16 digit (tanpa pemisah), bukan bagian dari angka yang lebih panjang. */
    private val CONTIGUOUS_DIGITS = Regex("""(?<!\d)\d{8,16}(?!\d)""")

    /**
     * Angka berkelompok, tiap kelompok 3-4 digit, 3-4 kelompok, dipisah spasi/dash
     * ("0812 3456 7890", "1234-5678-9012"). Kelompok 2 digit sengaja TIDAK cocok
     * supaya tanggal-waktu ("2026 11 24 11 54") tidak ikut terpindai.
     */
    private val GROUPED_DIGITS = Regex("""\b\d{3,4}(?:[ -]\d{3,4}){2,3}\b""")

    private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")

    private val PASSWORD_KEYWORDS = listOf("password", "passphrase", "kata sandi", "kata-sandi", "kata_sandi")

    /**
     * Pindai teks hasil ekstraksi + nama file. Satu finding per tipe yang ditemukan.
     * List kosong = tidak ada yang ditemukan.
     */
    fun scan(text: String, fileName: String): List<SensitiveFinding> {
        val found = LinkedHashSet<String>()
        scanTextInto(text, found)
        scanFileNameInto(fileName, found)
        return TYPE_ORDER.filter { it in found }.map { SensitiveFinding(it) }
    }

    /** Pindai pola nama file / metadata saja (tanpa isi file). */
    fun scanFileName(fileName: String): List<SensitiveFinding> {
        val found = LinkedHashSet<String>()
        scanFileNameInto(fileName, found)
        return TYPE_ORDER.filter { it in found }.map { SensitiveFinding(it) }
    }

    /**
     * Pindai foto lewat OCR on-device (ML Kit text recognition, model ter-bundle di
     * aplikasi — tidak butuh jaringan, tidak butuh Play Services, tidak mengirim
     * gambar ke mana pun). Teks hasil OCR lalu dipindai dengan [scan] yang sama.
     * Bila OCR gagal (apa pun penyebabnya) mengembalikan list kosong — TIDAK PERNAH
     * throw, dan TIDAK PERNAH mengklaim foto bersih: kegagalan OCR berarti "tidak
     * tahu", bukan "aman". UI menutup celah ini dengan scan nama file/metadata +
     * konfirmasi manual pengguna.
     */
    suspend fun scanImage(bitmap: Bitmap): List<SensitiveFinding> {
        return try {
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            try {
                val image = InputImage.fromBitmap(bitmap, 0)
                val ocrText = suspendCancellableCoroutine<String> { cont ->
                    recognizer.process(image)
                        .addOnSuccessListener { visionText ->
                            if (cont.isActive) cont.resume(visionText.text)
                        }
                        .addOnFailureListener { e ->
                            if (cont.isActive) cont.resumeWithException(e)
                        }
                }
                scan(ocrText, "")
            } finally {
                recognizer.close()
            }
        } catch (_: Exception) {
            // OCR tidak tersedia / gagal: kembalikan kosong, jangan crash, jangan klaim bersih.
            emptyList()
        }
    }

    // ------------------------------------------------------------------
    // Internals — klasifikasi angka dan kata kunci. Tidak ada nilai yang
    // cocok yang pernah keluar dari fungsi-fungsi ini (hanya label tipe).
    // ------------------------------------------------------------------

    private fun scanTextInto(text: String, found: MutableSet<String>) {
        if (text.isBlank()) return
        for (candidate in CONTIGUOUS_DIGITS.findAll(text)) {
            classifyDigits(candidate.value.filter { it.isDigit() }, found)
        }
        for (candidate in GROUPED_DIGITS.findAll(text)) {
            classifyDigits(candidate.value.filter { it.isDigit() }, found)
        }
        if (EMAIL.containsMatchIn(text)) found += TYPE_EMAIL
        val lower = text.lowercase()
        if (PASSWORD_KEYWORDS.any { it in lower }) found += TYPE_KATA_SANDI
    }

    private fun scanFileNameInto(fileName: String, found: MutableSet<String>) {
        if (fileName.isBlank()) return
        val lower = fileName.lowercase()
        if ("ktp" in lower) found += TYPE_KTP
        if ("rekening" in lower) found += TYPE_REKENING
        if ("email" in lower) found += TYPE_EMAIL
        if (PASSWORD_KEYWORDS.any { it in lower }) found += TYPE_KATA_SANDI
        if (listOf("no_hp", "no-hp", "nomor hp", "nomor-hp", "nomor_hp", "nohp").any { it in lower }) {
            found += TYPE_HP
        }
        // Nama file juga bisa mengandung angka (mis. "081234567890.jpg"):
        // klasifikasikan angka di nama file dengan aturan yang sama seperti isi teks.
        // Tanggal di nama file ("IMG_20261124_...") berdigit 8 ke bawah untuk tiap
        // kelompoknya sehingga tidak masuk klasifikasi (lihat klasifikasi di bawah).
        scanTextInto(fileName, found)
    }

    /**
     * Klasifikasi satu kandidat angka (hanya digit, pemisah sudah dibuang):
     * - 16 digit -> nomor KTP (lihat catatan ambiguitas di header file).
     * - Nomor HP Indonesia: awalan 08 (total 10-13 digit) atau +62/62 + 8...
     *   (total 11-13 digit).
     * - 10-15 digit lainnya -> nomor rekening (heuristik fail-safe).
     * - Di luar itu (8-9 digit) -> diabaikan.
     */
    private fun classifyDigits(digits: String, found: MutableSet<String>) {
        when {
            digits.length == 16 -> found += TYPE_KTP
            isIndonesianPhone(digits) -> found += TYPE_HP
            digits.length in 10..15 -> found += TYPE_REKENING
            else -> Unit // 8-9 digit: terlalu pendek untuk semua tipe di atas, abaikan.
        }
    }

    private fun isIndonesianPhone(digits: String): Boolean {
        return when {
            digits.startsWith("08") && digits.length in 10..13 -> true
            digits.startsWith("628") && digits.length in 11..13 -> true
            else -> false
        }
    }
}
