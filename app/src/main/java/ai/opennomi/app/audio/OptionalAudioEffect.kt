package ai.opennomi.app.audio

/** Optional hardware processing must never prevent ordinary microphone capture. */
object OptionalAudioEffect {
 fun <T> create(factory:()->T?,enable:(T)->Boolean,release:(T)->Unit):T? {
  val effect=runCatching(factory).getOrNull()?:return null
  if(runCatching{enable(effect)}.getOrDefault(false))return effect
  runCatching{release(effect)};return null
 }
}
