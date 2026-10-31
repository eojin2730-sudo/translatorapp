package com.example.xianyutranslator

import android.app.*
import android.content.*
import android.graphics.*
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.*
import android.widget.*
import com.google.mlkit.nl.translate.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.atomic.AtomicBoolean
import com.google.mlkit.common.model.DownloadConditions

class TranslateService : Service() {
    companion object { const val START = "start"; const val STOP = "stop"; const val CHANNEL = "translate" }
    private val handler = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private val translator = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(TranslateLanguage.CHINESE).setTargetLanguage(TranslateLanguage.KOREAN).build())
    private lateinit var wm: WindowManager
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var overlay: FrameLayout? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var ready = false
    private var generation = 0
    private val ticker = object : Runnable { override fun run() { capture(); handler.postDelayed(this, 1800) } }
    override fun onBind(intent: Intent?) = null
    override fun onCreate() { super.onCreate(); wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val channel = NotificationChannel(CHANNEL, "실시간 화면 번역", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action != START) return START_NOT_STICKY
        val notification = Notification.Builder(this, CHANNEL).setContentTitle("시엔위 번역 실행 중").setContentText("중지하려면 앱으로 돌아가세요").setSmallIcon(android.R.drawable.ic_menu_search).setOngoing(true).build()
        startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        val resultData = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("resultData", Intent::class.java) else intent.getParcelableExtra<Intent>("resultData")
        if (resultData == null) { stopSelf(); return START_NOT_STICKY }
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(intent.getIntExtra("resultCode", Activity.RESULT_CANCELED), resultData)
        projection?.registerCallback(object : MediaProjection.Callback() { override fun onStop() { handler.post { stopSelf() } } }, handler)
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels; val h = metrics.heightPixels
        reader = ImageReader.newInstance(w, h, android.graphics.PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection?.createVirtualDisplay("XianyuTranslate", w, h, metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, handler)
        overlayParams = WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, android.graphics.PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }
        overlay = FrameLayout(this)
        wm.addView(overlay, overlayParams)
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).addOnSuccessListener { ready = true; handler.post(ticker) }.addOnFailureListener { Toast.makeText(this, "번역 모델 다운로드 실패: 인터넷을 확인하세요", Toast.LENGTH_LONG).show(); stopSelf() }
        return START_NOT_STICKY
    }
    private fun capture() {
        if (!ready || !busy.compareAndSet(false, true)) return
        val image = reader?.acquireLatestImage()
        if (image == null) { busy.set(false); return }
        val plane = image.planes[0]; val buffer = plane.buffer
        val paddedWidth = plane.rowStride / plane.pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)
        val bitmap = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        padded.recycle(); image.close()
        recognizer.process(InputImage.fromBitmap(bitmap, 0)).addOnSuccessListener { result ->
            val blocks = result.textBlocks.filter { block -> block.boundingBox != null && block.text.any { it.code in 0x3400..0x9FFF } }.take(35)
            val current = ++generation
            overlay?.removeAllViews()
            if (blocks.isEmpty()) { busy.set(false); bitmap.recycle(); return@addOnSuccessListener }
            var remaining = blocks.size
            blocks.forEach { block ->
                translator.translate(block.text).addOnSuccessListener { translated ->
                    if (current == generation) showText(translated, block.boundingBox!!, bitmap.width, bitmap.height)
                }.addOnCompleteListener { if (--remaining == 0) { busy.set(false); bitmap.recycle() } }
            }
        }.addOnFailureListener { busy.set(false); bitmap.recycle() }
    }
    private fun showText(value: String, rect: Rect, srcW: Int, srcH: Int) {
        val frame = overlay ?: return
        val xScale = frame.width.toFloat() / srcW; val yScale = frame.height.toFloat() / srcH
        if (xScale <= 0f || yScale <= 0f) return
        val text = TextView(this).apply {
            text = value; setTextColor(android.graphics.Color.BLACK); setBackgroundColor(android.graphics.Color.rgb(255, 250, 225))
            textSize = (rect.height() * yScale / resources.displayMetrics.scaledDensity * 0.55f).coerceIn(10f, 19f)
            maxLines = 5; setPadding(3, 1, 3, 1)
        }
        val lp = FrameLayout.LayoutParams((rect.width() * xScale).toInt().coerceAtLeast(50), FrameLayout.LayoutParams.WRAP_CONTENT)
        lp.leftMargin = (rect.left * xScale).toInt(); lp.topMargin = (rect.top * yScale).toInt()
        frame.addView(text, lp)
    }
    override fun onDestroy() {
        handler.removeCallbacks(ticker); ready = false
        overlay?.let { try { wm.removeView(it) } catch (_: Exception) {} }; overlay = null
        virtualDisplay?.release(); reader?.close(); projection?.stop(); recognizer.close(); translator.close()
        super.onDestroy()
    }
}
