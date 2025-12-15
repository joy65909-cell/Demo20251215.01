package com.example.demo20251215

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlinx.coroutines.delay
import java.util.concurrent.Executors
import kotlin.math.pow
import kotlin.math.sqrt
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.toSize

// --- 数据模型 ---

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
data class UiButton(val label: String, val x: Float, val y: Float, val r: Float, val type: String, val value: Any)

// 全局状态类
class AppState {
    var strokes = mutableStateListOf<StrokeLine>()
    var particles = mutableStateListOf<Particle>()
    var brushColor by mutableStateOf(Color.Green)
    var brushSize by mutableStateOf(10f)
    var eraserSize by mutableStateOf(60f)
    var mode by mutableStateOf("drawing") // drawing, eraser
    var isMenuOpen by mutableStateOf(false)
    var isDrawing by mutableStateOf(false)
    var menuCooldown = 0
    var canvasSize by mutableStateOf(Size(1080f, 1920f))
    // 缩放相关
    var isScaling = false
    var baseScaleDist = 0f
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HandDrawingApp()
        }
    }
}

@Composable
fun HandDrawingApp() {
    val context = LocalContext.current
    var hasCameraPermission by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted -> hasCameraPermission = granted }
    )

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            hasCameraPermission = true
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    if (hasCameraPermission) {
        CameraWithOverlay()
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("需要相机权限来运行应用")
        }
    }
}

@Composable
fun CameraWithOverlay() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val appState = remember { AppState() }

    // 用于 Canvas 绘制的 State，确保每一帧都重绘
    var frameTrigger by remember { mutableStateOf(0L) }

    // MediaPipe 初始化
    val handLandmarker = remember {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("hand_landmarker.task") // 确保文件在 assets 目录
            .build()

        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setMinHandDetectionConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setNumHands(2)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setResultListener { result, _ -> processLandmarks(result, appState) }
            .build()

        try {
            HandLandmarker.createFromOptions(context, options)
        } catch (e: Exception) {
            Log.e("MediaPipe", "Error initializing: ${e.message}")
            null
        }
    }

    // 粒子动画循环
    LaunchedEffect(Unit) {
        while(true) {
            val iter = appState.particles.iterator()
            while (iter.hasNext()) {
                val p = iter.next()
                p.x += p.vx
                p.y += p.vy
                p.vx += (Math.random().toFloat() - 0.5f) * 0.5f
                p.alpha -= 0.02f
                if (p.alpha <= 0) iter.remove()
            }
            if (appState.menuCooldown > 0) appState.menuCooldown--
            frameTrigger++ // 触发重绘
            delay(16) // ~60FPS
        }
    }

    Box(modifier = Modifier
        .fillMaxSize()
        .onSizeChanged { appState.canvasSize = it.toSize() } // 【新增】获取真实尺寸传给 State
    ) {
        // 1. 相机层
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
            },
            update = { previewView ->
                val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build()
                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()

                    imageAnalysis.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                        handLandmarker?.let { detector ->
                            val bitmapBuffer = Bitmap.createBitmap(
                                imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888
                            )
                            bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer)

                            // 相机默认旋转处理 (通常前置是270或90，这里简单处理)
                            // 实际项目中需要根据 display rotation 精确计算
                            val matrix = android.graphics.Matrix()
                            matrix.postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                            // 前置摄像头需要水平镜像
                            matrix.postScale(-1f, 1f, imageProxy.width / 2f, imageProxy.height / 2f)

                            val rotatedBitmap = Bitmap.createBitmap(
                                bitmapBuffer, 0, 0, imageProxy.width, imageProxy.height, matrix, true
                            )

                            val mpImage = BitmapImageBuilder(rotatedBitmap).build()
                            detector.detectAsync(mpImage, System.currentTimeMillis())
                        }
                        imageProxy.close()
                    }

                    preview.setSurfaceProvider(previewView.surfaceProvider)
                    val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner, cameraSelector, preview, imageAnalysis
                        )
                    } catch (exc: Exception) {
                        Log.e("Camera", "Use case binding failed", exc)
                    }
                }, ContextCompat.getMainExecutor(context))
            }
        )

        // 2. 绘图层 (Canvas)
        Canvas(modifier = Modifier.fillMaxSize()) {
            // 只是为了读取 frameTrigger 触发重绘
            val trigger = frameTrigger

            // 绘制笔画
            appState.strokes.forEach { stroke ->
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
            appState.particles.forEach { p ->
                drawCircle(
                    color = p.color.copy(alpha = p.alpha.coerceIn(0f, 1f)),
                    radius = p.size,
                    center = Offset(p.x, p.y)
                )
            }

            // 绘制 UI 菜单
            if (appState.isMenuOpen) {
                drawRect(
                    color = Color(0xCC222222),
                    topLeft = Offset(50f, 50f),
                    size = Size(size.width - 100f, size.height * 0.4f)
                )

                val buttons = getUiButtons(size.width, size.height)
                buttons.forEach { btn ->
                    // 按钮背景
                    if (btn.type == "color") {
                        drawCircle(color = btn.value as Color, radius = btn.r, center = Offset(btn.x, btn.y))
                    } else {
                        val isActive = (btn.type == "tool" && appState.mode == btn.value) ||
                                (btn.type == "size" && appState.brushSize == btn.value)
                        drawCircle(
                            color = if (isActive) Color.Gray else Color.DarkGray,
                            radius = btn.r,
                            center = Offset(btn.x, btn.y)
                        )
                    }
                    // 选中框 (简单模拟)
                    drawCircle(
                        color = Color.White,
                        radius = btn.r,
                        center = Offset(btn.x, btn.y),
                        style = Stroke(width = 2f)
                    )
                }
            }
        }

        // 简单的状态文本
        Text(
            text = "模式: ${if(appState.mode == "drawing") "绘画" else "橡皮"} | 菜单: ${if(appState.isMenuOpen) "开" else "关"}",
            color = Color.White,
            modifier = Modifier.align(Alignment.BottomStart).padding(16.dp).background(Color.Black.copy(alpha=0.5f))
        )
    }
}

