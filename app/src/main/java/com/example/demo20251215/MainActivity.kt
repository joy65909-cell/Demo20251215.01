package com.example.demo20251215

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Surface
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
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

// ====================== 常量定义（提取所有魔法值） ======================
private const val TAG = "HandTest"
private const val CAMERA_PREVIEW_WIDTH = 640
private const val CAMERA_PREVIEW_HEIGHT = 480
private const val PARTICLE_ALPHA_DECREMENT = 0.02f
private const val PALM_OPEN_THRESHOLD = 0.03f
private const val PINCH_THRESHOLD = 0.05f
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

// UI 按钮定义
data class UiButton(
    val label: String,
    val x: Float,
    val y: Float,
    val r: Float,
    val type: String,
    val value: Any
)

// ====================== ViewModel（状态管理重构） ======================
class HandDrawingViewModel : ViewModel() {
    // 只读暴露，内部修改
    private val _strokes = mutableStateListOf<StrokeLine>()
    val strokes: List<StrokeLine> get() = _strokes

    private val _particles = mutableStateListOf<Particle>()
    val particles: List<Particle> get() = _particles

    // 基础绘制状态
    var brushColor by mutableStateOf(Color.Green)
    var brushSize by mutableStateOf(10f)
    var eraserSize by mutableStateOf(60f)
    var mode by mutableStateOf("drawing") // drawing, eraser
    var isMenuOpen by mutableStateOf(false)
    var isDrawing by mutableStateOf(false)
    var wasPalmOpen by mutableStateOf(false)
    var canvasSize by mutableStateOf(Size(0f, 0f))
    var previewSize by mutableStateOf(Size(CAMERA_PREVIEW_WIDTH.toFloat(), CAMERA_PREVIEW_HEIGHT.toFloat()))
    var deviceRotation by mutableStateOf(0)

    // 缩放相关
    var handLocation by mutableStateOf<DrawPoint?>(null)
    var isScaling by mutableStateOf(false)
    var baseScaleDist by mutableStateOf(0f)

    // 防抖相关
    private val menuClickFlow = MutableStateFlow<UiButton?>(null)

    init {
        // 菜单点击防抖
        viewModelScope.launch {
            menuClickFlow.debounce(MENU_CLICK_DEBOUNCE_MS)
                .collect { button ->
                    button?.let { handleMenuButtonClick(it) }
                }
        }
    }

    // 处理菜单按钮点击（防抖后）
    private fun handleMenuButtonClick(btn: UiButton) {
        when (btn.type) {
            "color" -> {
                brushColor = btn.value as Color
                mode = "drawing"
                if (BuildConfig.DEBUG) Log.d(TAG, "切换颜色: ${btn.label}")
            }
            "tool" -> {
                mode = btn.value as String
                if (BuildConfig.DEBUG) Log.d(TAG, "切换工具: ${btn.label}")
            }
            "size" -> {
                brushSize = btn.value as Float
                if (BuildConfig.DEBUG) Log.d(TAG, "切换尺寸: ${btn.label}")
            }
        }
    }

    // 对外暴露的点击触发（防抖）
    fun triggerMenuButtonClick(btn: UiButton) {
        menuClickFlow.value = btn
    }

    // 笔画操作
    fun addStroke(stroke: StrokeLine) {
        _strokes.add(stroke)
    }

    fun clearStrokes() {
        _strokes.clear()
    }

    fun removeEmptyStrokes() {
        _strokes.removeAll { it.points.isEmpty() }
    }

    // 粒子操作
    fun addParticle(particle: Particle) {
        _particles.add(particle)
    }

    fun updateParticles(deltaTime: Long) {
        val delta = deltaTime / 1_000_000_000f // 转秒
        val iterator = _particles.iterator()
        while (iterator.hasNext()) {
            val p = iterator.next()
            p.x += p.vx * delta * 60
            p.y += p.vy * delta * 60
            p.vx += (Math.random().toFloat() - 0.5f) * 0.5f
            p.alpha -= PARTICLE_ALPHA_DECREMENT * delta * 60

            // alpha为0立即移除，减少迭代量
            if (p.alpha <= 0) {
                iterator.remove()
            }
        }
    }

