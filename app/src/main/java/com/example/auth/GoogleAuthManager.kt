package com.example.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.MutableContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.lifecycleScope
import com.example.R
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.security.MessageDigest

object GoogleAuthManager {

    private const val TAG = "Auth"

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

    fun authStateFlow(auth: FirebaseAuth = Firebase.auth): Flow<FirebaseUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            trySend(firebaseAuth.currentUser)
        }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    fun attemptAutoSignIn(
        context: Context,
        credentialManager: CredentialManager,
        scope: CoroutineScope,
        onAuthSuccess: () -> Unit,
        onUnauthenticated: () -> Unit
    ) {
        val existingUser = runCatching { Firebase.auth.currentUser }.getOrNull()
        if (existingUser != null) {
            onAuthSuccess()
            return
        }
        val clientId = try {
            context.getString(R.string.default_web_client_id)
        } catch (_: Exception) {
            onUnauthenticated()
            return
        }

        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(true)
            .setServerClientId(clientId)
            .setAutoSelectEnabled(true)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        val activity = context.findActivity()
        val launchScope = (activity as? ComponentActivity)?.lifecycleScope ?: scope
        val targetContext = activity?.let { MutableContextWrapper(it) } ?: context

        launchScope.launch {
            try {
                val cred = credentialManager.getCredential(targetContext, request).credential
                if (cred is CustomCredential && cred.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
                    Firebase.auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await()
                    onAuthSuccess()
                } else {
                    onUnauthenticated()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                onUnauthenticated()
            }
        }
    }

    fun onGoogleSignInClicked(
        context: Context,
        credentialManager: CredentialManager,
        scope: CoroutineScope,
        onAuthSuccess: () -> Unit,
        onAuthError: (String) -> Unit,
        onAuthCancelled: (String?) -> Unit
    ) {
        launchCredentialSignIn(
            context = context,
            credentialManager = credentialManager,
            scope = scope,
            useBottomSheetFirst = false,
            onAuthSuccess = onAuthSuccess,
            onAuthError = onAuthError,
            onAuthCancelled = onAuthCancelled
        )
    }

    fun onGoogleBottomSheetSignInClicked(
        context: Context,
        credentialManager: CredentialManager,
        scope: CoroutineScope,
        onAuthSuccess: () -> Unit,
        onAuthError: (String) -> Unit,
        onAuthCancelled: (String?) -> Unit
    ) {
        launchCredentialSignIn(
            context = context,
            credentialManager = credentialManager,
            scope = scope,
            useBottomSheetFirst = true,
            onAuthSuccess = onAuthSuccess,
            onAuthError = onAuthError,
            onAuthCancelled = onAuthCancelled
        )
    }

    private fun launchCredentialSignIn(
        context: Context,
        credentialManager: CredentialManager,
        scope: CoroutineScope,
        useBottomSheetFirst: Boolean,
        onAuthSuccess: () -> Unit,
        onAuthError: (String) -> Unit,
        onAuthCancelled: (String?) -> Unit
    ) {
        val clientId = try {
            context.getString(R.string.default_web_client_id)
        } catch (_: Exception) {
            onAuthError("Google Sign-In configuration missing: default_web_client_id not found")
            return
        }

        val activity = context.findActivity()
        if (activity == null) {
            onAuthError("Unable to launch Google Sign-In without an Activity context.")
            return
        }

        val activityCredentialManager = CredentialManager.create(activity)
        val launchScope = (activity as? ComponentActivity)?.lifecycleScope ?: scope
        val credentialContext = MutableContextWrapper(activity)

        val primaryRequest = if (useBottomSheetFirst) {
            buildGoogleIdRequest(clientId)
        } else {
            buildSignInWithGoogleRequest(clientId)
        }

        launchScope.launch {
            try {
                val cred = try {
                    activityCredentialManager.getCredential(credentialContext, primaryRequest).credential
                } catch (e: NoCredentialException) {
                    if (useBottomSheetFirst) throw e
                    Log.w(TAG, "GetSignInWithGoogleOption returned NoCredentialException; retrying with GetGoogleIdOption", e)
                    activityCredentialManager.getCredential(credentialContext, buildGoogleIdRequest(clientId)).credential
                } catch (e: GetCredentialUnsupportedException) {
                    if (useBottomSheetFirst) throw e
                    Log.w(TAG, "GetSignInWithGoogleOption unsupported; retrying with GetGoogleIdOption", e)
                    activityCredentialManager.getCredential(credentialContext, buildGoogleIdRequest(clientId)).credential
                } catch (e: GetCredentialInterruptedException) {
                    if (useBottomSheetFirst) throw e
                    Log.w(TAG, "GetSignInWithGoogleOption interrupted; retrying with GetGoogleIdOption", e)
                    activityCredentialManager.getCredential(credentialContext, buildGoogleIdRequest(clientId)).credential
                }

                if (cred is CustomCredential && cred.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
                    Firebase.auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await()
                    onAuthSuccess()
                } else {
                    onAuthError("Unexpected credential type returned: ${cred.type}")
                }
            } catch (e: GetCredentialCancellationException) {
                Log.w(TAG, "Google Sign-In flow cancelled or dismissed: ${e.message}", e)
                val detail = buildCancellationDiagnostic(activity, clientId, e)
                onAuthCancelled(detail)
            } catch (e: NoCredentialException) {
                Log.e(TAG, "No credentials available after Google Sign-In fallback", e)
                onAuthError(
                    "No Google account credentials available on this device. Make sure a Google account is signed in on the device and Google Sign-In is enabled in Firebase Authentication."
                )
            } catch (e: GetCredentialProviderConfigurationException) {
                Log.e(TAG, "Google Sign-In provider configuration error", e)
                onAuthError(
                    "Google Sign-In provider misconfigured. Verify Google Play Services and Firebase SHA-1 (${getAppSigningSha1(activity)}) / OAuth client setup."
                )
            } catch (e: GetCredentialException) {
                Log.e(TAG, "Credential Manager sign-in failed (${e.type})", e)
                val msg = e.errorMessage?.toString() ?: e.localizedMessage ?: "Google Sign-In failed"
                onAuthError("$msg (${e.type}) • SHA-1: ${getAppSigningSha1(activity)}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Google Sign-In failed", e)
                onAuthError(e.localizedMessage ?: "Sign in failed")
            }
        }
    }

    private fun buildSignInWithGoogleRequest(clientId: String): GetCredentialRequest {
        val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
        return GetCredentialRequest.Builder()
            .addCredentialOption(signInOption)
            .build()
    }

    private fun buildGoogleIdRequest(clientId: String): GetCredentialRequest {
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(clientId)
            .build()
        return GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()
    }

    private fun buildCancellationDiagnostic(
        context: Context,
        clientId: String,
        e: GetCredentialCancellationException
    ): String {
        val reason = e.errorMessage?.toString()?.takeIf { it.isNotBlank() }
            ?: e.message?.takeIf { it.isNotBlank() }
            ?: "Activity cancelled"
        val sha1 = getAppSigningSha1(context)
        return "Sign-in did not complete ($reason). If you tapped an account and it closed immediately, verify in Firebase / Google Cloud for ${context.packageName}: " +
            "1) APK SHA-1 ($sha1) is registered, " +
            "2) Google Sign-In & Support Email are enabled in Firebase Auth, " +
            "3) OAuth Consent Screen is Published or includes your account as a Test User, " +
            "4) Web Client ID matches ($clientId)."
    }

    @Suppress("DEPRECATION")
    fun getAppSigningSha1(context: Context): String {
        return try {
            val pm = context.packageManager
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val signingInfo = info.signingInfo
                if (signingInfo != null) {
                    if (signingInfo.hasMultipleSigners()) signingInfo.apkContentsSigners
                    else signingInfo.signingCertificateHistory
                } else {
                    emptyArray()
                }
            } else {
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures ?: emptyArray()
            }
            val certBytes = signatures.firstOrNull()?.toByteArray() ?: return "unknown"
            val digest = MessageDigest.getInstance("SHA-1").digest(certBytes)
            digest.joinToString(":") { byte -> "%02X".format(byte) }
        } catch (_: Exception) {
            "unknown"
        }
    }

    fun signOut(
        credentialManager: CredentialManager,
        scope: CoroutineScope,
        onPreSignOutCleanup: suspend () -> Unit = {},
        onSignOutComplete: () -> Unit = {}
    ) {
        scope.launch {
            withContext(NonCancellable) {
                try {
                    onPreSignOutCleanup()
                    credentialManager.clearCredentialState(ClearCredentialStateRequest())
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to clear credential state: ${e.message}")
                } finally {
                    Firebase.auth.signOut()
                    onSignOutComplete()
                }
            }
        }
    }
}
