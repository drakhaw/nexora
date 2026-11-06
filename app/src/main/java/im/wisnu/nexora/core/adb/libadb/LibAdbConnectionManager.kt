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
) : AbsAdbConnectionManager() {

    init {
        // Default is Long.MAX_VALUE; always bound the handshake (SEC-10).
        setTimeout(handshakeTimeoutMillis, TimeUnit.MILLISECONDS)
        setThrowOnUnauthorised(true)
    }

    override fun getPrivateKey(): PrivateKey = identity.privateKey

    override fun getCertificate(): Certificate = identity.certificate

    override fun getDeviceName(): String = DEVICE_NAME

    private companion object {
        const val DEVICE_NAME = "Nexora"
    }
}