    fun clearParticles() {
        _particles.clear()
    }

    // 重置缩放状态
    fun resetScaling() {
        isScaling = false
        baseScaleDist = 0f
    }
}

// ====================== 主Activity ======================
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG) Log.d(TAG, "App Started: onCreate")
        setContent {
            HandDrawingApp()
        }
    }
}

// ====================== 权限申请与主界面 ======================
@Composable
fun HandDrawingApp() {
    val context = LocalContext.current
    val viewModel: HandDrawingViewModel = viewModel()
    var hasCameraPermission by remember { mutableStateOf(false) }
    var showPermissionSetting by remember { mutableStateOf(false) }

    // 权限申请Launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            if (BuildConfig.DEBUG) Log.d(TAG, "Permission Request Result: $granted")
            hasCameraPermission = granted
            if (!granted) {
                showPermissionSetting = true
            }
        }
    )

    // 初始化权限检查
    LaunchedEffect(Unit) {
        val hasPerm = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        hasCameraPermission = hasPerm
        if (!hasPerm) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Requesting permission...")
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // 权限已授予 - 显示相机绘图界面
    if (hasCameraPermission) {
        CameraWithOverlay(viewModel)
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "需要相机权限来运行应用",
                    color = Color.White,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.5f)).padding(16.dp)
                )

                // 拒绝后引导到设置页
                if (showPermissionSetting) {
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(onClick = {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        context.startActivity(intent)
                    }) {
                        Text("前往设置开启权限")
                    }
                }
            }
        }
    }
}

