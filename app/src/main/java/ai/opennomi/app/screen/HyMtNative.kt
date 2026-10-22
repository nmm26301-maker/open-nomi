package ai.opennomi.app.screen

import androidx.annotation.Keep

/** All inference/load/close calls run on HyMtTranslator's serial worker; cancel is atomic. */
@Keep internal object HyMtNative {
    init {System.loadLibrary("opennomi_hymt")}
    external fun open(path:ByteArray,threads:Int):Long
    external fun reset(handle:Long)
    external fun cancel(handle:Long)
    external fun close(handle:Long)
    external fun translate(handle:Long,instruction:ByteArray,sink:Sink):ByteArray
    @Keep interface Sink {@Keep fun onBytes(value:ByteArray)}
}
