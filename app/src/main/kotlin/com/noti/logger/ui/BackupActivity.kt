package com.noti.logger.ui

import android.net.Uri
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.noti.logger.R
import com.noti.logger.backup.Backup
import com.noti.sender.util.Haptics
import com.noti.shared.BackupCrypto
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Encrypted backup & restore. The backup is a single passphrase-encrypted file written through the
 * system file picker, so it can land in Drive, OneDrive, local storage - any provider. Restore reads
 * one back and replaces settings + message history. Lose the passphrase and the file is unrecoverable.
 */
class BackupActivity : ScreenActivity() {

    private companion object {
        // The backup bundles the relay key, service-account key, webhook token, and full SMS
        // history - a short passphrase is the only thing standing between a stolen file and all
        // of it. 12 chars pushes an offline brute-force well past what's practical even against a
        // fast KDF.
        const val MIN_NEW_PASSPHRASE_LEN = com.noti.shared.PassphrasePolicy.MIN_LENGTH
    }

    override val layoutRes = R.layout.activity_backup
    override val titleRes = R.string.menu_backup_title
    override val screenKind = com.noti.sender.ui.ScreenGuard.Kind.KEYS

    /** Holds the passphrase across the file picker. A ViewModel, not a field: the picker is a
     *  separate screen, and a rotation while it was open recreated this one and lost the field -
     *  the picker had already created the file, so the backup came out as 0 bytes, silently. Not
     *  saved instance state either: that can be written out by the system, and this is a secret. */
    class PassHolder : androidx.lifecycle.ViewModel() {
        var pending: String? = null
        override fun onCleared() { pending = null }
    }

    private val holder by lazy { androidx.lifecycle.ViewModelProvider(this)[PassHolder::class.java] }
    private var pendingPassphrase: String?
        get() = holder.pending
        set(value) { holder.pending = value }

