package ai.opennomi.app.screen;

/** One policy for the wait loop and the final upload gate. Uses observation timestamps. */
public final class FrameReadiness {
    private FrameReadiness() {}
    public static boolean matches(long expectedSession, String pageApp, long pageVersion,
            long frameSession, String frameApp, long frameVersion, long capturedAt,
            long earliestCapture, long now, long maxAge) {
        return pageApp != null && !pageApp.isEmpty() && pageApp.equals(frameApp)
            && expectedSession == frameSession && pageVersion == frameVersion
            && capturedAt >= earliestCapture && capturedAt <= now
            && now - capturedAt < maxAge;
    }
}
