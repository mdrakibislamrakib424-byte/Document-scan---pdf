package com.rakib.docscanner

import android.app.Application
import org.opencv.android.OpenCVLoader

class DocScannerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // org.opencv:opencv 4.9.x ships native libs directly in the AAR,
        // so a plain local init is enough — no separate OpenCV Manager app needed.
        OpenCVLoader.initLocal()
    }
}
