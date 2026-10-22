package ai.opennomi.app

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import ai.opennomi.app.screen.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class HyMtSmokeTest {
    @Test fun realOfficialModelStreamsTranslatesCancelsReloadsOfflineAndShowsChinese()=runBlocking<Unit> {
        val app=ApplicationProvider.getApplicationContext<NomiApplication>()
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val engine=HyMtTranslator();val report=JSONObject().put("model_sha256",HyMtModel.SHA256).put("scope","Actual on-device Hy-MT2; emulator only")
        val samples=JSONArray();report.put("translations",samples)
        val wifi=device.executeShellCommand("settings get global wifi_on").trim()
        val data=device.executeShellCommand("settings get global mobile_data").trim()
        val cfg=ScreenAssistant.settings();val oldPhone=cfg.phoneTranslation;val oldHy=cfg.hyMtTranslation
        try {
            withTimeout(1200000){engine.prepare(app,true)}
            assertTrue(HyMtModel.ready(app));assertEquals(HyMtModel.BYTES,HyMtModel.file(app).length())
            val probes=listOf(
                "Do not open the door." to listOf(Regex("不|别|勿"),Regex("门")),
                "Turn left, not right." to listOf(Regex("左"),Regex("右"),Regex("不|别|而|非")),
                "The ticket costs 25 dollars." to listOf(Regex("25|二十五"),Regex("美元|美金")),
                "会議は明日です。" to listOf(Regex("明天"),Regex("会议|会議"))
            )
            for((source,checks) in probes) {
                val start=SystemClock.elapsedRealtime();var first=-1L;val chunks=mutableListOf<String>()
                val translated=withTimeout(90000){engine.translate(app,source,true,partial={text->if(first<0)first=SystemClock.elapsedRealtime()-start;synchronized(chunks){chunks.add(text)}})}
                assertTrue("Native stream must deliver complete UTF-8",chunks.isNotEmpty() && chunks.none{it.contains('\uFFFD')})
                val row=JSONObject().put("source",source).put("translated",translated).put("first_chunk_ms",first).put("total_ms",SystemClock.elapsedRealtime()-start)
                    .put("semantic_checks_pass",checks.all{it.containsMatchIn(translated)})
                samples.put(row)
                assertTrue("Real Hy-MT2 semantic probe failed: $row",row.getBoolean("semantic_checks_pass"))
            }
            val firstToken=CompletableDeferred<Unit>()
            val long=async{engine.translate(app,"Do not open the door. ".repeat(35),true,partial={firstToken.complete(Unit)})}
            withTimeout(60000){firstToken.await()}
            val cancelAt=SystemClock.elapsedRealtime();withTimeout(4000){long.cancelAndJoin()}
            report.put("caller_cancel_ms",SystemClock.elapsedRealtime()-cancelAt)
            val after=withTimeout(90000){engine.translate(app,"The door is closed.",true)}
            assertTrue(after.any{it in '\u4e00'..'\u9fff'});report.put("after_cancel",after)
            engine.close()
            device.executeShellCommand("svc wifi disable");device.executeShellCommand("svc data disable")
            val offline=HyMtTranslator()
            try {
                withTimeout(90000){offline.prepare(app,false)}
                val result=withTimeout(90000){offline.translate(app,"The meeting is tomorrow, not today.")}
                assertTrue(result.contains("明天") && result.contains("今天"));report.put("offline_reload",result)
            }finally{offline.close()}
            cfg.phoneTranslation=true;cfg.hyMtTranslation=true
            ActivityScenario.launch<WorkspaceActivity>(Intent(app,WorkspaceActivity::class.java).putExtra("tab","translation").putExtra("translation-mode","text")).use{scenario->
                withTimeout(15000){while(!device.hasObject(By.text("Hy-MT2 离线")))delay(100)}
                withTimeout(15000){while(!device.hasObject(By.clazz("android.widget.EditText"))){device.findObjects(By.scrollable(true)).firstOrNull()?.scroll(Direction.DOWN,.3f);delay(100)}}
                val input=device.findObject(By.clazz("android.widget.EditText"));assertNotNull(input);input.click();input.text="Do not open the door."
                device.pressBack()
                withTimeout(15000){while(!device.hasObject(By.text("翻译为中文"))){device.findObjects(By.scrollable(true)).firstOrNull()?.scroll(Direction.DOWN,.3f);delay(100)}}
                val button=device.findObject(By.text("翻译为中文"));assertNotNull(button);button.click()
                val expected=samples.getJSONObject(0).getString("translated")
                withTimeout(90000){while(!device.hasObject(By.text(expected))) {
                    device.findObjects(By.scrollable(true)).firstOrNull()?.scroll(Direction.DOWN,.3f);delay(200)
                }}
                val output=File(app.getExternalFilesDir(null),"hymt-workspace.png")
                scenario.onActivity{activity->val view=activity.window.decorView;val bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
                    try{view.draw(android.graphics.Canvas(bitmap));output.outputStream().use{assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))}}finally{bitmap.recycle()}}
                device.executeShellCommand("cp ${output.absolutePath} /data/local/tmp/hymt-workspace.png")
            }
        }finally {
            engine.close();ScreenAssistant.hyMtTranslator.close();cfg.phoneTranslation=oldPhone;cfg.hyMtTranslation=oldHy
            if(wifi=="1")device.executeShellCommand("svc wifi enable")
            if(data=="1")device.executeShellCommand("svc data enable")
            val path=File(app.getExternalFilesDir(null),"hymt-quality.json");path.writeText(report.toString(2))
            device.executeShellCommand("cp ${path.absolutePath} /data/local/tmp/hymt-quality.json")
        }
    }
}
