package io.stamethyst.backend.file_interactive

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import io.stamethyst.R
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * Opens SAF "CreateDocument" so users can choose where to save exported files.
 */
class SafExportActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_SOURCE_PATH = "io.stamethyst.backend.file.source_path"
        const val EXTRA_SUGGESTED_NAME = "io.stamethyst.backend.file.suggested_name"
        const val EXTRA_MIME_TYPE = "io.stamethyst.backend.file.mime_type"
    }

    private var sourceFile: File? = null
    private var suggestedName: String = "export.bin"
    private var mimeType: String = "application/octet-stream"

    private val createDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val targetUri = if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data
            } else {
                null
            }
            onDocumentPicked(targetUri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sourcePath = intent.getStringExtra(EXTRA_SOURCE_PATH).orEmpty().trim()
        if (sourcePath.isEmpty()) {
            finish()
            return
        }
        val file = File(sourcePath)
        if (!file.isFile || !file.exists()) {
            Toast.makeText(this, getString(R.string.saf_export_source_missing), Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        sourceFile = file

        val extraName = intent.getStringExtra(EXTRA_SUGGESTED_NAME).orEmpty().trim()
        suggestedName = if (extraName.isNotEmpty()) extraName else file.name
        if (suggestedName.isBlank()) {
            suggestedName = "export.bin"
        }

        val extraMime = intent.getStringExtra(EXTRA_MIME_TYPE).orEmpty().trim()
        mimeType = if (extraMime.isNotEmpty()) extraMime else "application/octet-stream"

        if (savedInstanceState == null) {
            launchCreateDocument()
        }
    }

    private fun launchCreateDocument() {
        val createDocumentIntent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
            putExtra(Intent.EXTRA_TITLE, suggestedName)
        }
        createDocumentLauncher.launch(createDocumentIntent)
    }

    private fun onDocumentPicked(targetUri: Uri?) {
        val file = sourceFile
        if (targetUri == null || file == null) {
            finish()
            return
        }

        try {
            writeToUri(file, targetUri)
            Toast.makeText(this, getString(R.string.saf_export_success), Toast.LENGTH_SHORT).show()
        } catch (error: Throwable) {
            Toast.makeText(
                this,
                getString(
                    R.string.saf_export_failed,
                    error.message ?: getString(R.string.feedback_unknown_error)
                ),
                Toast.LENGTH_LONG
            ).show()
        } finally {
            finish()
        }
    }

    @Throws(IOException::class)
    private fun writeToUri(source: File, targetUri: Uri) {
        contentResolver.openOutputStream(targetUri, "w").use { output ->
            if (output == null) {
                throw IOException("Cannot open target stream")
            }
            FileInputStream(source).use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) {
                        break
                    }
                    output.write(buffer, 0, read)
                }
                output.flush()
            }
        }
    }
}
