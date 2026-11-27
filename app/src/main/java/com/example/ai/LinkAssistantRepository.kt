package com.example.ai

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

enum class AssistantActionType {
    ANSWER_INFO,
    SUMMARIZE_NOTIFICATIONS,
    SEND_CLIPBOARD,
    CREATE_HANDOFF,
    CREATE_WORKSPACE,
    LOCATE_DEVICE,
    START_DISCOVERY
}

data class AssistantStructuredReply(
    val replyText: String,
    val actionType: AssistantActionType,
    val targetDeviceName: String?,
    val payloadTitle: String?,
    val payloadBody: String?,
    val requiresConfirmation: Boolean
)

data class EcosystemContextSnapshot(
    val localDeviceName: String,
    val localBatteryPercent: Int,
    val localNetworkType: String,
    val connectedDevicesSummary: String,
    val pairedDevicesSummary: String,
    val recentTransfersSummary: String,
    val notificationCount: Int,
    val recentNotificationTitles: String,
    val workspaceCount: Int
)

class LinkAssistantRepository {

    private val actionSchema = Schema.obj(
        properties = mapOf(
            "replyText" to Schema.string(description = "Helpful concise answer for the user grounded only in the provided device context."),
            "actionType" to Schema.enumeration(
                values = AssistantActionType.entries.map { it.name },
                description = "The defined LINK ecosystem action to propose or perform."
            ),
            "targetDeviceName" to Schema.string(description = "Matching target device name if applicable", nullable = true),
            "payloadTitle" to Schema.string(description = "Workspace title or handoff title if applicable", nullable = true),
            "payloadBody" to Schema.string(description = "URL, note, or summary payload if applicable", nullable = true),
            "requiresConfirmation" to Schema.boolean(description = "True when modifying state, creating a workspace, sending clipboard, or ringing a device.")
        ),
        optionalProperties = listOf("targetDeviceName", "payloadTitle", "payloadBody")
    )

    private val model by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = actionSchema
            }
        )
    }

    suspend fun askAssistant(
        userPrompt: String,
        snapshot: EcosystemContextSnapshot
    ): Result<AssistantStructuredReply> {
        return try {
            val prompt = buildString {
                appendLine("You are the LINK Android Device Ecosystem Assistant.")
                appendLine("Never invent device names, battery percentages, or files not present in the context below.")
                appendLine("If the user asks to execute a state-changing action (SEND_CLIPBOARD, CREATE_HANDOFF, CREATE_WORKSPACE, LOCATE_DEVICE), set requiresConfirmation = true.")
                appendLine("Context:")
                appendLine("- Local device: ${snapshot.localDeviceName}, Battery: ${snapshot.localBatteryPercent}%, Network: ${snapshot.localNetworkType}")
                appendLine("- Connected devices: ${snapshot.connectedDevicesSummary}")
                appendLine("- Paired devices: ${snapshot.pairedDevicesSummary}")
                appendLine("- Recent transfers: ${snapshot.recentTransfersSummary}")
                appendLine("- Notifications (${snapshot.notificationCount}): ${snapshot.recentNotificationTitles}")
                appendLine("- Shared workspaces count: ${snapshot.workspaceCount}")
                appendLine("User request: $userPrompt")
            }

            val response = withTimeout(60_000L) {
                model.generateContent(prompt)
            }
            val rawJson = response.text ?: throw IllegalStateException("Gemini returned an empty response.")
            val json = JSONObject(rawJson)
            val actionRaw = json.optString("actionType", AssistantActionType.ANSWER_INFO.name)
            val action = AssistantActionType.entries.firstOrNull { it.name == actionRaw }
                ?: AssistantActionType.ANSWER_INFO

            Result.success(
                AssistantStructuredReply(
                    replyText = json.optString("replyText", "Done."),
                    actionType = action,
                    targetDeviceName = json.optString("targetDeviceName").takeIf { it.isNotBlank() && it != "null" },
                    payloadTitle = json.optString("payloadTitle").takeIf { it.isNotBlank() && it != "null" },
                    payloadBody = json.optString("payloadBody").takeIf { it.isNotBlank() && it != "null" },
                    requiresConfirmation = json.optBoolean("requiresConfirmation", action != AssistantActionType.ANSWER_INFO)
                )
            )
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Gemini request timed out", e)
            Result.failure(IllegalStateException("Gemini request timed out after 60 seconds."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Gemini assistant error", e)
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "LinkAssistantRepo"
    }
}
