package com.noti.logger.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.noti.logger.config.Settings
import com.noti.shared.Diag
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Where the database key lives: its own file (`no_backup/db.key`), AES-GCM-wrapped by a dedicated
 * Android Keystore key. Deliberately *not* only in the settings store: that store resets itself
 * when it can't be read (see Settings.openPrefs), and in v1.6, where the key lived only there, a
 * settings hiccup orphaned the whole database. Nothing here ever deletes the file; one that can't
 * be unwrapped is moved aside, not removed.
 *
 * A second copy stays in the settings store, so either one surviving is enough.
 */
object DbKeyStore {

    private const val FILE = "db.key"
    private const val ALIAS = "noti_db_key_wrap_v1"
    private const val FORMAT = 1
    private const val IV_LEN = 12
    private const val ASIDE = ".unreadable-"

    @Volatile private var cached: ByteArray? = null

    @Synchronized
    fun key(context: Context): ByteArray {
        cached?.let { return it.copyOf() }
        val file = File(context.noBackupFilesDir, FILE)
        val settings = Settings.get(context)
        val fromSettings = settings.dbKey.takeIf { it.isNotBlank() }
            ?.let { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }

        val fromFile = if (file.exists()) {
            try { unwrap(file.readBytes()) } catch (e: Exception) {
                Diag.log("database key: file couldn't be unwrapped (${e.javaClass.simpleName})")
                null
            }
        } else null

        val key = fromFile ?: fromSettings ?: ByteArray(32).also { SecureRandom().nextBytes(it) }
        // Make both copies whole again: the file from settings (v1.6 installs, or a lost file), and
        // settings from the file (after a settings reset).
        if (fromFile == null) {
            // Moved aside, not replaced: a keystore that failed this once may read it next time,
            // and [asideKeys] keeps offering it to the database set aside with it.
            if (file.exists()) {
                val aside = File(file.path + ASIDE + System.currentTimeMillis())
                // Aged from now, not from when the key was made: a rename keeps the old file time,
                // and [asideKeys] would have deleted a months-old key before ever trying it.
                if (file.renameTo(aside)) aside.setLastModified(System.currentTimeMillis())
            }
            writeAtomically(file, wrap(key))
        }
        // A settings copy that disagrees is kept too: it may be the key of a set-aside database.
        if (fromSettings != null && !fromSettings.contentEquals(key)) {
            try { writeAtomically(File(file.path + ASIDE + System.currentTimeMillis() + "-settings"), wrap(fromSettings)) } catch (ignored: Exception) { /* best effort: the spare of a spare */ }
        }
        if (fromSettings == null || !fromSettings.contentEquals(key)) {
            try {
                settings.dbKeySync = android.util.Base64.encodeToString(key, android.util.Base64.NO_WRAP)
            } catch (e: Exception) {
                // The file copy is the one that matters; settings is the spare.
            }
        }
        cached = key
        return key.copyOf()
    }

    /**
     * Earlier keys still on file: key files that couldn't be read when they were set aside, and
     * settings copies that disagreed. Each is tried again now - the keystore may answer today - and
     * the ones that read are offered to databases set aside as unreadable (DbEncryption). Files
     * older than [keepMs] are deleted, readable or not, along with the databases they'd open.
     */
    fun asideKeys(context: Context, keepMs: Long, now: Long = System.currentTimeMillis()): List<ByteArray> {
        val dir = context.noBackupFilesDir
        val files = dir.listFiles { f -> f.name.startsWith(FILE + ASIDE) && !f.name.endsWith(".tmp") } ?: return emptyList()
        val out = ArrayList<ByteArray>()
        for (f in files) {
            if (now - asideSince(f) > keepMs) { f.delete(); continue }
            try { out.add(unwrap(f.readBytes())) } catch (ignored: Exception) { /* still unreadable: next start */ }
        }
        return out
    }

    /**
     * When [f] was set aside: the time in its name ("db.key.unreadable-<ms>", written by every
     * version that sets one aside), else its file time. Not the file time first - a rename keeps
     * the key's original time, and v1.7 didn't reset it, so its set-aside keys looked months old.
     */
    internal fun asideSince(f: File): Long =
        f.name.substringAfter(ASIDE, "").takeWhile { it.isDigit() }.toLongOrNull() ?: f.lastModified()

    /** Test hook: forget the in-memory copy. */
    internal fun clearCache() { cached = null }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun wrap(key: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, keystoreKey())
        return byteArrayOf(FORMAT.toByte()) + c.iv + c.doFinal(key)
    }

    private fun unwrap(blob: ByteArray): ByteArray {
        require(blob.size > 1 + IV_LEN && blob[0].toInt() == FORMAT) { "unknown key file format" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, blob, 1, IV_LEN))
        return c.doFinal(blob, 1 + IV_LEN, blob.size - 1 - IV_LEN)
    }

    /** Write to a temp file, fsync, rename: a crash leaves the old file or the new one, never half. */
    private fun writeAtomically(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        java.io.FileOutputStream(tmp).use { out -> out.write(bytes); out.fd.sync() }
        check(tmp.renameTo(file)) { "couldn't store the database key" }
    }
}
