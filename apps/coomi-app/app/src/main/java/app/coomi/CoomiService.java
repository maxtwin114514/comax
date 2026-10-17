package app.coomi;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Owns deployment and lifecycle of the native coomi-rs process. */
public class CoomiService extends Service {

    private static final String LOG_TAG = "CoomiService";
    private static final int HEALTH_CHECK_TIMEOUT_MS = 2000;
    private static final int CMD_TIMEOUT_SEC = 30;
    /**
     * 起进程后等它变健康的上限。
     *
     * 之前是"拉起就报成功"：引擎若因端口冲突/二进制被 SELinux 拦/配置损坏而秒退，
     * 前端已经显示"已启动"，要等 30 秒后监控才发现不健康再重试 —— 用户看到的就是
     * 「一直启动中，最后失败」。改成同步等健康，失败立刻带日志重来。
     */
    private static final int ENGINE_START_WAIT_MS = 12_000;
    private static final int ENGINE_HEALTH_POLL_MS = 300;
    /** 引擎日志超出这个大小就轮转，避免长期使用后几个 GB 的日志把存储写满。 */
    private static final long ENGINE_LOG_MAX_BYTES = 8L * 1024 * 1024;
    /** 日志轮转后保留的尾部大小（排错需要的就是最后这一段）。 */
    private static final int ENGINE_LOG_KEEP_BYTES = 1 * 1024 * 1024;
    /** ~/.profile 与 ~/.bashrc 里由 Comax 管理的块标记：块内整体替换，块外保留用户内容。 */
    /**
     * 判定「这是 Comax 自己拉起来的进程」的特征串（扫描 /proc 用）。
     *
     * - 引擎二进制名：引擎本体，以及 `sh -c "... exec libcoomi.so ..."` 那层包装；
     * - 运行时目录：proot 宿主与 guest 里跑着的工具子进程。
     *
     * 两个特征都只在**本应用 UID** 下才有，所以不会误杀别的应用；反过来，
     * 引擎被强杀后这些子进程会被 reparent 到 init 继续跑，正是需要清掉的那批。
     */
    private static final String[] STALE_PROCESS_MARKERS = {
        CoomiConstants.NATIVE_BINARY_NAME,
        "/runtime-v2/",
    };
    private static final String SHELL_BLOCK_START = "# >>> Comax Android managed block >>>";
    private static final String SHELL_BLOCK_END = "# <<< Comax Android managed block <<<";

    private final IBinder mBinder = new LocalBinder();
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    private volatile Process mEngineProcess;
    private volatile int mEnginePort = CoomiConstants.DEFAULT_ENGINE_PORT;
    /** 每次引擎启动生成的随机访问令牌（WebView 经 URL query 注入，防同设备 app 直连）。 */
    private volatile String mEngineToken = "";
    private volatile boolean mIsEngineRunning;
    /** 引擎启动流程进行中（含部署检查/进程拉起/健康探测），供控制台显示「引擎启动中」。 */
    private volatile boolean mIsEngineStarting;
    private volatile boolean mUpdateInProgress;
    /** Cancels health/install callbacks when the service is torn down. */
    private volatile boolean mDestroyed;
    private Thread mRuntimeInstallThread;
    private ShizukuShellBridge mShizukuShellBridge;
    private static volatile CoomiService current;
    public static CoomiService current() { return current; }

    private static String prefix() { return TermuxConstants.TERMUX_PREFIX_DIR_PATH; }
    private static String home() { return TermuxConstants.TERMUX_HOME_DIR_PATH; }
    private static String preload() { return prefix() + "/lib/libtermux-exec-ld-preload.so"; }

    private static String termuxEnvironment() {
        return "export HOME=" + shellQuote(home())
            + " PREFIX=" + shellQuote(prefix())
            + " TMPDIR=" + shellQuote(prefix() + "/tmp")
            + " PATH=" + shellQuote(prefix() + "/bin:/system/bin")
            + " LD_LIBRARY_PATH=" + shellQuote(prefix() + "/lib")
            + " LD_PRELOAD=" + shellQuote(preload())
            + " COOMI_HOME=" + shellQuote(CoomiConstants.COOMI_CONFIG_DIR)
            + " COOMI_SHELL=" + shellQuote(prefix() + "/bin/bash")
            + " SSL_CERT_FILE=" + shellQuote(prefix() + "/etc/tls/cert.pem")
            + "; ";
    }