// ====================== 相机与绘制叠加层 ======================
@Composable
fun CameraWithOverlay(viewModel: HandDrawingViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    var frameTrigger by remember { mutableStateOf(0L) }
    var lastFrameTime by remember { mutableStateOf(0L) }
    var mediaPipeInitError by remember { mutableStateOf(false) }

    // MediaPipe 异步初始化（后台线程）
    val handLandmarker by remember {
        mutableStateOf<HandLandmarker?>(null)
    }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching {
                val baseOptions = BaseOptions.builder()
                    .setModelAssetPath("hand_landmarker.task")
                    .build()

                val options = HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setMinHandDetectionConfidence(0.5f)
                    .setMinTrackingConfidence(0.5f)
                    .setMinHandPresenceConfidence(0.5f)
                    .setNumHands(2)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setResultListener { result, _ ->
                        processLandmarks(result, viewModel)
                    }
                    .build()

                HandLandmarker.createFromOptions(context, options)
            }.onSuccess {
                handLandmarker = it
                if (BuildConfig.DEBUG) Log.d(TAG, "MediaPipe Initialized Successfully!")
            }.onFailure {
                mediaPipeInitError = true
                Log.e(TAG, "MediaPipe 初始化失败", it)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(
                        context,
                        "MediaPipe初始化失败: ${it.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // 粒子动画（高性能帧驱动）
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { currentTime ->
                if (lastFrameTime != 0L) {
                    val deltaTime = currentTime - lastFrameTime
                    viewModel.updateParticles(deltaTime)
                    frameTrigger++
                }
                lastFrameTime = currentTime
            }
        }
    }

    // 资源释放（生命周期管理）
    DisposableEffect(Unit) {
        onDispose {
            // 释放MediaPipe
            handLandmarker?.close()
            // 释放相机
            runCatching {
                ProcessCameraProvider.getInstance(context).get().unbindAll()
            }
            // 关闭线程池
            executor.shutdown()
            // 清空状态
            viewModel.clearStrokes()
            viewModel.clearParticles()
            if (BuildConfig.DEBUG) Log.d(TAG, "资源已释放")
        }
    }

    // MediaPipe初始化失败提示
    if (mediaPipeInitError) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "手部识别初始化失败，请检查模型文件",
                color = Color.Red,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.5f)).padding(16.dp)
            )
        }
        return
    }

    Box(modifier = Modifier
        .fillMaxSize()
        .onSizeChanged {
            viewModel.canvasSize = it.toSize()
            if (BuildConfig.DEBUG) Log.d(TAG, "Canvas Size Changed: ${it.width} x ${it.height}")
        }) {
        // 1. 相机层（适配FIT_CENTER）
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FIT_CENTER
                    implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                }
            },
            update = { previewView ->
                val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build()
                    val imageAnalysis = ImageAnalysis.Builder()
                        .setTargetResolution(android.util.Size(CAMERA_PREVIEW_WIDTH, CAMERA_PREVIEW_HEIGHT))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()

                    // 图像分析器（修正imageProxy关闭时机）
                    imageAnalysis.setAnalyzer(executor) { imageProxy ->
                        // 更新设备旋转角度
                        viewModel.deviceRotation = when (imageProxy.imageInfo.rotationDegrees) {
                            0 -> Surface.ROTATION_0
                            90 -> Surface.ROTATION_90
                            180 -> Surface.ROTATION_180
                            else -> Surface.ROTATION_270
                        }
                        // 更新预览尺寸
                        viewModel.previewSize = Size(
                            imageProxy.width.toFloat(),
                            imageProxy.height.toFloat()
                        )

                        handLandmarker?.let { detector ->
                            runCatching {
                                val bitmapBuffer = Bitmap.createBitmap(
                                    imageProxy.width,
                                    imageProxy.height,
                                    Bitmap.Config.ARGB_8888
                                )
                                bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer)

                                val matrix = Matrix()
                                matrix.postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                                matrix.postScale(-1f, 1f, imageProxy.width / 2f, imageProxy.height / 2f)

                                val rotatedBitmap = Bitmap.createBitmap(
                                    bitmapBuffer,
                                    0,
                                    0,
                                    imageProxy.width,
                                    imageProxy.height,
                                    matrix,
                                    true
                                )

                                val mpImage = BitmapImageBuilder(rotatedBitmap).build()
                                // 异步检测完成后关闭imageProxy
                                detector.detectAsync(mpImage, imageProxy.imageInfo.timestamp) { _, _ ->
                                    imageProxy.close()
                                }
                            }.onFailure {
                                Log.e(TAG, "图像处理失败", it)
                                imageProxy.close()
                            }
                        } ?: imageProxy.close() // 无检测器时直接关闭
                    }

                    preview.setSurfaceProvider(previewView.surfaceProvider)
                    val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageAnalysis
                        )
                        if (BuildConfig.DEBUG) Log.d(TAG, "Camera bound to lifecycle")
                    } catch (exc: Exception) {
                        Log.e(TAG, "Camera Use case binding failed", exc)
                        Toast.makeText(context, "相机绑定失败: ${exc.message}", Toast.LENGTH_SHORT).show()
                    }
                }, ContextCompat.getMainExecutor(context))
            }
        )

        // 2. 绘图层
        Canvas(modifier = Modifier.fillMaxSize()) {
            // 触发重绘
            val trigger = frameTrigger

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
                        style = Stroke(
                            width = stroke.size,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }
            }

            // 绘制粒子
            viewModel.particles.forEach { p ->
                drawCircle(
                    color = p.color.copy(alpha = p.alpha.coerceIn(0f, 1f)),
                    radius = p.size,
                    center = Offset(p.x, p.y)
                )
            }

            // 绘制UI菜单（按比例适配）
            if (viewModel.isMenuOpen) {
                drawRect(
                    color = Color(0xCC222222),
                    topLeft = Offset(50f, 50f),
                    size = Size(size.width - 100f, size.height * 0.4f)
                )

                val buttons = getUiButtons(size.width, size.height)
                buttons.forEach { btn ->
                    val isActive = (btn.type == "tool" && viewModel.mode == btn.value) ||
                            (btn.type == "size" && viewModel.brushSize == btn.value)

                    drawCircle(
                        color = if (btn.type == "color") btn.value as Color else if (isActive) Color.Gray else Color.DarkGray,
                        radius = btn.r,
                        center = Offset(btn.x, btn.y)
                    )
                    drawCircle(
                        color = Color.White,
                        radius = btn.r,
                        center = Offset(btn.x, btn.y),
                        style = Stroke(width = 2f)
                    )
                    // 绘制按钮文字
                    drawContext.canvas.nativeCanvas.drawText(
                        btn.label,
                        btn.x - 10f,
                        btn.y + 5f,
                        android.graphics.Paint().apply {
                            color = android.graphics.Color.WHITE
                            textSize = 20f
                            textAlign = android.graphics.Paint.Align.CENTER
                        }
                    )
                }
            }
        }

        // 状态文本
        Text(
            text = "模式: ${if (viewModel.mode == "drawing") "绘画" else "橡皮"} | 菜单: ${if (viewModel.isMenuOpen) "开" else "关"}",
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
                .background(Color.Black.copy(alpha = 0.5f))
        )
    }
}

