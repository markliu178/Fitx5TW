/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.im

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar as Toolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.RimeDataDirectoryState
import org.fcitx.fcitx5.android.ui.common.SettingsUiActivity
import org.fcitx.fcitx5.android.ui.common.OperationStatusView
import org.fcitx.fcitx5.android.ui.common.OperationStatusView.State
import java.io.File
import java.util.zip.ZipFile

/** Imports a profile ZIP under the app's internal rime/ directory. */
class RimeImportActivity : SettingsUiActivity() {
    private val connection by lazy { FcitxDaemon.connect(javaClass.name) }
    private lateinit var status: OperationStatusView
    private var statusTitle = ""
    private var statusDetails = ""
    private var statusState = State.IDLE
    private lateinit var choose: MaterialButton
    private var pendingArchivePath: String? = null
    private var pendingArchiveName: String? = null

    private val zipPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) copyArchiveAndProceed(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingArchivePath = savedInstanceState?.getString(STATE_PENDING_ARCHIVE)
            ?.takeIf(::isPendingArchive)
        pendingArchiveName = if (pendingArchivePath != null) {
            savedInstanceState?.getString(STATE_PENDING_ARCHIVE_NAME)
        } else null
        connection
        val padding = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toolbar = Toolbar(this).apply {
            id = R.id.toolbar
            setTitle(R.string.direct_rime_import)
            setNavigationIcon(R.drawable.ic_baseline_arrow_back_24)
            setNavigationContentDescription(androidx.appcompat.R.string.abc_action_bar_up_description)
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        content.addView(TextView(this).apply {
            setText(R.string.direct_rime_import_description)
            textSize = 16f
        })
        choose = MaterialButton(this).apply {
            setText(R.string.direct_rime_choose_zip)
            isEnabled = pendingArchivePath == null
            setOnClickListener {
                zipPicker.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed"))
            }
        }
        content.addView(choose)
        status = OperationStatusView(this).apply {
            show(getString(R.string.direct_rime_choose_zip))
        }
        content.addView(status, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = padding / 2
            bottomMargin = padding
        })
        savedInstanceState?.getString("ui_status_title")?.let { title ->
            statusTitle = title
            statusDetails = savedInstanceState.getString("ui_status_details").orEmpty()
            statusState = if (savedInstanceState.getBoolean("ui_status_error")) State.ERROR else State.IDLE
            status.show(statusTitle, statusDetails, statusState)
        }
        content.addView(TextInputLayout(this).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            hint = getString(R.string.direct_rime_test_input)
            addView(TextInputEditText(context).apply {
                id = R.id.direct_rime_test_editor
                minLines = 3
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            }, LinearLayout.LayoutParams(-1, -2))
        })
        root.addView(ScrollView(this).apply { addView(content) })
        setContentView(root)
        setSupportActionBar(toolbar)
    }

    private fun copyArchiveAndProceed(uri: Uri) {
        choose.isEnabled = false
        pendingArchiveName = suggestedProfileName(uri)
        showStatus(R.string.direct_rime_reading_archive, State.WORKING)
        lifecycleScope.launch {
            var archive: File? = null
            try {
                val folder = File(cacheDir, "rime-import").apply {
                    check(isDirectory || mkdirs()) { "无法创建临时目录" }
                }
                val copiedArchive = File.createTempFile("profile-", ".zip", folder)
                archive = copiedArchive
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { input ->
                        copiedArchive.outputStream().use { output ->
                            val buffer = ByteArray(32 * 1024)
                            var bytes = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                bytes += count
                                require(bytes <= MAX_ARCHIVE_BYTES) {
                                    getString(R.string.direct_rime_archive_too_large)
                                }
                                output.write(buffer, 0, count)
                            }
                        }
                    } ?: error("无法读取所选 ZIP")
                }
                showStatus(R.string.direct_rime_validating_archive, State.WORKING)
                withContext(Dispatchers.IO) { validateRimeArchive(copiedArchive) }
                pendingArchivePath = copiedArchive.absolutePath
                promptProfileName(pendingArchiveName ?: "rime-scheme") { name ->
                    if (name == null) cancelPendingImport()
                    else importArchive(copiedArchive, name)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                archive?.delete()
                throw e
            } catch (e: Exception) {
                archive?.delete()
                pendingArchivePath = null
                pendingArchiveName = null
                choose.isEnabled = true
                showStatus(R.string.ui_operation_failed, State.ERROR,
                    getString(R.string.direct_rime_import_failed, e.message.orEmpty()))
            }
        }
    }

    private fun validateRimeArchive(archive: File) {
        val hasRootSchema = runCatching {
            ZipFile(archive).use { zip ->
                val entries = zip.entries()
                var found = false
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val segments = entry.name.split('/').filter { it.isNotEmpty() && it != "." }
                    val safeRootPath = !entry.name.startsWith('/') && '\\' !in entry.name &&
                        '\u0000' !in entry.name && segments.none { it == ".." || ':' in it }
                    if (!entry.isDirectory && safeRootPath && segments.size == 1 &&
                        (segments.single() == "default.yaml" || segments.single() == "default.custom.yaml")) {
                        found = true
                        break
                    }
                }
                found
            }
        }.getOrDefault(false)
        require(hasRootSchema) { getString(R.string.direct_rime_archive_not_profile) }
    }

    private fun suggestedProfileName(uri: Uri): String {
        val raw = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull().orEmpty()
        val base = raw.substringBeforeLast('.', raw)
            .map { c -> if (c == '/' || c == '\\' || c.code < 32 || c.code == 127) '-' else c }
            .joinToString("")
            .trim()
            .trim('.')
        val builder = StringBuilder()
        for (c in base) {
            if ((builder.toString() + c).toByteArray(Charsets.UTF_8).size > 64) break
            builder.append(c)
        }
        return builder.toString().ifBlank { "rime-scheme" }
    }

    private fun promptProfileName(defaultName: String, onResult: (String?) -> Unit) {
        val input = TextInputEditText(this).apply {
            setText(defaultName)
            setSelection(text?.length ?: 0)
            isSingleLine = true
        }
        val density = resources.displayMetrics.density
        val box = TextInputLayout(this).apply {
            hint = getString(R.string.direct_rime_profile_name_label)
            setPadding((24 * density).toInt(), (8 * density).toInt(), (24 * density).toInt(), 0)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        var reported = false
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.direct_rime_profile_name_title)
            .setView(box)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                if (!reported) { reported = true; onResult(null) }
            }
            .setOnCancelListener {
                if (!reported) { reported = true; onResult(null) }
            }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text?.toString()?.trim().orEmpty()
                if (!RimeDataDirectoryState.isValidName(name)) {
                    input.error = getString(R.string.direct_rime_profile_name_invalid)
                } else {
                    reported = true
                    dialog.dismiss()
                    onResult(name)
                }
            }
        }
        dialog.show()
    }

    private fun importArchive(archive: File, name: String) {
        pendingArchivePath = null
        pendingArchiveName = null
        showStatus(R.string.direct_rime_importing, State.WORKING,
            getString(R.string.ui_import_destination, getString(R.string.direct_rime_app_directory)))
        lifecycleScope.launch {
            try {
                connection.runOnReady {
                    setAddonSubConfig(
                        "rime",
                        "import-profile",
                        RawConfig(arrayOf(
                            RawConfig("Archive", archive.absolutePath),
                            RawConfig("Name", name)
                        ))
                    )
                }
                showStatus(R.string.direct_rime_imported, State.SUCCESS,
                    getString(R.string.ui_import_destination, getString(R.string.direct_rime_app_directory)))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                showStatus(R.string.ui_operation_failed, State.ERROR,
                    getString(R.string.direct_rime_import_failed, e.message.orEmpty()))
            } finally {
                archive.delete()
                choose.isEnabled = true
            }
        }
    }

    private fun cancelPendingImport() {
        pendingArchiveFile()?.delete()
        pendingArchivePath = null
        pendingArchiveName = null
        choose.isEnabled = true
        showStatus(R.string.direct_rime_import_cancelled)
    }

    private fun showStatus(title: Int, state: State = State.IDLE, details: String = "") {
        statusTitle = getString(title)
        statusDetails = details
        statusState = state
        status.show(statusTitle, statusDetails, state)
    }

    private fun isPendingArchive(path: String): Boolean = runCatching {
        val file = File(path)
        file.isFile && file.canonicalFile.parentFile == File(cacheDir, "rime-import").canonicalFile
    }.getOrDefault(false)

    private fun pendingArchiveFile(): File? = pendingArchivePath?.takeIf(::isPendingArchive)?.let(::File)

    override fun onSaveInstanceState(outState: Bundle) {
        if (statusState != State.WORKING && statusTitle.isNotEmpty()) {
            outState.putString("ui_status_title", statusTitle)
            outState.putString("ui_status_details", statusDetails)
            outState.putBoolean("ui_status_error", statusState == State.ERROR)
        }
        outState.putString(STATE_PENDING_ARCHIVE, pendingArchivePath)
        outState.putString(STATE_PENDING_ARCHIVE_NAME, pendingArchiveName)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (isFinishing) pendingArchiveFile()?.delete()
        super.onDestroy()
        FcitxDaemon.disconnect(javaClass.name)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private companion object {
        const val STATE_PENDING_ARCHIVE = "pending_rime_import_archive"
        const val STATE_PENDING_ARCHIVE_NAME = "pending_rime_import_archive_name"
        const val MAX_ARCHIVE_BYTES = 512L * 1024 * 1024
    }
}
