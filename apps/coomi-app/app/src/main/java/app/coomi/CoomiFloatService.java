package app.coomi;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.AnimationSet;
import android.view.animation.ScaleAnimation;
import android.view.animation.TranslateAnimation;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 控制模式的桌面悬浮层。
 *
 * <p>启动默认只显示一个蓝白悬浮球；点击后以 alpha+scale+translation 动画展开
 * 任务输入卡，收起时反向播放动画。卡片顶部保留单行状态条，覆盖更新而非追加列表。
 * 点击/长按前显示不拦截触摸的坐标反馈环，滑动时显示方向反馈轨迹且不来回切换窗口。
 *
 * <p>用前台服务承载：控制模式一次可能持续几分钟，普通后台服务会被系统回收。</p>
 */
public final class CoomiFloatService extends Service {

    private static final String CHANNEL_ID = "coomi_control_float";
    private static final int NOTIFICATION_ID = 4201;

    public static final String ACTION_START = "app.coomi.float.START";
    public static final String ACTION_STOP = "app.coomi.float.STOP";
    public static final String ACTION_TRACE = "app.coomi.float.TRACE";
    public static final String ACTION_COLLAPSE = "app.coomi.float.COLLAPSE";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_BODY = "body";

    private static final int ANIM_DURATION_MS = 220;
    private static final int FEEDBACK_DURATION_MS = 450;

    private static volatile CoomiFloatService instance;

    private WindowManager windowManager;
    private ViewGroup root;              // full-screen overlay root
    private LinearLayout card;           // input + actions
    private TextView topStatus;          // single-line always-on-top status
    private TextView ballView;           // collapsed ball
    private EditText input;
    private FeedbackView feedback;
    private WindowManager.LayoutParams params;

    private boolean collapsed = true;    // start collapsed
    private final Handler handler = new Handler(Looper.getMainLooper());

    private int lastX = -1;
    private int lastY = -1;
    private static volatile String controlSessionId = "", controlProviderId = "", controlModel = "";

    public static void setSession(String id, String provider, String model) {
        controlSessionId = id == null ? "" : id;
        controlProviderId = provider == null ? "" : provider;
        controlModel = model == null ? "" : model;
    }

    public static CoomiFloatService getInstance() {
        return instance;
    }

    private final AtomicBoolean releaseInProgress = new AtomicBoolean(false);
    public static void releaseForAutomation() {
        CoomiFloatService service = instance;
        if (service == null) return;
        if (!service.releaseInProgress.compareAndSet(false, true)) return;
        service.handler.post(() -> {
            service.setCollapsedInternal(true);
            service.releaseInProgress.set(false);
        });
    }

    private int touchStartX;
    private int touchStartY;
    private float touchDownRawX;
    private float touchDownRawY;
    private boolean dragging;

    public static boolean isRunning() {
        return instance != null;
    }

    /** 追加一条思考/工具记录；服务没起就忽略（控制模式没开时不需要）。 */
    public static void pushTrace(Context context, String title, String body) {
        if (context == null || instance == null) return;
        context.startService(new Intent(context, CoomiFloatService.class)
            .setAction(ACTION_TRACE)
            .putExtra(EXTRA_TITLE, title)
            .putExtra(EXTRA_BODY, body));
    }

    /** 只更新顶部状态文字，不动思考区。 */
    public static void pushStatus(Context context, String status) {
        pushTrace(context, status, null);
    }

    public static void setCollapsed(Context context, boolean value) {
        if (context == null) return;
        context.startService(new Intent(context, CoomiFloatService.class)
            .setAction(ACTION_COLLAPSE)
            .putExtra("collapsed", value));
    }

    public static void start(Context context) {
        if (context == null) return;
        context.startService(new Intent(context, CoomiFloatService.class).setAction(ACTION_START));
    }

