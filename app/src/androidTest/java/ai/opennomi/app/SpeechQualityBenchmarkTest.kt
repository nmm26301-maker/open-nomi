package ai.opennomi.app

import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import ai.opennomi.app.screen.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/** Public/synthetic inputs only; emulator timing is not real-phone end-to-end latency. */
@RunWith(AndroidJUnit4::class)
class SpeechQualityBenchmarkTest {
    private val reference="and so my fellow americans ask not what your country can do for you ask what you can do for your country"
    private fun tokens(text:String)=Regex("[a-z']+").findAll(text.lowercase()).map{it.value}.toList()
    private fun wordErrors(actual:String):Int {
        val a=tokens(reference);val b=tokens(actual);var row=IntArray(b.size+1){it}
        for(i in a.indices) {
            val next=IntArray(b.size+1);next[0]=i+1
            for(j in b.indices)next[j+1]=minOf(next[j]+1,row[j+1]+1,row[j]+if(a[i]==b[j])0 else 1)
            row=next
        }
        return row[b.size]
    }
    private fun decode(model:Model,pcm:ByteArray,timing:SubtitleTiming):JSONObject {
        val segments=mutableListOf<String>()
        var firstAt=-1L;val phrase=AudioPhraseClock(timing.maximumMs);var quietSince=0L
        var hypothesis="";var changedAt=0L;var audioAt=0L
        fun emit(raw:String) {
            val text=JSONObject(raw).optString("text").trim()
            if(text.isNotBlank()){segments.add(text);if(firstAt<0)firstAt=audioAt}
            phrase.reset();hypothesis="";changedAt=0L
        }
        val started=SystemClock.elapsedRealtime()
        Recognizer(model,16000f).use{r->
            r.setWords(true)
            r.setEndpointerDelays(5f,timing.endSilenceSeconds,20f)
            var offset=0
            while(offset<pcm.size) {
                val count=minOf(3200,pcm.size-offset)
                val buffer=pcm.copyOfRange(offset,offset+count)
                offset+=count;audioAt=offset/32L
                var energy=0L
                for(i in 0 until count-1 step 2) {
                    val sample=((buffer[i+1].toInt() shl 8) or (buffer[i].toInt() and 255)).toShort().toInt()
                    energy+=kotlin.math.abs(sample)
                }
                if(energy>count*8)quietSince=audioAt
                if(r.acceptWaveForm(buffer,count))emit(r.result)
                else {
                    // Use the production pause/grace policy. Recognition is deliberately unchanged.
                    val partial=JSONObject(r.partialResult).optString("partial")
                    if(partial!=hypothesis){hypothesis=partial;changedAt=audioAt}
                    val boundary=audioAt-quietSince>=200 && audioAt-changedAt>=200
                    if(phrase.update(partial.isNotBlank(),audioAt,boundary)) {
                        emit(r.finalResult);r.reset()
                    }
                }
            }
            emit(r.finalResult) // This is the actual end of the recording.
        }
        val elapsed=SystemClock.elapsedRealtime()-started
        val recognized=segments.joinToString(" ")
        return JSONObject().put("mode",timing.name)
            .put("recognized",recognized).put("segments",JSONArray(segments))
            .put("word_errors",wordErrors(recognized)).put("reference_words",tokens(reference).size)
            .put("wer",wordErrors(recognized).toDouble()/tokens(reference).size)
            .put("audio_ms",pcm.size/32L).put("decode_ms",elapsed)
            .put("decode_real_time_factor",elapsed.toDouble()/(pcm.size/32L))
            .put("first_segment_audio_ms",firstAt)
    }
    @Test fun publicContinuousSpeechAndRealTranslationProduceInspectableQualityEvidence()=runBlocking<Unit> {
        val app=ApplicationProvider.getApplicationContext<NomiApplication>()
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val pcm=InstrumentationRegistry.getInstrumentation().context.assets.open("quality-jfk.pcm").use{it.readBytes()}
        assertTrue(pcm.isNotEmpty())
        val report=JSONObject().put("scope","Public JFK excerpt and six authored English sentences; emulator only")
            .put("reference",reference).put("timing_note","Decode speed and audio position of first segment; not end-to-end latency")
        val speech=JSONArray();val translations=JSONArray()
        report.put("speech",speech).put("translation",translations)
        val engine=PhoneTranslator()
        var baseline:com.google.mlkit.nl.translate.Translator?=null
        try {
            for(id in listOf("en","en-wide")) {
                val spec=PlaybackModels.selected(id)
                val path=withTimeout(480000){withContext(Dispatchers.IO){PlaybackModels.prepare(app,spec,true)}}
                withContext(Dispatchers.IO) {
                    Model(path.absolutePath).use{model->
                        for(timing in SubtitleTiming.entries)
                            speech.put(decode(model,pcm,timing).put("model",id))
                    }
                }
            }
            withTimeout(240000){engine.prepare("en",true)}
            engine.translate("Hello world.","en")
            val raw=com.google.mlkit.nl.translate.Translation.getClient(com.google.mlkit.nl.translate.TranslatorOptions.Builder()
                .setSourceLanguage("en").setTargetLanguage("zh").build())
            baseline=raw
            val probes=listOf(
                "Do not open the door." to listOf(Regex("不|别|勿"),Regex("门")),
                "The ticket costs 25 dollars." to listOf(Regex("25|二十五"),Regex("美元|美金")),
                "The meeting is tomorrow, not today." to listOf(Regex("明天"),Regex("今天"),Regex("不|非|而")),
                "Turn left, not right." to listOf(Regex("左"),Regex("右"),Regex("不|别|非|而")),
                "Go right, not left." to listOf(Regex("左"),Regex("右"),Regex("不|别|非|而")),
                "Move up, not down." to listOf(Regex("上"),Regex("下"),Regex("不|别|非|而"))
            )
            for((source,checks) in probes) {
                val rawOutput=withContext(Dispatchers.IO){com.google.android.gms.tasks.Tasks.await(raw.translate(source),30,java.util.concurrent.TimeUnit.SECONDS)}
                val start=SystemClock.elapsedRealtime();val translated=engine.translate(source,"en")
                translations.put(JSONObject().put("source",source).put("translated",translated)
                    .put("warm_translate_ms",SystemClock.elapsedRealtime()-start)
                    .put("raw_model_output",rawOutput).put("model_input",EnglishContrast.complete(source))
                    .put("semantic_checks_pass",checks.all{it.containsMatchIn(translated)}))
            }
        } finally {
            baseline?.close();engine.close()
            val output=File(app.getExternalFilesDir(null),"translation-quality.json")
            output.writeText(report.toString(2))
            device.executeShellCommand("cp "+output.absolutePath+" /data/local/tmp/translation-quality.json")
        }
        assertEquals(4,speech.length());assertEquals(6,translations.length())
        for(i in 0 until speech.length()) {
            val result=speech.getJSONObject(i)
            assertTrue("Public speech regression threshold failed: "+result,result.getInt("word_errors")<=3)
        }
        for(i in 0 until translations.length())assertTrue(
            "Negation, direction, date or number changed: "+translations.getJSONObject(i),
            translations.getJSONObject(i).getBoolean("semantic_checks_pass"))
    }
}
