package com.example.demo20251215

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas // [修复] 必须导入这个扩展属性
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import java.util.concurrent.Executors
import kotlin.math.sqrt
import kotlin.math.pow
import kotlin.math.min

// ====================== 常量定义 ======================
private const val TAG = "HandTest"
private const val CAMERA_PREVIEW_WIDTH = 640
private const val CAMERA_PREVIEW_HEIGHT = 480
private const val PARTICLE_ALPHA_DECREMENT = 0.02f
private const val PALM_OPEN_THRESHOLD = 0.03f
private const val PINCH_THRESHOLD = 0.1f // [优化] 稍微调大一点，更容易触发
private const val MENU_CLICK_DEBOUNCE_MS = 500L
private const val BRUSH_SIZE_MIN = 1f
private const val BRUSH_SIZE_MAX = 100f
private const val PARTICLE_VELOCITY_RANGE = 10f
private const val PARTICLE_SIZE_SCALE = 0.5f
private const val ERASER_RADIUS_OFFSET = 20f
private const val BTN_RADIUS_SCALE = 0.08f
private const val BTN_SPACING_SCALE = 0.1f
private const val BTN_TOP_Y_SCALE = 0.1f

// ====================== 数据模型 ======================
data class DrawPoint(var x: Float, var y: Float)
data class StrokeLine(
    val points: MutableList<DrawPoint>,
    val color: Color,
    var size: Float,
    val isEraser: Boolean = false
)

data class Particle(
    var x: Float, var y: Float,
    var vx: Float, var vy: Float,
    var alpha: Float,
    val color: Color,
    val size: Float
)

data class UiButton(
    val label: String,
    val x: Float,
    val y: Float,
    val r: Float,
    val type: String,
    val value: Any
)

// ====================== ViewModel ======================
class HandDrawingViewModel : ViewModel() {
    private val _strokes = mutableStateListOf<StrokeLine>()
    val strokes: List<StrokeLine> get() = _strokes

    private val _particles = mutableStateListOf<Particle>()
    val particles: List<Particle> get() = _particles

    var brushColor by mutableStateOf(Color.Green)
    var brushSize by mutableStateOf(10f)
    var eraserSize by mutableStateOf(60f)
    var mode by mutableStateOf("drawing")
    var isMenuOpen by mutableStateOf(false)
    var isDrawing by mutableStateOf(false)
    var wasPalmOpen by mutableStateOf(false)
    var canvasSize by mutableStateOf(Size(0f, 0f))
    // 预览尺寸，默认给个初始值防止除零
    var previewSize by mutableStateOf(Size(CAMERA_PREVIEW_WIDTH.toFloat(), CAMERA_PREVIEW_HEIGHT.toFloat()))

    // [优化] 移除 deviceRotation，因为我们会在 Analyzer 里直接旋转 Bitmap

    var handLocation by mutableStateOf<DrawPoint?>(null)
    var isScaling by mutableStateOf(false)
    var baseScaleDist by mutableStateOf(0f)

    private val menuClickFlow = MutableStateFlow<UiButton?>(null)

    init {
        viewModelScope.launch {
            @OptIn(FlowPreview::class) // debounce 需要 opt-in
            menuClickFlow.debounce(MENU_CLICK_DEBOUNCE_MS)
                .collect { button ->
                    button?.let { handleMenuButtonClick(it) }
                }
        }
    }

    private fun handleMenuButtonClick(btn: UiButton) {
        when (btn.type) {
            "color" -> {
                brushColor = btn.value as Color
                mode = "drawing"
                Log.d(TAG, "切换颜色: ${btn.label}")
            }
            "tool" -> {
                mode = btn.value as String
                Log.d(TAG, "切换工具: ${btn.label}")
            }
            "size" -> {
                brushSize = btn.value as Float
                Log.d(TAG, "切换尺寸: ${btn.label}")
            }
        }
    }

    fun triggerMenuButtonClick(btn: UiButton) {
        menuClickFlow.value = btn
    }

    fun addStroke(stroke: StrokeLine) { _strokes.add(stroke) }
    fun clearStrokes() { _strokes.clear() }

    fun removeEmptyStrokes() {
        // [修复] 防止并发修改异常，使用 Iterator 或 removeAll
        _strokes.removeAll { it.points.isEmpty() }
    }

