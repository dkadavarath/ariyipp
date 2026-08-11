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
import com.noti.shared.BackupCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Encrypted backup & restore. The backup is a single passphrase-encrypted file written through the
 * system file picker, so it can land in Drive, OneDrive, local storage — any provider. Restore reads
 * one back and replaces settings + message history. Lose the passphrase and the file is unrecoverable.
 */
class BackupActivity : ScreenActivity() {

    override val layoutRes = R.layout.activity_backup
    override val titleRes = R.string.menu_backup_title

    private var pendingPassphrase: String? = null

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
        val pass = pendingPassphrase ?: return
        pendingPassphrase = null
        setStatus(getString(R.string.backup_working))
        lifecycleScope.launch(Dispatchers.IO) {
            val result = try {
                val jsonText = Backup.export(applicationContext)
                val blob = BackupCrypto.encrypt(jsonText.toByteArray(Charsets.UTF_8), pass)
                contentResolver.openOutputStream(uri)?.use { it.write(blob) }
                    ?: throw IllegalStateException("could not open file")
                getString(R.string.backup_done)
            } catch (e: Exception) {
                getString(R.string.backup_failed, e.message ?: "error")
            }
            withContext(Dispatchers.Main) { setStatus(result); toast(result) }
        }
    }

    private fun readBackup(uri: Uri) {
        val blob = try {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
        if (blob == null) {
            toast(getString(R.string.restore_read_failed)); return
        }
        askPassphrase(confirm = false) { pass -> decryptThenConfirm(blob, pass) }
    }

    private fun decryptThenConfirm(blob: ByteArray, pass: String) {
        setStatus(getString(R.string.restore_working))
        lifecycleScope.launch(Dispatchers.IO) {
            val plain = try {
                String(BackupCrypto.decrypt(blob, pass), Charsets.UTF_8)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { setStatus(""); toast(getString(R.string.restore_bad_pass)) }
                return@launch
            }
            withContext(Dispatchers.Main) {
                setStatus("")
                MaterialAlertDialogBuilder(this@BackupActivity)
                    .setTitle(R.string.restore_confirm_title)
                    .setMessage(R.string.restore_confirm_msg)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.restore_confirm_yes) { _, _ -> applyRestore(plain) }
                    .show()
            }
        }
    }

    private fun applyRestore(jsonText: String) {
        setStatus(getString(R.string.restore_working))
        lifecycleScope.launch(Dispatchers.IO) {
            val result = try {
                Backup.import(applicationContext, jsonText)
                getString(R.string.restore_done)
            } catch (e: Exception) {
                getString(R.string.restore_failed, e.message ?: "error")
            }
            withContext(Dispatchers.Main) { setStatus(result); toast(result) }
        }
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
                    pass.length < 6 -> passField.error = getString(R.string.backup_pass_short)
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
}
