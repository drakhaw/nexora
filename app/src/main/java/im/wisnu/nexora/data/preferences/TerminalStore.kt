package im.wisnu.nexora.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Local terminal history (TER-04), saved commands (TER-06) and the risk acknowledgement (TER-08). */
@Singleton
class TerminalStore @Inject constructor(private val prefs: DataStore<Preferences>) {

    val history: Flow<List<String>> = prefs.data.map { decode(it[HISTORY]) }
    val saved: Flow<List<String>> = prefs.data.map { decode(it[SAVED]) }
    val warningAcknowledged: Flow<Boolean> = prefs.data.map { it[WARNING_ACK] == true }

    suspend fun addHistory(command: String) = prefs.edit {
        it[HISTORY] = encode((listOf(command) + decode(it[HISTORY]).filter { c -> c != command }).take(MAX_HISTORY))
    }

    suspend fun clearHistory() = prefs.edit { it.remove(HISTORY) }

    suspend fun save(command: String) = prefs.edit {
        val list = decode(it[SAVED])
        if (command !in list) it[SAVED] = encode(list + command)
    }

    suspend fun unsave(command: String) = prefs.edit { it[SAVED] = encode(decode(it[SAVED]) - command) }

    suspend fun acknowledgeWarning() = prefs.edit { it[WARNING_ACK] = true }

    private fun decode(s: String?): List<String> =
        s?.let { runCatching { Json.decodeFromString(LIST, it) }.getOrNull() } ?: emptyList()

    private fun encode(list: List<String>): String = Json.encodeToString(LIST, list)

    private companion object {
        val HISTORY = stringPreferencesKey("terminal_history")
        val SAVED = stringPreferencesKey("terminal_saved")
        val WARNING_ACK = booleanPreferencesKey("terminal_warning_ack")
        val LIST = ListSerializer(String.serializer())
        const val MAX_HISTORY = 50
    }
}
