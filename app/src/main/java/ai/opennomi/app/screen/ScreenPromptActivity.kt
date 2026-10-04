package ai.opennomi.app.screen

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.graphics.Color
import android.widget.*
import android.view.Gravity

class ScreenPromptActivity:androidx.activity.ComponentActivity() {
    private val mic=registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()){ok->
        if(ok)startChat() else Toast.makeText(this,"请允许麦克风权限后再与小智聊天",Toast.LENGTH_LONG).show()
    }
    companion object { var observedPage:Page?=null;var observedFrame:ScreenFrame?=null }
    private lateinit var input:EditText;private var page:Page?=null;private var frame:ScreenFrame?=null
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);ScreenAssistant.init(this);page=observedPage;frame=observedFrame;observedPage=null;observedFrame=null
        window.setGravity(Gravity.BOTTOM);window.setLayout(-1,-2)
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(32,30,32,32);setBackgroundColor(0xFF111C2B.toInt())}
        root.addView(TextView(this).apply{text="NOMI · 正在看这页";textSize=21f;setTextColor(Color.WHITE)})
        input=EditText(this).apply{hint="这是什么？帮我看下一步…";setTextColor(Color.WHITE);setHintTextColor(0xFF9BB2CD.toInt());minLines=2;maxLines=4};root.addView(input)
        fun button(label:String,action:()->Unit){root.addView(Button(this).apply{text=label;setOnClickListener{action()}})}
        button("解释当前页面"){ScreenAssistant.ask(input.text.toString().ifBlank{"解释当前页面，告诉我重点"},pageOverride=page,frameOverride=frame);finish()}
        button("小智语音聊天 · 开始 / 暂停"){voice()}
        button("规划下一步"){if(input.text.isNotBlank()){val task=input.text.toString();finish();ScreenAssistant.askAfterReturning(task,true)}else input.error="说说你想完成什么"}
        button("关闭"){finish()};setContentView(root)
        if(intent.getBooleanExtra("voice",false))voice()
    }
    private fun voice(){
        val model=(application as ai.opennomi.app.NomiApplication).cloudModel
        if(model.backgroundConversation.value){ai.opennomi.app.voice.NomiVoiceService.stop(this);finish();return}
        if(androidx.core.content.ContextCompat.checkSelfPermission(this,android.Manifest.permission.RECORD_AUDIO)==android.content.pm.PackageManager.PERMISSION_GRANTED)startChat()
        else mic.launch(android.Manifest.permission.RECORD_AUDIO)
    }
    private fun startChat(){
        runCatching{ai.opennomi.app.voice.NomiVoiceService.start(this,true)}
            .onSuccess{android.os.Handler(mainLooper).postDelayed({finish()},350)}
            .onFailure{Toast.makeText(this,"语音未能启动：${it.message}",Toast.LENGTH_LONG).show()}
    }
}
