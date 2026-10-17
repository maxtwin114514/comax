package app.coomi.assistant;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.Intent;
import android.service.voice.VoiceInteractionService;
import android.service.voice.VoiceInteractionSession;

import com.termux.shared.logger.Logger;

import app.coomi.CoomiEngineMonitor;
import app.coomi.CoomiService;

/**
 * Android-owned entry point used only after the user selects Comax as the default assistant.
 *
 * <p>除了系统拉起，本类还提供 {@link #requestVoiceSession()} 给应用内两条「唤醒」路径用：
 * 无障碍里的电源键长按（0.5s 阈值）与 ACTION_ASSIST 入口。系统只允许
 * VoiceInteractionService 自己弹会话，所以必须经过这里，不能从 Activity 直接 new。
 */
public final class CoomiVoiceInteractionService extends VoiceInteractionService {
    private static final String LOG_TAG = "CoomiVoiceAssist";

    /** 系统绑定后的实例；应用内唤醒需要它来 showSession。 */
    private static volatile CoomiVoiceInteractionService sCurrent;

    static CoomiVoiceInteractionService current() {
        return sCurrent;
    }

    /**
     * 应用内请求弹一次语音胶囊。
     *
     * @return true 表示已经把请求交给系统（不代表胶囊一定显示成功，系统仍可能拒绝）
     */
    public static boolean requestVoiceSession() {
        CoomiVoiceInteractionService service = sCurrent;
        if (service == null) return false;
        try {
            service.showSession(new Bundle(), VoiceInteractionSession.SHOW_WITH_ASSIST);
            return true;
        } catch (Exception error) {
            Logger.logError(LOG_TAG, "showSession failed: " + error.getMessage());
            return false;
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        sCurrent = this;
    }

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

    /** 系统拒绝/失败时留一行日志：用户报「长按没反应」时这是唯一线索。 */
    @Override public void onShowSessionFailed(Bundle args) {
        super.onShowSessionFailed(args);
        Logger.logError(LOG_TAG, "System rejected the assistant session request");
    }

    @Override public void onDestroy() {
        if (sCurrent == this) sCurrent = null;
        super.onDestroy();
    }
}