// ====================== 核心逻辑处理 ======================
/**
 * 处理手部地标数据，映射坐标并识别手势
 */
fun processLandmarks(result: HandLandmarkerResult, viewModel: HandDrawingViewModel) {
    if (result.landmarks().isEmpty()) {
        viewModel.resetScaling()
        return
    }

    val firstHand = result.landmarks().firstOrNull() ?: return
    val screenW = viewModel.canvasSize.width
    val screenH = viewModel.canvasSize.height

    // 坐标映射（适配预览尺寸和设备旋转）
    fun toScreen(landmark: com.google.mediapipe.tasks.components.containers.NormalizedLandmark): DrawPoint {
        val (x, y) = when (viewModel.deviceRotation) {
            Surface.ROTATION_90 -> Pair(1 - landmark.y(), landmark.x())
            Surface.ROTATION_270 -> Pair(landmark.y(), 1 - landmark.x())
            Surface.ROTATION_180 -> Pair(1 - landmark.x(), 1 - landmark.y())
            else -> Pair(landmark.x(), landmark.y())
        }

        // 等比缩放射配Canvas
        val previewSize = viewModel.previewSize
        val scaleX = screenW / previewSize.width
        val scaleY = screenH / previewSize.height
        val scale = min(scaleX, scaleY)

        return DrawPoint(
            x = x * previewSize.width * scale,
            y = y * previewSize.height * scale
        )
    }

    val indexTip = toScreen(firstHand[8])
    val thumbTip = toScreen(firstHand[4])

    // 主线程更新UI状态
    Handler(Looper.getMainLooper()).post {
        // 1. 双手缩放逻辑
        if (result.landmarks().size == 2) {
            val h2 = result.landmarks()[1]
            val p1 = indexTip
            val p2 = toScreen(h2[8])
            val dist = sqrt((p1.x - p2.x).pow(2) + (p1.y - p2.y).pow(2))

            if (!viewModel.isScaling) {
                if (BuildConfig.DEBUG) Log.d(TAG, "Double Hand: Start Scaling")
                viewModel.isScaling = true
                viewModel.baseScaleDist = dist
            } else {
                val scaleFactor = 1f + (dist - viewModel.baseScaleDist) * 0.002f
                val cx = (p1.x + p2.x) / 2
                val cy = (p1.y + p2.y) / 2

                // 优化缩放：只处理非空笔画
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
            if (viewModel.isScaling && BuildConfig.DEBUG) Log.d(TAG, "Double Hand: End Scaling")
            viewModel.resetScaling()
        }

        // 2. 单手逻辑
        fun dist(i: Int, j: Int): Float {
            val p1 = firstHand[i]
            val p2 = firstHand[j]
            return sqrt((p1.x() - p2.x()).pow(2) + (p1.y() - p2.y()).pow(2))
        }

        // 修正手势识别阈值
        val isPalmOpen = dist(8, 5) > PALM_OPEN_THRESHOLD &&
                dist(12, 9) > PALM_OPEN_THRESHOLD &&
                dist(16, 13) > PALM_OPEN_THRESHOLD &&
                dist(20, 17) > PALM_OPEN_THRESHOLD

        val isPinch = dist(8, 4) < PINCH_THRESHOLD

        // 修正胜利手势判断（MediaPipe Y轴从上到下）
        val isVictory = firstHand[8].y() > firstHand[6].y() &&
                firstHand[12].y() > firstHand[10].y() &&
                firstHand[16].y() < firstHand[14].y()

        // A. 菜单开关（手掌打开）
        if (isPalmOpen && !viewModel.wasPalmOpen) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Gesture: Palm Open -> Toggle Menu")
            viewModel.isMenuOpen = !viewModel.isMenuOpen
            viewModel.isDrawing = false
        }
        viewModel.wasPalmOpen = isPalmOpen

        // B. 菜单交互（防抖处理）
        if (viewModel.isMenuOpen) {
            val buttons = getUiButtons(screenW, screenH)
            buttons.forEach { btn ->
                val d = sqrt((indexTip.x - btn.x).pow(2) + (indexTip.y - btn.y).pow(2))
                if (d < btn.r + ERASER_RADIUS_OFFSET) {
                    viewModel.triggerMenuButtonClick(btn)
                }
            }
        }
        // C. 绘图/擦除/消散
        else {
            if (isVictory) {
                if (BuildConfig.DEBUG) Log.d(TAG, "Gesture: Victory -> Dissolve")
                triggerDissolve(viewModel)
            } else if (isPinch) {
                val midX = (indexTip.x + thumbTip.x) / 2
                val midY = (indexTip.y + thumbTip.y) / 2
                viewModel.handLocation = DrawPoint(midX, midY)

                if (!viewModel.isDrawing) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "Gesture: Pinch Start -> New Stroke")
                    viewModel.isDrawing = true
                    viewModel.addStroke(
                        StrokeLine(
                            points = mutableListOf(DrawPoint(midX, midY)),
                            color = if (viewModel.mode == "eraser") Color.Transparent else viewModel.brushColor,
                            size = if (viewModel.mode == "eraser") viewModel.eraserSize else viewModel.brushSize,
                            isEraser = viewModel.mode == "eraser"
                        )
                    )
                } else {
                    if (viewModel.strokes.isNotEmpty()) {
                        val lastStroke = viewModel.strokes.last()
                        lastStroke.points.add(DrawPoint(midX, midY))

                        if (viewModel.mode == "eraser") {
                            eraseAt(midX, midY, viewModel.eraserSize, viewModel)
                        }
                    }
                }
            } else {
                if (viewModel.isDrawing && BuildConfig.DEBUG) Log.d(TAG, "Gesture: Pinch End")
                viewModel.isDrawing = false
                // 更新手部位置
                val midX = (indexTip.x + thumbTip.x) / 2
                val midY = (indexTip.y + thumbTip.y) / 2
                viewModel.handLocation = DrawPoint(midX, midY)
            }
        }
    }
}

