package im.wisnu.nexora.core.adb

/** Connection states from CON-12. */
sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data object Validating : ConnectionState
    data object PairingRequired : ConnectionState
    data object Unauthorized : ConnectionState
    data object Connected : ConnectionState
    data class Error(val error: AdbError) : ConnectionState
}

/**
 * True while a session can be used. Validating counts: it runs on every
 * foreground return and must not blank screens or reset their state.
 */
val ConnectionState.isUsable: Boolean
    get() = this == ConnectionState.Connected || this == ConnectionState.Validating

fun AdbError.toConnectionState(): ConnectionState = when (this) {
    AdbError.PairingRequired -> ConnectionState.PairingRequired
    AdbError.Unauthorized -> ConnectionState.Unauthorized
    AdbError.Timeout, AdbError.Offline, AdbError.NetworkUnreachable, AdbError.ConnectionRefused ->
        ConnectionState.Disconnected
    else -> ConnectionState.Error(this)
}
