package ai.opennomi.app.screen

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Official immutable weights; no executable model code is downloaded. */
object HyMtModel {
    const val NAME="Hy-MT2 1.8B · Q4_K_M"
    const val FILE="Hy-MT2-1.8B-Q4_K_M.gguf"
    const val REVISION="b27182d810fa3ceb6ed04e7c324c54e35c0d209c"
    const val BYTES=1133080448L
    const val SHA256="dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699"
    const val URL="https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF/resolve/$REVISION/$FILE"
    private val client=OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS)
        .callTimeout(25,TimeUnit.MINUTES).followSslRedirects(false).build()
    @Volatile private var call:Call?=null
    fun cancel(){call?.cancel()}
    fun file(context:Context)=File(context.filesDir,"hymt2/$FILE")
    private fun marker(context:Context)=File(file(context).parentFile,"verified.sha256")
    fun ready(context:Context):Boolean {
        val path=file(context)
        return path.isFile && path.length()==BYTES && runCatching{marker(context).readText()=="$SHA256\n${path.lastModified()}"}.getOrDefault(false)
    }
    private fun verify(path:File,active:()->Boolean) {
        check(path.length()==BYTES){"Hy-MT2 文件大小不符，请选择官方 Q4_K_M 模型（约 1.13 GB）"}
        val digest=MessageDigest.getInstance("SHA-256")
        path.inputStream().use{input->val buffer=ByteArray(262144);while(true){check(active()){"已取消模型校验"};val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}}
        check(digest.digest().joinToString(""){"%02x".format(it)}==SHA256){"Hy-MT2 校验失败，文件不完整或不是指定的官方模型"}
    }
    private fun install(context:Context,staging:File,active:()->Boolean):File {
        verify(staging,active)
        check(active()){"已取消模型安装"}
        val path=file(context)
        check(staging.renameTo(path)){"Hy-MT2 安装失败，请检查手机存储"}
        marker(context).writeText("$SHA256\n${path.lastModified()}")
        return path
    }
    private fun installDownload(context:Context,staging:File,active:()->Boolean):File {
        return try{install(context,staging,active)}catch(e:Exception){if(staging.length()==BYTES && active())staging.delete();throw e}
    }
    @Synchronized fun prepare(context:Context,cellular:Boolean,active:()->Boolean,progress:(String)->Unit):File {
        if(ready(context))return file(context)
        val path=file(context);check(path.parentFile!!.let{it.isDirectory || it.mkdirs()}){"无法创建 Hy-MT2 目录"}
        val staging=File(path.parentFile,"$FILE.download")
        if(staging.length()>BYTES)staging.delete()
        if(staging.length()==BYTES) {
            progress("正在校验已下载的 Hy-MT2，无需重新联网")
            return installDownload(context,staging,active)
        }
        val manager=context.getSystemService(ConnectivityManager::class.java)
        val caps=manager.getNetworkCapabilities(manager.activeNetwork ?: error("首次下载 Hy-MT2 需要联网，也可导入官方模型文件"))
        check(cellular || caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true || caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)==true){"请连接 Wi-Fi，或允许移动网络下载 Hy-MT2（约 1.13 GB）"}
        val offset=staging.length()
        check(path.parentFile!!.usableSpace>BYTES-offset+128L*1024*1024){"手机存储不足，请腾出约 1.3 GB 后重试"}
        check(active()){"已取消模型下载"}
        progress("正在下载 Hy-MT2 · ${offset/1024/1024} / 1080 MB · 可取消后续传")
        val request=Request.Builder().url(URL).apply{if(offset>0)header("Range","bytes=$offset-")}.build()
        val current=client.newCall(request);call=current
        try {
            current.execute().use{response->
                check(response.code==200 || response.code==206){"Hy-MT2 下载失败（HTTP ${response.code}），可稍后重试或导入模型"}
                check(response.request.url.scheme=="https"){"Hy-MT2 下载来源异常"}
                val append=response.code==206 && offset>0
                if(append)check(response.header("Content-Range").orEmpty().startsWith("bytes $offset-")){"Hy-MT2 续传位置不一致，请重试"}
                var received=if(append)offset else 0L;var last=received
                val body=response.body ?: error("Hy-MT2 下载内容为空")
                body.byteStream().use{input->FileOutputStream(staging,append).use{output->
                    val buffer=ByteArray(262144)
                    while(true){check(active()){"已取消模型下载"};val n=input.read(buffer);if(n<0)break;received+=n
                        check(received<=BYTES){"Hy-MT2 下载内容超过官方大小"};output.write(buffer,0,n)
                        if(received-last>=2L*1024*1024){last=received;progress("下载 Hy-MT2 · ${received/1024/1024} / 1080 MB")}
                    }
                    output.fd.sync()
                }}
            }
            progress("正在校验 Hy-MT2，请稍候")
            return installDownload(context,staging,active)
        } finally {if(call===current)call=null}
    }
    @Synchronized fun import(context:Context,uri:Uri,active:()->Boolean,progress:(String)->Unit):File {
        val target=file(context);target.parentFile!!.mkdirs()
        check(target.parentFile!!.usableSpace>BYTES+64L*1024*1024){"导入 Hy-MT2 需要约 1.2 GB 空闲存储"}
        val staging=File(target.parentFile,"$FILE.importing")
        try {
            progress("正在导入 Hy-MT2 官方模型")
            (context.contentResolver.openInputStream(uri) ?: error("无法读取所选模型")).use{input->staging.outputStream().use{output->
                var size=0L;val buffer=ByteArray(262144)
                while(true){check(active()){"已取消模型导入"};val n=input.read(buffer);if(n<0)break;size+=n;check(size<=BYTES){"所选文件不是指定的 Hy-MT2 Q4_K_M 模型"};output.write(buffer,0,n)}
                output.fd.sync()
            }}
            progress("正在校验 Hy-MT2 官方模型")
            return install(context,staging,active)
        } finally {staging.delete()}
    }
}

