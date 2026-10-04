package ai.opennomi.app.audio

/** Utterance endpoint and sustained barge-in detection; independent of the recognizer/server. */
class SpeechGate(private val silenceMillis:Long=1800,private val bargeInFrames:Int=6,private val now: () -> Long) {
    private var started = now()
    private var lastVoice = started
    private var voicedFrames = 0
    private var heard = false
    private var announced = false
    fun accept(rms: Float): Boolean {
        val time = now()
        if (rms > 0.025f) { lastVoice = time; heard = true }
        if (rms > 0.045f && time - started > 650) {
            if (++voicedFrames >= bargeInFrames && !announced) { announced = true; return true }
        } else { voicedFrames = 0; if (time - lastVoice > 350) announced = false }
        return false
    }
    fun ended(): Boolean = heard && now() - lastVoice >= silenceMillis
    fun consumeEnd():Boolean{if(!ended())return false;heard=false;announced=false;voicedFrames=0;return true}
}