/**
 * 触发笔画消散效果
 */
fun triggerDissolve(viewModel: HandDrawingViewModel) {
    viewModel.strokes.forEach { stroke ->
        // 间隔采样，减少粒子数量提升性能
        for (i in stroke.points.indices step 5) {
            val p = stroke.points[i]
            viewModel.addParticle(Particle(
                x = p.x,
                y = p.y,
                vx = (Math.random().toFloat() - 0.5f) * PARTICLE_VELOCITY_RANGE,
                vy = (Math.random().toFloat() * 5f) + 5f,
                alpha = 1f,
                color = stroke.color,
                size = stroke.size * PARTICLE_SIZE_SCALE
            ))
        }
    }
    viewModel.clearStrokes()
}

/**
 * 橡皮擦逻辑（优化版：只处理附近点，移除空笔画）
 */
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
    // 移除空笔画，减少内存占用
    viewModel.removeEmptyStrokes()
}

/**
 * 获取UI按钮（按屏幕比例适配）
 */
fun getUiButtons(canvasWidth: Float, canvasHeight: Float): List<UiButton> {
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

// ====================== BuildConfig 兼容（确保调试日志生效） ======================
// 如需关闭调试日志，在build.gradle中设置 buildConfigField "boolean", "DEBUG", "false"
object BuildConfig {
    const val DEBUG = true // 实际项目中由Gradle自动生成，此处为兼容
}