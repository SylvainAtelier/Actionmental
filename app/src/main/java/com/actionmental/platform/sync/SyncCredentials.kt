package com.actionmental.platform.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * NAS 密码的保险柜。
 *
 * 动作配置会进 DataStore、进配置导出、进云备份，所以密码绝不能放在里面。
 * 这里用 Android Keystore 里的 AES-GCM 密钥加密，密文放在应用私有的 SharedPreferences 里 ——
 * 密钥本身出不了硬件/系统的安全区，拷走密文文件也解不开。
 * 备份规则只收 datastore/，所以这份密文不会跟着备份走；换机后需要重新输入一次密码。
 */
class SyncCredentials(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun has(key: String): Boolean = prefs.contains(key)

    fun save(key: String, password: String) {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val encrypted = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        val blob = Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
        prefs.edit().putString(key, blob).apply()
    }

    /** 读不到、解不开（密钥被系统清掉）都返回 null，由调用方提示重新输入。 */
    fun load(key: String): CharArray? = runCatching {
        val blob = Base64.decode(prefs.getString(key, null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
        }
        String(cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES), Charsets.UTF_8).toCharArray()
    }.getOrNull()

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val FILE = "sync_credentials"
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "actionmental.sync"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
