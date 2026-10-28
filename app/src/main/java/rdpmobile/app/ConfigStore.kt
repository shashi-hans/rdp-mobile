package rdpmobile.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class Resolution(val label: String) {
    PHONE("Match phone"),
    HD("1280 × 720"),
    FHD("1920 × 1080"),
}

data class LaptopConfig(
    val name: String = "My laptop",
    val host: String = "",
    val rdpPort: Int = 3389,
    val username: String = "",
    val password: String = "",
    val agentPort: Int = 8765,
    val agentToken: String = "",
    val resolution: Resolution = Resolution.FHD,
) {
    val isComplete: Boolean get() = Tailnet.isTailnetLiteral(host) && username.isNotBlank()
    val hasAgent: Boolean get() = agentToken.isNotBlank()
    val pinKey: String get() = "${host.lowercase()}:$rdpPort"

    /** Changes when the agent target or its token changes. */
    val agentKey: String get() = "${host.trim()}:$agentPort:${agentToken.hashCode()}"
}

/**
 * Stores the laptop config as one JSON blob encrypted with an AES-256-GCM key
 * held in the Android Keystore. The key never leaves the keystore, so the
 * prefs file alone does not reveal the RDP password or agent token.
 */
class ConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("config", Context.MODE_PRIVATE)
    private val pins = context.getSharedPreferences("cert_pins", Context.MODE_PRIVATE)

    fun load(): LaptopConfig {
        val blob = prefs.getString(KEY_BLOB, null) ?: return LaptopConfig()
        val json = runCatching { JSONObject(decrypt(blob)) }.getOrElse {
            // The keystore key is gone or changed (for example after clearing
            // credentials), so the blob can never be read again. Drop both so
            // the next save starts with a fresh key instead of failing.
            Log.w(TAG, "saved settings unreadable, resetting: ${it.javaClass.simpleName}")
            reset()
            return LaptopConfig()
        }
        return LaptopConfig(
            name = json.optString("name", "My laptop"),
            host = json.optString("host"),
            rdpPort = json.optInt("rdpPort", 3389),
            username = json.optString("username"),
            password = json.optString("password"),
            agentPort = json.optInt("agentPort", 8765),
            agentToken = json.optString("agentToken"),
            resolution = runCatching { Resolution.valueOf(json.optString("resolution")) }
                .getOrDefault(Resolution.FHD),
        )
    }

    fun save(c: LaptopConfig) {
        val json = JSONObject()
            .put("name", c.name)
            .put("host", c.host.trim())
            .put("rdpPort", c.rdpPort)
            .put("username", c.username)
            .put("password", c.password)
            .put("agentPort", c.agentPort)
            .put("agentToken", c.agentToken.trim())
            .put("resolution", c.resolution.name)
        prefs.edit().putString(KEY_BLOB, encrypt(json.toString())).apply()
    }

    private fun reset() {
        prefs.edit().remove(KEY_BLOB).apply()
        runCatching { KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS) }
    }

    /** Pinned certificate fingerprint for host:port, set on first accepted connect. */
    fun pin(key: String): String? = pins.getString(key, null)

    fun setPin(key: String, fingerprint: String) {
        pins.edit().putString(key, fingerprint).apply()
    }

    fun clearPin(key: String) {
        pins.edit().remove(key).apply()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = cipher.iv + cipher.doFinal(plain.toByteArray())
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String {
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, IV_LEN))
        return String(cipher.doFinal(raw, IV_LEN, raw.size - IV_LEN))
    }

    private companion object {
        const val TAG = "ConfigStore"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "rdpmobile.config"
        const val KEY_BLOB = "blob"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
    }
}

/**
 * FreeRDP passes either a colon-separated hash or a full PEM certificate
 * (VERIFY_CERT_FLAG_FP_IS_PEM). Pins always store a SHA-256 hex string.
 */
fun normalizeFingerprint(fingerprint: String): String {
    if (!fingerprint.startsWith("-----BEGIN")) return fingerprint.lowercase()
    val der = Base64.decode(
        fingerprint.lines().filterNot { it.startsWith("-----") }.joinToString(""),
        Base64.DEFAULT,
    )
    return MessageDigest.getInstance("SHA-256").digest(der)
        .joinToString(":") { "%02x".format(it) }
}
