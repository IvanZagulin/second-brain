package ru.secondbrain.app.core

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** The one biometric / device-credential prompt, shared by the app lock and the share sheet. */
object BiometricGate {
    private val authenticators = if (Build.VERSION.SDK_INT >= 30)
        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    else BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /** False when the device has neither biometrics nor a screen lock: there is nothing to check against. */
    fun available(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS

    /** Shows the prompt; [onResult] gets true on success, false on error/cancel. */
    fun prompt(activity: FragmentActivity, subtitle: String? = null, onResult: (Boolean) -> Unit) {
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(true)
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onResult(false)
        }
        val info = BiometricPrompt.PromptInfo.Builder().setTitle("Второй мозг")
            .apply { if (subtitle != null) setSubtitle(subtitle) }
            .setAllowedAuthenticators(authenticators).build()
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback).authenticate(info)
    }

    /** Suspending form of [prompt]. */
    suspend fun authenticate(activity: FragmentActivity, subtitle: String? = null): Boolean =
        suspendCancellableCoroutine { cont ->
            prompt(activity, subtitle) { ok -> if (cont.isActive) cont.resume(ok) }
        }
}
