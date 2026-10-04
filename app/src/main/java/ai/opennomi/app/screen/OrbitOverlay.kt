package ai.opennomi.app.screen

import android.content.*
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import kotlin.math.*

class OrbitOverlay(private val service:ScreenShareService) {
    private val wm=service.getSystemService(WindowManager::class.java);private val main=Handler(Looper.getMainLooper());private val density=service.resources.displayMetrics.density
    private fun dp(n:Int)=(n*density).toInt()
    private var ball: GalaxyOrb?=null;private var panel:LinearLayout?=null;private var caption:TextView?=null;private var description:TextView?=null
    private val color=0xFF7DD3FC.toInt();private var params:WindowManager.LayoutParams?=null
    @Volatile private var regions:List<Rect> = emptyList()
    private fun layout(w:Int,h:Int,gravity:Int)=WindowManager.LayoutParams(w,h,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_SECURE,PixelFormat.TRANSLUCENT).apply{this.gravity=gravity}
    private fun bg()=GradientDrawable().apply{setColor(0xF2101828.toInt());cornerRadius=dp(22).toFloat();setStroke(dp(1),0x705D89BD)}
    fun show() {
        if(ball!=null)return
        if(!Settings.canDrawOverlays(service)){ScreenState.event("共享已开启；允许悬浮窗后可显示球球");return}
        val dm=service.resources.displayMetrics
        params=layout(dp(74),dp(74),Gravity.TOP or Gravity.LEFT).apply{x=dm.widthPixels-dp(84);y=dm.heightPixels/2}
        val orb=GalaxyOrb(service);ball=orb
        var downX=0f;var downY=0f;var originX=0;var originY=0;var downTime=0L;var moved=false
        val detector=GestureDetector(service,object:GestureDetector.SimpleOnGestureListener(){
            override fun onDown(e:MotionEvent)=true
            override fun onSingleTapConfirmed(e:MotionEvent):Boolean{if(!moved)togglePanel();return true}
            override fun onDoubleTap(e:MotionEvent):Boolean{ScreenAssistant.savePage();return true}
            override fun onLongPress(e:MotionEvent){if(!moved)prompt(false)}
        })
        orb.setOnTouchListener { _,e ->
            if(e.actionMasked==MotionEvent.ACTION_DOWN){downX=e.rawX;downY=e.rawY;originX=params!!.x;originY=params!!.y;downTime=System.currentTimeMillis();moved=false}
            val dx=e.rawX-downX;val dy=e.rawY-downY
            if(e.actionMasked==MotionEvent.ACTION_MOVE && hypot(dx,dy)>dp(12)){moved=true;params!!.x=(originX+dx.toInt()).coerceIn(0,maxOf(0,dm.widthPixels-dp(74)));params!!.y=(originY+dy.toInt()).coerceIn(dp(32),maxOf(dp(32),dm.heightPixels-dp(104)));runCatching{wm.updateViewLayout(orb,params)};updateRegions()}
            detector.onTouchEvent(e)
            if(e.actionMasked==MotionEvent.ACTION_UP && moved && System.currentTimeMillis()-downTime<420 && hypot(dx,dy)>dp(64)){
                when { abs(dy)>abs(dx) && dy<0 -> service.toggleTranslation();abs(dy)>abs(dx) -> service.startActivity(Intent(service,WorkspaceActivity::class.java).putExtra("tab","tasks").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));dx>0 -> prompt(true) }
            }
            true
        }
        runCatching{wm.addView(orb,params)}.onFailure{ball=null;ScreenState.event("悬浮窗未能显示，请检查权限")}
        updateRegions()
    }
    private fun prompt(voice:Boolean){closePanel();ScreenPromptActivity.observedPage=ScreenState.state.value.page;ScreenPromptActivity.observedFrame=ScreenState.frame;service.startActivity(Intent(service,ScreenPromptActivity::class.java).putExtra("voice",voice).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
    private fun togglePanel(){if(panel!=null){closePanel();return}
        val root=LinearLayout(service).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(12),dp(18),dp(12));background=bg()}
        description=TextView(service).apply{setTextColor(0xFFE2ECFA.toInt());textSize=14f;maxLines=6;setPadding(0,0,0,dp(8));text=ScreenState.state.value.status+"\n"+ScreenState.state.value.reply.take(600)}
        root.addView(description)
        fun button(text:String,action:()->Unit){root.addView(TextView(service).apply{this.text=text;setTextColor(color);textSize=14f;setPadding(dp(4),dp(10),dp(4),dp(10));setOnClickListener{action()}})}
        button("确认下一步操作") { closePanel(); main.postDelayed({ ScreenAssistant.confirm() }, 250) }
        button("问问这页 / 语音互动"){prompt(false)}
        button("翻译开关"){service.toggleTranslation()}
        button("关闭浮条提示"){ScreenState.update{it.copy(notice="")}}
        button("保存页面文字"){ScreenAssistant.savePage()}
        button("工作台 · 原图 / 任务 / 记忆"){closePanel();service.startActivity(Intent(service,WorkspaceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
        button("停止共享"){service.stopSelf()}
        panel=root;runCatching{wm.addView(root,layout(service.resources.displayMetrics.widthPixels-dp(32),WindowManager.LayoutParams.WRAP_CONTENT,Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply{y=dp(32)})};root.post{updateRegions()}
    }
    private fun closePanel(){panel?.let{runCatching{wm.removeView(it)}};panel=null;description=null;updateRegions()}
    fun render(state:WorkspaceState){
        ball?.mode=when{state.busy->2;state.proposed!=null->3;state.audio->1;state.status.contains("完成")->4;else->0}
        description?.text=state.status+"\n"+state.reply.take(600)
        val line=state.notice.ifBlank{if(state.translation)state.caption else ""}
        if(line.isNotBlank()){
            if(caption==null){val text=TextView(service).apply{setTextColor(0xFFE9F3FF.toInt());textSize=14f;maxLines=7;setPadding(dp(18),dp(12),dp(18),dp(12));background=bg();setOnClickListener{runCatching{wm.removeView(this)};caption=null;updateRegions()}}
                caption=text;runCatching{wm.addView(text,layout(service.resources.displayMetrics.widthPixels-dp(24),WindowManager.LayoutParams.WRAP_CONTENT,Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply{y=dp(14);flags=flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;alpha=.78f})}}
            caption?.text=line;caption?.post{updateRegions()}
        } else {caption?.let{runCatching{wm.removeView(it)}};caption=null;updateRegions()}
    }
    private fun updateRegions(){regions=listOfNotNull(ball,panel,caption).map{v->val pos=IntArray(2);v.getLocationOnScreen(pos);Rect(pos[0],pos[1],pos[0]+v.width,pos[1]+v.height)}}
    fun mask(bitmap:Bitmap){val dm=service.resources.displayMetrics;val scale=bitmap.width.toFloat()/dm.widthPixels;val c=Canvas(bitmap);val p=Paint().apply{color=Color.BLACK};regions.forEach{r->c.drawRect(r.left*scale,r.top*scale,r.right*scale,r.bottom*scale,p)}}
    fun close(){main.removeCallbacksAndMessages(null);closePanel();caption?.let{runCatching{wm.removeView(it)}};caption=null;ball?.let{it.stop();runCatching{wm.removeView(it)}};ball=null}
}
class GalaxyOrb(context:Context):View(context) {
    var mode=0;private val paint=Paint(Paint.ANTI_ALIAS_FLAG);private var active=true;private val started=SystemClock.elapsedRealtime()
    override fun onDraw(c:Canvas){super.onDraw(c);val x=width/2f;val y=height/2f;val breath=1f+sin((SystemClock.elapsedRealtime()-started)/2000.0*2*PI).toFloat()*.035f;val r=min(width,height)*.38f*breath
        val accent=when(mode){1->0xFF7DD3FC.toInt();2->0xFFC084FC.toInt();3->0xFFFCD34D.toInt();4->0xFF34D399.toInt();else->0xFF7399FF.toInt()}
        paint.shader=RadialGradient(x,y,r*1.35f,intArrayOf(accent and 0xFFFFFF or 0x70000000,Color.TRANSPARENT),floatArrayOf(.55f,1f),Shader.TileMode.CLAMP);c.drawCircle(x,y,r*1.35f,paint)
        paint.shader=RadialGradient(x-r*.25f,y-r*.3f,r*1.45f,intArrayOf(0xFF6C3AAC.toInt(),0xFF081423.toInt(),accent),floatArrayOf(0f,.62f,1f),Shader.TileMode.CLAMP);c.drawCircle(x,y,r,paint)
        paint.shader=null;paint.style=Paint.Style.STROKE;paint.strokeWidth=r*.028f;paint.color=accent;c.drawCircle(x,y,r,paint);paint.style=Paint.Style.FILL;paint.color=0xFFDDF5FF.toInt();c.drawOval(x-r*.23f,y-r*.07f,x-r*.13f,y+r*.1f,paint);c.drawOval(x+r*.13f,y-r*.07f,x+r*.23f,y+r*.1f,paint)
        if(active)postInvalidateDelayed(40)
    }
    fun stop(){active=false}
}
