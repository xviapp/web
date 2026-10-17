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
    private var onCancel: ((deliberate: Boolean) -> Unit)? = null
    private val prompt = BiometricPrompt(host, ContextCompat.getMainExecutor(host), object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = finish(onSuccess)
        // A single unrecognised finger is not an answer; the prompt keeps asking until it errors out.
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            val cancel = onCancel
            finish { cancel?.invoke(errorCode in DELIBERATE) }
        }
    })

    val available get() = BiometricManager.from(host).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    /**
     * [cancelled] learns whether the prompt ended deliberately: dismissed, or locked out after too many
     * wrong tries. The system closing it (a call, the screen turning off, leaving the app) is not.
     */
    fun confirm(title: String, cancelled: (deliberate: Boolean) -> Unit = {}, success: () -> Unit) {
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

    private companion object {
        const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        val DELIBERATE = setOf(
            BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON,
            BiometricPrompt.ERROR_LOCKOUT, BiometricPrompt.ERROR_LOCKOUT_PERMANENT
        )
    }
}
