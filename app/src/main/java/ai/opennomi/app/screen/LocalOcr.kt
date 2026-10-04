package ai.opennomi.app.screen

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File

/** One instance, serialized on the capture worker; never close it during a recognition. */
class LocalOcr(private val context: Context) {
    private var api: TessBaseAPI?=null
    fun recognize(bitmap: Bitmap): String {
        if(api==null) {
            val root=File(context.filesDir,"screen-ocr");val data=File(root,"tessdata").apply{mkdirs()}
            for(name in listOf("eng.traineddata","chi_sim.traineddata")) { val f=File(data,name);if(!f.exists())context.assets.open("tessdata/$name").use { input -> f.outputStream().use{input.copyTo(it)} } }
            val tess=TessBaseAPI();check(tess.init(root.absolutePath,"eng+chi_sim",TessBaseAPI.OEM_LSTM_ONLY)){"OCR 初始化失败"};tess.pageSegMode=TessBaseAPI.PageSegMode.PSM_AUTO;api=tess
        }
        return api!!.run { setImage(bitmap);val text=getUTF8Text().orEmpty();clear();text.take(16000) }
    }
    fun close(){api?.recycle();api=null}
}
