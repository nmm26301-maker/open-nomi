package ai.opennomi.app.screen

import android.content.Context
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteDatabase
import android.content.ContentValues
import android.net.Uri
import java.io.File

data class SavedItem(val id: Long, val kind: String, val text: String, val image: String, val source: String, val time: Long)
class MemoryStore(private val context: Context): SQLiteOpenHelper(context,"nomi-workspace.db",null,1) {
    override fun onCreate(db: SQLiteDatabase) { db.execSQL("CREATE TABLE items (id INTEGER PRIMARY KEY AUTOINCREMENT,kind TEXT NOT NULL,body TEXT NOT NULL,image TEXT NOT NULL,source TEXT NOT NULL,created INTEGER NOT NULL)") }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = Unit
    fun add(kind: String, text: String, source: String = "手动记录", image: String = ""): Long {
        val id=writableDatabase.insertOrThrow("items",null,ContentValues().apply { put("kind",kind); put("body",text.take(60000)); put("image",image); put("source",source); put("created",System.currentTimeMillis()) })
        if(kind=="translation")writableDatabase.execSQL("DELETE FROM items WHERE kind='translation' AND id NOT IN (SELECT id FROM items WHERE kind='translation' ORDER BY created DESC LIMIT 2000)")
        return id
    }
    fun list(kind: String = ""): List<SavedItem> = readableDatabase.query("items",null,if(kind.isBlank()) null else "kind=?",if(kind.isBlank()) null else arrayOf(kind),null,null,"created DESC","200").use { c -> buildList { while(c.moveToNext()) add(SavedItem(c.getLong(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getLong(5))) } }
    fun delete(item: SavedItem) { writableDatabase.delete("items","id=?",arrayOf(item.id.toString())); if(item.image.isNotBlank()) File(item.image).delete() }
    fun importImage(uri: Uri, text: String): Long {
        val mime=context.contentResolver.getType(uri).orEmpty(); require(mime.startsWith("image/")) { "仅支持图片分享" }
        val dir=File(context.filesDir,"fragments").apply { mkdirs() }; val f=File.createTempFile("original-",".image",dir)
        try { context.contentResolver.openInputStream(uri).use { input -> requireNotNull(input); f.outputStream().use { out -> val buffer=ByteArray(8192); var total=0L; while(true) { val n=input.read(buffer);if(n<0)break;total+=n;require(total <= 30L*1024*1024) { "图片超过 30 MB" };out.write(buffer,0,n) } } }; return add("image",text.ifBlank { "分享的原图" },"系统分享 · 原始图片",f.absolutePath) } catch(t:Throwable){f.delete();throw t}
    }
}
