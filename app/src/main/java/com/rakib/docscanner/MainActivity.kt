package com.rakib.docscanner

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.rakib.docscanner.databinding.ActivityMainBinding

/**
 * Home screen.
 * Two entry points into a NEW page: live camera capture, or an existing
 * gallery photo. Both funnel into ReviewActivity, and from there into the
 * Phase 4 multi-page SessionActivity. "Continue Document" reappears
 * whenever a session already has pages waiting (e.g. the user backed out
 * mid-scan), and "My Documents" opens the list of already-exported PDFs.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val intent = Intent(this, ReviewActivity::class.java).apply {
                putExtra(ReviewActivity.EXTRA_IMAGE_URI, uri.toString())
            }
            startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnScan.setOnClickListener {
            startActivity(Intent(this, ScanActivity::class.java))
        }

        binding.btnImport.setOnClickListener {
            pickImage.launch("image/*")
        }

        binding.btnContinueSession.setOnClickListener {
            startActivity(Intent(this, SessionActivity::class.java))
        }

        binding.btnDocuments.setOnClickListener {
            startActivity(Intent(this, DocumentsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        val count = PageSession.pageCount()
        if (count > 0) {
            binding.btnContinueSession.visibility = android.view.View.VISIBLE
            binding.btnContinueSession.text = "Continue Document ($count page${if (count == 1) "" else "s"})"
        } else {
            binding.btnContinueSession.visibility = android.view.View.GONE
        }
    }
}
