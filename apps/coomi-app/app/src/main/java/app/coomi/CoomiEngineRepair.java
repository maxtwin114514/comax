package app.coomi;

import android.content.Context;
import android.os.Process;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;

/**
 * 引擎修复（1.5.4o4 第 5 项）。
 *
 * 目标场景：引擎起不来了 —— 前端资产解包坏了、Runtime V2 版本目录被写坏、
 * 部署标记与二进制指纹不一致、或者反复拉起引擎留下了一堆残废进程。
 * 这种情况下用户装不了新版本、也用不了对话，只能卸载重装（数据全丢）。
 *
 * 这个类只做一件事：**把「能再生」的东西删干净，让下次启动按 APK 内置资产重新部署**。
 * 严格保留：
 *   · ~/.coomi/config            提供商、设置、插件配置
 *   · ~/.coomi/sessions          全部对话历史
 *   · ~/.coomi/runtime-v2/home   guest 持久 home（工具产物、登录态）
 *   · ~/.coomi/runtime-v2/downloads  APK 内置的 runtime 压缩包（离线重装依赖它）
 *   · /workspace 工作区          用户的代码与文件
 *
 * 删除后引擎会在下一次启动时：
 *   ① 重新解包 web.zip → files/web；
 *   ② 重新解包 runtime 压缩包 → runtime-v2/versions/<ver>；
 *   ③ 重建 bin/coomi 软链与部署标记。
 */
public final class CoomiEngineRepair {

    private static final String LOG_TAG = "CoomiEngineRepair";

    private CoomiEngineRepair() {}

    /** 修复结果：给用户看的摘要 + 是否全部成功。 */
    public static final class Result {
        public final boolean success;
        public final String summary;
        public final int purgedProcesses;

        Result(boolean success, String summary, int purgedProcesses) {
            this.success = success;
            this.summary = summary;
            this.purgedProcesses = purgedProcesses;
        }
    }

    /** 可以安全删除的路径：全部能从 APK 内置资产重建。 */
    private static String[] regenerablePaths(Context context) {
        String home = TermuxConstants.TERMUX_HOME_DIR_PATH;
        String coomi = home + "/.coomi";
        return new String[] {
            // 前端解包目录：ensureCurrentWebAssets() 会按 .app-stamp 重新部署
            new File(context.getFilesDir(), "web").getAbsolutePath(),
            // Runtime V2：版本目录 + 状态机。下载缓存与 guest home 不动。
            coomi + "/runtime-v2/versions",
            coomi + "/runtime-v2/state.json",
            // 引擎二进制指纹：清掉后下次启动会重新判断/重启
            coomi + "/engine.version",
            // 部署标记：startEngineSync 的 repairDeploymentIfNeeded() 会自己补回来
            home + "/.coomi_deployed",
            // 日志轮转残留（旧日志本身保留，这里只清 .1）
            CoomiLogSettings.engineLogPath(context) + ".1",
        };
    }

    /**
     * 执行修复。会先杀掉残留的引擎/运行时进程（否则它们握着 runtime 目录，
     * 删一半就失败，修完还是起不来），再删可再生产物。
     */
    public static Result repair(Context context) {
        int purged = purgeStaleProcesses();
        StringBuilder report = new StringBuilder();
        boolean ok = true;
        for (String path : regenerablePaths(context)) {
            File file = new File(path);
            if (!file.exists()) continue;
            boolean deleted = deleteRecursively(file);
            report.append(deleted ? "已清理 " : "未能清理 ").append(path).append('\n');
            if (!deleted) ok = false;
        }
        if (report.length() == 0) report.append("没有需要清理的残留文件。\n");
        if (purged > 0) report.append("已终止 ").append(purged).append(" 个残留进程。\n");
        report.append("对话历史、提供商配置、持久 home 与工作区均已保留。\n")
            .append("下一次启动引擎会自动按 APK 内置资产重新部署。");
        Logger.logInfo(LOG_TAG, "Engine repair finished (ok=" + ok + ", purged=" + purged + ")");
        return new Result(ok, report.toString(), purged);
    }

    private static boolean deleteRecursively(File file) {
        if (file == null || !file.exists()) return true;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursively(child)) return false;
                }
            }
        }
        return file.delete() || !file.exists();
    }

    /**
     * 扫 /proc 终止本应用拉起的引擎与 PRoot 里跑着的工具子进程。
     *
     * 判定串与 CoomiService 保持一致：引擎二进制名 + runtime-v2 目录。
     * 只看 /proc 里自己 UID 能看到的那部分，不会误杀别的应用。
     */
    private static int purgeStaleProcesses() {
        String[] markers = { CoomiConstants.NATIVE_BINARY_NAME, "/runtime-v2/" };
        int self = Process.myPid();
        int signalled = 0;
        File[] entries = new File("/proc").listFiles();
        if (entries == null) return 0;
        for (File entry : entries) {
            String name = entry.getName();
            if (name.isEmpty() || !Character.isDigit(name.charAt(0))) continue;
            int pid;
            try {
                pid = Integer.parseInt(name);
            } catch (NumberFormatException invalid) {
                continue;
            }
            if (pid <= 1 || pid == self) continue;
            String cmdline = readCmdline(new File(entry, "cmdline"));
            if (cmdline.isEmpty()) continue;
            boolean match = false;
            for (String marker : markers) {
                if (!marker.isEmpty() && cmdline.contains(marker)) { match = true; break; }
            }
            if (!match) continue;
            try {
                Process.sendSignal(pid, 15);
                signalled++;
            } catch (Exception ignored) {
                // 进程可能已经退出，或不属于本 UID
            }
        }
        if (signalled > 0) {
            try { Thread.sleep(320); } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            // 补一刀：SIGTERM 之后仍活着的直接 SIGKILL
            for (File entry : entries) {
                String name = entry.getName();
                if (name.isEmpty() || !Character.isDigit(name.charAt(0))) continue;
                int pid;
                try { pid = Integer.parseInt(name); } catch (NumberFormatException invalid) { continue; }
                if (pid <= 1 || pid == self) continue;
                String cmdline = readCmdline(new File(entry, "cmdline"));
                if (cmdline.isEmpty()) continue;
                for (String marker : markers) {
                    if (!marker.isEmpty() && cmdline.contains(marker)) {
                        try { Process.sendSignal(pid, 9); } catch (Exception ignored) { /* gone */ }
                        break;
                    }
                }
            }
        }
        return signalled;
    }

    private static String readCmdline(File file) {
        try (java.io.FileInputStream input = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[2048];
            int read = input.read(buffer);
            if (read <= 0) return "";
            StringBuilder text = new StringBuilder(read);
            for (int i = 0; i < read; i++) {
                byte value = buffer[i];
                text.append(value == 0 ? ' ' : (char) (value & 0xFF));
            }
            return text.toString().trim();
        } catch (Exception ignored) {
            return "";
        }
    }
}
