package com.saher.sniper
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.media.*
import android.os.*
import android.widget.*
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.core.content.ContextCompat
import org.tensorflow.lite.Interpreter
import java.nio.*
import java.io.FileInputStream
import java.nio.channels.FileChannel
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
 private val executor = Executors.newSingleThreadExecutor()
 private lateinit var preview: PreviewView
 private lateinit var status: TextView
 private lateinit var overlay: DetectionOverlay
 private var model: Interpreter? = null
 private var tone: ToneGenerator? = null
 @Volatile private var mutedUntil = 0L
 @Volatile private var threshold = 0.40f
 private var lastAlert = -10000L
 private var boundCamera: Camera? = null
 private var requestedZoom = 1f
 private lateinit var zoomButtons: List<Button>
 private val zoomPreferences by lazy { getSharedPreferences("camera_zoom",MODE_PRIVATE) }
 override fun onCreate(state: Bundle?) {
  super.onCreate(state)
  window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
  val root = FrameLayout(this)
  preview = PreviewView(this)
  preview.scaleType = PreviewView.ScaleType.FIT_CENTER
  root.addView(preview)
  overlay = DetectionOverlay(this)
  root.addView(overlay)
  status = TextView(this).apply { text = "جاري تحميل النموذج"; setTextColor(Color.WHITE); setBackgroundColor(0xAA000000.toInt()); textSize=18f }
  root.addView(status, FrameLayout.LayoutParams(-1,-2,android.view.Gravity.TOP))
  requestedZoom = zoomPreferences.getFloat("zoom_ratio",1f).takeIf { it in listOf(1f,2f,3f) } ?: 1f
  val buttons = LinearLayout(this)
  buttons.addView(Button(this).apply { text="اختبار الصوت"; setOnClickListener { alert() } })
  buttons.addView(Button(this).apply { text="كتم 10 ث"; setOnClickListener { mutedUntil=SystemClock.elapsedRealtime()+10000 } })
  buttons.addView(Button(this).apply { text="نافذة"; setOnClickListener { enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational(9,16)).build()) } })
  val controls=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
  val sensitivity=TextView(this).apply { text="عتبة الثقة 40%"; setTextColor(Color.WHITE); setBackgroundColor(Color.BLACK) }
  val zoomRow = LinearLayout(this).apply { gravity=android.view.Gravity.CENTER; orientation=LinearLayout.HORIZONTAL }
  zoomButtons=listOf(1f,2f,3f).map { ratio ->
   Button(this).apply {
    text="${ratio.toInt()}X"
    setOnClickListener { setZoom(ratio) }
    zoomRow.addView(this, LinearLayout.LayoutParams(0,-2,1f))
   }
  }
  controls.addView(zoomRow)
  controls.addView(sensitivity)
  updateZoomButtons()
  controls.addView(SeekBar(this).apply { max=80; progress=30; setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
   override fun onProgressChanged(s:SeekBar?,p:Int,u:Boolean){ threshold=(p+10)/100f; sensitivity.text="عتبة الثقة "+(p+10)+"%" }
   override fun onStartTrackingTouch(s:SeekBar?) {}
   override fun onStopTrackingTouch(s:SeekBar?) {}
  }) })
  controls.addView(buttons)
  root.addView(controls,FrameLayout.LayoutParams(-1,-2,android.view.Gravity.BOTTOM))
  setContentView(root)
  try {
   tone=ToneGenerator(AudioManager.STREAM_ALARM,100)
   val mapped=assets.openFd("best.tflite").use { fd -> FileInputStream(fd.fileDescriptor).use { it.channel.map(FileChannel.MapMode.READ_ONLY,fd.startOffset,fd.declaredLength) } }
   model=Interpreter(mapped,Interpreter.Options().setNumThreads(4))
   status.text="النموذج جاهز"
   if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) camera() else requestPermissions(arrayOf(Manifest.permission.CAMERA),100)
  } catch(e:Exception) { status.text="خطأ النموذج: "+e.message }
 }
 override fun onRequestPermissionsResult(r:Int,p:Array<String>,g:IntArray) { super.onRequestPermissionsResult(r,p,g); if(r==100 && g.firstOrNull()==PackageManager.PERMISSION_GRANTED) camera() else status.text="يلزم إذن الكاميرا" }
 private fun alert() {
  tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD,700)
  (getSystemService(VIBRATOR_SERVICE) as Vibrator).vibrate(VibrationEffect.createOneShot(400,VibrationEffect.DEFAULT_AMPLITUDE))
 }
 private fun updateZoomButtons() {
  if(!::zoomButtons.isInitialized)return
  zoomButtons.forEachIndexed { i, button ->
   val selected = requestedZoom == (i+1).toFloat()
   button.alpha = if(selected) 1f else 0.65f
   button.isSelected = selected
  }
 }
 private fun setZoom(ratio:Float) {
  val camera=boundCamera
  if(camera==null) {
   requestedZoom=ratio
   zoomPreferences.edit().putFloat("zoom_ratio",ratio).apply()
   updateZoomButtons()
   return
  }
  val min=camera.cameraInfo.zoomState.value?.minZoomRatio ?: 1f
  val max=camera.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
  if(ratio<min || ratio>max) {
   Toast.makeText(this,"مستوى تقريب ${ratio.toInt()}X غير مدعوم في هذه الكاميرا",Toast.LENGTH_SHORT).show()
   return
  }
  camera.cameraControl.setZoomRatio(ratio).addListener({
   // CameraX applies the same zoom crop to preview and analysis.
   try { camera.cameraInfo.zoomState.value ?: throw IllegalStateException("Zoom state unavailable") }
   catch (e:Exception) { Toast.makeText(this,"تعذر تطبيق التقريب",Toast.LENGTH_SHORT).show(); return@addListener }
   requestedZoom=ratio
   zoomPreferences.edit().putFloat("zoom_ratio",ratio).apply()
   updateZoomButtons()
  },ContextCompat.getMainExecutor(this))
 }
 private fun camera() {
  val f=ProcessCameraProvider.getInstance(this)
  f.addListener({
   try {
    val provider=f.get()
    val p=Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
    val a=ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
    a.setAnalyzer(executor){ analyze(it) }
    provider.unbindAll()
    // CameraX's default rear logical camera is the device's main 1x camera path.
    // Selecting by longest focal length can accidentally pick a telephoto lens.
    boundCamera=provider.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,p,a)
    setZoom(requestedZoom)
   } catch(e:Exception){ status.text="خطأ الكاميرا: "+e.message }
  },ContextCompat.getMainExecutor(this))
 }
 private fun analyze(img:ImageProxy) {
  try {
   val m=model ?: return
   val shape=m.getInputTensor(0).shape()
   require(shape.contentEquals(intArrayOf(1,3,512,512)) && m.getInputTensor(0).dataType()==org.tensorflow.lite.DataType.FLOAT32)
   val h=shape[2]; val w=shape[3]
   val b=img.toBitmap()
   val rot=Bitmap.createBitmap(b,0,0,b.width,b.height,Matrix().apply{postRotate(img.imageInfo.rotationDegrees.toFloat())},true)
   val resized=Bitmap.createScaledBitmap(rot,w,h,true)
   val pixels=IntArray(w*h); resized.getPixels(pixels,0,w,0,0,w,h)
   val input=ByteBuffer.allocateDirect(w*h*12).order(ByteOrder.nativeOrder())
   for(p in pixels) input.putFloat(Color.red(p)/255f)
   for(p in pixels) input.putFloat(Color.green(p)/255f)
   for(p in pixels) input.putFloat(Color.blue(p)/255f)
   input.rewind()
   val s=m.getOutputTensor(0).shape()
   require(s.contentEquals(intArrayOf(1,5,5376))) { "Output: "+s.contentToString() }
   val out=Array(1){Array(s[1]){FloatArray(s[2])}}
   m.run(input,out)
   val transposed=s[1]<s[2]
   val channels=if(transposed)s[1] else s[2]
   val count=if(transposed)s[2] else s[1]
   require(channels>=5)
   fun v(c:Int,i:Int)=if(transposed)out[0][c][i] else out[0][i][c]
   val boxes=mutableListOf<Pair<RectF,Float>>()
   var peak=0f
   for(i in 0 until count){
    var score=0f
    for(c in 4 until channels)score=maxOf(score,v(c,i))
    if(score.isFinite()) peak=maxOf(peak,score)
    if(score<threshold || !score.isFinite())continue
    val x=v(0,i);val y=v(1,i);val bw=v(2,i);val bh=v(3,i)
    val sx=if(maxOf(kotlin.math.abs(x),kotlin.math.abs(bw))>2f)w.toFloat() else 1f
    val sy=if(maxOf(kotlin.math.abs(y),kotlin.math.abs(bh))>2f)h.toFloat() else 1f
    boxes.add(RectF((x-bw/2)/sx,(y-bh/2)/sy,(x+bw/2)/sx,(y+bh/2)/sy) to score)
   }
   val best=boxes.maxByOrNull{it.second}
   runOnUiThread {
    overlay.box=best?.first;overlay.invalidate()
    status.text=if(best==null)"الرصد نشط | أعلى ثقة "+(peak*100).toInt()+"%" else "تم الرصد "+(best.second*100).toInt()+"%"
    val now=SystemClock.elapsedRealtime()
    if(best!=null && now>=mutedUntil && now-lastAlert>=3000){lastAlert=now;alert()}
   }
  }catch(e:Exception){runOnUiThread{status.text="خطأ المعالجة: "+e.message}}finally{img.close()}
 }
 override fun onDestroy(){super.onDestroy();executor.shutdown();tone?.release()}
 class DetectionOverlay(c:android.content.Context):View(c){
  var box:RectF?=null
  private val paint=Paint().apply{color=Color.RED;style=Paint.Style.STROKE;strokeWidth=6f}
  override fun onDraw(canvas:Canvas){super.onDraw(canvas);box?.let{canvas.drawRect(it.left*width,it.top*height,it.right*width,it.bottom*height,paint)}}
 }
}