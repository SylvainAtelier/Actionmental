package com.actionmental.platform.adb

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x509.Time
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 本应用作为 adb 客户端时的身份：一把 RSA 密钥和包着它公钥的自签证书。
 *
 * adbd 认的就是这把公钥 —— 配对时交给它，之后每次 TLS 握手都拿它出示。
 * 私钥用 Keystore 里的 AES-GCM 密钥加密后放在应用私有的 SharedPreferences 里，和 NAS 密码同一种做法：
 * 它能以 shell 身份进这台手机，拷走密文文件也解不开。备份规则只收 datastore/，换机后自然重新配对。
 */
internal class AdbIdentity(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * 配对成功过没有。只是本地记的一笔：用户在开发者选项里撤销了配对，这里并不知道 ——
     * 那时连接会被拒，后端照常重新配对一次。
     */
    var paired: Boolean
        get() = prefs.getBoolean(KEY_PAIRED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_PAIRED, value).apply()
        }

    /** 读出密钥与证书；第一次用或密文解不开（Keystore 被清过）时现生成一份，旧的配对随之作废。 */
    @Synchronized
    fun load(): Pair<PrivateKey, X509Certificate> {
        runCatching { read() }.getOrNull()?.let { return it }
        val generated = generate()
        write(generated.first, generated.second)
        paired = false
        return generated
    }

    private fun read(): Pair<PrivateKey, X509Certificate>? {
        val sealed = prefs.getString(KEY_PRIVATE, null) ?: return null
        val certDer = prefs.getString(KEY_CERT, null) ?: return null
        val blob = Base64.decode(sealed, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
        }
        val pkcs8 = cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
        return key to certificate(Base64.decode(certDer, Base64.NO_WRAP))
    }

    private fun write(key: PrivateKey, cert: X509Certificate) {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val sealed = cipher.iv + cipher.doFinal(key.encoded)
        prefs.edit()
            .putString(KEY_PRIVATE, Base64.encodeToString(sealed, Base64.NO_WRAP))
            .putString(KEY_CERT, Base64.encodeToString(cert.encoded, Base64.NO_WRAP))
            .commit()
    }

    /**
     * adbd 不校验证书本身（不看签发者、不看有效期），只取里面的公钥去比对，
     * 所以一张最小的自签 v3 证书就够了。用 BouncyCastle 的 ASN.1 拼 TBS、自己签名 ——
     * 工程里已有的 bcprov 不带证书构建器（那在 bcpkix 里），为这一张证书再引一个库不值得。
     */
    private fun generate(): Pair<PrivateKey, X509Certificate> {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_BITS, SecureRandom()) }.generateKeyPair()
        val algorithm = AlgorithmIdentifier(PKCSObjectIdentifiers.sha256WithRSAEncryption, DERNull.INSTANCE)
        val name = X500Name("CN=" + CLIENT_NAME)
        val now = System.currentTimeMillis()
        val tbs = V3TBSCertificateGenerator().apply {
            setSerialNumber(ASN1Integer(BigInteger(64, SecureRandom()).abs()))
            setIssuer(name)
            setSubject(name)
            setStartDate(Time(Date(now - DAY_MS)))
            setEndDate(Time(Date(now + VALID_YEARS * 365 * DAY_MS)))
            setSignature(algorithm)
            setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(pair.public.encoded))
        }.generateTBSCertificate()
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(pair.private)
            update(tbs.encoded)
            sign()
        }
        val der = DERSequence(ASN1EncodableVector().apply {
            add(tbs)
            add(algorithm)
            add(DERBitString(signature))
        }).encoded
        return pair.private to certificate(der)
    }

    private fun certificate(der: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate

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

    companion object {
        /** 开发者选项「已配对的设备」里显示的名字。 */
        const val CLIENT_NAME = "Actionmental"

        private const val FILE = "adb_identity"
        private const val KEY_PRIVATE = "private"
        private const val KEY_CERT = "cert"
        private const val KEY_PAIRED = "paired"
        private const val PROVIDER = "AndroidKeyStore"
        private const val ALIAS = "actionmental.adb"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val KEY_BITS = 2048
        private const val VALID_YEARS = 30L
        private const val DAY_MS = 86_400_000L
    }
}
