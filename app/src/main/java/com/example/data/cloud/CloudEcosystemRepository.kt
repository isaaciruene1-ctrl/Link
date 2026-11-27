package com.example.data.cloud

import android.content.Context
import com.example.R
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class CloudEcosystemRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String =
        auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing cloud sync.")

    fun observeDevices(userId: String): Flow<List<UserDeviceDoc>> = flow {
        require(userId.isNotBlank()) { "User ID cannot be blank." }
        val path = "users/$userId/devices"
        emitAll(
            db.collection("users").document(userId).collection("devices")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        UserDeviceDoc::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val toLog = firestoreError ?: (error as? Exception)
                    if (toLog != null && auth.currentUser?.uid == userId) {
                        handleFirestoreError(toLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    fun observeWorkspaces(userId: String): Flow<List<SharedWorkspaceDoc>> = flow {
        require(userId.isNotBlank()) { "User ID cannot be blank." }
        val path = "users/$userId/workspaces"
        emitAll(
            db.collection("users").document(userId).collection("workspaces")
                .orderBy("updatedAt", Query.Direction.DESCENDING)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        SharedWorkspaceDoc::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val toLog = firestoreError ?: (error as? Exception)
                    if (toLog != null && auth.currentUser?.uid == userId) {
                        handleFirestoreError(toLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    fun observeHandoffs(userId: String): Flow<List<CloudHandoffDoc>> = flow {
        require(userId.isNotBlank()) { "User ID cannot be blank." }
        val path = "users/$userId/handoffs"
        emitAll(
            db.collection("users").document(userId).collection("handoffs")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(20)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        CloudHandoffDoc::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val toLog = firestoreError ?: (error as? Exception)
                    if (toLog != null && auth.currentUser?.uid == userId) {
                        handleFirestoreError(toLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    fun observeRelaySignals(userId: String): Flow<List<RelaySignalDoc>> = flow {
        require(userId.isNotBlank()) { "User ID cannot be blank." }
        val path = "users/$userId/relaySignals"
        emitAll(
            db.collection("users").document(userId).collection("relaySignals")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(30)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        RelaySignalDoc::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val toLog = firestoreError ?: (error as? Exception)
                    if (toLog != null && auth.currentUser?.uid == userId) {
                        handleFirestoreError(toLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun upsertDevicePresence(
        deviceId: String,
        deviceName: String,
        manufacturer: String,
        model: String,
        deviceType: String,
        groupName: String,
        publicKeyFingerprint: String,
        batteryLevel: Int,
        charging: Boolean,
        networkType: String,
        online: Boolean,
        trusted: Boolean
    ): Result<Unit> {
        val safeId = sanitizeFirestoreId(deviceId)
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/devices/$safeId"
        return try {
            val uid = requireUserId()
            val safeName = deviceName.trim().take(60).ifBlank { "Android Device" }
            val safeGroup = if (groupName in ALLOWED_GROUPS) groupName else "My Devices"
            val safeType = if (deviceType in ALLOWED_DEVICE_TYPES) deviceType else "PHONE"
            val safeNet = if (networkType in ALLOWED_NETWORK_TYPES) networkType else "UNKNOWN"
            val safeFingerprint = publicKeyFingerprint.trim().take(128).ifBlank { safeId.padEnd(8, '0') }
            val safeBattery = batteryLevel.coerceIn(-1, 100).toLong()

            val docRef = db.collection("users").document(uid).collection("devices").document(safeId)
            docPath = docRef.path
            val existing = docRef.get().await()
            if (existing.exists()) {
                docRef.update(
                    mapOf(
                        "deviceName" to safeName,
                        "groupName" to safeGroup,
                        "batteryLevel" to safeBattery,
                        "charging" to charging,
                        "networkType" to safeNet,
                        "online" to online,
                        "trusted" to trusted,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                docRef.set(
                    mapOf(
                        "userId" to uid,
                        "deviceName" to safeName,
                        "manufacturer" to manufacturer.take(60),
                        "model" to model.take(60),
                        "deviceType" to safeType,
                        "groupName" to safeGroup,
                        "publicKeyFingerprint" to safeFingerprint,
                        "batteryLevel" to safeBattery,
                        "charging" to charging,
                        "networkType" to safeNet,
                        "online" to online,
                        "trusted" to trusted,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docPath)
            Result.failure(e)
        }
    }

    suspend fun removeDevice(deviceId: String): Result<Unit> {
        val safeId = sanitizeFirestoreId(deviceId)
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/devices/$safeId"
        return try {
            val uid = requireUserId()
            val docRef = db.collection("users").document(uid).collection("devices").document(safeId)
            docPath = docRef.path
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    suspend fun createWorkspace(
        title: String,
        notes: String,
        pinnedLink: String?,
        tasks: List<String>,
        activeDeviceName: String
    ): Result<Unit> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/workspaces"
        return try {
            val uid = requireUserId()
            val cleanTitle = title.trim().take(80)
            require(cleanTitle.isNotEmpty()) { "Workspace title must not be empty." }
            val docRef = db.collection("users").document(uid).collection("workspaces").document()
            docPath = docRef.path
            val payload = mapOf(
                "userId" to uid,
                "title" to cleanTitle,
                "notes" to notes.trim().take(2000),
                "pinnedLink" to pinnedLink?.trim()?.takeIf { it.isNotEmpty() }?.take(500),
                "tasks" to tasks.map { it.trim().take(120) }.filter { it.isNotEmpty() }.take(25),
                "activeDeviceName" to activeDeviceName.trim().take(60),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            ).filterValues { it != null } as Map<String, Any>
            docRef.set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updateWorkspace(
        workspaceId: String,
        title: String,
        notes: String,
        pinnedLink: String?,
        tasks: List<String>,
        activeDeviceName: String
    ): Result<Unit> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/workspaces/$workspaceId"
        return try {
            val uid = requireUserId()
            require(workspaceId.isNotBlank()) { "Workspace ID must not be blank." }
            val cleanTitle = title.trim().take(80)
            require(cleanTitle.isNotEmpty()) { "Workspace title must not be empty." }
            val docRef = db.collection("users").document(uid).collection("workspaces").document(workspaceId)
            docPath = docRef.path
            val payload = mutableMapOf<String, Any>(
                "title" to cleanTitle,
                "notes" to notes.trim().take(2000),
                "tasks" to tasks.map { it.trim().take(120) }.filter { it.isNotEmpty() }.take(25),
                "activeDeviceName" to activeDeviceName.trim().take(60),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            val cleanLink = pinnedLink?.trim()?.take(500)
            if (!cleanLink.isNullOrEmpty()) {
                payload["pinnedLink"] = cleanLink
            }
            docRef.update(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deleteWorkspace(workspaceId: String): Result<Unit> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/workspaces/$workspaceId"
        return try {
            val uid = requireUserId()
            val docRef = db.collection("users").document(uid).collection("workspaces").document(workspaceId)
            docPath = docRef.path
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    suspend fun publishHandoff(
        sourceDeviceId: String,
        sourceDeviceName: String,
        targetDeviceId: String,
        actionType: String,
        title: String,
        payload: String
    ): Result<Unit> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/handoffs"
        return try {
            val uid = requireUserId()
            val safeAction = if (actionType in ALLOWED_HANDOFF_ACTIONS) actionType else "OPEN_URL"
            val docRef = db.collection("users").document(uid).collection("handoffs").document()
            docPath = docRef.path
            docRef.set(
                mapOf(
                    "userId" to uid,
                    "sourceDeviceId" to sanitizeFirestoreId(sourceDeviceId),
                    "sourceDeviceName" to sourceDeviceName.trim().take(60).ifBlank { "Android Device" },
                    "targetDeviceId" to sanitizeFirestoreId(targetDeviceId),
                    "actionType" to safeAction,
                    "title" to title.trim().take(120).ifBlank { "Shared Handoff" },
                    "payload" to payload.trim().take(2000),
                    "consumed" to false,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun markHandoffConsumed(handoffId: String): Result<Unit> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/handoffs/$handoffId"
        return try {
            val uid = requireUserId()
            val docRef = db.collection("users").document(uid).collection("handoffs").document(handoffId)
            docPath = docRef.path
            docRef.update(
                mapOf(
                    "consumed" to true,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun sendRelaySignal(
        roomId: String,
        senderDeviceId: String,
        senderDeviceName: String,
        targetDeviceId: String,
        signalType: String,
        payload: String
    ): Result<Unit> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/relaySignals"
        return try {
            val uid = requireUserId()
            val safeType = if (signalType in ALLOWED_SIGNAL_TYPES) signalType else "ENCRYPTED_RELAY_PACKET"
            val docRef = db.collection("users").document(uid).collection("relaySignals").document()
            docPath = docRef.path
            docRef.set(
                mapOf(
                    "userId" to uid,
                    "roomId" to sanitizeFirestoreId(roomId),
                    "senderDeviceId" to sanitizeFirestoreId(senderDeviceId),
                    "senderDeviceName" to senderDeviceName.trim().take(60).ifBlank { "Android Device" },
                    "targetDeviceId" to sanitizeFirestoreId(targetDeviceId),
                    "signalType" to safeType,
                    "payload" to payload.take(8000).ifBlank { "{}" },
                    "consumed" to false,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun markRelaySignalConsumed(signalId: String): Result<Unit> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/relaySignals/$signalId"
        return try {
            val uid = requireUserId()
            val docRef = db.collection("users").document(uid).collection("relaySignals").document(signalId)
            docPath = docRef.path
            docRef.update(
                mapOf(
                    "consumed" to true,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    private fun sanitizeFirestoreId(raw: String): String =
        raw.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(128).ifBlank { "device_default" }

    companion object {
        private val ALLOWED_GROUPS = setOf("My Devices", "Work Devices", "Home Devices", "Temporary Devices")
        private val ALLOWED_DEVICE_TYPES = setOf("PHONE", "TABLET", "OTHER")
        private val ALLOWED_NETWORK_TYPES = setOf("WIFI", "CELLULAR", "OFFLINE", "UNKNOWN")
        private val ALLOWED_HANDOFF_ACTIONS = setOf("OPEN_URL", "OPEN_WORKSPACE", "CLIPBOARD_NOTE")
        private val ALLOWED_SIGNAL_TYPES = setOf(
            "OFFER",
            "ANSWER",
            "ICE_CANDIDATE",
            "ENCRYPTED_RELAY_PACKET",
            "WAKE_PING"
        )
    }
}
