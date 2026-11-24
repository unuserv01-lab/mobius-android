package com.mobius.core

import android.content.Context
import java.io.File

/**
 * v6 CodeAgent — the ONE scoped directory for agent-written code.
 *
 * Directory (app-private storage, no other app can read it):
 *   <filesDir>/mobius-code
 * i.e. /data/data/com.mobius/files/mobius-code on the device.
 *
 * Rules, enforced here (canonical-path check) AND in [ActionGate.checkFileWrite]
 * (lexical check, no I/O):
 *   - Every path the file tools accept is RELATIVE to this directory.
 *   - Absolute paths, ".." traversal, NUL bytes and backslashes are rejected.
 *   - Symlinks escaping the directory are rejected: [resolve] compares canonical paths.
 *   - A path that resolves to the directory itself (".", "") is not a file and fails
 *     read/write as "not a file".
 *   - Writes are capped at [MAX_FILE_CHARS] characters (a script is text, not a blob).
 *
 * Nothing outside this directory is ever read or written by the code tools. There is
 * exactly one scoped directory — no per-task subdirectories, no user-chosen paths.
 */
class ScopedFileStore(context: Context) {

    /** Created on first use (mkdirs is a no-op when it already exists). */
    val baseDir: File = File(context.applicationContext.filesDir, SCOPED_DIR_NAME).apply { mkdirs() }

    /**
     * Resolves [relativePath] inside the scoped directory.
     * Returns null when the path is unsafe or escapes the directory (including via symlink).
     */
    fun resolve(relativePath: String): File? {
        if (!isRelativePathSafe(relativePath)) return null
        val base = try {
            baseDir.canonicalFile
        } catch (e: Exception) {
            return null
        }
        val target = try {
            File(base, relativePath).canonicalFile
        } catch (e: Exception) {
            return null
        }
        if (target == base) return null // the directory itself is not a file
        if (!target.path.startsWith(base.path + File.separator)) return null // escaped (.., symlink)
        return target
    }

    /** Reads a scoped text file, or null when it does not exist / is not a file / is unreadable. */
    fun read(relativePath: String): String? {
        val file = resolve(relativePath) ?: return null
        if (!file.isFile) return null
        return try {
            file.readText()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Writes [content] to a scoped text file (creates parent dirs inside the scope).
     * Returns false when the path is unsafe, the content exceeds [MAX_FILE_CHARS],
     * or the write fails.
     */
    fun write(relativePath: String, content: String): Boolean {
        if (content.length > MAX_FILE_CHARS) return false
        val file = resolve(relativePath) ?: return false
        return try {
            file.parentFile?.mkdirs()
            file.writeText(content)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** True when [relativePath] is an existing file inside the scope. */
    fun exists(relativePath: String): Boolean {
        val file = resolve(relativePath) ?: return false
        return file.isFile
    }

    companion object {
        /** The single scoped directory name under the app-private filesDir. */
        const val SCOPED_DIR_NAME = "mobius-code"

        /** Largest script/text file the file tools will write (characters). */
        const val MAX_FILE_CHARS = 512_000

        /**
         * Lexical safety check, no I/O (also used by [ActionGate.checkFileWrite] and
         * [CommandRunner.validate]): relative, no NUL, no backslash, no ".." segment,
         * no empty segment, not blank. Canonical/symlink containment is checked by
         * [resolve] at use time — this check alone is NOT sufficient.
         */
        fun isRelativePathSafe(path: String): Boolean {
            if (path.isBlank()) return false
            if (path.startsWith("/")) return false // absolute
            if ('\u0000' in path) return false
            if ('\\' in path) return false // no Windows-style separators
            val segments = path.split("/")
            if (segments.any { it == ".." || it.isEmpty() }) return false
            return true
        }
    }
}
