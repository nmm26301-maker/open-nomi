package ai.opennomi.playbackfixture;

import android.app.Activity;
import android.os.Bundle;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.widget.TextView;
import android.view.WindowManager;

/** Separate UID: the production capturer excludes OpenNomi's own playback. */
public class PlaybackFixtureActivity extends Activity {
    private MediaPlayer player;
    private int taps;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        TextView text = new TextView(this);
        text.setText("Synthetic capture test · taps=0\nVosk public sample audio\nNo embedded subtitles");
        text.setOnClickListener(view -> {
            taps++;
            text.setText("Synthetic capture test · taps=" + taps + "\nVosk public sample audio\nNo embedded subtitles");
        });
        text.setTextSize(22); text.setPadding(24,96,24,24); setContentView(text);
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL).build());
            try (android.content.res.AssetFileDescriptor file=getResources().openRawResourceFd(getIntent().getBooleanExtra("natural", false) ? R.raw.jfk : R.raw.speech)) {
                player.setDataSource(file.getFileDescriptor(),file.getStartOffset(),file.getLength());
            }
            player.setLooping(true);player.prepare();player.start();
        } catch(Exception e) { throw new IllegalStateException("Fixture playback failed",e); }
    }
    @Override public void onDestroy() { if(player!=null)player.release(); super.onDestroy(); }
}
