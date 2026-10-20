package ai.opennomi.app.screen

import java.util.Locale

/** Restore the omitted imperative in a narrow, unambiguous directional contrast.
 * "Turn left, not right" -> "Turn to the left. Do not turn to the right."
 * This supplies the shared verb, not a guessed translation or additional context. */
object EnglishContrast {
    private const val direction="left|right|up|down|forward|backward|backwards|ahead|back|north|south|east|west|clockwise|counterclockwise|anticlockwise"
    private val command=Regex(
        "^(please\\s+)?(turn|go|move|look|walk|drive|head|keep)\\s+($direction)\\s*,?\\s+(?:not|rather\\s+than)\\s+($direction)\\s*[.!]?$",
        RegexOption.IGNORE_CASE)
    fun complete(text:String):String {
        val match=command.matchEntire(text.trim()) ?: return text
        val polite=match.groupValues[1]
        val verb=match.groupValues[2].lowercase(Locale.ROOT)
        val first=match.groupValues[3].lowercase(Locale.ROOT)
        val second=match.groupValues[4].lowercase(Locale.ROOT)
        fun complement(direction:String)=if(direction=="left" || direction=="right")"to the "+direction else direction
        return polite+verb+" "+complement(first)+". Do not "+verb+" "+complement(second)+"."
    }
}
