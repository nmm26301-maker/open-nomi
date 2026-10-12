package ai.opennomi.app.screen

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A single reusable on-device translator. Language models remain in app-private SDK storage.
 * Downloading models needs network; translating already downloaded pairs works offline. */
class PhoneTranslator {
    private val mutex=Mutex()
    private val _status=MutableStateFlow("首次使用请在 Wi-Fi 下准备语言模型")
    val status=_status.asStateFlow()
    private var source=""
    private var translator:Translator?=null
    private var ready=false

    private fun client(language:String):Translator {
        if(source!=language) {
            translator?.close();ready=false;source=language
            translator=Translation.getClient(TranslatorOptions.Builder()
                .setSourceLanguage(language).setTargetLanguage(TranslateLanguage.CHINESE).build())
        }
        return checkNotNull(translator)
    }
    private suspend fun prepareLocked(language:String,cellular:Boolean) {
        if(ready && source==language)return
        _status.value="正在准备手机翻译模型 · 首次下载可能需要几分钟"
        val conditions=DownloadConditions.Builder().apply{if(!cellular)requireWifi()}.build()
        try {
            withTimeout(180000){client(language).downloadModelIfNeeded(conditions).awaitResult()}
            ready=true;_status.value="手机翻译模型已就绪 · Google 翻译"
        } catch(e:TimeoutCancellationException) {
            _status.value="模型准备超时，请检查 Wi-Fi 后重试"
            throw IllegalStateException(_status.value,e)
        } catch(e:CancellationException){throw e}
        catch(e:Exception) {
            _status.value="模型准备失败，请检查网络或允许移动网络下载后重试"
            throw IllegalStateException(_status.value,e)
        }
    }
    suspend fun prepare(language:String,cellular:Boolean=false) = mutex.withLock {
        require(language!=TranslateLanguage.CHINESE && language in TranslateLanguage.getAllLanguages()){"请选择支持的源语言"}
        prepareLocked(language,cellular)
    }
    suspend fun close() = mutex.withLock {translator?.close();translator=null;source="";ready=false}
    suspend fun translate(text:String,selected:String="auto",cellular:Boolean=false):String = mutex.withLock {
        val input=text.trim();if(input.isBlank())return@withLock ""
        val language=if(selected=="auto") {
            _status.value="正在识别原文语言"
            val identifier=LanguageIdentification.getClient()
            try {withTimeout(15000){identifier.identifyLanguage(input).awaitResult()}}
            finally{identifier.close()}
        } else selected
        if(language=="zh") {_status.value="原文已是中文";return@withLock input}
        val supported=TranslateLanguage.fromLanguageTag(language)
            ?: throw IllegalArgumentException("原文语言不明确，请手动选择英语、日语等源语言")
        prepareLocked(supported,cellular)
        _status.value="正在手机上翻译 · Google 翻译"
        try {
            val output=withTimeout(30000){client(supported).translate(input).awaitResult()}
            check(output.isNotBlank()){"手机翻译未返回译文"}
            _status.value="翻译完成 · Google 翻译";output
        } catch(e:TimeoutCancellationException){throw IllegalStateException("手机翻译超时，请缩短原文重试",e)}
    }
}

private suspend fun <T> Task<T>.awaitResult():T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener{value->if(continuation.isActive)continuation.resume(value)}
    addOnFailureListener{error->if(continuation.isActive)continuation.resumeWithException(error)}
    addOnCanceledListener{continuation.cancel()}
}
