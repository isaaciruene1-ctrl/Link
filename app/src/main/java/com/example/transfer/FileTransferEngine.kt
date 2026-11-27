package com.example.transfer

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Log
import com.example.connectivity.ConnectivityManagerEngine
import com.example.connectivity.WireFrameType
import com.example.data.local.ActivityLogDao
import com.example.data.local.ActivityLogEntity
import com.example.data.local.TransferRecordDao
import com.example.data.local.TransferRecordEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class ActiveTransferProgress(
    val transferId: String,
    val fileName: String,
    val peerName: String,
    val direction: String,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val speedBytesPerSec: Long,
    val etaSeconds: Long,
    val status: String
)

private class IncomingFileSink(
    val transferId: String,
    val peerDeviceId: String,
    val peerName: String,
    val fileName: String,
    val mimeType: String,
    val totalBytes: Long,
    val expectedSha256: String,
    val targetFile: File,
    val output: BufferedOutputStream,
    val digest: MessageDigest,
    var writtenBytes: Long = 0L,
    val startedAt: Long = System.currentTimeMillis()
)

class FileTransferEngine(
    private val context: Context,
    private val connectivityEngine: ConnectivityManagerEngine,
    private val transferRecordDao: TransferRecordDao,
    private val activityLogDao: ActivityLogDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val pausedIds = ConcurrentHashMap.newKeySet<String>()
    private val incomingSinks = ConcurrentHashMap<String, IncomingFileSink>()

    private val _activeProgress = MutableStateFlow<Map<String, ActiveTransferProgress>>(emptyMap())
    val activeProgress: StateFlow<Map<String, ActiveTransferProgress>> = _activeProgress.asStateFlow()

    val transferHistory: Flow<List<TransferRecordEntity>> = transferRecordDao.observeTransfers()

    init {
        observeIncomingWireEvents()
    }

    private fun observeIncomingWireEvents() {
        scope.launch {
            connectivityEngine.controlEvents.collect { event ->
                when (event.type) {
                    "FILE_META" -> handleIncomingMeta(event.peerDeviceId, event.peerName, event.payload)
                    "FILE_COMPLETE" -> handleIncomingComplete(event.peerDeviceId, event.payload)
                    "FILE_CANCEL" -> handleRemoteCancel(event.payload.optString("transferId"))
                }
            }
        }
        scope.launch {
            connectivityEngine.binaryEvents.collect { event ->
                if (event.frameType == WireFrameType.FILE_CHUNK) {
                    handleIncomingChunk(event.bytes)
                }
            }
        }
    }

    fun sendFiles(uris: List<Uri>, targetDeviceIds: List<String>, peerNamesById: Map<String, String>) {
        uris.forEach { uri ->
            targetDeviceIds.forEach { peerId ->
                val peerName = peerNamesById[peerId] ?: "Connected Device"
                startSendSingleFile(uri, peerId, peerName)
            }
        }
    }

    private fun startSendSingleFile(uri: Uri, targetDeviceId: String, targetDeviceName: String) {
        val transferId = UUID.randomUUID().toString()
        val job = scope.launch {
            val meta = resolveUriMetadata(uri)
            val fileName = meta.first
            val totalBytes = meta.second
            val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"

            transferRecordDao.upsert(
                TransferRecordEntity(
                    transferId = transferId,
                    peerDeviceId = targetDeviceId,
                    peerDeviceName = targetDeviceName,
                    direction = "OUTGOING",
                    fileName = fileName,
                    mimeType = mimeType,
                    totalBytes = totalBytes,
                    transferredBytes = 0L,
                    status = "IN_PROGRESS",
                    localUri = uri.toString()
                )
            )

            try {
                val sha256 = computeUriSha256(uri)
                val metaSent = connectivityEngine.sendControlMessage(
                    targetDeviceId,
                    "FILE_META",
                    JSONObject().apply {
                        put("transferId", transferId)
                        put("fileName", fileName)
                        put("mimeType", mimeType)
                        put("totalBytes", totalBytes)
                        put("sha256", sha256)
                    }
                )
                if (!metaSent) {
                    markTransferFailed(transferId, targetDeviceId, targetDeviceName, "OUTGOING", fileName, mimeType, totalBytes, uri.toString(), "Device is not connected over local Wi-Fi.")
                    return@launch
                }

                val startTime = System.currentTimeMillis()
                var sentBytes = 0L
                val buffer = ByteArray(CHUNK_SIZE_BYTES)
                val stream = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Cannot open selected file URI.")

                BufferedInputStream(stream, CHUNK_SIZE_BYTES).use { bis ->
                    while (true) {
                        if (pausedIds.contains(transferId)) {
                            transferRecordDao.upsert(
                                TransferRecordEntity(
                                    transferId = transferId,
                                    peerDeviceId = targetDeviceId,
                                    peerDeviceName = targetDeviceName,
                                    direction = "OUTGOING",
                                    fileName = fileName,
                                    mimeType = mimeType,
                                    totalBytes = totalBytes,
                                    transferredBytes = sentBytes,
                                    sha256Checksum = sha256,
                                    status = "PAUSED",
                                    localUri = uri.toString()
                                )
                            )
                            return@launch
                        }

                        val read = bis.read(buffer)
                        if (read <= 0) break

                        val idBytes = transferId.toByteArray(Charsets.UTF_8)
                        val packet = ByteBuffer.allocate(4 + idBytes.size + 8 + 4 + read)
                            .putInt(idBytes.size)
                            .put(idBytes)
                            .putLong(sentBytes)
                            .putInt(read)
                            .put(buffer, 0, read)
                            .array()

                        val ok = connectivityEngine.sendBinaryPayload(
                            targetDeviceId,
                            WireFrameType.FILE_CHUNK,
                            packet
                        )
                        if (!ok) {
                            throw IllegalStateException("Encrypted socket interrupted during transfer.")
                        }

                        sentBytes += read
                        updateLiveProgress(transferId, fileName, targetDeviceName, "OUTGOING", sentBytes, totalBytes, startTime, "IN_PROGRESS")
                    }
                }

                connectivityEngine.sendControlMessage(
                    targetDeviceId,
                    "FILE_COMPLETE",
                    JSONObject().apply {
                        put("transferId", transferId)
                        put("sha256", sha256)
                    }
                )

                transferRecordDao.upsert(
                    TransferRecordEntity(
                        transferId = transferId,
                        peerDeviceId = targetDeviceId,
                        peerDeviceName = targetDeviceName,
                        direction = "OUTGOING",
                        fileName = fileName,
                        mimeType = mimeType,
                        totalBytes = totalBytes.coerceAtLeast(sentBytes),
                        transferredBytes = sentBytes,
                        sha256Checksum = sha256,
                        checksumVerified = true,
                        status = "COMPLETED",
                        localUri = uri.toString(),
                        completedAt = System.currentTimeMillis()
                    )
                )
                activityLogDao.insert(
                    ActivityLogEntity(
                        logId = UUID.randomUUID().toString(),
                        category = "TRANSFER",
                        deviceId = targetDeviceId,
                        summary = "Sent $fileName (${formatBytes(sentBytes)}) to $targetDeviceName"
                    )
                )
                removeLiveProgress(transferId)
            } catch (e: Exception) {
                Log.e(TAG, "File send failed", e)
                markTransferFailed(transferId, targetDeviceId, targetDeviceName, "OUTGOING", fileName, mimeType, totalBytes, uri.toString(), e.localizedMessage ?: "Transfer failed")
            } finally {
                activeJobs.remove(transferId)
            }
        }
        activeJobs[transferId] = job
    }

    fun cancelTransfer(transferId: String) {
        pausedIds.remove(transferId)
        activeJobs.remove(transferId)?.cancel()
        removeLiveProgress(transferId)
        scope.launch {
            val existing = transferRecordDao.getById(transferId) ?: return@launch
            connectivityEngine.sendControlMessage(
                existing.peerDeviceId,
                "FILE_CANCEL",
                JSONObject().put("transferId", transferId)
            )
            transferRecordDao.upsert(existing.copy(status = "CANCELLED"))
        }
    }

    fun pauseTransfer(transferId: String) {
        pausedIds.add(transferId)
        removeLiveProgress(transferId)
    }

    fun retryTransfer(record: TransferRecordEntity) {
        if (record.direction == "OUTGOING") {
            pausedIds.remove(record.transferId)
            val uri = runCatching { Uri.parse(record.localUri) }.getOrNull() ?: return
            startSendSingleFile(uri, record.peerDeviceId, record.peerDeviceName)
        }
    }

    fun clearHistory() {
        scope.launch { transferRecordDao.clearCompleted() }
    }

    private suspend fun handleIncomingMeta(peerDeviceId: String, peerName: String, payload: JSONObject) {
        val transferId = payload.optString("transferId").ifBlank { return }
        val fileName = payload.optString("fileName", "link_file_${System.currentTimeMillis()}")
            .replace(Regex("[^a-zA-Z0-9._\\- ]"), "_")
        val mimeType = payload.optString("mimeType", "application/octet-stream")
        val totalBytes = payload.optLong("totalBytes", 0L)
        val sha256 = payload.optString("sha256", "")

        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "LINK").apply { mkdirs() }
        val targetFile = deduplicateFile(dir, fileName)
        val fos = BufferedOutputStream(FileOutputStream(targetFile), CHUNK_SIZE_BYTES)
        val sink = IncomingFileSink(
            transferId = transferId,
            peerDeviceId = peerDeviceId,
            peerName = peerName,
            fileName = targetFile.name,
            mimeType = mimeType,
            totalBytes = totalBytes,
            expectedSha256 = sha256,
            targetFile = targetFile,
            output = fos,
            digest = MessageDigest.getInstance("SHA-256")
        )
        incomingSinks[transferId] = sink
        transferRecordDao.upsert(
            TransferRecordEntity(
                transferId = transferId,
                peerDeviceId = peerDeviceId,
                peerDeviceName = peerName,
                direction = "INCOMING",
                fileName = targetFile.name,
                mimeType = mimeType,
                totalBytes = totalBytes,
                transferredBytes = 0L,
                sha256Checksum = sha256,
                status = "IN_PROGRESS",
                localUri = Uri.fromFile(targetFile).toString()
            )
        )
    }

    private fun handleIncomingChunk(packet: ByteArray) {
        val buf = ByteBuffer.wrap(packet)
        if (buf.remaining() < 16) return
        val idLen = buf.int
        if (idLen <= 0 || idLen > 128 || buf.remaining() < idLen + 12) return
        val idBytes = ByteArray(idLen)
        buf.get(idBytes)
        val transferId = String(idBytes, Charsets.UTF_8)
        val sink = incomingSinks[transferId] ?: return
        buf.long // offset
        val chunkLen = buf.int
        if (chunkLen <= 0 || chunkLen > buf.remaining()) return
        val data = ByteArray(chunkLen)
        buf.get(data)

        runCatching {
            sink.output.write(data)
            sink.digest.update(data)
            sink.writtenBytes += chunkLen
            updateLiveProgress(
                transferId,
                sink.fileName,
                sink.peerName,
                "INCOMING",
                sink.writtenBytes,
                sink.totalBytes,
                sink.startedAt,
                "IN_PROGRESS"
            )
        }
    }

    private suspend fun handleIncomingComplete(peerDeviceId: String, payload: JSONObject) {
        val transferId = payload.optString("transferId")
        val sink = incomingSinks.remove(transferId) ?: return
        runCatching {
            sink.output.flush()
            sink.output.close()
        }
        val actualSha = sink.digest.digest().joinToString("") { "%02x".format(it) }
        val verified = sink.expectedSha256.isNotBlank() && actualSha.equals(sink.expectedSha256, ignoreCase = true)

        transferRecordDao.upsert(
            TransferRecordEntity(
                transferId = transferId,
                peerDeviceId = peerDeviceId,
                peerDeviceName = sink.peerName,
                direction = "INCOMING",
                fileName = sink.fileName,
                mimeType = sink.mimeType,
                totalBytes = sink.totalBytes.coerceAtLeast(sink.writtenBytes),
                transferredBytes = sink.writtenBytes,
                sha256Checksum = actualSha,
                checksumVerified = verified,
                status = if (verified) "COMPLETED" else "FAILED",
                localUri = Uri.fromFile(sink.targetFile).toString(),
                completedAt = System.currentTimeMillis(),
                errorMessage = if (verified) null else "SHA-256 verification mismatch"
            )
        )
        activityLogDao.insert(
            ActivityLogEntity(
                logId = UUID.randomUUID().toString(),
                category = "TRANSFER",
                deviceId = peerDeviceId,
                summary = if (verified) "Received ${sink.fileName} (SHA-256 verified)" else "Checksum mismatch for ${sink.fileName}"
            )
        )
        removeLiveProgress(transferId)
    }

    private suspend fun handleRemoteCancel(transferId: String) {
        val sink = incomingSinks.remove(transferId) ?: return
        runCatching { sink.output.close(); sink.targetFile.delete() }
        removeLiveProgress(transferId)
        transferRecordDao.getById(transferId)?.let {
            transferRecordDao.upsert(it.copy(status = "CANCELLED"))
        }
    }

    private suspend fun markTransferFailed(
        transferId: String,
        peerDeviceId: String,
        peerName: String,
        direction: String,
        fileName: String,
        mimeType: String,
        totalBytes: Long,
        localUri: String,
        error: String
    ) {
        removeLiveProgress(transferId)
        transferRecordDao.upsert(
            TransferRecordEntity(
                transferId = transferId,
                peerDeviceId = peerDeviceId,
                peerDeviceName = peerName,
                direction = direction,
                fileName = fileName,
                mimeType = mimeType,
                totalBytes = totalBytes,
                transferredBytes = 0L,
                status = "FAILED",
                localUri = localUri,
                errorMessage = error
            )
        )
    }

    private fun updateLiveProgress(
        transferId: String,
        fileName: String,
        peerName: String,
        direction: String,
        transferred: Long,
        total: Long,
        startedAt: Long,
        status: String
    ) {
        val elapsedMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
        val speedBps = (transferred * 1000L) / elapsedMs
        val remaining = (total - transferred).coerceAtLeast(0L)
        val etaSec = if (speedBps > 0) remaining / speedBps else 0L
        _activeProgress.value = _activeProgress.value + (
            transferId to ActiveTransferProgress(
                transferId = transferId,
                fileName = fileName,
                peerName = peerName,
                direction = direction,
                bytesTransferred = transferred,
                totalBytes = total,
                speedBytesPerSec = speedBps,
                etaSeconds = etaSec,
                status = status
            )
        )
    }

    private fun removeLiveProgress(transferId: String) {
        _activeProgress.value = _activeProgress.value - transferId
    }

    private fun resolveUriMetadata(uri: Uri): Pair<String, Long> {
        var name = "shared_file_${System.currentTimeMillis()}"
        var size = 0L
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: name
                if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
            }
        }
        return name to size
    }

    private fun computeUriSha256(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(CHUNK_SIZE_BYTES)
        context.contentResolver.openInputStream(uri)?.use { stream ->
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun computeSha256ForFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(CHUNK_SIZE_BYTES)
        FileInputStream(file).use { fis ->
            while (true) {
                val read = fis.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun deduplicateFile(dir: File, originalName: String): File {
        var candidate = File(dir, originalName)
        if (!candidate.exists()) return candidate
        val dot = originalName.lastIndexOf('.')
        val base = if (dot > 0) originalName.substring(0, dot) else originalName
        val ext = if (dot > 0) originalName.substring(dot) else ""
        var index = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base ($index)$ext")
            index++
        }
        return candidate
    }

    companion object {
        private const val TAG = "FileTransferEngine"
        private const val CHUNK_SIZE_BYTES = 65_536

        fun formatBytes(bytes: Long): String {
            if (bytes < 1024L) return "$bytes B"
            val kb = bytes / 1024.0
            if (kb < 1024.0) return "%.1f KB".format(kb)
            val mb = kb / 1024.0
            if (mb < 1024.0) return "%.1f MB".format(mb)
            return "%.2f GB".format(mb / 1024.0)
        }
    }
}