    private CommandResult execTermux(String command) {
        try {
            String shell = termuxEnvironment()
                + "exec " + shellQuote(prefix() + "/bin/bash") + " -lc " + shellQuote(command);
            ProcessBuilder builder = new ProcessBuilder("/system/bin/sh", "-c", shell);
            builder.redirectErrorStream(true);
            Process process = builder.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) output.append(line).append('\n');
            }
            boolean exited = process.waitFor(CMD_TIMEOUT_SEC, TimeUnit.SECONDS);
            if (!exited) process.destroyForcibly();
            int code = exited ? process.exitValue() : -1;
            return new CommandResult(code == 0, output.toString().trim(), "", code);
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Termux command failed: " + e.getMessage());
            return new CommandResult(false, "", e.getMessage(), -1);
        }
    }

    public static class CommandResult {
        public final boolean success;
        public final String stdout;
        public final String stderr;
        public final int exitCode;

        public CommandResult(boolean success, String stdout, String stderr, int exitCode) {
            this.success = success;
            this.stdout = stdout == null ? "" : stdout;
            this.stderr = stderr == null ? "" : stderr;
            this.exitCode = exitCode;
        }
    }

    public interface ProgressCallback {
        void onStep(String message);
        void onError(String error);
        void onComplete();
    }

    public class LocalBinder extends Binder {
        public CoomiService getService() { return CoomiService.this; }
    }

    @Override public IBinder onBind(Intent intent) { return mBinder; }
    @Override
    public void onCreate() {
        super.onCreate();
        current = this;
        mShizukuShellBridge = new ShizukuShellBridge();
        Logger.logInfo(LOG_TAG, "Native service created");
        mExecutor.execute(() -> {
            try {
                ensureRuntimeManifestCurrent();
                ensureBundledRuntimeArtifactsCurrent();
            } catch (Exception error) {
                Logger.logError(LOG_TAG, "Bundled Runtime V2 deployment failed: " + error.getMessage());
            }
        });
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }

    @Override
    public void onDestroy() {
        mDestroyed = true;
        if (current == this) current = null;
        if (mShizukuShellBridge != null) mShizukuShellBridge.close();
        Thread installThread = mRuntimeInstallThread;
        if (installThread != null) installThread.interrupt();
        stopEngineSync();
        mExecutor.shutdownNow();
        super.onDestroy();
    }

    /**
     * bootstrap 是否已完整安装。只检查 bin/bash 不可靠：安装中断会留下残缺 prefix
     * （bin/ 在、lib/ 缺）→ bash/dpkg 因缺 libandroid-support.so 等起不来
     * （CANNOT LINK EXECUTABLE）。以核心 shell 与基础库同时存在为准。
     */
    public static boolean isBootstrapInstalled() {
        File bash = new File(prefix() + "/bin/bash");
        // canExecute(): 覆盖安装/中断会留下「存在但不可执行」的 bash（sh 直接
        // 报 Permission denied），此时必须重装而非跳过。
        return bash.isFile() && bash.canExecute()
            && new File(prefix() + "/lib/libandroid-support.so").isFile();
    }

    public static boolean isDeployComplete() {
        return new File(CoomiConstants.INSTALL_MARKER_PATH).isFile();
    }

    private File nativeBinary() {
        return new File(getApplicationInfo().nativeLibraryDir, CoomiConstants.NATIVE_BINARY_NAME);
    }

    /**
     * APK 覆盖安装会更换 /data/app 下的 nativeLibraryDir，旧的 usr/bin/coomi
     * 因而变成悬空软链接。已有部署标记时在启动前将它刷新到当前 APK。
     */
    private boolean ensureNativeBinaryLinkCurrent() {
        File marker = new File(CoomiConstants.INSTALL_MARKER_PATH);
        File binary = nativeBinary();
        File link = new File(prefix() + "/bin/coomi");
        if (!marker.isFile() || !binary.isFile()) return false;

        String expected = binary.getAbsolutePath();
        String actual = "";
        try {
            actual = Os.readlink(link.getAbsolutePath());
        } catch (ErrnoException e) {
            if (e.errno != OsConstants.ENOENT && e.errno != OsConstants.EINVAL) {
                Logger.logError(LOG_TAG, "Failed to inspect native engine link: " + e.getMessage());
                return false;
            }
        }

        if (expected.equals(actual) && link.isFile()) return true;

        try {
            try {
                Os.remove(link.getAbsolutePath());
            } catch (ErrnoException e) {
                if (e.errno != OsConstants.ENOENT) throw e;
            }
            Os.symlink(expected, link.getAbsolutePath());
            if (!expected.equals(Os.readlink(link.getAbsolutePath())) || !link.isFile()) {
                Logger.logError(LOG_TAG, "Native engine link verification failed");
                return false;
            }
            Logger.logInfo(LOG_TAG, "Refreshed native engine link after APK update: " + expected);
            updateInstallMarkerNativePath(marker, expected);
            return true;
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to refresh native engine link: " + e.getMessage());
            return false;
        }
    }

    private void updateInstallMarkerNativePath(File marker, String nativePath) {
        try {
            String markerText = readText(marker).trim();
            int newline = markerText.indexOf('\n');
            String version = newline >= 0 ? markerText.substring(0, newline).trim() : markerText;
            try (FileWriter writer = new FileWriter(marker)) {
                if (!version.isEmpty()) writer.write(version + "\n");
                writer.write(nativePath + "\n");
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to update deployment marker: " + e.getMessage());
        }
    }

    public String getRuntimeVersion() {
        CommandResult result = execTermux("coomi --version");
        return result.success ? result.stdout : result.stderr;
    }

    public void deployCoomi(ProgressCallback callback) {
        mExecutor.execute(() -> {
            mUpdateInProgress = true;
            try {
                File binary = nativeBinary();
                File web = ensureCurrentWebAssets();
                ensureRuntimeManifestCurrent();
                ensureBundledRuntimeArtifactsCurrent();
                if (!binary.isFile()) {
                    callback.onError("APK 中缺少 ARM64 coomi-rs 二进制：" + binary.getAbsolutePath());
                    return;
                }
                if (!new File(web, "index.html").isFile()) {
                    callback.onError("APK 中缺少已构建的前端 web.zip");
                    return;
                }

                callback.onStep("准备 Rust 运行目录");
                CommandResult directories = execTermux(
                    "mkdir -p " + shellQuote(home() + "/.coomi/config")
                        + " " + shellQuote(home() + "/.coomi/sessions")
                        + " " + shellQuote(home() + "/coomi"));
                if (!directories.success) {
                    callback.onError("无法创建运行目录：" + directories.stdout);
                    return;
                }

                callback.onStep("部署 coomi-rs ARM64 二进制");
                CommandResult link = execTermux(
                    "ln -sf " + shellQuote(binary.getAbsolutePath())
                        + " " + shellQuote(prefix() + "/bin/coomi"));
                if (!link.success) {
                    callback.onError("无法部署 coomi-rs：" + link.stdout);
                    return;
                }

                callback.onStep("校验原生引擎");
                CommandResult version = execTermux("coomi --version");
                if (!version.success || !version.stdout.contains("coomi")) {
                    callback.onError("coomi-rs 无法启动：\n" + version.stdout + "\n" + version.stderr);
                    return;
                }
                callback.onStep(version.stdout);

                writeShellEnvironment();
                removeLegacyRuntimePayloads();
                try (FileWriter writer = new FileWriter(CoomiConstants.INSTALL_MARKER_PATH)) {
                    writer.write(version.stdout + "\n" + binary.getAbsolutePath() + "\n");
                }
                callback.onComplete();
            } catch (Exception e) {
                Logger.logError(LOG_TAG, "Native deployment failed: " + e.getMessage());
                callback.onError(e.getMessage());
            } finally {
                mUpdateInProgress = false;
            }
        });
    }

    /**
     * 把 Comax 需要的环境变量写入 ~/.profile / ~/.bashrc。
     * 用「托管块」而非整文件覆盖：软件升级不会丢用户自定义的终端环境。
     */
    private void writeShellEnvironment() throws Exception {
        writeShellBlock(new File(home(), ".profile"),
            "# Created by Comax Android\n"
                + "export PREFIX=\"" + prefix() + "\"\n"
                + "export HOME=\"" + home() + "\"\n"
                + "export COOMI_HOME=\"$HOME/.coomi\"\n"
                + "export COOMI_SHELL=\"$PREFIX/bin/bash\"\n"
                + "export SSL_CERT_FILE=\"$PREFIX/etc/tls/cert.pem\"\n"
                + "export PATH=\"$PREFIX/bin:$PATH\"\n"
                + "[ -f ~/.bashrc ] && . ~/.bashrc\n");
        writeShellBlock(new File(home(), ".bashrc"),
            "# Created by Comax Android\n"
                + "export COOMI_HOME=\"$HOME/.coomi\"\n"
                + "export COOMI_SHELL=\"$PREFIX/bin/bash\"\n"
                + "export SSL_CERT_FILE=\"$PREFIX/etc/tls/cert.pem\"\n"
                + "alias ll='ls -la'\n");
    }

    /** 幂等合并写入：仅替换两个标记之间的托管块，块外用户内容原样保留。 */
    private void writeShellBlock(File file, String body) throws Exception {
        String existing = file.exists() ? readText(file) : "";
        // 旧版（无块标记）整文件都是 Comax 生成的：整体迁移为新格式。
        // 旧版本来就是覆盖写，用户内容无从保留，一次性迁移后进入块保护。
        if (!existing.isEmpty() && !existing.contains(SHELL_BLOCK_START)
            && existing.contains("# Created by Comax Android")) {
            existing = "";
        }
        String block = SHELL_BLOCK_START + "\n" + body + SHELL_BLOCK_END + "\n";
        if (existing.contains(SHELL_BLOCK_START)) {
            int start = existing.indexOf(SHELL_BLOCK_START);
            int end = existing.indexOf(SHELL_BLOCK_END, start);
            if (end >= 0) end += SHELL_BLOCK_END.length();
            else end = start; // 块未闭合（被用户截断过），从标记处截掉
            existing = existing.substring(0, start) + existing.substring(end);
        }
        String content = existing;
        if (!content.isEmpty() && !content.endsWith("\n")) content += "\n";
        content += block;
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(content);
        }
    }

    private static String readText(File file) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private void removeLegacyRuntimePayloads() {
        CoomiBootstrap.deleteRecursive(new File(getFilesDir(), "pysrc"));
        CoomiBootstrap.deleteRecursive(new File(getFilesDir(), "wheels"));
        new File(home() + "/.coomi/config.json").delete();
        new File(home() + "/.coomi/credentials.json").delete();
        new File(prefix() + "/share/coomi/install.sh").delete();
    }

    public void startEngine(Consumer<CommandResult> callback) {
        mExecutor.execute(() -> callback.accept(startEngineSync()));
    }

    /** InputStream.readAllBytes 是 Java 9 / API 33+ 才有的方法，Android 7-12 调用必然
     * NoSuchMethodError（已引发首装冷启动崩溃）。手写循环读取代之，全版本可用。 */
    private static byte[] readFully(InputStream input) throws java.io.IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream(4096);
        byte[] chunk = new byte[4096];
        int count;
        while ((count = input.read(chunk)) != -1) {
            buffer.write(chunk, 0, count);
        }
        return buffer.toByteArray();
    }

    /** java.nio.file.Files.readAllBytes 是 API 26+；minSdk 24 请走这里。 */
    private static byte[] readFileBytes(File file) throws java.io.IOException {
        try (java.io.FileInputStream input = new java.io.FileInputStream(file)) {
            return readFully(input);
        }
    }

    /** java.nio.file.Files.write/move 是 API 26+；用 tmp + rename 保持原子写语义。 */
    private static void writeFileAtomically(File target, byte[] bytes) throws java.io.IOException {
        File parent = target.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new java.io.IOException("cannot create parent for " + target.getAbsolutePath());
        }
        File temporary = new File(parent, target.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(bytes);
            output.flush();
            output.getFD().sync();
        }
        if (temporary.exists() && !temporary.renameTo(target)) {
            temporary.delete();
            throw new java.io.IOException("failed to move " + temporary + " -> " + target);
        }
    }

    /** 引擎自身写入的指纹（~/.coomi/engine.version，MD5+版本）是否与 APK 内二进制一致。 */
    /** 引擎日志轮转：超限就只保留尾部，避免长期使用后把存储写满。 */
    private void rotateEngineLogIfHuge() {
        File log = new File(CoomiConstants.ENGINE_LOG_PATH);
        if (!log.isFile() || log.length() <= ENGINE_LOG_MAX_BYTES) return;
        try {
            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(log, "rw");
            try {
                long keepFrom = Math.max(0, raf.length() - ENGINE_LOG_KEEP_BYTES);
                byte[] tail = new byte[(int) (raf.length() - keepFrom)];
                raf.seek(keepFrom);
                raf.readFully(tail);
                raf.setLength(0);
                raf.seek(0);
                raf.write(("[--- log rotated, kept last " + tail.length + " bytes ---]\n").getBytes("UTF-8"));
                raf.write(tail);
            } finally {
                raf.close();
            }
            Logger.logInfo(LOG_TAG, "Rotated oversized engine log");
        } catch (Exception error) {
            Logger.logError(LOG_TAG, "Failed to rotate engine log: " + error.getMessage());
        }
    }

    /** 取引擎日志尾部（排错用）。读不到就返回空串，绝不抛。 */
    private static String tailEngineLog(int maxChars) {
        File log = new File(CoomiConstants.ENGINE_LOG_PATH);
        if (!log.isFile()) return "";
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(log, "r")) {
            long length = raf.length();
            int want = (int) Math.min(length, maxChars);
            byte[] buffer = new byte[want];
            raf.seek(length - want);
            raf.readFully(buffer);
            String text = new String(buffer, java.nio.charset.StandardCharsets.UTF_8).trim();
            // 只留最后 12 行：完整的启动日志动辄上千行，UI 里显示不完也没用。
            String[] lines = text.split("\n");
            int from = Math.max(0, lines.length - 12);
            StringBuilder tail = new StringBuilder();
            for (int i = from; i < lines.length; i++) {
                if (tail.length() > 0) tail.append('\n');
                tail.append(lines[i]);
            }
            return tail.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    /**
     * 等引擎变健康。
     *
     * @return true 表示健康；false 表示进程提前退出或超时。
     */
    private boolean waitForHealthy(Process process, int port) {
        long deadline = android.os.SystemClock.elapsedRealtime() + ENGINE_START_WAIT_MS;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (process != null && !process.isAlive()) return false;
            if (checkHealth(port)) return true;
            try {
                Thread.sleep(ENGINE_HEALTH_POLL_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return checkHealth(port);
    }

    /**
     * 部署自愈。
     *
     * 起因：`startEngineSync` 硬依赖 ~/.coomi_deployed 这个 marker，marker 一旦丢失
     * （清数据、清理脚本、旧版本迁移中断）就直接报 "coomi-rs is not deployed"，
     * 而且**永远不会自己恢复** —— 用户只能重装。这是「升级后无法启动引擎」里最硬的
     * 一堵墙。
     *
     * 这里只做引擎启动真正需要的最小修复：建运行目录、刷新 bin/coomi 软链、校验
     * 二进制能跑、补回 marker。终端的 .profile/.bashrc 托管块不在这里写 —— 那只影响
     * 终端便利性，不影响引擎。
     *
     * @return null 表示已就绪；否则返回给用户看的失败原因。
     */
    private String repairDeploymentIfNeeded() {
        boolean markerPresent = isDeployComplete();
        // 软链检查本身依赖 marker，所以先看二进制与软链是否真的可用
        File binary = nativeBinary();
        if (!binary.isFile()) {
            return "APK 内缺少 ARM64 引擎二进制（" + binary.getAbsolutePath() + "），请重新安装本应用";
        }
        File link = new File(prefix() + "/bin/coomi");
        boolean linkOk = false;
        try {
            linkOk = link.isFile() && binary.getAbsolutePath().equals(Os.readlink(link.getAbsolutePath()));
        } catch (ErrnoException ignored) {
            linkOk = false;
        }
        if (markerPresent && linkOk) return null;

        Logger.logInfo(LOG_TAG, "Repairing deployment (markerPresent=" + markerPresent + ", linkOk=" + linkOk + ")");
        CommandResult directories = execTermux(
            "mkdir -p " + shellQuote(home() + "/.coomi/config")
                + " " + shellQuote(home() + "/.coomi/sessions")
                + " " + shellQuote(prefix() + "/bin")
                + " " + shellQuote(home() + "/coomi"));
        if (!directories.success) {
            return "无法创建运行目录：" + directories.stdout;
        }
        CommandResult linkResult = execTermux(
            "ln -sf " + shellQuote(binary.getAbsolutePath()) + " " + shellQuote(link.getAbsolutePath()));
        if (!linkResult.success) {
            return "无法链接引擎二进制：" + linkResult.stdout;
        }
        CommandResult version = execTermux("coomi --version");
        if (!version.success || version.stdout.isEmpty()) {
            return "引擎二进制无法执行：\n" + version.stdout + "\n" + version.stderr;
        }
        try (FileWriter writer = new FileWriter(CoomiConstants.INSTALL_MARKER_PATH)) {
            writer.write(version.stdout.trim() + "\n" + binary.getAbsolutePath() + "\n");
        } catch (Exception error) {
            return "无法写入部署标记：" + error.getMessage();
        }
        Logger.logInfo(LOG_TAG, "Deployment repaired: " + version.stdout.trim());
        return null;
    }

    private boolean engineMatchesApk() {
        try {
            File versionFile = new File(CoomiConstants.COOMI_CONFIG_DIR, "engine.version");
            if (!versionFile.isFile()) return false; // 旧引擎无指纹文件：视为不匹配，重启以加载新代码
            String recorded = new String(
                readFileBytes(versionFile),
                java.nio.charset.StandardCharsets.UTF_8
            ).trim();
            String current = binaryFingerprint(nativeBinary());
            if (current.isEmpty()) return false;
            return recorded.startsWith(current);
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "engine fingerprint check failed: " + e.getMessage());
            return true; // 读不到指纹时不冒险杀引擎
        }
    }

    /** 文件 MD5（十六进制小写）；失败返回空串。 */
    private static String binaryFingerprint(File file) {
        try (java.io.InputStream in = new java.io.FileInputStream(file)) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private CommandResult startEngineSync() {
        mIsEngineStarting = true;
        try {
            // 冷启动：本进程手里没有引擎句柄，但上一轮 App 进程留下的引擎/子进程
            // 可能还在跑（引擎是独立进程，App 被系统杀掉后它变成孤儿）。不清就直接
            // 再拉一个，两个引擎会抢同一份会话目录，失败重试又会再叠一层 —— 最后就是
            // 「反复拉起引擎 → 堆积僵尸进程 → 引擎再也起不来」。
            if (mEngineProcess == null) {
                int purged = purgeStaleEngineProcesses();
                if (purged > 0) {
                    Logger.logInfo(LOG_TAG, "Cleaned " + purged + " leftover processes before starting engine");
                }
            }
            if (!engineMatchesApk()) {
                Logger.logInfo(LOG_TAG, "Engine binary changed (APK updated), stopping stale engine");
                stopEngineSync();
            }
            if (mEngineProcess != null && mEngineProcess.isAlive()) {
                if (checkHealth(mEnginePort)) {
                    // 引擎进程活着且健康，但 APK 更新后引擎二进制可能已换新：
                    // 旧进程加载的还是旧代码（新旧 API 不匹配，前端会 404）。
                    // 对比引擎自身写入的二进制指纹，不一致则重启。
                    mIsEngineStarting = false;
                    return new CommandResult(true, "already running", "", 0);
                } else {
                    // 进程活着但健康检查失败（假死/端口错乱）：先清理旧进程再重启，
                    // 避免双引擎并发写同一会话目录。
                    Logger.logInfo(LOG_TAG, "Engine process alive but unhealthy, killing before restart");
                    stopEngineSync();
                }
            }
            // 部署自愈：marker 丢失 / 软链悬空都自动重建，而不是永久报 "not deployed"。
            String repairError = repairDeploymentIfNeeded();
            if (repairError != null) {
                mIsEngineStarting = false;
                Logger.logError(LOG_TAG, "Deployment repair failed: " + repairError);
                return new CommandResult(false, "", repairError, -1);
            }
            File binary = nativeBinary();
            File web = ensureCurrentWebAssets();
            ensureRuntimeManifestCurrent();
            ensureBundledRuntimeArtifactsCurrent();
            if (!binary.isFile() || !new File(web, "index.html").isFile()) {
                mIsEngineStarting = false;
                return new CommandResult(false, "", "native binary or frontend is missing", -1);
            }

            // 日志先轮转：长期升级使用后 coomi.log 可能上百 MB，追加写会变慢甚至写失败。
            rotateEngineLogIfHuge();

            String lastFailure = "";
            for (int attempt = 1; attempt <= 2; attempt++) {
                int port = findFreePort();
                String token = generateToken();
                mEngineToken = token;
                String command = termuxEnvironment()
                    // 告诉引擎它的配置目录（也就是 .coomi），PoW 求解队列要用它定位。
                    + "export COOMI_HOME=" + shellQuote(CoomiConstants.COOMI_CONFIG_DIR) + "; "
                    + "export RUST_BACKTRACE=1; cd " + shellQuote(home()) + "; "
                    + "exec >>" + shellQuote(CoomiConstants.ENGINE_LOG_PATH) + " 2>&1; "
                    + "exec " + shellQuote(binary.getAbsolutePath())
                    + " --home " + shellQuote(CoomiConstants.COOMI_CONFIG_DIR)
                    + " --cwd " + shellQuote(home())
                    + " serve --port " + port
                    + " --token " + shellQuote(token)
                    + " --static-dir " + shellQuote(web.getAbsolutePath());
                ProcessBuilder builder = new ProcessBuilder("/system/bin/sh", "-c", command);
                builder.redirectErrorStream(true);
                Process process = builder.start();
                mEngineProcess = process;
                mEnginePort = port;
                mIsEngineRunning = true;

                // 同步等健康：健康了才算启动成功，避免"报成功但引擎其实秒退"。
                boolean healthy = waitForHealthy(process, port);
                if (healthy) {
                    // DeepSeek 验证码登录依赖官方 librscrypto.so 求解 PoW：
                    // 引擎把 challenge 写进 ~/.coomi/control/pow/，这个服务轮询并用原生库求解。
                    CoomiPowService.start(this);
                    startBundledRuntimeInstallWhenReady(process, port, token);

                    new Thread(() -> {
                        try {
                            int code = process.waitFor();
                            Logger.logInfo(LOG_TAG, "coomi-rs exited with code " + code);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            if (mEngineProcess == process) {
                                mEngineProcess = null;
                                mIsEngineRunning = false;
                            }
                        }
                    }, "coomi-rs-waiter").start();

                    Logger.logInfo(LOG_TAG, "Engine healthy on port " + port);
                    return new CommandResult(true, "Engine started on port " + port, "", 0);
                }

                // 不健康：抓日志尾部（这是用户报问题时唯一能看到的线索），清干净再试一次。
                String detail = tailEngineLog(6_000);
                boolean alive = process.isAlive();
                Logger.logError(LOG_TAG, "Engine attempt " + attempt + " failed (alive=" + alive + ")");
                lastFailure = (alive
                    ? "引擎在 " + (ENGINE_START_WAIT_MS / 1000) + " 秒内没有响应健康检查"
                    : "引擎进程启动后立即退出")
                    + (detail.isEmpty() ? "" : "\n\n最近日志：\n" + detail);
                stopEngineSync();
                if (attempt == 1) {
                    // 第二次尝试前再清一遍残留：第一次失败可能留下占着会话目录或端口的子进程。
                    purgeStaleEngineProcesses();
                }
            }
            mIsEngineStarting = false;
            return new CommandResult(false, "", lastFailure, -1);
        } catch (Exception e) {
            mEngineProcess = null;
            mIsEngineRunning = false;
            return new CommandResult(false, "", e.getMessage(), -1);
        } finally {
            mIsEngineStarting = false;
        }
    }

    private synchronized File ensureCurrentWebAssets() throws Exception {
        File web = new File(getFilesDir(), CoomiConstants.WEB_DIR_BASENAME);
        File stampFile = new File(web, ".app-stamp");
        String expected = CoomiBootstrap.appStamp(this);
        String actual = "";
        if (stampFile.isFile()) {
            actual = new String(readFileBytes(stampFile), java.nio.charset.StandardCharsets.UTF_8).trim();
        }
        if (!expected.equals(actual) || !new File(web, "index.html").isFile()) {
            CoomiBootstrap.deleteRecursive(web);
            int count = CoomiBootstrap.deployZipAsset(this, CoomiConstants.WEB_ASSET, web);
            if (count < 1 || !new File(web, "index.html").isFile()) {
                throw new IllegalStateException("无法部署 APK 内置前端");
            }
            try (FileWriter writer = new FileWriter(stampFile)) {
                writer.write(expected);
            }
        }
        return web;
    }

    /** Keep the signed-in-APK Runtime V2 manifest in the engine config directory across upgrades. */
    private synchronized void ensureRuntimeManifestCurrent() throws Exception {
        byte[] expected;
        try (InputStream input = getAssets().open(CoomiConstants.RUNTIME_V2_MANIFEST_ASSET)) {
            expected = readFully(input);
        }
        if (expected.length == 0) {
            throw new IllegalStateException("APK Runtime V2 manifest is empty");
        }

        File target = new File(CoomiConstants.RUNTIME_V2_MANIFEST_PATH);
        File parent = target.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IllegalStateException("cannot create Runtime V2 config directory");
        }
        if (target.isFile() && Arrays.equals(expected, readFileBytes(target))) {
            return;
        }

        writeFileAtomically(target, expected);
        Logger.logInfo(LOG_TAG, "Runtime V2 manifest deployed to " + target.getAbsolutePath());
    }

    /**
     * Seed the signed APK's verified runtime archives into RuntimeManager's download cache.
     * A per-APK stamp avoids copying the 150+ MB rootfs on every service start.
     */
    private synchronized void ensureBundledRuntimeArtifactsCurrent() throws Exception {
        File directory = new File(CoomiConstants.RUNTIME_V2_DOWNLOAD_DIR);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("cannot create Runtime V2 download directory");
        }
        File stamp = new File(directory, ".bundled-app-stamp");
        String expectedStamp = CoomiBootstrap.appStamp(this);
        String actualStamp = stamp.isFile() ? readText(stamp).trim() : "";
        File host = new File(CoomiConstants.RUNTIME_V2_HOST_PATH);
        File rootfs = new File(CoomiConstants.RUNTIME_V2_ROOTFS_PATH);
        if (expectedStamp.equals(actualStamp) && host.isFile() && rootfs.isFile()) return;

        copyAssetAtomically(CoomiConstants.RUNTIME_V2_HOST_ASSET, host);
        copyAssetAtomically(CoomiConstants.RUNTIME_V2_ROOTFS_ASSET, rootfs);
        writeFileAtomically(stamp, expectedStamp.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Logger.logInfo(LOG_TAG, "Bundled Runtime V2 artifacts are ready for offline installation");
    }

    private void copyAssetAtomically(String assetName, File target) throws Exception {
        File parent = target.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IllegalStateException("cannot create parent for " + target.getAbsolutePath());
        }
        File temporary = new File(parent, target.getName() + ".asset.tmp");
        try (InputStream input = getAssets().open(assetName);
             FileOutputStream output = new FileOutputStream(temporary, false)) {
            byte[] buffer = new byte[128 * 1024];
            int count;
            long total = 0;
            while ((count = input.read(buffer)) >= 0) {
                if (count == 0) continue;
                output.write(buffer, 0, count);
                total += count;
            }
            output.getFD().sync();
            if (total == 0) throw new IllegalStateException("APK asset is empty: " + assetName);
        }
        if (temporary.exists() && !temporary.renameTo(target)) {
            temporary.delete();
            throw new java.io.IOException("failed to move " + temporary + " -> " + target);
        }
    }

    /** Start the persistent Rust installation state machine once the local API is healthy. */
    private void startBundledRuntimeInstallWhenReady(Process process, int port, String token) {
        Thread previous = mRuntimeInstallThread;
        if (previous != null && previous.isAlive()) return;
        Thread installThread = new Thread(() -> {
            for (int attempt = 0; attempt < 180; attempt++) {
                if (mDestroyed || mEngineProcess != process || !process.isAlive()) return;
                if (checkHealth(port)) break;
                try {
                    Thread.sleep(500);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (mDestroyed || mEngineProcess != process || !process.isAlive()) return;
            if (!checkHealth(port)) {
                Logger.logError(LOG_TAG, "Runtime V2 auto-install skipped because the engine did not become healthy");
                return;
            }
            HttpURLConnection connection = null;
            try {
                byte[] body = "{\"action\":\"install\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                connection = (HttpURLConnection) new URL(
                    "http://127.0.0.1:" + port + "/api/runtime/v2").openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(3000);
                connection.setReadTimeout(5000);
                connection.setRequestProperty("Authorization", "Bearer " + token);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(body.length);
                try (java.io.OutputStream output = connection.getOutputStream()) {
                    output.write(body);
                }
                int code = connection.getResponseCode();
                if (code >= 200 && code < 300) {
                    Logger.logInfo(LOG_TAG, "Runtime V2 automatic installation requested");
                } else {
                    Logger.logError(LOG_TAG, "Runtime V2 auto-install request failed with HTTP " + code);
                }
            } catch (Exception error) {
                Logger.logError(LOG_TAG, "Runtime V2 auto-install request failed: " + error.getMessage());
            } finally {
                if (connection != null) connection.disconnect();
            }
        }, "coomi-runtime-v2-install");
        mRuntimeInstallThread = installThread;
        installThread.start();
    }

    /**
     * 按 cmdline 特征扫 /proc，给自己 UID 下的残留进程发信号。
     *
     * 为什么不用 pkill：Android 的 toybox 在不同版本上 pkill 行为不一致（有的只匹配
     * 进程名，不认 -f 全文匹配），而 `/proc/<pid>/cmdline` 是稳定的。另外这里只看
     * 本应用自己能看到的那部分 /proc（hidepid 生效时本来就只有自己 UID 的进程），
     * 从根上避免误杀。
     *
     * @return 实际发出信号的进程数
     */
    private int sweepStaleProcesses(int signal) {
        File[] entries = new File("/proc").listFiles();
        if (entries == null) return 0;
        int self = android.os.Process.myPid();
        int signalled = 0;
        for (File entry : entries) {
            String name = entry.getName();
            if (name.isEmpty() || !Character.isDigit(name.charAt(0))) continue;
            int pid;
            try {
                pid = Integer.parseInt(name);
            } catch (NumberFormatException invalid) {
                continue;
            }
            // pid 1 是 init，永远不能碰；自己更不用说。
            if (pid <= 1 || pid == self) continue;
            String cmdline = readCmdline(new File(entry, "cmdline"));
            if (cmdline.isEmpty() || !matchesStaleProcess(cmdline)) continue;
            try {
                android.os.Process.sendSignal(pid, signal);
                signalled++;
            } catch (Exception ignored) {
                // 进程可能刚好退出；没权限（不是本 UID）也走这里，跳过即可。
            }
        }
        return signalled;
    }

    private static String readCmdline(File file) {
        try (java.io.FileInputStream input = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[2048];
            int read = input.read(buffer);
            if (read <= 0) return "";
            // cmdline 用 NUL 分隔参数，拼成一行再匹配。
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

    private static boolean matchesStaleProcess(String cmdline) {
        for (String marker : STALE_PROCESS_MARKERS) {
            if (!marker.isEmpty() && cmdline.contains(marker)) return true;
        }
        return false;
    }

    /**
     * 清理上一轮留下的引擎/运行时进程。
     *
     * 先 SIGTERM 让引擎有机会自己收拾工具子进程，再补 SIGKILL 清掉不听话的。
     * 「暂停引擎」与「冷启动引擎」都会走这里：前者是用户点的，后者是防堆积的
     * 自动动作 —— 反复拉起引擎最终会攒下一堆孤儿 proot 进程，把进程表吃光，
     * 表现就是「引擎起不来」。
     */
    private int purgeStaleEngineProcesses() {
        int signalled = sweepStaleProcesses(15);
        if (signalled == 0) return 0;
        try {
            Thread.sleep(320);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        int forced = sweepStaleProcesses(9);
        int total = signalled + forced;
        Logger.logInfo(LOG_TAG, "Purged stale engine processes: term=" + signalled + " kill=" + forced);
        return total;
    }

    public void stopEngine(Consumer<CommandResult> callback) {
        mExecutor.execute(() -> {
            stopEngineSync();
            if (callback != null) callback.accept(new CommandResult(true, "stopped", "", 0));
        });
    }

    private void stopEngineSync() {
        Process process = mEngineProcess;
        if (process != null) {
            process.destroy();
            try { process.waitFor(5, TimeUnit.SECONDS); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (process.isAlive()) process.destroyForcibly();
        }
        // 兜底：清掉可能残留的 coomi 进程（Rust 侧收到 SIGTERM 会先清理全部工具子进程）。
        // ^/[^ ]*libcoomi\.so 锚定引擎二进制路径开头，避免误匹配执行本命令的 shell 自身。
        try {
            execTermux("pkill -f '^/[^ ]*" + CoomiConstants.NATIVE_BINARY_NAME + "' 2>/dev/null; true");
        } catch (Exception ignored) { /* best-effort */ }
        // 「暂停引擎」的同时把堆积清干净：引擎死了但它的 proot/工具子进程可能还活着，
        // 反复暂停-启动就会越攒越多。
        purgeStaleEngineProcesses();
        mEngineProcess = null;
        mIsEngineRunning = false;
        mIsEngineStarting = false;
    }

    public void restartEngine(Consumer<CommandResult> callback) {
        mExecutor.execute(() -> {
            stopEngineSync();
            callback.accept(startEngineSync());
        });
    }

    public void getEngineStatus(Consumer<CommandResult> callback) {
        mExecutor.execute(() -> {
            // 启动流程进行中（无论进程是否已拉起）都报 starting，控制台显示「引擎启动中」。
            if (mIsEngineStarting) {
                callback.accept(new CommandResult(true, "starting", "", 0));
                return;
            }
            boolean alive = mIsEngineRunning && mEngineProcess != null && mEngineProcess.isAlive();
            String status = alive ? (checkHealth(mEnginePort) ? "running" : "starting") : "stopped";
            callback.accept(new CommandResult(true, status, "", 0));
        });
    }

    public boolean isUpdateInProgress() { return mUpdateInProgress; }
    public int getEnginePort() { return mEnginePort; }

    public void refreshRegistryCache() {
        mExecutor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(
                    "http://127.0.0.1:" + mEnginePort + "/api/registry/refresh").openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(2000);
                connection.setReadTimeout(2000);
                connection.setRequestProperty("Authorization", "Bearer " + mEngineToken);
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(0);
                connection.getOutputStream().close();
                connection.getResponseCode();
            } catch (Exception error) {
                Logger.logDebug(LOG_TAG, "Registry refresh skipped: " + error.getMessage());
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    public static String readEngineLogTail(int count) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(CoomiConstants.ENGINE_LOG_PATH))) {
            String line;
            while ((line = reader.readLine()) != null) lines.add(line);
        } catch (Exception ignored) {
            return "";
        }
        StringBuilder output = new StringBuilder();
        for (int i = Math.max(0, lines.size() - count); i < lines.size(); i++) {
            output.append(lines.get(i)).append('\n');
        }
        return output.toString().trim();
    }

    private static int findFreePort() {
        // 随机高位端口（缩小同设备其它 app 枚举命中的概率）。
        java.util.Random random = new java.util.Random();
        for (int attempt = 0; attempt < 50; attempt++) {
            int port = 20000 + random.nextInt(40000);
            try (java.net.ServerSocket socket = new java.net.ServerSocket(port)) {
                return socket.getLocalPort();
            } catch (Exception ignored) {}
        }
        return CoomiConstants.DEFAULT_ENGINE_PORT;
    }

    /** 生成 128 位十六进制随机令牌（Android 端与 WebView 共享，不落盘不写 JS）。 */
    private static String generateToken() {
        byte[] bytes = new byte[64];
        new java.security.SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    public String getEngineToken() { return mEngineToken; }

    /** Blocking loopback transcription used by the system assistant on its worker thread. */
    public String[] transcribeAssistant(File audio, String provider, String model, String language) {
        if (audio == null || !audio.isFile()) return new String[]{null, "录音文件不存在"};
        if (audio.length() <= 44 || audio.length() > 16L * 1024L * 1024L) {
            return new String[]{null, "录音大小无效"};
        }
        if (mEngineToken.isEmpty()) return new String[]{null, "Comax 引擎尚未就绪"};
        HttpURLConnection connection = null;
        String boundary = "----ComaxAssistant" + Long.toHexString(System.nanoTime());
        try {
            connection = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + mEnginePort + "/api/assistant/transcribe").openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(60000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + mEngineToken);
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            try (java.io.OutputStream out = connection.getOutputStream()) {
                writeMultipartText(out, boundary, "provider_id", provider);
                writeMultipartText(out, boundary, "model", model);
                writeMultipartText(out, boundary, "language", language);
                out.write(("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\n"
                    + "Content-Type: audio/wav\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                try (InputStream input = new java.io.FileInputStream(audio)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) > 0) out.write(buffer, 0, read);
                }
                out.write(("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                ? connection.getInputStream() : connection.getErrorStream();
            String payload = stream == null ? "" : readSmallResponse(stream, 256 * 1024);
            org.json.JSONObject json = payload.isEmpty() ? new org.json.JSONObject() : new org.json.JSONObject(payload);
            if (status >= 200 && status < 300) {
                String text = json.optString("text", "").trim();
                return text.isEmpty() ? new String[]{null, "语音服务没有返回文本"} : new String[]{text, null};
            }
            String error = json.optString("error", json.optString("message", "语音识别失败 HTTP " + status));
            if (error.length() > 300) error = error.substring(0, 300) + "…";
            return new String[]{null, error};
        } catch (Exception error) {
            return new String[]{null, "语音识别连接失败：" + error.getClass().getSimpleName()};
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void writeMultipartText(java.io.OutputStream out, String boundary, String name, String value)
        throws java.io.IOException {
        String safe = value == null ? "" : value.replace("\r", "").replace("\n", "");
        out.write(("--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
            + safe + "\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String readSmallResponse(InputStream input, int limit) throws java.io.IOException {
        try (InputStream stream = input; java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int read;
            while ((read = stream.read(buffer)) > 0 && total < limit) {
                int keep = Math.min(read, limit - total);
                output.write(buffer, 0, keep);
                total += keep;
            }
            return new String(output.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /** Loopback authenticated submit; does not require the Activity/WebView to remain foreground. */
    public String submitControlTask(String session, String provider, String model, String text) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL("http://127.0.0.1:" + mEnginePort + "/api/control/tasks").openConnection();
            connection.setConnectTimeout(3000); connection.setReadTimeout(10000);
            connection.setRequestMethod("POST"); connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + mEngineToken);
            connection.setRequestProperty("Content-Type", "application/json");
            org.json.JSONObject body = new org.json.JSONObject().put("session_id", session)
                .put("provider_id", provider).put("model", model).put("text", text);
            try (java.io.OutputStream out = connection.getOutputStream()) { out.write(body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
            int status = connection.getResponseCode();
            if (status >= 200 && status < 300) return null;
            String message = "任务提交失败 HTTP " + status;
            InputStream err = connection.getErrorStream();
            if (err != null) try (BufferedReader reader = new BufferedReader(new InputStreamReader(err))) {
                String line = reader.readLine(); if (line != null) message = new org.json.JSONObject(line).optString("error", message);
            }
            return message;
        } catch (Exception error) { return "引擎连接失败：" + error.getMessage(); }
        finally { if (connection != null) connection.disconnect(); }
    }

    private boolean checkHealth(int port) {
        try {
            // health 端点已由引擎免认证放行（仅返回最小字段），无需携带令牌。
            HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + port + CoomiConstants.HEALTH_ENDPOINT).openConnection();
            connection.setConnectTimeout(HEALTH_CHECK_TIMEOUT_MS);
            connection.setReadTimeout(HEALTH_CHECK_TIMEOUT_MS);
            int responseCode = connection.getResponseCode();
            connection.disconnect();
            return responseCode == 200;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