    fun addParticle(particle: Particle) { _particles.add(particle) }
    fun clearParticles() { _particles.clear() }

    fun updateParticles(deltaTime: Long) {
        val delta = deltaTime / 1_000_000_000f
        val iterator = _particles.iterator()
        while (iterator.hasNext()) {
            val p = iterator.next()
            p.x += p.vx * delta * 60
            p.y += p.vy * delta * 60
            p.vx += (Math.random().toFloat() - 0.5f) * 0.5f
            p.alpha -= PARTICLE_ALPHA_DECREMENT * delta * 60
            if (p.alpha <= 0) iterator.remove()
        }
    }

    fun resetScaling() {
        isScaling = false
        baseScaleDist = 0f
    }
}

// ====================== Activity ======================
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "App Started: onCreate")
        setContent {
            HandDrawingApp()
        }
    }
}

// ====================== Composable ======================
@Composable
fun HandDrawingApp() {
    val context = LocalContext.current
    // 使用 viewModel() 需要依赖: implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.0")
    // 如果没有依赖，可以用 val viewModel = remember { HandDrawingViewModel() } 暂替
    val viewModel: HandDrawingViewModel = viewModel()

    var hasCameraPermission by remember { mutableStateOf(false) }
    var showPermissionSetting by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            hasCameraPermission = granted
            if (!granted) showPermissionSetting = true
        }
    )

    LaunchedEffect(Unit) {
        val hasPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        hasCameraPermission = hasPerm
        if (!hasPerm) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    if (hasCameraPermission) {
        CameraWithOverlay(viewModel)
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("需要相机权限", color = Color.White, modifier = Modifier.background(Color.Black.copy(0.5f)).padding(16.dp))
                if (showPermissionSetting) {
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(onClick = {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        context.startActivity(intent)
                    }) { Text("去设置") }
                }
            }
        }
    }
}

