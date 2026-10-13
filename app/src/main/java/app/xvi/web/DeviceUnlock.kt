package app.xvi.web

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Asks for the phone's own unlock (fingerprint, face, PIN, pattern or password) before something
 * private. A phone without a screen lock has nothing to ask for, so the action simply goes ahead.
 * Create it in onCreate, so the prompt survives the window being rebuilt.
 */
internal class DeviceUnlock(private val host: FragmentActivity) {
    /** True from the prompt opening until it answers; the system may cover the window meanwhile. */
    var asking = false
        private set
    private var onSuccess: (() -> Unit)? = null
    private var onCancel: (() -> Unit)? = null
    private val prompt = BiometricPrompt(host, ContextCompat.getMainExecutor(host), object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = finish(onSuccess)
        // A single unrecognised finger is not an answer; the prompt keeps asking until it errors out.
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = finish(onCancel)
    })

    val available get() = BiometricManager.from(host).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    fun confirm(title: String, cancelled: () -> Unit = {}, success: () -> Unit) {
        if (asking) return
        if (!available) { success(); return }
        asking = true
        onSuccess = success
        onCancel = cancelled
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(title).setAllowedAuthenticators(AUTHENTICATORS).build())
    }

    private fun finish(then: (() -> Unit)?) {
        asking = false
        onSuccess = null
        onCancel = null
        if (!host.isDestroyed) then?.invoke()
    }

    private companion object { const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL }
}
