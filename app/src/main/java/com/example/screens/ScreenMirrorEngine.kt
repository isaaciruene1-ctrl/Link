package com.example.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.example.connectivity.ConnectivityManagerEngine
import com.example.connectivity.WireFrameType
import com.example.data.local.ActivityLogDao
import com.example.data.local.ActivityLogEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

enum class StreamQualityPreset(
    val label: String,
    val width: Int,
    val height: Int,
    val jpegQuality: Int,
    val h264BitrateBps: Int
) {
    LOW("480p Low Latency", 480, 854, 48, 1_200_000),
    BALANCED("720p Balanced", 720, 1280, 62, 2_500_000),
    CRISP("1080p High Detail", 1080, 1920, 75, 4_500_000)
}

enum class StreamCodecMode(val label: String) {
    H264_HARDWARE("H.264 Hardware (MediaCodec)"),
    JPEG_ADAPTIVE("Adaptive JPEG Frames")
}

data class ScreenSessionUiState(
    val isBroadcasting: Boolean = false,
    val targetDeviceId: String? = null,
    val targetDeviceName: String? = null,
    val quality: StreamQualityPreset = StreamQualityPreset.BALANCED,
    val codecMode: StreamCodecMode = StreamCodecMode.H264_HARDWARE,
    val incomingFrame: Bitmap? = null,
    val isReceivingH264: Boolean = false,
    val incomingWidth: Int = 0,
    val incomingHeight: Int = 0,
    val incomingFramesCount: Long = 0L,
    val incomingSourceName: String? = null,
    val estimatedLatencyMs: Long? = null,
    val statusText: String = "Ready — select a paired device and grant screen capture consent"
)