@Composable
fun CameraWithOverlay(viewModel: HandDrawingViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }

    // [修复] handLandmarker 需要是 var 才能重新赋值
    var handLandmarker by remember { mutableStateOf<HandLandmarker?>(null) }

    var frameTrigger by remember { mutableStateOf(0L) }
    var lastFrameTime by remember { mutableStateOf(0L) }
    var mediaPipeInitError by remember { mutableStateOf(false) }
    val previewView = remember { PreviewView(context) }

    // 1. 初始化 MediaPipe (只执行一次)
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching {
                Log.d(TAG, "MediaPipe 初始化中...")
                val baseOptions = BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build()
                val options = HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setMinHandDetectionConfidence(0.5f)
                    .setMinTrackingConfidence(0.5f)
                    .setMinHandPresenceConfidence(0.5f)
                    .setNumHands(2)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setResultListener { result, _ -> processLandmarks(result, viewModel) }
                    .build()
                HandLandmarker.createFromOptions(context, options)
            }.onSuccess {
                handLandmarker = it
                Log.d(TAG, "MediaPipe 初始化成功!")
            }.onFailure {
                mediaPipeInitError = true
                Log.e(TAG, "MediaPipe 初始化失败", it)
            }
        }
    }

    // [修复] 移除原来这里重复同步初始化 MediaPipe 的 try-catch 代码块

    // 2. 绑定相机 (只执行一次，不要放在 AndroidView 的 update 里!)
    LaunchedEffect(Unit) {
        val cameraProvider = ProcessCameraProvider.getInstance(context).get()
        val preview = Preview.Builder().build()
        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(android.util.Size(CAMERA_PREVIEW_WIDTH, CAMERA_PREVIEW_HEIGHT))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        imageAnalysis.setAnalyzer(executor) { imageProxy ->
            // 如果 MediaPipe 还没初始化好，直接关闭帧
            val detector = handLandmarker
            if (detector == null) {
                imageProxy.close()
                return@setAnalyzer
            }

            // 更新预览尺寸 (用于坐标映射)
            // 注意：这里可能需要 post 到主线程更新 state，但为了性能暂时直接更新（Compose快照系统会处理）
            if (viewModel.previewSize.width != imageProxy.width.toFloat()) {
                viewModel.previewSize = Size(imageProxy.width.toFloat(), imageProxy.height.toFloat())
            }

            try {
                // 1. 获取 Bitmap
                val bitmapBuffer = Bitmap.createBitmap(
                    imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888
                )
                bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer)

                // 2. 旋转 + 镜像翻转 (前置摄像头)
                val matrix = Matrix()
                matrix.postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                matrix.postScale(-1f, 1f, imageProxy.width / 2f, imageProxy.height / 2f)

                val rotatedBitmap = Bitmap.createBitmap(
                    bitmapBuffer, 0, 0, imageProxy.width, imageProxy.height, matrix, true
                )

                // 3. 识别
                val mpImage = BitmapImageBuilder(rotatedBitmap).build()
                detector.detectAsync(mpImage, System.currentTimeMillis())

            } catch (e: Exception) {
                Log.e(TAG, "Analyzer Error: ${e.message}")
            } finally {
                imageProxy.close() // [关键] 必须关闭，否则相机流会卡死
            }
        }

        preview.setSurfaceProvider(previewView.surfaceProvider)
        val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)
            Log.d(TAG, "相机绑定成功")
        } catch (exc: Exception) {
            Log.e(TAG, "相机绑定失败", exc)
        }
    }

    // 3. 粒子动画循环
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { currentTime ->
                if (lastFrameTime != 0L) {
                    val deltaTime = currentTime - lastFrameTime
                    viewModel.updateParticles(deltaTime)
                    frameTrigger++ // 触发重绘
                }
                lastFrameTime = currentTime
            }
        }
    }

    // 4. 资源释放
    DisposableEffect(Unit) {
        onDispose {
            handLandmarker?.close()
            executor.shutdown()
        }
    }

    if (mediaPipeInitError) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("手势识别初始化失败", color = Color.Red, modifier = Modifier.background(Color.Black).padding(16.dp))
        }
        return
    }

    Box(modifier = Modifier
        .fillMaxSize()
        .onSizeChanged { viewModel.canvasSize = it.toSize() }
    ) {
        // 相机预览 View
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
            // [修复] 移除 update 块里的相机绑定逻辑，避免重绘闪烁
        )

        // 绘图 Canvas
        Canvas(modifier = Modifier.fillMaxSize()) {
            val trigger = frameTrigger // 读取 trigger 触发重绘

            // 绘制笔画
            viewModel.strokes.forEach { stroke ->
                if (stroke.points.isNotEmpty() && !stroke.isEraser) {
                    val path = Path()
                    path.moveTo(stroke.points.first().x, stroke.points.first().y)
                    for (i in 1 until stroke.points.size) {
                        path.lineTo(stroke.points[i].x, stroke.points[i].y)
                    }
                    drawPath(
                        path = path,
                        color = stroke.color,
                        style = Stroke(width = stroke.size, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }
            }

            // 绘制粒子
            viewModel.particles.forEach { p ->
                drawCircle(color = p.color.copy(alpha = p.alpha.coerceIn(0f, 1f)), radius = p.size, center = Offset(p.x, p.y))
            }

            // 绘制光标 (Debug用)
            viewModel.handLocation?.let {
                drawCircle(color = Color.Yellow, radius = 10f, center = Offset(it.x, it.y), style = Stroke(2f))
            }

            // 绘制菜单
            if (viewModel.isMenuOpen) {
                // 这里的 Paint 对象创建最好提出来，但为了代码简单先这样
                val textPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = 40f
                    textAlign = android.graphics.Paint.Align.CENTER
                }

                drawRect(color = Color(0xCC222222), topLeft = Offset(50f, 50f), size = Size(size.width - 100f, size.height * 0.4f))

                val buttons = getUiButtons(size.width, size.height)
                buttons.forEach { btn ->
                    val isActive = (btn.type == "tool" && viewModel.mode == btn.value) ||
                            (btn.type == "size" && viewModel.brushSize == btn.value)

                    drawCircle(
                        color = if (btn.type == "color") btn.value as Color else if (isActive) Color.Gray else Color.DarkGray,
                        radius = btn.r,
                        center = Offset(btn.x, btn.y)
                    )
                    drawCircle(color = Color.White, radius = btn.r, center = Offset(btn.x, btn.y), style = Stroke(width = 2f))

                    // [修复] 移动到 Canvas 内部的正确文字绘制逻辑
                    drawContext.canvas.nativeCanvas.drawText(
                        btn.label,
                        btn.x,        // x (Paint设置了Align.CENTER)
                        btn.y + 15f,  // y (稍微向下偏移垂直居中)
                        textPaint
                    )
                }
            }
        }

        // 状态栏
        Text(
            text = "模式: ${if(viewModel.mode == "drawing") "绘画" else "橡皮"} | 菜单: ${if(viewModel.isMenuOpen) "开" else "关"}",
            color = Color.White,
            modifier = Modifier.align(Alignment.BottomStart).padding(16.dp).background(Color.Black.copy(0.5f))
        )
    }
}

