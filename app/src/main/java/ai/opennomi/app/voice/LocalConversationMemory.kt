package ai.opennomi.app.voice

import android.content.Context

/** App-private persistence; Android applies disk writes asynchronously and preserves cover installs. */
class LocalConversationMemory(context: Context, key: String = "nomi") {
    private val prefs=context.getSharedPreferences("open_nomi_conversation_memory",Context.MODE_PRIVATE)
    val conversation=ConversationMemory(prefs.getString(key,"").orEmpty()) { value -> prefs.edit().putString(key,value).apply() }
}
