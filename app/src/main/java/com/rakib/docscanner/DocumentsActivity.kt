package com.rakib.docscanner

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.rakib.docscanner.databinding.ActivityDocumentsBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

class DocumentsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDocumentsBinding
    private var documents: List<File> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDocumentsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.listDocuments.setOnItemClickListener { _, _, position, _ -> shareDocument(documents[position]) }
        binding.listDocuments.setOnItemLongClickListener { _, _, position, _ ->
            confirmDelete(documents[position])
            true
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        documents = PdfExporter.listExportedDocuments(this)
        binding.tvEmptyDocuments.visibility = if (documents.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        binding.listDocuments.visibility = if (documents.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE

        val dateFormat = SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.US)
        val labels = documents.map { "${it.nameWithoutExtension}\n${dateFormat.format(it.lastModified())}" }
        binding.listDocuments.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
    }

    private fun shareDocument(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Share document"))
    }

    private fun confirmDelete(file: File) {
        AlertDialog.Builder(this)
            .setTitle("Delete document")
            .setMessage("Delete \"${file.nameWithoutExtension}\"? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                if (file.delete()) {
                    Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
                    refresh()
                } else {
                    Toast.makeText(this, "Could not delete file", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