// ====================== 逻辑处理 ======================
fun processLandmarks(result: HandLandmarkerResult, viewModel: HandDrawingViewModel) {
    if (result.landmarks().isEmpty()) {
        viewModel.resetScaling()
        return
    }

    val firstHand = result.landmarks().first()
    val screenW = viewModel.canvasSize.width
    val screenH = viewModel.canvasSize.height

    // [修复] 简化的坐标映射逻辑
    // 因为我们在 Analyzer 里已经把 Bitmap 旋转正了，并且处理了镜像
    // 所以这里的 landmarks 坐标已经是 (0..1) 归一化的正向坐标
    // 只需要简单缩放到屏幕尺寸即可，不需要再处理 deviceRotation
    fun toScreen(landmark: com.google.mediapipe.tasks.components.containers.NormalizedLandmark): DrawPoint {
        return DrawPoint(
            x = landmark.x() * screenW,
            y = landmark.y() * screenH
        )
    }

    val indexTip = toScreen(firstHand[8])
    val thumbTip = toScreen(firstHand[4])

    Handler(Looper.getMainLooper()).post {
        // 双手缩放逻辑保持不变...
        if (result.landmarks().size == 2) {
            val h2 = result.landmarks()[1]
            val p2 = toScreen(h2[8])
            val dist = sqrt((indexTip.x - p2.x).pow(2) + (indexTip.y - p2.y).pow(2))

            if (!viewModel.isScaling) {
                viewModel.isScaling = true
                viewModel.baseScaleDist = dist
            } else {
                val scaleFactor = 1f + (dist - viewModel.baseScaleDist) * 0.002f
                val cx = (indexTip.x + p2.x) / 2
                val cy = (indexTip.y + p2.y) / 2

                viewModel.strokes.filter { it.points.isNotEmpty() }.forEach { stroke ->
                    stroke.size = (stroke.size * scaleFactor).coerceIn(BRUSH_SIZE_MIN, BRUSH_SIZE_MAX)
                    stroke.points.forEach { pt ->
                        pt.x = cx + (pt.x - cx) * scaleFactor
                        pt.y = cy + (pt.y - cy) * scaleFactor
                    }
                }
                viewModel.baseScaleDist = dist
            }
            return@post
        } else {
            viewModel.resetScaling()
        }

        // 单手逻辑
        fun dist(i: Int, j: Int): Float {
            val p1 = firstHand[i]
            val p2 = firstHand[j]
            // 注意：这里用原始 landmark 计算相对距离比较准，不受屏幕比例拉伸影响
            return sqrt((p1.x() - p2.x()).pow(2) + (p1.y() - p2.y()).pow(2))
        }

        val isPalmOpen = dist(8, 5) > PALM_OPEN_THRESHOLD &&
                dist(12, 9) > PALM_OPEN_THRESHOLD &&
                dist(16, 13) > PALM_OPEN_THRESHOLD &&
                dist(20, 17) > PALM_OPEN_THRESHOLD

        val isPinch = dist(8, 4) < PINCH_THRESHOLD

        val isVictory = firstHand[8].y() < firstHand[6].y() && // 指尖在指根上方 (注意Y轴向下为正，所以上方是 < )
                firstHand[12].y() < firstHand[10].y() &&
                firstHand[16].y() > firstHand[14].y() // 无名指弯曲

        // A. 菜单开关
        if (isPalmOpen && !viewModel.wasPalmOpen) {
            viewModel.isMenuOpen = !viewModel.isMenuOpen
            viewModel.isDrawing = false
        }
        viewModel.wasPalmOpen = isPalmOpen

        // B. 菜单交互
        if (viewModel.isMenuOpen) {
            val buttons = getUiButtons(screenW, screenH)
            buttons.forEach { btn ->
                val d = sqrt((indexTip.x - btn.x).pow(2) + (indexTip.y - btn.y).pow(2))
                if (d < btn.r + 20) {
                    viewModel.triggerMenuButtonClick(btn)
                }
            }
        } else {
            // C. 绘图
            if (isVictory) {
                triggerDissolve(viewModel)
            } else if (isPinch) {
                val midX = (indexTip.x + thumbTip.x) / 2
                val midY = (indexTip.y + thumbTip.y) / 2
                viewModel.handLocation = DrawPoint(midX, midY)

                if (!viewModel.isDrawing) {
                    viewModel.isDrawing = true
                    viewModel.addStroke(StrokeLine(
                        points = mutableListOf(DrawPoint(midX, midY)),
                        color = if(viewModel.mode=="eraser") Color.Transparent else viewModel.brushColor,
                        size = if(viewModel.mode=="eraser") viewModel.eraserSize else viewModel.brushSize,
                        isEraser = viewModel.mode == "eraser"
                    ))
                } else {
                    if (viewModel.strokes.isNotEmpty()) {
                        val lastStroke = viewModel.strokes.last()
                        lastStroke.points.add(DrawPoint(midX, midY))
                        if (viewModel.mode == "eraser") eraseAt(midX, midY, viewModel.eraserSize, viewModel)
                    }
                }
            } else {
                viewModel.isDrawing = false
                val midX = (indexTip.x + thumbTip.x) / 2
                val midY = (indexTip.y + thumbTip.y) / 2
                viewModel.handLocation = DrawPoint(midX, midY)
            }
        }
    }
}

