import ai.opennomi.app.screen.FrameReadiness;

public class FrameReadinessRegression {
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }
    private static boolean match(long session, String app, long version, long timestamp) {
        return FrameReadiness.matches(7, "target.app", 12, session, app, version, timestamp, 1000, 9000, 8000);
    }
    public static void main(String[] args) {
        check(match(7, "target.app", 12, 2000), "fresh matching frame must be usable");
        check(!match(6, "target.app", 12, 2000), "previous sharing session must be rejected");
        check(!match(7, "other.app", 12, 2000), "other app image must be rejected");
        check(!match(7, "target.app", 11, 2000), "previous page image must be rejected");
        check(!match(7, "target.app", 12, 999), "frame captured before the new request must be rejected");
        check(!match(7, "target.app", 12, 1000), "8-second age boundary must be rejected");
        check(!match(7, "target.app", 12, 9001), "future timestamps must be rejected");
        check(!FrameReadiness.matches(7, "", 12, 7, "", 12, 2000, 1000, 9000, 8000), "unknown page must be rejected");
        System.out.println("PASS: 8 frame freshness/session/app/version regressions");
    }
}
