package im.wisnu.nexora.core.adb.libadb

import im.wisnu.nexora.core.security.HostIdentity
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.security.PrivateKey
import java.security.cert.Certificate
import java.util.concurrent.TimeUnit

/** One libadb connection manager per target, so sessions stay isolated. */
internal class LibAdbConnectionManager(
    private val identity: HostIdentity,
    handshakeTimeoutMillis: Long,
    throwOnUnauthorised: Boolean = true,
) : AbsAdbConnectionManager() {

    init {
        // Default is Long.MAX_VALUE; always bound the handshake (SEC-10).
        setTimeout(handshakeTimeoutMillis, TimeUnit.MILLISECONDS)
        // true: fail fast when the signature is rejected. false: send the public key,
        // which makes the target show its authorization prompt, then wait for it.
        setThrowOnUnauthorised(throwOnUnauthorised)
    }

    override fun getPrivateKey(): PrivateKey = identity.privateKey

    override fun getCertificate(): Certificate = identity.certificate

    override fun getDeviceName(): String = DEVICE_NAME

    private companion object {
        const val DEVICE_NAME = "Nexora"
    }
}