// 其余辅助函数 (triggerDissolve, eraseAt, getUiButtons) 保持不变，
// 可以直接使用你原有代码中的这些函数，只要确保 eraseAt 调用了 viewModel.removeEmptyStrokes()
fun triggerDissolve(viewModel: HandDrawingViewModel) {
    viewModel.strokes.forEach { stroke ->
        for (i in stroke.points.indices step 5) {
            val p = stroke.points[i]
            viewModel.addParticle(Particle(
                x = p.x, y = p.y,
                vx = (Math.random().toFloat() - 0.5f) * PARTICLE_VELOCITY_RANGE,
                vy = (Math.random().toFloat() * 5f) + 5f,
                alpha = 1f, color = stroke.color, size = stroke.size * PARTICLE_SIZE_SCALE
            ))
        }
    }
    viewModel.clearStrokes()
}

fun eraseAt(x: Float, y: Float, r: Float, viewModel: HandDrawingViewModel) {
    viewModel.strokes.forEach { stroke ->
        if (!stroke.isEraser) {
            val iterator = stroke.points.iterator()
            while (iterator.hasNext()) {
                val p = iterator.next()
                if (sqrt((p.x - x).pow(2) + (p.y - y).pow(2)) < r) {
                    iterator.remove()
                }
            }
        }
    }
    viewModel.removeEmptyStrokes()
}

fun getUiButtons(canvasWidth: Float, canvasHeight: Float): List<UiButton> {
    // 保持原有逻辑...
    val btnRadius = canvasWidth * BTN_RADIUS_SCALE
    val topY = canvasHeight * BTN_TOP_Y_SCALE
    val spacing = canvasWidth * BTN_SPACING_SCALE
    return listOf(
        UiButton("红", spacing, topY, btnRadius, "color", Color.Red),
        UiButton("绿", spacing * 2, topY, btnRadius, "color", Color.Green),
        UiButton("蓝", spacing * 3, topY, btnRadius, "color", Color.Blue),
        UiButton("笔", spacing, topY + spacing * 2, btnRadius * 1.2f, "tool", "drawing"),
        UiButton("擦", spacing * 2.2f, topY + spacing * 2, btnRadius * 1.2f, "tool", "eraser"),
        UiButton("大", spacing, topY + spacing * 4, btnRadius * 0.8f, "size", 20f),
        UiButton("小", spacing * 2, topY + spacing * 4, btnRadius * 0.6f, "size", 5f),
    )
}