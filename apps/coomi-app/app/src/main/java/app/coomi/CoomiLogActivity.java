package app.coomi;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.BuildConfig;
import com.termux.R;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 报错日志（1.5.4 第 12 项）。
 *
 * 这个页面存在的唯一理由：**引擎起不来的时候，用户仍然有地方看原因**。
 * 所以：
 *   · 不绑定 CoomiService、不请求引擎接口 —— 引擎死了它照样能用；
 *   · 只读三个磁盘文件：用户指定的引擎日志、Java 崩溃日志、logcat 快照；
 *   · 允许修改引擎日志路径，方便把日志放到用户自己能拿到的地方（例如公共下载目录）；
 *   · 一键复制 / 分享整段内容，用户可以直接发给开发者。
 *
 * 读取上限 256KB：手机上打开一个几十 MB 的日志只会让界面卡死，
 * 而排查真正需要的通常是**末尾**那几屏，所以从文件尾部读。
 */
public final class CoomiLogActivity extends AppCompatActivity {

    /** 单次最多读这么多字节（从尾部读）。 */
    private static final int MAX_READ_BYTES = 256 * 1024;

    private enum Source { ENGINE, CRASH, LOGCAT }

    private EditText pathInput;
    private TextView pathNote;
    private TextView fileLabel;
    private TextView content;
    private Button engineButton;
    private Button crashButton;
    private Button logcatButton;
    private Source source = Source.ENGINE;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CoomiTheme.applyPageSystemBars(this);
        setContentView(R.layout.activity_coomi_logs);
        CoomiTheme.applyConsoleBackground(this, findViewById(android.R.id.content));

        pathInput = findViewById(R.id.input_log_path);
        pathNote = findViewById(R.id.txt_log_path_note);
        fileLabel = findViewById(R.id.txt_log_file);
        content = findViewById(R.id.txt_log_content);
        engineButton = findViewById(R.id.btn_log_source_engine);
        crashButton = findViewById(R.id.btn_log_source_crash);
        logcatButton = findViewById(R.id.btn_log_source_logcat);
        content.setTypeface(Typeface.MONOSPACE);

        findViewById(R.id.btn_log_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_log_refresh).setOnClickListener(v -> load());
        findViewById(R.id.btn_log_save_path).setOnClickListener(v -> savePath());
        findViewById(R.id.btn_log_reset_path).setOnClickListener(v -> {
            CoomiLogSettings.setEngineLogPath(this, "");
            bindPath();
            source = Source.ENGINE;
            load();
            toast("已恢复默认路径（下次启动引擎生效）");
        });
        findViewById(R.id.btn_log_copy).setOnClickListener(v -> copyAll());
        findViewById(R.id.btn_log_share).setOnClickListener(v -> shareAll());

        engineButton.setOnClickListener(v -> selectSource(Source.ENGINE));
        crashButton.setOnClickListener(v -> selectSource(Source.CRASH));
        logcatButton.setOnClickListener(v -> selectSource(Source.LOGCAT));

        pathInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        bindPath();
        load();
    }

    private void bindPath() {
        pathInput.setText(CoomiLogSettings.engineLogPath(this));
        boolean custom = !CoomiLogSettings.engineLogPath(this)
            .equals(CoomiConstants.ENGINE_LOG_PATH);
        pathNote.setText(custom
            ? "正在使用自定义路径；改动对下一次启动引擎生效"
            : "默认路径；可改成任意可写路径，改动对下一次启动引擎生效");
    }

    private void savePath() {
        String value = pathInput.getText() == null ? "" : pathInput.getText().toString().trim();
        if (value.isEmpty()) {
            toast("路径不能为空；要恢复默认请点「恢复默认」");
            return;
        }
        if (!value.startsWith("/")) {
            toast("请填写绝对路径（以 / 开头）");
            return;
        }
        CoomiLogSettings.setEngineLogPath(this, value);
        String problem = CoomiLogSettings.prepareEngineLog(this);
        bindPath();
        if (problem != null) {
            toast(problem);
        } else {
            toast("已保存；下次启动引擎会写到这个文件");
        }
        load();
    }

    private void selectSource(Source next) {
        source = next;
        load();
    }

    private File currentFile() {
        switch (source) {
            case CRASH:
                return new File(new File(getFilesDir(), "logs"), "crash.log");
            case LOGCAT:
                return firstExisting(
                    new File(new File(getFilesDir(), "logs"), "logcat_crash.log"),
                    new File(new File(getFilesDir(), "logs"), "logcat_boot.log"));
            case ENGINE:
            default:
                return new File(CoomiLogSettings.engineLogPath(this));
        }
    }

    private static File firstExisting(File... files) {
        for (File file : files) {
            if (file != null && file.isFile()) return file;
        }
        return files.length == 0 ? null : files[0];
    }

    private void load() {
        highlightSource();
        File file = currentFile();
        if (file == null) {
            fileLabel.setText("没有可读的日志文件");
            content.setText("");
            return;
        }
        String path = file.getAbsolutePath();
        if (!file.isFile()) {
            fileLabel.setText(path + "（文件还不存在）");
            content.setText("这个文件还没有生成。\n\n"
                + "· 引擎日志要等你启动过一次引擎才会出现；\n"
                + "· 崩溃日志只有在发生 Java 异常时才会写入。");
            return;
        }
        long size = file.length();
        String updated = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
            .format(new Date(file.lastModified()));
        fileLabel.setText(path + "\n" + humanSize(size) + " · 更新于 " + updated
            + (size > MAX_READ_BYTES ? "（只显示末尾 " + humanSize(MAX_READ_BYTES) + "）" : ""));
        content.setText(readTail(file, MAX_READ_BYTES));
    }

    private void highlightSource() {
        styleButton(engineButton, source == Source.ENGINE);
        styleButton(crashButton, source == Source.CRASH);
        styleButton(logcatButton, source == Source.LOGCAT);
    }

    private void styleButton(Button button, boolean active) {
        if (button == null) return;
        button.setAlpha(active ? 1f : 0.55f);
        button.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
    }

    private static String readTail(File file, int maxBytes) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long length = raf.length();
            long start = Math.max(0, length - maxBytes);
            raf.seek(start);
            byte[] buffer = new byte[(int) Math.min(maxBytes, length - start)];
            int read = raf.read(buffer);
            String text = new String(buffer, 0, Math.max(0, read), StandardCharsets.UTF_8);
            if (start > 0) {
                // 从中间截断可能把一行切开：丢掉第一行不完整的部分。
                int newline = text.indexOf('\n');
                if (newline >= 0) text = text.substring(newline + 1);
                text = "…（前 " + humanSize(start) + " 已省略）\n" + text;
            }
            return text;
        } catch (Exception error) {
            return "读取失败：" + error.getMessage();
        }
    }

    private String combined() {
        StringBuilder builder = new StringBuilder();
        builder.append("==== Comax 日志快照 · ")
            .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()))
            .append(" ====\n");
        builder.append("版本：").append(BuildConfig.VERSION_NAME)
            .append(" (").append(BuildConfig.VERSION_CODE).append(")\n");
        builder.append("引擎日志路径：").append(CoomiLogSettings.engineLogPath(this)).append('\n');
        String[][] sources = {
            {"引擎日志", CoomiLogSettings.engineLogPath(this)},
            {"崩溃日志", new File(new File(getFilesDir(), "logs"), "crash.log").getAbsolutePath()},
            {"logcat 快照", new File(new File(getFilesDir(), "logs"), "logcat_boot.log").getAbsolutePath()},
        };
        for (String[] item : sources) {
            builder.append("\n──────── ").append(item[0]).append(" ────────\n");
            File file = new File(item[1]);
            if (!file.isFile()) {
                builder.append("（不存在）\n");
                continue;
            }
            builder.append(readTail(file, 64 * 1024)).append('\n');
        }
        return builder.toString();
    }

    private void copyAll() {
        String text = combined();
        ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager == null) {
            toast("剪贴板不可用");
            return;
        }
        manager.setPrimaryClip(ClipData.newPlainText("Comax 日志", text));
        toast("已复制 " + humanSize(text.getBytes(StandardCharsets.UTF_8).length));
    }

    private void shareAll() {
        String text = combined();
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, "Comax 日志快照");
        intent.putExtra(Intent.EXTRA_TEXT, text);
        try {
            startActivity(Intent.createChooser(intent, "分享日志"));
        } catch (Exception error) {
            toast("没有可用的分享目标：" + error.getMessage());
        }
    }

    private static String humanSize(long bytes) {
        if (bytes >= 1024L * 1024L) return String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0);
        if (bytes >= 1024L) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return bytes + " B";
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}