// --- 逻辑处理 ---

fun processLandmarks(result: HandLandmarkerResult, state: AppState) {
    if (result.landmarks().isEmpty()) return

// 【修改】不再写死，而是从 state 里取
    val screenW = state.canvasSize.width
    val screenH = state.canvasSize.height

    // 镜像已经由 Bitmap 处理了，所以这里 x 不需要 1-x
    fun toScreen(p: com.google.mediapipe.tasks.components.containers.NormalizedLandmark): DrawPoint {
        return DrawPoint(p.x() * screenW, p.y() * screenH)
    }

    val landmarks = result.landmarks()[0] // 第一只手
    val indexTip = toScreen(landmarks[8])
    val thumbTip = toScreen(landmarks[4])

    val post = Handler(Looper.getMainLooper()).post {
        // 1. 双手缩放逻辑
        if (result.landmarks().size == 2) {
            val h2 = result.landmarks()[1]
            val p1 = indexTip
            val p2 = toScreen(h2[8])
            val dist = sqrt((p1.x - p2.x).pow(2) + (p1.y - p2.y).pow(2))

            if (!state.isScaling) {
                state.isScaling = true
                state.baseScaleDist = dist
            } else {
                val factor = 1f + (dist - state.baseScaleDist) * 0.002f
                // 应用缩放
                val cx = (p1.x + p2.x) / 2
                val cy = (p1.y + p2.y) / 2

                state.strokes.forEach { s ->
                    s.size = (s.size * factor).coerceIn(1f, 100f)
                    s.points.forEach { pt ->
                        pt.x = cx + (pt.x - cx) * factor
                        pt.y = cy + (pt.y - cy) * factor
                    }
                }
                state.baseScaleDist = dist
            }
            return@post // 双手操作时不进行单手逻辑
        } else {
            state.isScaling = false
        }

        // 2. 单手逻辑

        // 辅助函数
        fun dist(i: Int, j: Int): Float {
            val p1 = landmarks[i]
            val p2 = landmarks[j]
            return sqrt((p1.x() - p2.x()).pow(2) + (p1.y() - p2.y()).pow(2))
        }

        val isPalmOpen =
            (dist(8, 5) > 0.1 && dist(12, 9) > 0.1 && dist(16, 13) > 0.1 && dist(20, 17) > 0.1)
        val isPinch = dist(8, 4) < 0.05
        val isVictory =
            (landmarks[8].y() < landmarks[6].y() && landmarks[12].y() < landmarks[10].y() && landmarks[16].y() > landmarks[14].y())

        // A. 菜单开关
        if (isPalmOpen && state.menuCooldown == 0) {
            state.isMenuOpen = !state.isMenuOpen
            state.menuCooldown = 30
            state.isDrawing = false
        }

        // B. 菜单交互
        if (state.isMenuOpen) {
            // 检测食指是否触碰按钮 (简单距离检测)
            val buttons = getUiButtons(screenW, screenH)
            buttons.forEach { btn ->
                val d = sqrt((indexTip.x - btn.x).pow(2) + (indexTip.y - btn.y).pow(2))
                if (d < btn.r + 20) { // 稍微大一点的判定区
                    if (btn.type == "color") {
                        state.brushColor = btn.value as Color
                        state.mode = "drawing"
                    } else if (btn.type == "tool") {
                        state.mode = btn.value as String
                    } else if (btn.type == "size") {
                        state.brushSize = btn.value as Float
                    }
                }
            }
        }
        // C. 绘图/擦除/消散
        else {
            if (isVictory) {
                triggerDissolve(state)
            } else if (isPinch) {
                val midX = (indexTip.x + thumbTip.x) / 2
                val midY = (indexTip.y + thumbTip.y) / 2

                if (!state.isDrawing) {
                    state.isDrawing = true
                    state.strokes.add(
                        StrokeLine(
                            points = mutableListOf(DrawPoint(midX, midY)),
                            color = if (state.mode == "eraser") Color.Transparent else state.brushColor,
                            size = if (state.mode == "eraser") state.eraserSize else state.brushSize,
                            isEraser = state.mode == "eraser"
                        )
                    )
                } else {
                    if (state.strokes.isNotEmpty()) {
                        val lastStroke = state.strokes.last()
                        lastStroke.points.add(DrawPoint(midX, midY))

                        if (state.mode == "eraser") {
                            eraseAt(midX, midY, state.eraserSize, state)
                        }
                    }
                }
            } else {
                state.isDrawing = false
            }
        }
    }

}

    fun triggerDissolve(state: AppState) {
    state.strokes.forEach { stroke ->
        for (i in stroke.points.indices step 5) { // 采样
            val p = stroke.points[i]
            state.particles.add(Particle(
                p.x, p.y,
                (Math.random().toFloat() - 0.5f) * 10f,
                (Math.random().toFloat() * 5f) + 5f,
                1f, stroke.color, stroke.size / 2
            ))
        }
    }
    state.strokes.clear()
}

fun eraseAt(x: Float, y: Float, r: Float, state: AppState) {
    try {
        state.strokes.forEach { stroke ->
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
    } catch (e: Exception) {}
}

fun getUiButtons(w: Float, h: Float): List<UiButton> {
    return listOf(
        UiButton("红", 150f, 200f, 40f, "color", Color.Red),
        UiButton("绿", 250f, 200f, 40f, "color", Color.Green),
        UiButton("蓝", 350f, 200f, 40f, "color", Color.Blue),
        UiButton("笔", 150f, 350f, 50f, "tool", "drawing"),
        UiButton("擦", 280f, 350f, 50f, "tool", "eraser"),
        UiButton("大", 150f, 500f, 30f, "size", 20f),
        UiButton("小", 250f, 500f, 20f, "size", 5f),
    )
}