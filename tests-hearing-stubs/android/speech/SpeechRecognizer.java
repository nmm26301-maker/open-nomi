package android.speech;
import java.util.*;import android.app.Activity;import android.content.Intent;import android.os.Bundle;
public class SpeechRecognizer {
 public static final String RESULTS_RECOGNITION="results";
 public static final int ERROR_NO_MATCH=7,ERROR_SPEECH_TIMEOUT=6,ERROR_RECOGNIZER_BUSY=8,ERROR_INSUFFICIENT_PERMISSIONS=9,ERROR_NETWORK=2,ERROR_NETWORK_TIMEOUT=1,ERROR_AUDIO=3;
 public static boolean available=true,ready=true,cancelThrows;
 public static final List<SpeechRecognizer> instances=new ArrayList<>();
 public RecognitionListener listener; public boolean destroyed;
 public static boolean isRecognitionAvailable(Activity a){return available;}
 public static SpeechRecognizer createSpeechRecognizer(Activity a){SpeechRecognizer s=new SpeechRecognizer();instances.add(s);return s;}
 public void setRecognitionListener(RecognitionListener l){listener=l;}
 public void startListening(Intent i){if(ready)listener.onReadyForSpeech(new Bundle());}
 public void cancel(){if(cancelThrows)throw new IllegalStateException("cancel failed");} public void destroy(){destroyed=true;}
 public static SpeechRecognizer latest(){return instances.get(instances.size()-1);}
 public static void reset(){available=true;ready=true;cancelThrows=false;instances.clear();}
}
