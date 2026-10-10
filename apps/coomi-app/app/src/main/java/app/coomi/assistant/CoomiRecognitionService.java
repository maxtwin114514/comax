package app.coomi.assistant;

import android.content.Intent;
import android.os.RemoteException;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;

/**
 * Compatibility declaration required by VoiceInteractionService on Android 11 and older.
 * Actual configurable transcription is handled by the local Comax engine.
 */
public final class CoomiRecognitionService extends RecognitionService {
    @Override protected void onStartListening(Intent recognizerIntent, Callback listener) {
        try { listener.error(SpeechRecognizer.ERROR_CLIENT); } catch (RemoteException ignored) {}
    }

    @Override protected void onCancel(Callback listener) {}

    @Override protected void onStopListening(Callback listener) {
        try { listener.error(SpeechRecognizer.ERROR_CLIENT); } catch (RemoteException ignored) {}
    }
}