    public static void stop(Context context) {
        if (context == null) return;
        context.stopService(new Intent(context, CoomiFloatService.class));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        startForegroundSafely();
        handler.post(this::buildOverlay);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_TRACE.equals(action)) {
            final String title = intent.getStringExtra(EXTRA_TITLE);
            final String body = intent.getStringExtra(EXTRA_BODY);
            handler.post(() -> updateStatusLine(title, body));
            return START_STICKY;
        }
        if (ACTION_COLLAPSE.equals(action)) {
            final boolean value = intent.getBooleanExtra("collapsed", true);
            handler.post(() -> setCollapsedInternal(value));
            return START_STICKY;
        }
        handler.post(() -> {
            if (root != null && root.getParent() == null) attach();
        });
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        handler.removeCallbacksAndMessages(null);
        detach();
        super.onDestroy();
    }

    // ── 构建 ──────────────────────────────────────────────────────────

    private void startForegroundSafely() {
        try {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null) {
                NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, getString(com.termux.R.string.coomi_float_channel_name),
                    NotificationManager.IMPORTANCE_MIN);
                channel.setShowBadge(false);
                manager.createNotificationChannel(channel);
            }
            Intent open = new Intent(this, CoomiLauncherActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pending = PendingIntent.getActivity(this, 0, open, pendingFlags);
            Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
            builder.setContentTitle("控制模式运行中")
                .setContentText("悬浮层正在显示任务进度")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true)
                .setContentIntent(pending);
            startForeground(NOTIFICATION_ID, builder.build());
        } catch (Throwable ignored) {
            // 通知不可用不影响悬浮层；部分 ROM 会拦截前台服务。
        }
    }

    private int dp(float value) {
        return (int) TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    private GradientDrawable rounded(int color, int radiusDp, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private void buildOverlay() {
        if (root != null) return;
        root = new FrameLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setBackgroundColor(Color.TRANSPARENT);

        // ── 顶部单行状态 ──
        topStatus = new TextView(this);
        topStatus.setTextColor(0xFFFFFFFF);
        topStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        topStatus.setSingleLine(true);
        topStatus.setEllipsize(TextUtils.TruncateAt.END);
        topStatus.setPadding(dp(12), dp(6), dp(12), dp(6));
        topStatus.setBackgroundColor(0xCC2F6BD8);
        FrameLayout.LayoutParams statusLp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        statusLp.gravity = Gravity.TOP;
        root.addView(topStatus, statusLp);

        // ── 展开卡片：输入 + 快捷操作 ──
        card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(9), dp(12), dp(10));
        card.setBackground(rounded(0xFFFBFCFE, 16, 0x22000000));
        card.setElevation(dp(8));
        card.setVisibility(View.GONE);
        card.setAlpha(0f);

        // ── 顶栏：标题 + 收起 ──
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView dotView = new TextView(this);
        dotView.setText("●");
        dotView.setTextColor(0xFF2F6BD8);
        dotView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        header.addView(dotView);

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titleBoxParams =
            new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titleBoxParams.leftMargin = dp(6);
        titleBox.setLayoutParams(titleBoxParams);

        TextView titleView = new TextView(this);
        titleView.setText("控制模式");
        titleView.setTextColor(0xFF1A1D24);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleBox.addView(titleView);

        header.addView(titleBox);

        TextView collapse = new TextView(this);
        collapse.setText("收起");
        collapse.setTextColor(0xFF2F6BD8);
        collapse.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        collapse.setPadding(dp(10), dp(4), 0, dp(4));
        collapse.setOnClickListener(v -> setCollapsedInternal(true));
        header.addView(collapse);
        card.addView(header);

        // ── 输入框 ──
        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams inputRowParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        inputRowParams.topMargin = dp(8);
        inputRow.setLayoutParams(inputRowParams);

        input = new EditText(this);
        input.setHint("输入要执行的任务…");
        input.setFocusableInTouchMode(true);
        input.setOnClickListener(v -> showKeyboard(input));
        input.setHintTextColor(0xFF6E7A90);
        input.setTextColor(0xFF1A1D24);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setBackground(rounded(0xFFFFFFFF, 10, 0x33000000));
        input.setPadding(dp(10), dp(7), dp(10), dp(7));
        LinearLayout.LayoutParams inputParams =
            new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        input.setLayoutParams(inputParams);
        input.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER;
            if (actionId == EditorInfo.IME_ACTION_SEND || enter) {
                submitInput();
                return true;
            }
            return false;
        });
        inputRow.addView(input);

        Button send = new Button(this);
        send.setText("发送任务");
        send.setAllCaps(false);
        send.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        send.setTextColor(Color.WHITE);
        send.setBackground(rounded(0xFF2F6BD8, 10, 0));
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(36));
        sendParams.leftMargin = dp(7);
        send.setLayoutParams(sendParams);
        send.setPadding(dp(12), 0, dp(12), 0);
        send.setOnClickListener(v -> submitInput());
        inputRow.addView(send);
        card.addView(inputRow);

        // ── 快捷操作 ──
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        actionsParams.topMargin = dp(7);
        actions.setLayoutParams(actionsParams);
        actions.addView(quickAction("返回", () -> runGlobal("back")));
        actions.addView(quickAction("桌面", () -> runGlobal("home")));
        actions.addView(quickAction("最近", () -> runGlobal("recents")));
        actions.addView(quickAction("只填入", this::fillOnly));
        card.addView(actions);

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.TOP | Gravity.START;
        root.addView(card, cardLp);

        // ── 悬浮球 ──
        ballView = new TextView(this);
        ballView.setText("控");
        ballView.setGravity(Gravity.CENTER);
        ballView.setTextColor(Color.WHITE);
        ballView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        ballView.setBackground(rounded(0xFF2F6BD8, 24, 0xFFFFFFFF));
        ballView.setElevation(dp(8));
        ballView.setVisibility(View.VISIBLE);
        ballView.setOnClickListener(v -> setCollapsedInternal(false));
        FrameLayout.LayoutParams ballLp = new FrameLayout.LayoutParams(dp(46), dp(46));
        ballLp.gravity = Gravity.TOP | Gravity.START;
        root.addView(ballView, ballLp);

        ballView.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dragging = false;
                    touchDownRawX = event.getRawX();
                    touchDownRawY = event.getRawY();
                    touchStartX = params.x;
                    touchStartY = params.y;
                    return false;
                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - touchDownRawX;
                    float dy = event.getRawY() - touchDownRawY;
                    if (!dragging && Math.hypot(dx, dy) < dp(6)) return false;
                    dragging = true;
                    params.x = Math.max(0, touchStartX + (int) dx);
                    params.y = Math.max(0, touchStartY + (int) dy);
                    lastX = params.x;
                    lastY = params.y;
                    updateParams();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragging) { dragging = false; return true; }
                    return false;
                default:
                    return false;
            }
        });

        header.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dragging = false;
                        touchDownRawX = event.getRawX();
                        touchDownRawY = event.getRawY();
                        touchStartX = params.x;
                        touchStartY = params.y;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = event.getRawX() - touchDownRawX;
                        float dy = event.getRawY() - touchDownRawY;
                        if (!dragging && Math.hypot(dx, dy) < dp(6)) return true;
                        dragging = true;
                        params.x = Math.max(0, touchStartX + (int) dx);
                        params.y = Math.max(0, touchStartY + (int) dy);
                        lastX = params.x;
                        lastY = params.y;
                        updateParams();
                        return true;
                    }
                    default:
                        return false;
                }
            }
        });

        updateTopStatus("就绪");
        attach();
        applyCollapsedState(false);
    }

    private TextView quickAction(String label, Runnable action) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextColor(0xFF2F6BD8);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        view.setGravity(Gravity.CENTER);
        view.setBackground(rounded(0x222F6BD8, 9, 0));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(32), 1f);
        params.rightMargin = dp(6);
        view.setLayoutParams(params);
        view.setOnClickListener(v -> action.run());
        return view;
    }

    // ── 反馈层（不拦截触摸） ─────────────────────────────────────────

    private void showTapFeedback(float x, float y) {
        if (feedback == null) {
            feedback = new FeedbackView(this);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
            root.addView(feedback, lp);
        }
        feedback.showTap(x, y);
        invalidateScreenCache();
    }

    private void showSwipeFeedback(float x1, float y1, float x2, float y2) {
        if (feedback == null) {
            feedback = new FeedbackView(this);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
            root.addView(feedback, lp);
        }
        feedback.showSwipe(x1, y1, x2, y2);
    }

    private void invalidateScreenCache() {
        CoomiAccessibilityService svc = CoomiAccessibilityService.get();
        if (svc != null) svc.invalidateScreenCache();
    }

    private final class FeedbackView extends View {
        private float fx, fy, fx2, fy2;
        private boolean isSwipe = false;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private android.animation.ValueAnimator anim;

        FeedbackView(Context c) {
            super(c);
            setBackgroundColor(Color.TRANSPARENT);
            setClickable(false);
            setFocusable(false);
        }

        void showTap(float x, float y) {
            isSwipe = false;
            fx = x; fy = y;
            setVisibility(VISIBLE);
            if (anim != null) anim.cancel();
            anim = android.animation.ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(FEEDBACK_DURATION_MS);
            anim.addUpdateListener(a -> invalidate());
            anim.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator a) { setVisibility(GONE); }
            });
            anim.start();
        }

        void showSwipe(float x1, float y1, float x2, float y2) {
            isSwipe = true;
            fx = x1; fy = y1; fx2 = x2; fy2 = y2;
            setVisibility(VISIBLE);
            if (anim != null) anim.cancel();
            anim = android.animation.ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(FEEDBACK_DURATION_MS);
            anim.addUpdateListener(a -> invalidate());
            anim.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator a) { setVisibility(GONE); }
            });
            anim.start();
        }

        @Override protected void onDraw(Canvas canvas) {
            if (anim == null) return;
            float p = (float) anim.getAnimatedValue();
            if (Float.isNaN(p)) return;
            if (isSwipe) {
                paint.setColor(Color.argb((int) (180 * (1f - p)), 0x2F, 0x6B, 0xD8));
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(3) * p);
                paint.setStrokeCap(Paint.Cap.ROUND);
                canvas.drawLine(fx, fy, fx + (fx2 - fx) * p, fy + (fy2 - fy) * p, paint);
            } else {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2));
                paint.setColor(Color.argb((int) (180 * (1f - p)), 0x2F, 0x6B, 0xD8));
                float r = dp(8) + dp(20) * p;
                canvas.drawCircle(fx, fy, r, paint);
            }
        }
    }

    // ── 输入与操作 ────────────────────────────────────────────────────

    private void submitInput() {
        if (input == null) return;
        String text = input.getText() == null ? "" : input.getText().toString().trim();
        if (text.isEmpty()) {
            updateTopStatus("输入为空，先写点内容");
            return;
        }
        CoomiService service = CoomiService.current();
        if (service == null || controlSessionId.isEmpty()) {
            updateTopStatus("引擎或控制会话未就绪，请先在应用里开启控制模式");
            return;
        }
        setCollapsedInternal(true);
        final String session = controlSessionId, provider = controlProviderId, model = controlModel;
        new Thread(() -> {
            String error = service.submitControlTask(session, provider, model, text);
            handler.post(() -> {
                if (error == null) { input.setText(""); updateTopStatus("任务已提交: " + text); }
                else { updateTopStatus("任务未提交: " + error); toast(error); }
            });
        }, "coomi-control-submit").start();
    }

    private void fillOnly() {
        if (input == null) return;
        String text = input.getText() == null ? "" : input.getText().toString().trim();
        if (text.isEmpty()) {
            updateTopStatus("输入为空");
            return;
        }
        input.setText("");
        if (!CoomiAccessibilityService.isReady()) {
            updateTopStatus("无障碍未开启");
            return;
        }
        setCollapsedInternal(true);
        boolean ok = CoomiAccessibilityService.get().inputText(text);
        updateTopStatus(ok ? "已填入输入框（未发送）" : "没找到可输入的输入框");
    }

    private void fillAndSend(String text) {
        if (!CoomiAccessibilityService.isReady()) {
            updateTopStatus("无障碍未开启");
            toast("无障碍未开启");
            return;
        }
        CoomiAccessibilityService service = CoomiAccessibilityService.get();
        boolean filled = service.inputText(text);
        if (!filled && !service.tapFirstEditor()) {
            updateTopStatus("没找到输入框");
            toast("没找到输入框");
            return;
        }
        if (filled) {
            handler.postDelayed(() -> {
                boolean sent = service.send();
                updateTopStatus(sent ? "已发送" : "已填入，但没找到发送按钮");
                if (!sent) toast("没找到发送按钮");
            }, 220);
        } else {
            updateTopStatus("已聚焦输入框");
        }
    }

    private void runGlobal(String action) {
        if (!CoomiAccessibilityService.isReady()) {
            updateTopStatus("无障碍未开启，无法执行 " + action);
            return;
        }
        boolean ok = CoomiAccessibilityService.get().globalAction(action);
        updateTopStatus(ok ? "已执行：" + action : "执行失败：" + action);
    }

    private void toast(String message) {
        handler.post(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    // ── 窗口 ──────────────────────────────────────────────────────────

    private void attach() {
        if (root == null) return;
        if (params == null) {
            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
            params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            params.x = lastX >= 0 ? lastX : dp(10);
            params.y = lastY >= 0 ? lastY : dp(40);
        }
        try {
            if (root.getParent() == null) windowManager.addView(root, params);
        } catch (Throwable ignored) {
            // 权限被回收或窗口被拒：静默失败，控制模式其余能力仍可用。
        }
    }

    private void updateParams() {
        if (root == null || params == null) return;
        try {
            if (root.getParent() != null) windowManager.updateViewLayout(root, params);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private void detach() {
        if (root == null) return;
        try {
            if (root.getParent() != null) windowManager.removeView(root);
        } catch (Throwable ignored) {
            // 忽略
        }
        root = null;
        params = null;
    }

    /**
     * 展开 / 收起。
     *
     * <p>展开时必须去掉 FLAG_NOT_FOCUSABLE，否则输入框拿不到焦点、输入法弹不出来 ——
     * 这正是「悬浮窗里打不了字」的原因。收起成小球后立刻加回该标志，把焦点还给目标 App。</p>
     */
    private void setCollapsedInternal(boolean value) {
        if (collapsed == value) return;
        collapsed = value;
        if (card == null || ballView == null) return;
        applyCollapsedState(true);
    }

    private void applyCollapsedState(boolean animate) {
        boolean value = collapsed;
        if (params != null) {
            if (value) {
                params.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                params.flags &= ~WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
                hideKeyboard();
            } else {
                // 展开时：去掉 NOT_FOCUSABLE 让输入框能拿到焦点、弹输入法。
                // 同时去掉 ALT_FOCUSABLE_IM，否则 IME 输入无法到达窗口。
                params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                params.flags &= ~WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
                params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
                params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE;
            }
            updateParams();
        }

        if (!animate) {
            card.setVisibility(value ? View.GONE : View.VISIBLE);
            card.setAlpha(value ? 0f : 1f);
            card.setScaleX(value ? 0.7f : 1f);
            card.setScaleY(value ? 0.7f : 1f);
            ballView.setVisibility(value ? View.VISIBLE : View.GONE);
            ballView.setAlpha(value ? 1f : 0f);
            ballView.setScaleX(value ? 1f : 0.1f);
            ballView.setScaleY(value ? 1f : 0.1f);
            if (!value) focusInput();
            return;
        }

        if (!value) {
            // 展开动画：卡片从 0.7 缩放到 1、淡入；球缩小淡出
            card.setVisibility(View.VISIBLE);
            card.setAlpha(0f);
            card.setScaleX(0.7f);
            card.setScaleY(0.7f);
            ballView.setVisibility(View.VISIBLE);
            ballView.setAlpha(1f);
            ballView.setScaleX(1f);
            ballView.setScaleY(1f);

            AnimationSet cardAnim = new AnimationSet(true);
            cardAnim.setDuration(ANIM_DURATION_MS);
            cardAnim.addAnimation(new AlphaAnimation(0f, 1f));
            cardAnim.addAnimation(new ScaleAnimation(0.7f, 1f, 0.7f, 1f,
                ScaleAnimation.RELATIVE_TO_SELF, 0.5f, ScaleAnimation.RELATIVE_TO_SELF, 0.5f));
            cardAnim.setAnimationListener(new android.view.animation.Animation.AnimationListener() {
                @Override public void onAnimationStart(android.view.animation.Animation a) {}
                @Override public void onAnimationRepeat(android.view.animation.Animation a) {}
                @Override public void onAnimationEnd(android.view.animation.Animation a) {
                    ballView.setVisibility(View.GONE);
                    focusInput();
                }
            });
            card.startAnimation(cardAnim);

            AnimationSet ballAnim = new AnimationSet(true);
            ballAnim.setDuration(ANIM_DURATION_MS);
            ballAnim.addAnimation(new AlphaAnimation(1f, 0f));
            ballAnim.addAnimation(new ScaleAnimation(1f, 0.1f, 1f, 0.1f,
                ScaleAnimation.RELATIVE_TO_SELF, 0.5f, ScaleAnimation.RELATIVE_TO_SELF, 0.5f));
            ballView.startAnimation(ballAnim);
        } else {
            // 收起动画：卡片缩小淡出；球放大淡入
            card.setVisibility(View.VISIBLE);
            card.setAlpha(1f);
            card.setScaleX(1f);
            card.setScaleY(1f);
            ballView.setVisibility(View.VISIBLE);
            ballView.setAlpha(0f);
            ballView.setScaleX(0.1f);
            ballView.setScaleY(0.1f);

            AnimationSet cardAnim = new AnimationSet(true);
            cardAnim.setDuration(ANIM_DURATION_MS);
            cardAnim.addAnimation(new AlphaAnimation(1f, 0f));
            cardAnim.addAnimation(new ScaleAnimation(1f, 0.7f, 1f, 0.7f,
                ScaleAnimation.RELATIVE_TO_SELF, 0.5f, ScaleAnimation.RELATIVE_TO_SELF, 0.5f));
            cardAnim.setAnimationListener(new android.view.animation.Animation.AnimationListener() {
                @Override public void onAnimationStart(android.view.animation.Animation a) {}
                @Override public void onAnimationRepeat(android.view.animation.Animation a) {}
                @Override public void onAnimationEnd(android.view.animation.Animation a) {
                    card.setVisibility(View.GONE);
                }
            });
            card.startAnimation(cardAnim);

            AnimationSet ballAnim = new AnimationSet(true);
            ballAnim.setDuration(ANIM_DURATION_MS);
            ballAnim.addAnimation(new AlphaAnimation(0f, 1f));
            ballAnim.addAnimation(new ScaleAnimation(0.1f, 1f, 0.1f, 1f,
                ScaleAnimation.RELATIVE_TO_SELF, 0.5f, ScaleAnimation.RELATIVE_TO_SELF, 0.5f));
            ballView.startAnimation(ballAnim);
        }
    }

    private void focusInput() {
        if (input == null) return;
        input.setVisibility(View.VISIBLE);
        input.requestFocus();
        handler.postDelayed(() -> showKeyboard(input), 120);
        handler.postDelayed(() -> {
            if (!collapsed && input != null && input.isFocused()) showKeyboard(input);
        }, 420);
    }

    private void hideKeyboard() {
        try {
            InputMethodManager manager =
                (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (manager != null && input != null) {
                manager.hideSoftInputFromWindow(input.getWindowToken(), 0);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private void showKeyboard(View target) {
        if (!collapsed && target != null) {
            try {
                InputMethodManager manager =
                    (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (manager != null) {
                    manager.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT);
                }
            } catch (Throwable ignored) {
                // 输入法唤起失败不影响悬浮窗其余功能
            }
        }
    }

    // ── 顶部状态（单行覆盖） ─────────────────────────────────────────

    private void updateTopStatus(String text) {
        if (topStatus != null) {
            topStatus.setText(text);
        }
    }

    private void updateStatusLine(String title, String body) {
        String line = body != null && !body.isEmpty()
            ? (title != null && !title.isEmpty() ? title + "：" + body : body)
            : (title != null ? title : "");
        if (TextUtils.isEmpty(line)) return;
        updateTopStatus(line);
    }

    // ── 反馈触发（从 AccessibilityService 调用） ──────────────────────

    public void showTapAt(float x, float y) { showTapFeedback(x, y); }
    public void showSwipeFrom(float x1, float y1, float x2, float y2) { showSwipeFeedback(x1, y1, x2, y2); }
}
