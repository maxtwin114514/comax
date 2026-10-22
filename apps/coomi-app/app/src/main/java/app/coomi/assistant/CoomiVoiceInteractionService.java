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
        // 这里**故意什么都不做**（1.5.4 第 2 项）。
        //
        // 之前的写法在 onReady 里就把 CoomiService + 引擎监控拉起来并启动引擎。
        // 问题在于 onReady 是「系统绑定助手服务」时调用的 —— 用户把 Comax 设成
        // 默认助手之后，**开机就会调用**，跟"有没有打开 App""有没有给自启动权限"
        // 完全无关。于是用户看到的现象就是：没开软件，引擎在后台跑起来了。
        //
        // 现在改成按需拉起：只有真正要弹助手会话时（见 CoomiVoiceInteractionSession
        // 与 {@link #wakeEngineHost()}）才创建宿主并启动引擎，界面在前台时由
        // CoomiActivity 自己负责拉起。
    }

    /**
     * 真正要用助手了才把引擎宿主拉起来。
     *
     * 由语音会话创建流程调用；界面在前台时这一步通常已经由 App 完成，
     * 重复调用是安全的（startEngine 内部幂等）。
     */
    static void wakeEngineHost() {
        CoomiVoiceInteractionService service = sCurrent;
        if (service == null) return;
        try {
            service.startService(new Intent(service, CoomiService.class));
            service.startService(new Intent(service, CoomiEngineMonitor.class));
        } catch (Exception error) {
            Logger.logError(LOG_TAG, "engine host start failed: " + error.getMessage());
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            CoomiService service2 = CoomiService.current();
            if (service2 != null) service2.startEngine(result -> { /* 会话里会展示状态 */ });
        }, 180L);
    }

    @Override public void onLaunchVoiceAssistFromKeyguard() {
        wakeEngineHost();
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