class ScreenMirrorEngine(
    private val context: Context,
    private val connectivityEngine: ConnectivityManagerEngine,
    private val activityLogDao: ActivityLogDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var h264Encoder: MediaCodec? = null
    private var h264InputSurface: Surface? = null
    private var h264Decoder: MediaCodec? = null
    private var decoderSurface: Surface? = null
    private var decoderWidth = 0
    private var decoderHeight = 0
    private val encodingActive = AtomicBoolean(false)
    private var handlerThread: HandlerThread? = null
    private var lastFrameSentAt = 0L

    private val _state = MutableStateFlow(ScreenSessionUiState())
    val state: StateFlow<ScreenSessionUiState> = _state.asStateFlow()

    init {
        scope.launch {
            connectivityEngine.binaryEvents.collect { event ->
                when (event.frameType) {
                    WireFrameType.SCREEN_FRAME -> decodeIncomingJpegFrame(event.bytes)
                    WireFrameType.SCREEN_H264_FRAME -> decodeIncomingH264Frame(event.bytes)
                    else -> Unit
                }
            }
        }
    }

    fun setQuality(preset: StreamQualityPreset) {
        _state.value = _state.value.copy(quality = preset)
    }

    fun setCodecMode(mode: StreamCodecMode) {
        _state.value = _state.value.copy(codecMode = mode)
    }

    fun attachDecoderSurface(surface: Surface?) {
        decoderSurface = surface
        if (surface == null) {
            releaseDecoder()
        }
    }

    fun createCaptureConsentIntent(): Intent {
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        return manager.createScreenCaptureIntent()
    }

    fun beginProjectionCapture(
        resultCode: Int,
        data: Intent,
        targetDeviceId: String,
        targetDeviceName: String
    ) {
        if (resultCode != Activity.RESULT_OK) {
            _state.value = _state.value.copy(statusText = "Screen capture permission was declined.")
            return
        }

        stopProjection()
        val manager = context.getSystemService(MediaProjectionManager::class.java) ?: return
        val projection = manager.getMediaProjection(resultCode, data) ?: run {
            _state.value = _state.value.copy(statusText = "MediaProjection session unavailable.")
            return
        }

        val quality = _state.value.quality
        val selectedCodec = _state.value.codecMode
        val thread = HandlerThread("LinkScreenCapture").apply { start() }
        val handler = Handler(thread.looper)

        projection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    stopProjection()
                }
            },
            handler
        )

        val startedH264 = if (selectedCodec == StreamCodecMode.H264_HARDWARE) {
            startH264HardwarePipeline(projection, targetDeviceId, targetDeviceName, quality, handler)
        } else {
            false
        }

        if (!startedH264) {
            startAdaptiveJpegPipeline(projection, targetDeviceId, targetDeviceName, quality, handler)
        }

        mediaProjection = projection
        handlerThread = thread

        val activeLabel = if (startedH264) StreamCodecMode.H264_HARDWARE.label else StreamCodecMode.JPEG_ADAPTIVE.label
        _state.value = _state.value.copy(
            isBroadcasting = true,
            targetDeviceId = targetDeviceId,
            targetDeviceName = targetDeviceName,
            statusText = "Mirroring to $targetDeviceName ($activeLabel · ${quality.label})"
        )

        scope.launch {
            activityLogDao.insert(
                ActivityLogEntity(
                    logId = UUID.randomUUID().toString(),
                    category = "SCREEN",
                    deviceId = targetDeviceId,
                    summary = "Started $activeLabel stream to $targetDeviceName"
                )
            )
        }
    }

    private fun startH264HardwarePipeline(
        projection: MediaProjection,
        targetDeviceId: String,
        targetDeviceName: String,
        quality: StreamQualityPreset,
        handler: Handler
    ): Boolean {
        return runCatching {
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                quality.width,
                quality.height
            ).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, quality.h264BitrateBps)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            codec.start()

            val display = projection.createVirtualDisplay(
                "LINK_H264_${targetDeviceName.take(12)}",
                quality.width,
                quality.height,
                context.resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                handler
            )

            h264Encoder = codec
            h264InputSurface = surface
            virtualDisplay = display
            encodingActive.set(true)

            scope.launch {
                drainH264Encoder(codec, targetDeviceId, quality.width, quality.height)
            }
            true
        }.getOrElse { err ->
            Log.w(TAG, "Hardware H.264 unavailable, falling back to JPEG: ${err.localizedMessage}")
            false
        }
    }

    private fun drainH264Encoder(
        codec: MediaCodec,
        targetDeviceId: String,
        width: Int,
        height: Int
    ) {
        val info = MediaCodec.BufferInfo()
        while (encodingActive.get() && scope.isActive) {
            val outIndex = runCatching { codec.dequeueOutputBuffer(info, 15_000L) }.getOrDefault(-1)
            if (outIndex >= 0) {
                val outBuf = codec.getOutputBuffer(outIndex)
                if (outBuf != null && info.size > 0) {
                    val nalBytes = ByteArray(info.size)
                    outBuf.position(info.offset)
                    outBuf.limit(info.offset + info.size)
                    outBuf.get(nalBytes)

                    val now = System.currentTimeMillis()
                    val packet = ByteBuffer.allocate(20 + nalBytes.size)
                        .putLong(now)
                        .putInt(width)
                        .putInt(height)
                        .putInt(info.flags)
                        .put(nalBytes)
                        .array()

                    connectivityEngine.sendBinaryPayload(targetDeviceId, WireFrameType.SCREEN_H264_FRAME, packet)
                }
                runCatching { codec.releaseOutputBuffer(outIndex, false) }
            }
        }
    }

    private fun startAdaptiveJpegPipeline(
        projection: MediaProjection,
        targetDeviceId: String,
        targetDeviceName: String,
        quality: StreamQualityPreset,
        handler: Handler
    ) {
        val reader = ImageReader.newInstance(quality.width, quality.height, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val now = System.currentTimeMillis()
                if (now - lastFrameSentAt < 65L) {
                    return@setOnImageAvailableListener
                }
                lastFrameSentAt = now
                val plane = image.planes[0]
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                val rowPadding = rowStride - pixelStride * quality.width
                val bitmap = Bitmap.createBitmap(
                    quality.width + rowPadding / pixelStride,
                    quality.height,
                    Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(plane.buffer)
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, quality.width, quality.height)
                if (cropped !== bitmap) bitmap.recycle()

                val baos = ByteArrayOutputStream()
                cropped.compress(Bitmap.CompressFormat.JPEG, quality.jpegQuality, baos)
                cropped.recycle()
                val jpegBytes = baos.toByteArray()

                val packet = ByteBuffer.allocate(8 + jpegBytes.size)
                    .putLong(now)
                    .put(jpegBytes)
                    .array()

                connectivityEngine.sendBinaryPayload(targetDeviceId, WireFrameType.SCREEN_FRAME, packet)
            } catch (e: Exception) {
                Log.w(TAG, "Frame encode skipped: ${e.localizedMessage}")
            } finally {
                image.close()
            }
        }, handler)

        val display = projection.createVirtualDisplay(
            "LINK_Mirror_${targetDeviceName.take(12)}",
            quality.width,
            quality.height,
            context.resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler
        )

        imageReader = reader
        virtualDisplay = display
    }

    fun stopProjection() {
        encodingActive.set(false)
        runCatching { h264Encoder?.stop() }
        runCatching { h264Encoder?.release() }
        runCatching { h264InputSurface?.release() }
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        runCatching { mediaProjection?.stop() }
        runCatching { handlerThread?.quitSafely() }
        h264Encoder = null
        h264InputSurface = null
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
        handlerThread = null
        LinkScreenShareService.stop(context)
        _state.value = _state.value.copy(
            isBroadcasting = false,
            targetDeviceId = null,
            targetDeviceName = null,
            statusText = "Screen mirroring stopped"
        )
    }

    private fun decodeIncomingJpegFrame(bytes: ByteArray) {
        if (bytes.size <= 8) return
        val buf = ByteBuffer.wrap(bytes)
        val sentTs = buf.long
        val jpeg = ByteArray(buf.remaining())
        buf.get(jpeg)
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return
        val latency = (System.currentTimeMillis() - sentTs).coerceAtLeast(1L)
        _state.value = _state.value.copy(
            incomingFrame = bmp,
            isReceivingH264 = false,
            incomingFramesCount = _state.value.incomingFramesCount + 1,
            estimatedLatencyMs = latency,
            statusText = "Receiving Adaptive JPEG stream (~${latency}ms)"
        )
    }

    private fun decodeIncomingH264Frame(bytes: ByteArray) {
        if (bytes.size <= 20) return
        val buf = ByteBuffer.wrap(bytes)
        val sentTs = buf.long
        val width = buf.int
        val height = buf.int
        val flags = buf.int
        val nal = ByteArray(buf.remaining())
        buf.get(nal)

        val surface = decoderSurface
        if (surface != null && surface.isValid) {
            ensureH264Decoder(width, height, surface)
            h264Decoder?.let { dec ->
                runCatching {
                    val inIdx = dec.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val inputBuf = dec.getInputBuffer(inIdx)
                        inputBuf?.clear()
                        inputBuf?.put(nal)
                        dec.queueInputBuffer(inIdx, 0, nal.size, sentTs * 1000L, flags)
                    }
                    val info = MediaCodec.BufferInfo()
                    var outIdx = dec.dequeueOutputBuffer(info, 0L)
                    while (outIdx >= 0) {
                        dec.releaseOutputBuffer(outIdx, true)
                        outIdx = dec.dequeueOutputBuffer(info, 0L)
                    }
                }
            }
        }

        val latency = (System.currentTimeMillis() - sentTs).coerceAtLeast(1L)
        _state.value = _state.value.copy(
            isReceivingH264 = true,
            incomingWidth = width,
            incomingHeight = height,
            incomingFramesCount = _state.value.incomingFramesCount + 1,
            estimatedLatencyMs = latency,
            statusText = "Receiving H.264 MediaCodec stream (${width}x$height · ~${latency}ms)"
        )
    }

    private fun ensureH264Decoder(width: Int, height: Int, surface: Surface) {
        if (h264Decoder != null && decoderWidth == width && decoderHeight == height) return
        releaseDecoder()
        runCatching {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            val dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            dec.configure(format, surface, null, 0)
            dec.start()
            h264Decoder = dec
            decoderWidth = width
            decoderHeight = height
        }.onFailure {
            Log.w(TAG, "Failed to start H.264 decoder: ${it.localizedMessage}")
        }
    }

    private fun releaseDecoder() {
        runCatching { h264Decoder?.stop() }
        runCatching { h264Decoder?.release() }
        h264Decoder = null
        decoderWidth = 0
        decoderHeight = 0
    }

    companion object {
        private const val TAG = "ScreenMirrorEngine"
    }
}
