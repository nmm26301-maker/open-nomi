package ai.opennomi.app.screen

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import okhttp3.OkHttpClient
import okhttp3.Request
import org.vosk.Model
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

data class PlaybackModel(val id:String,val language:String,val label:String,val folder:String,val sizeMb:Int) {
    val url get()="https://alphacephei.com/vosk/models/$folder.zip"
}

/** Models are selected explicitly: the English recognizer must never decode Japanese audio. */
object PlaybackModels {
    val choices=listOf(
        PlaybackModel("en","en","英语 · 内置","vosk-model-small-en-us-0.15",40),
        PlaybackModel("en-wide","en","英语 · 增强","vosk-model-en-us-0.22-lgraph",128),
        PlaybackModel("ja","ja","日语","vosk-model-small-ja-0.22",48),
        PlaybackModel("ko","ko","韩语","vosk-model-small-ko-0.22",82),
        PlaybackModel("fr","fr","法语","vosk-model-small-fr-0.22",41),
        PlaybackModel("de","de","德语","vosk-model-small-de-0.15",45),
        PlaybackModel("ru","ru","俄语","vosk-model-small-ru-0.22",45),
        PlaybackModel("es","es","西班牙语","vosk-model-small-es-0.42",39)
    )
    fun selected(id:String)=choices.firstOrNull{it.id==id} ?: choices.first()
    private val client=OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS)
        .readTimeout(45,TimeUnit.SECONDS).callTimeout(10,TimeUnit.MINUTES).followSslRedirects(false).build()
    private fun root(context:Context,model:PlaybackModel)=if(model.id=="en")File(context.filesDir,"playback-en") else File(context.filesDir,"playback-models/${model.folder}")
    fun ready(context:Context,model:PlaybackModel)=File(root(context,model),"ready").isFile && valid(root(context,model))
    private fun valid(root:File)=File(root,"am/final.mdl").isFile && File(root,"conf/mfcc.conf").isFile && File(root,"graph").isDirectory

    @Synchronized fun prepare(context:Context,model:PlaybackModel,cellular:Boolean=false,
        active:()->Boolean={true},progress:(String)->Unit={}):File {
        val target=root(context,model)
        if(ready(context,model))return target
        check(active()){"已取消模型准备"}
        val staging=File(target.parentFile,"${target.name}.installing")
        staging.deleteRecursively();check(staging.mkdirs()){"无法创建语音模型目录"}
        try {
            if(model.id=="en") {
                progress("正在准备内置英语语音模型")
                fun copy(path:String,relative:String) {
                    check(active()){"已取消模型准备"}
                    val children=context.assets.list(path).orEmpty()
                    if(children.isEmpty()) {
                        val file=File(staging,relative);file.parentFile?.mkdirs()
                        context.assets.open(path).use{input->file.outputStream().use{input.copyTo(it)}}
                    } else children.forEach{copy("$path/$it",if(relative.isEmpty())it else "$relative/$it")}
                }
                copy("playback-model","")
            } else {
                val connectivity=context.getSystemService(ConnectivityManager::class.java)
                val network=connectivity.activeNetwork ?: error("首次准备${model.label}语音模型需要联网")
                val caps=connectivity.getNetworkCapabilities(network)
                check(cellular || caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true || caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)==true){"请连接 Wi-Fi，或在翻译页允许移动网络下载模型"}
                progress("正在下载${model.label}语音模型 · 约 ${model.sizeMb} MB")
                val zip=File(context.cacheDir,"${model.folder}.download")
                try {
                    client.newCall(Request.Builder().url(model.url).build()).execute().use{response->
                        check(response.isSuccessful){"语音模型下载失败（HTTP ${response.code}），请稍后重试"}
                        check(response.request.url.scheme=="https" && response.request.url.host=="alphacephei.com"){"语音模型下载来源异常"}
                        val body=response.body ?: error("下载内容为空")
                        val total=body.contentLength();var received=0L;var last=0L
                        body.byteStream().use{input->zip.outputStream().use{output->
                            val buffer=ByteArray(65536)
                            while(true){check(active()){"已取消模型下载"};val n=input.read(buffer);if(n<0)break;received+=n
                                check(received<=256L*1024*1024){"语音模型下载超过大小限制"};output.write(buffer,0,n)
                                if(received-last>=1024*1024){last=received;progress("下载${model.label}模型 · ${received/1024/1024} MB${if(total>0)" / ${total/1024/1024} MB" else ""}")}
                            }
                        }}
                        check(total<0 || received==total){"语音模型下载不完整，请重试"}
                    }
                    val digest=MessageDigest.getInstance("SHA-256")
                    zip.inputStream().use{input->val b=ByteArray(65536);while(true){val n=input.read(b);if(n<0)break;digest.update(b,0,n)}}
                    progress("正在校验并安装${model.label}语音模型")
                    ZipInputStream(zip.inputStream()).use{input->
                        var expanded=0L;var entries=0
                        while(true){check(active()){"已取消模型安装"};val entry=input.nextEntry ?: break
                            check(++entries<=2000){"语音模型文件数量异常"}
                            check(entry.name.startsWith("${model.folder}/")){"语音模型目录不符合预期"}
                            val relative=entry.name.removePrefix("${model.folder}/")
                            if(relative.isEmpty()){input.closeEntry();continue}
                            val out=File(staging,relative)
                            check(out.canonicalPath.startsWith(staging.canonicalPath+File.separator)){"语音模型含非法文件路径"}
                            if(entry.isDirectory)out.mkdirs() else {
                                out.parentFile?.mkdirs();out.outputStream().use{output->val b=ByteArray(65536)
                                    while(true){check(active()){"已取消模型安装"};val n=input.read(b);if(n<0)break;expanded+=n
                                        check(expanded<=768L*1024*1024){"语音模型解压超过大小限制"};output.write(b,0,n)}
                                }
                            };input.closeEntry()
                        }
                    }
                    File(staging,"archive-sha256").writeText(digest.digest().joinToString(""){"%02x".format(it)})
                } finally {zip.delete()}
            }
            check(valid(staging)){"语音模型缺少必要文件，请重新下载"}
            // A ready marker is written only after the real native recognizer accepts the model.
            Model(staging.absolutePath).close()
            check(active()){"已取消模型准备"}
            File(staging,"ready").writeText(model.folder)
            if(target.exists())check(target.deleteRecursively()){"旧语音模型清理失败"}
            check(staging.renameTo(target)){"语音模型安装失败"}
            progress("${model.label}语音模型已就绪 · 可离线识别")
            return target
        } finally {staging.deleteRecursively()}
    }
}
