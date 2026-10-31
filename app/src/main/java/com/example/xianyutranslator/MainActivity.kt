package com.example.xianyutranslator

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private val requestCode = 100
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(45, 80, 45, 30) }
        val title = TextView(this).apply { text = "시엔위 실시간 한국어 번역기"; textSize = 23f }
        val info = TextView(this).apply { text = "1. 오버레이 권한 허용\n2. 번역 시작 및 화면 공유 허용\n3. 시엔위 앱 실행\n\n첫 실행에는 번역 모델 다운로드가 필요합니다.\n화면 캡처 내용은 외부 서버로 업로드하지 않습니다."; textSize = 16f }
        val start = Button(this).apply { text = "자동 번역 시작"; setOnClickListener { begin() } }
        val stop = Button(this).apply { text = "번역 중지"; setOnClickListener { startService(Intent(this@MainActivity, TranslateService::class.java).apply { action = TranslateService.STOP }) } }
        root.addView(title); root.addView(info); root.addView(start); root.addView(stop); setContentView(root)
    }
    private fun begin() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), requestCode)
    }
    @Deprecated("Legacy callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == this.requestCode && resultCode == RESULT_OK && data != null) {
            startForegroundService(Intent(this, TranslateService::class.java).apply {
                action = TranslateService.START; putExtra("resultCode", resultCode); putExtra("resultData", data)
            })
            moveTaskToBack(true)
        }
    }
}