    private val createDoc = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> uri?.let { writeBackup(it) } }

    private val openDoc = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { readBackup(it) } }

    override fun onScreenCreated() {
        findViewById<MaterialButton>(R.id.btn_backup).setOnClickListener {
            askPassphrase(confirm = true) { pass ->
                pendingPassphrase = pass
                val name = "noti-backup-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.enc"
                createDoc.launch(name)
            }
        }
        findViewById<MaterialButton>(R.id.btn_restore).setOnClickListener {
            openDoc.launch(arrayOf("*/*"))
        }
    }

    private fun writeBackup(uri: Uri) {
        val pass = pendingPassphrase
        if (pass == null) {
            // The app was closed while the picker was open, taking the passphrase with it. Don't
            // leave the empty file the picker made behind as if it were a backup.
            try { android.provider.DocumentsContract.deleteDocument(contentResolver, uri) } catch (e: Exception) { }
            val msg = getString(R.string.backup_interrupted)
            setStatus(msg); toast(msg); feedback(false)
            return
        }
        pendingPassphrase = null
        setStatus(getString(R.string.backup_working))
        lifecycleScope.launch(Dispatchers.IO) {
            var ok = false
            val result = try {
                // Written straight to bytes and encrypted from that buffer, without the copies a
                // String in between took (see Backup.exportTo); then the plaintext is wiped.
                val buf = PlainBuffer()
                val blob = try {
                    Backup.exportTo(applicationContext, buf)
                    BackupCrypto.encrypt(buf.bytes(), 0, buf.size(), pass)
                } finally {
                    buf.wipe()
                }
                contentResolver.openOutputStream(uri)?.use { it.write(blob) }
                    ?: throw IllegalStateException("could not open file")
                ok = true
                getString(R.string.backup_done)
            } catch (e: Exception) {
                getString(R.string.backup_failed, e.message ?: "error")
            }
            withContext(Dispatchers.Main) { setStatus(result); toast(result); feedback(ok) }
        }
    }

    private fun readBackup(uri: Uri) {
        lifecycleScope.launch {
            // Off the main thread: the file may sit on a slow provider (cloud storage, USB).
            val blob = withContext(Dispatchers.IO) {
                try { contentResolver.openInputStream(uri)?.use { it.readBytes() } } catch (e: Exception) { null }
            }
            if (blob == null) {
                toast(getString(R.string.restore_read_failed)); return@launch
            }
            askPassphrase(confirm = false) { pass -> decryptThenConfirm(blob, pass) }
        }
    }

    private fun decryptThenConfirm(blob: ByteArray, pass: String) {
        setStatus(getString(R.string.restore_working))
        lifecycleScope.launch(Dispatchers.IO) {
            val plain = try {
                BackupCrypto.decrypt(blob, pass)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { setStatus(""); toast(getString(R.string.restore_bad_pass)); feedback(false) }
                return@launch
            }
            // Say where uploads will go: a restore sets the webhook (and the service-account key)
            // with no further prompt, so a backup from someone else would quietly redirect them.
            val host = Backup.webhookHostOf(plain)
            withContext(Dispatchers.Main) {
                setStatus("")
                val msg = getString(R.string.restore_confirm_msg) +
                    (host?.let { "\n\n" + getString(R.string.restore_confirm_webhook, it) } ?: "")
                MaterialAlertDialogBuilder(this@BackupActivity)
                    .setTitle(R.string.restore_confirm_title)
                    .setMessage(msg)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.restore_confirm_yes) { _, _ -> applyRestore(plain) }
                    .show()
            }
        }
    }

    private fun applyRestore(jsonText: ByteArray) {
        setStatus(getString(R.string.restore_working))
        lifecycleScope.launch(Dispatchers.IO) {
            var ok = false
            val result = try {
                try { Backup.import(applicationContext, jsonText) } finally { java.util.Arrays.fill(jsonText, 0.toByte()) }
                ok = true
                getString(R.string.restore_done)
            } catch (e: Exception) {
                getString(R.string.restore_failed, e.message ?: "error")
            }
            withContext(Dispatchers.Main) { setStatus(result); toast(result); feedback(ok) }
        }
    }

    private fun feedback(ok: Boolean) {
        val v = findViewById<View>(R.id.screen_root)
        if (ok) Haptics.confirm(v) else Haptics.reject(v)
    }

    /** Password dialog. When [confirm], a second field must match before [onOk] fires. */
    private fun askPassphrase(confirm: Boolean, onOk: (String) -> Unit) {
        val view = layoutInflater.inflate(R.layout.dialog_passphrase, null)
        val passField = view.findViewById<TextInputEditText>(R.id.et_pass)
        val confirmLayout = view.findViewById<TextInputLayout>(R.id.til_pass_confirm)
        val confirmField = view.findViewById<TextInputEditText>(R.id.et_pass_confirm)
        confirmLayout.visibility = if (confirm) View.VISIBLE else View.GONE

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (confirm) R.string.backup_pass_title else R.string.restore_pass_title)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null) // set below so validation can keep it open
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val pass = passField.text?.toString().orEmpty()
                when {
                    // Only enforced when setting a NEW passphrase (confirm == true) - restoring
                    // must still accept whatever (possibly shorter) passphrase an older backup was
                    // actually created with, or existing backups would become unrestorable.
                    confirm && com.noti.shared.PassphrasePolicy.problem(pass) != null ->
                        passField.error = getString(
                            when (com.noti.shared.PassphrasePolicy.problem(pass)!!) {
                                com.noti.shared.PassphrasePolicy.Problem.TOO_SHORT -> R.string.backup_pass_short
                                com.noti.shared.PassphrasePolicy.Problem.TOO_FEW_DISTINCT -> R.string.backup_pass_variety
                                com.noti.shared.PassphrasePolicy.Problem.REPEATS -> R.string.backup_pass_repeats
                                com.noti.shared.PassphrasePolicy.Problem.SEQUENCE -> R.string.backup_pass_sequence
                            }
                        )
                    confirm && pass != confirmField.text?.toString() ->
                        confirmField.error = getString(R.string.backup_pass_mismatch)
                    else -> { dialog.dismiss(); onOk(pass) }
                }
            }
        }
        dialog.show()
    }

    private fun setStatus(text: String) {
        findViewById<TextView>(R.id.txt_backup_status).text = text
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    /**
     * A byte buffer whose contents can be read without a copy and wiped after. It grows itself,
     * zeroing each array it outgrows: ByteArrayOutputStream's own growth leaves every earlier
     * array, plaintext and all, behind on the heap.
     */
    private class PlainBuffer : java.io.OutputStream() {
        private var buf = ByteArray(1 shl 16)
        private var count = 0

        override fun write(b: Int) {
            ensure(count + 1)
            buf[count++] = b.toByte()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            ensure(count + len)
            System.arraycopy(b, off, buf, count, len)
            count += len
        }

        private fun ensure(needed: Int) {
            if (needed <= buf.size) return
            var size = buf.size
            while (size < needed) size = if (size > Int.MAX_VALUE / 2) Int.MAX_VALUE else size * 2
            val grown = buf.copyOf(size)
            java.util.Arrays.fill(buf, 0.toByte())
            buf = grown
        }

        fun bytes(): ByteArray = buf
        fun size(): Int = count
        fun wipe() { java.util.Arrays.fill(buf, 0.toByte()); count = 0 }
    }
}
