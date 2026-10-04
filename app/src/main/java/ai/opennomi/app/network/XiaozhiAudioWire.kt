package ai.opennomi.app.network

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** BinaryProtocol1/2/3 from 78/xiaozhi-esp32's public websocket protocol.
 * Version comes from device registration, matching the official client.
 */
object XiaozhiAudioWire {
    fun encode(opus:ByteArray,version:Int):ByteArray = when(version) {
        1->opus
        2->ByteBuffer.allocate(16+opus.size).order(ByteOrder.BIG_ENDIAN).putShort(2).putShort(0).putInt(0).putInt(0).putInt(opus.size).put(opus).array()
        3->ByteBuffer.allocate(4+opus.size).order(ByteOrder.BIG_ENDIAN).put(0).put(0).putShort(opus.size.toShort()).put(opus).array()
        else->error("不支持的语音协议版本")
    }
    fun decode(data:ByteArray,version:Int):ByteArray? = when(version) {
        1->data.takeIf{it.isNotEmpty()}
        2->if(data.size<=16)null else ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN).let{b->
            val v=b.short.toInt();val type=b.short.toInt();b.int;b.int;val n=b.int
            if(v==2 && type==0 && n>0 && n==data.size-16)data.copyOfRange(16,data.size) else null
        }
        3->if(data.size<=4)null else {
            val n=((data[2].toInt() and 255) shl 8) or (data[3].toInt() and 255)
            if(data[0].toInt()==0 && n==data.size-4)data.copyOfRange(4,data.size) else null
        }
        else->null
    }
}
