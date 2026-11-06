package im.wisnu.nexora.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** The phone's ADB host identity: RSA key plus self-signed certificate for TLS. */
class HostIdentity(val privateKey: PrivateKey, val certificate: X509Certificate)

/**
 * Persists the ADB host identity (SEC-03).
 *
 * The ADB library needs the raw RSA private key (NoPadding signing and TLS in
 * bundled Conscrypt), so a non-exportable Keystore RSA key will not work. The
 * key is instead encrypted at rest with an AES-GCM key held in Android
 * Keystore, and only decrypted in memory. Never log key material (SEC-04).
 */
@Singleton
class HostIdentityStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val mutex = Mutex()
    @Volatile private var cached: HostIdentity? = null

    private val file: File get() = File(context.noBackupFilesDir, FILE_NAME)

    suspend fun get(): HostIdentity = cached ?: mutex.withLock {
        cached ?: (load() ?: create()).also { cached = it }
    }

    /** Deletes the identity. Every target must then re-authorize this phone. */
    suspend fun reset() = mutex.withLock {
        cached = null
        file.delete()
        keyStore().deleteEntry(WRAP_ALIAS)
    }

    private fun load(): HostIdentity? {
        if (!file.exists()) return null
        return runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                require(input.readInt() == FORMAT_VERSION)
                val iv = ByteArray(input.readInt()).also(input::readFully)
                val sealed = ByteArray(input.readInt()).also(input::readFully)
                val certDer = ByteArray(input.readInt()).also(input::readFully)
                val cipher = Cipher.getInstance(AES_MODE)
                cipher.init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, iv))
                val pkcs8 = cipher.doFinal(sealed)
                val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
                pkcs8.fill(0)
                val cert = CertificateFactory.getInstance("X.509")
                    .generateCertificate(certDer.inputStream()) as X509Certificate
                HostIdentity(key, cert)
            }
        }.getOrNull() // Corrupt or unreadable: fall through to create(); targets re-authorize.
    }

    private fun create(): HostIdentity {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }
            .generateKeyPair()
        val now = System.currentTimeMillis()
        val subject = X500Name("CN=Nexora")
        val holder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger(64, SecureRandom()),
            Date(now - 86_400_000L),
            Date(now + CERT_VALIDITY_MS),
            subject,
            pair.public,
        ).build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private))
        val cert = JcaX509CertificateConverter().getCertificate(holder)

        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(Cipher.ENCRYPT_MODE, wrapKey())
        val pkcs8 = pair.private.encoded
        val sealed = cipher.doFinal(pkcs8)
        pkcs8.fill(0)

        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { out ->
            out.writeInt(FORMAT_VERSION)
            out.writeInt(cipher.iv.size); out.write(cipher.iv)
            out.writeInt(sealed.size); out.write(sealed)
            val der = cert.encoded
            out.writeInt(der.size); out.write(der)
        }
        check(tmp.renameTo(file)) { "could not persist host identity" }
        return HostIdentity(pair.private, cert)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun wrapKey(): SecretKey {
        (keyStore().getKey(WRAP_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    WRAP_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val WRAP_ALIAS = "adb_host_identity_wrap"
        const val FILE_NAME = "adb_host_identity.bin"
        const val AES_MODE = "AES/GCM/NoPadding"
        const val FORMAT_VERSION = 1
        const val CERT_VALIDITY_MS = 10L * 365 * 86_400_000L
    }
}
