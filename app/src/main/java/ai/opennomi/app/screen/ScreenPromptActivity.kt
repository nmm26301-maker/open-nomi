package ai.opennomi.app.screen

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.graphics.Color
import android.widget.*
import android.view.Gravity

class ScreenPromptActivity:Activity() {
    companion object { var observedPage:Page?=null;var observedFrame:ScreenFrame?=null }
    private lateinit var input:EditText;private var page:Page?=null;private var frame:ScreenFrame?=null
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);ScreenAssistant.init(this);page=observedPage;frame=observedFrame;observedPage=null;observedFrame=null
        window.setGravity(Gravity.BOTTOM);window.setLayout(-1,-2)
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(32,30,32,32);setBackgroundColor(0xFF111C2B.toInt())}
        root.addView(TextView(this).apply{text="NOMI · 正在看这页";textSize=21f;setTextColor(Color.WHITE)})
        input=EditText(this).apply{hint="这是什么？帮我看下一步…";setTextColor(Color.WHITE);setHintTextColor(0xFF9BB2CD.toInt());minLines=2;maxLines=4};root.addView(input)
        fun button(label:String,action:()->Unit){root.addView(Button(this).apply{text=label;setOnClickListener{action()}})}
        button("解释当前页面"){ScreenAssistant.ask(input.text.toString().ifBlank{"解释当前页面，告诉我重点"},pageOverride=page,frameOverride=frame);finish()}
        button("语音问屏幕"){voice()}
        button("规划下一步"){if(input.text.isNotBlank()){val task=input.text.toString();finish();ScreenAssistant.askAfterReturning(task,true)}else input.error="说说你想完成什么"}
        button("关闭"){finish()};setContentView(root)
        if(intent.getBooleanExtra("voice",false))voice()
    }
    private fun voice(){runCatching{startActivityForResult(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_LANGUAGE,"zh-CN").putExtra(RecognizerIntent.EXTRA_PROMPT,"问问当前屏幕"),8)}.onFailure{Toast.makeText(this,"当前手机没有系统语音识别，可直接输入",Toast.LENGTH_LONG).show()}}
    @Deprecated("Uses platform speech activity result") override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){super.onActivityResult(requestCode,resultCode,data);if(requestCode==8&&resultCode==RESULT_OK){input.setText(data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty())}}
}
