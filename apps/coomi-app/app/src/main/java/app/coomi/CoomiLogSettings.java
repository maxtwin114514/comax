package app.coomi;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

/**
 * 引擎日志的位置与轮转策略。
 *
 * 为什么单独抽出来（1.5.4 第 12 项）：用户要求「在控制台里能指定引擎启动失败后的
 * 日志放在哪」，方便把文件直接发给开发者排查。之前路径是 {@link CoomiConstants}
 * 里的一个常量，改不了；现在默认值不变（$HOME/coomi.log），但用户可以在
 * 「报错日志」页面里改成任意可写路径，改完对**下一次启动**生效。
 *
 * 注意：引擎是用 shell 追加重定向写日志的（`exec >>路径 2>&1`），
 * 所以这里返回的路径必须是一个**文件**路径而不是目录；父目录会在启动前自动创建。
 */
public final class CoomiLogSettings {
    private static final String PREFS = "coomi_log_settings";
    private static final String KEY_ENGINE_LOG = "engine_log_path";

    private CoomiLogSettings() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 引擎日志路径（默认 $HOME/coomi.log）。 */
    public static String engineLogPath(Context context) {
        String saved = prefs(context).getString(KEY_ENGINE_LOG, "");
        if (saved != null && !saved.trim().isEmpty()) return saved.trim();
        return CoomiConstants.ENGINE_LOG_PATH;
    }

    /** 保存自定义路径；传空字符串表示恢复默认。 */
    public static void setEngineLogPath(Context context, String path) {
        prefs(context).edit().putString(KEY_ENGINE_LOG, path == null ? "" : path.trim()).apply();
    }

    /**
     * 启动引擎前准备日志文件：建父目录，并在超过 8MB 时轮转成 `.1`。
     *
     * 返回失败原因（可读中文）；成功返回 null。引擎启动流程会把它拼进失败信息里，
     * 用户因此能看到「日志写不了」而不是一个凭空的"引擎起不来"。
     */
    public static String prepareEngineLog(Context context) {
        File target = new File(engineLogPath(context));
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            return "无法创建日志目录：" + parent.getAbsolutePath();
        }
        try {
            if (target.isFile() && target.length() > 8L * 1024L * 1024L) {
                File rotated = new File(target.getAbsolutePath() + ".1");
                if (rotated.isFile() && !rotated.delete()) {
                    // 删不掉旧轮转文件不算致命：直接继续追加，避免因为日志把引擎拦下来。
                    return null;
                }
                if (!target.renameTo(rotated)) return null;
            }
            if (!target.exists() && !target.createNewFile()) {
                return "无法创建日志文件：" + target.getAbsolutePath();
            }
        } catch (Exception error) {
            return "日志文件不可写：" + error.getMessage();
        }
        return null;
    }
}
