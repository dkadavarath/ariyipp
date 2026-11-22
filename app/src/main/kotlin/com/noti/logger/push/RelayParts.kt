package com.noti.logger.push

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * The parts of long relayed SMS that are still waiting for the rest, kept on disk so a process
 * death between two parts doesn't lose the message. Each part is stored as the encrypted payload
 * it arrived in; it's only decrypted once the whole message is in (see PushMessageHandler).
 *
 * One directory per message (named by a hash of its identity, which is never written out), one file
 * per part, and a small file with when the first part came. Not thread-safe on its own: the caller
 * serialises access.
 */
internal class RelayParts(context: Context) {

    private val root = File(context.noBackupFilesDir, "relay_parts")

    /** Stores part [index] of [group]; returns every part of it stored so far, by index. */
    fun put(group: String, index: Int, ciphertext: String, now: Long): Map<Int, String> {
        val dir = dirFor(group)
        if (!dir.isDirectory) {
            dir.mkdirs()
            File(dir, AT).writeText(now.toString())
        }
        write(File(dir, index.toString()), ciphertext)
        return read(dir)
    }

    fun remove(group: String) { dirFor(group).deleteRecursively() }

    /** Drops messages whose first part came more than [ttlMs] ago (a part was lost), and the
     *  oldest beyond [maxGroups]. */
    fun evict(now: Long, ttlMs: Long, maxGroups: Int) {
        val groups = root.listFiles()?.filter { it.isDirectory } ?: return
        val byAge = groups.map { it to startedAt(it) }.sortedBy { it.second }
        var left = byAge.size
        for ((dir, at) in byAge) {
            if (now - at > ttlMs || at > now + ttlMs || left >= maxGroups) {
                dir.deleteRecursively()
                left--
            }
        }
    }

    private fun startedAt(dir: File): Long =
        try { File(dir, AT).readText().trim().toLong() } catch (e: Exception) { 0L }

    private fun read(dir: File): Map<Int, String> =
        dir.listFiles()?.mapNotNull { f -> f.name.toIntOrNull()?.let { it to f.readText() } }?.toMap() ?: emptyMap()

    /** Temp file then rename: a crash mid-write leaves no half part to decrypt later. */
    private fun write(file: File, text: String) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    private fun dirFor(group: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(group.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)
        return File(root, hash)
    }

    private companion object { const val AT = "at" }
}
