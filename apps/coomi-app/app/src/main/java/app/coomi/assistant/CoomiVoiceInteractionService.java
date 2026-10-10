package app.coomi.assistant;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.Intent;
import android.service.voice.VoiceInteractionService;
import android.service.voice.VoiceInteractionSession;

import app.coomi.CoomiEngineMonitor;
import app.coomi.CoomiService;

/** Android-owned entry point used only after the user selects Comax as the default assistant. */
public final class CoomiVoiceInteractionService extends VoiceInteractionService {
    @Override public void onReady() {
        super.onReady();
        // The system can invoke the assistant after Comax's UI process was reclaimed.
        // Recreate the local engine host without opening an Activity.
        startService(new Intent(this, CoomiService.class));
        startService(new Intent(this, CoomiEngineMonitor.class));
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            CoomiService service = CoomiService.current();
            if (service != null) service.startEngine(result -> { /* status is shown by the session */ });
        }, 180L);
    }

    @Override public void onLaunchVoiceAssistFromKeyguard() {
        showSession(new Bundle(), VoiceInteractionSession.SHOW_WITH_ASSIST
            | VoiceInteractionSession.SHOW_WITH_SCREENSHOT);
    }
}
