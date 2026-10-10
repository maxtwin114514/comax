package app.coomi.assistant;

import android.Manifest;
import android.app.Activity;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.service.voice.VoiceInteractionService;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.R;

import java.util.ArrayList;
import java.util.List;

import app.coomi.CoomiAccessibilityService;
import app.coomi.CoomiService;
import app.coomi.CoomiTheme;

/** Native setup surface for Android's user-selected default assistant role. */
public final class CoomiAssistantSetupActivity extends Activity {
    private static final int REQUEST_MIC = 4101;
    private static final int REQUEST_ROLE = 4102;
    private static final String LAUNCHER_PREFS = "coomi_launcher";

    private AssistantSettingsStore store;
    private Spinner providerSpinner;
    private AutoCompleteTextView modelInput;
    private EditText languageInput;
    private Switch fallbackSwitch;
    private Switch enabledSwitch;
    private TextView summary;
    private TextView roleStatus;
    private TextView micStatus;
    private TextView accessibilityStatus;
    private TextView overlayStatus;
    private TextView autostartStatus;
    private TextView engineStatus;
    private Button roleButton;
    private Button micButton;
    private List<AssistantSettingsStore.ProviderOption> providers = new ArrayList<>();

    @Override protected void onCreate(Bundle state) {
        CoomiTheme.applyPageTheme(this);
        super.onCreate(state);
        setContentView(R.layout.activity_coomi_assistant_setup);
        CoomiTheme.applyPageSystemBars(this);
        CoomiTheme.applyConsoleBackground(this, findViewById(R.id.coomi_assistant_root));
        store = new AssistantSettingsStore(this);

        findViewById(R.id.btn_assistant_back).setOnClickListener(view -> finish());
        summary = findViewById(R.id.txt_assistant_summary);
        roleStatus = findViewById(R.id.txt_assistant_role);
        micStatus = findViewById(R.id.txt_assistant_mic);
        accessibilityStatus = findViewById(R.id.txt_assistant_accessibility);
        overlayStatus = findViewById(R.id.txt_assistant_overlay);
        autostartStatus = findViewById(R.id.txt_assistant_autostart);
        engineStatus = findViewById(R.id.txt_assistant_engine);
        roleButton = findViewById(R.id.btn_assistant_role);
        micButton = findViewById(R.id.btn_assistant_mic);
        providerSpinner = findViewById(R.id.spinner_assistant_provider);
        modelInput = findViewById(R.id.input_assistant_model);
        languageInput = findViewById(R.id.input_assistant_language);
        fallbackSwitch = findViewById(R.id.switch_assistant_system_fallback);
        enabledSwitch = findViewById(R.id.switch_assistant_enabled);

        roleButton.setOnClickListener(view -> requestAssistantRole());
        micButton.setOnClickListener(view -> requestMic());
        findViewById(R.id.row_assistant_role).setOnClickListener(view -> requestAssistantRole());
        findViewById(R.id.row_assistant_mic).setOnClickListener(view -> requestMic());
        findViewById(R.id.row_assistant_accessibility).setOnClickListener(view ->
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        findViewById(R.id.row_assistant_overlay).setOnClickListener(view -> openOverlaySettings());
        findViewById(R.id.row_assistant_autostart).setOnClickListener(view -> openAutostartSettings());
        findViewById(R.id.btn_assistant_save).setOnClickListener(view -> save());

        loadModels();
        languageInput.setText(store.language());
        fallbackSwitch.setChecked(store.useSystemFallback());
        enabledSwitch.setChecked(store.isEnabled());
    }

    @Override protected void onResume() {
        super.onResume();
        if (getSharedPreferences(LAUNCHER_PREFS, MODE_PRIVATE).getBoolean("autostart_pending", false)) {
            getSharedPreferences(LAUNCHER_PREFS, MODE_PRIVATE).edit()
                .putBoolean("autostart_pending", false).putBoolean("autostart_enabled", true).apply();
        }
        refreshStatus();
    }

    private void loadModels() {
        providers = store.configuredProviders();
        ArrayList<String> labels = new ArrayList<>();
        labels.add(getString(R.string.coomi_assistant_choose_provider));
        for (AssistantSettingsStore.ProviderOption provider : providers) labels.add(provider.display);
        providerSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        providerSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                refreshModelSpinner(position - 1);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { refreshModelSpinner(-1); }
        });
        int selected = -1;
        for (int index = 0; index < providers.size(); index++) {
            if (providers.get(index).id.equals(store.speechProvider())) { selected = index; break; }
        }
        providerSpinner.setSelection(selected + 1);
    }

    private void refreshModelSpinner(int providerIndex) {
        ArrayList<String> models = new ArrayList<>();
        if (providerIndex >= 0 && providerIndex < providers.size()) {
            models.addAll(providers.get(providerIndex).models);
        }
        modelInput.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, models));
        if (!store.speechModel().isEmpty()) modelInput.setText(store.speechModel(), false);
    }

    private void save() {
        int providerIndex = providerSpinner.getSelectedItemPosition() - 1;
        String provider = providerIndex >= 0 && providerIndex < providers.size()
            ? providers.get(providerIndex).id : "";
        String model = modelInput.getText() == null ? "" : modelInput.getText().toString().trim();
        if (enabledSwitch.isChecked() && (provider.isEmpty() || model.isEmpty()) && !fallbackSwitch.isChecked()) {
            Toast.makeText(this, R.string.coomi_assistant_need_speech, Toast.LENGTH_LONG).show();
            return;
        }
        store.saveSpeech(provider, model, languageInput.getText().toString(), fallbackSwitch.isChecked());
        store.setEnabled(enabledSwitch.isChecked());
        Toast.makeText(this, R.string.coomi_assistant_saved, Toast.LENGTH_SHORT).show();
        refreshStatus();
    }

    private boolean hasMic() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
            || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestMic() {
        if (hasMic()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MIC);
    }

    private boolean isAssistantRoleHeld() {
        ComponentName component = new ComponentName(this, CoomiVoiceInteractionService.class);
        if (VoiceInteractionService.isActiveService(this, component)) return true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager roles = getSystemService(RoleManager.class);
            return roles != null && roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT)
                && roles.isRoleHeld(RoleManager.ROLE_ASSISTANT);
        }
        return false;
    }

    private void requestAssistantRole() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                RoleManager roles = getSystemService(RoleManager.class);
                if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                    startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT), REQUEST_ROLE);
                    return;
                }
            }
            startActivity(new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS));
        } catch (Exception error) {
            startActivity(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS));
        }
    }

    private void openOverlaySettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) return;
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
        } catch (Exception ignored) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
        }
    }

    private void openAutostartSettings() {
        getSharedPreferences(LAUNCHER_PREFS, MODE_PRIVATE).edit().putBoolean("autostart_pending", true).apply();
        String[] targets = {
            "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.huawei.systemmanager/.startupmgr.ui.StartupNormalAppListActivity",
            "com.coloros.safecenter/.startupapp.StartupAppListActivity",
            "com.oneplus.security/.chainlaunch.view.ChainLaunchAppListActivity",
            "com.samsung.android.lool/.auto_run_apps.AutoRunAppsActivity",
        };
        for (String target : targets) {
            try {
                Intent intent = Intent.parseUri("intent:#Intent;action=android.intent.action.MAIN;component=" + target + ";end", Intent.URI_INTENT_SCHEME);
                startActivity(intent);
                return;
            } catch (Exception ignored) {}
        }
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
    }

    private void refreshStatus() {
        boolean role = isAssistantRoleHeld();
        boolean mic = hasMic();
        boolean accessibility = CoomiAccessibilityService.isReady();
        boolean overlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
        boolean autostart = getSharedPreferences(LAUNCHER_PREFS, MODE_PRIVATE).getBoolean("autostart_enabled", false);
        CoomiService engine = CoomiService.current();
        boolean engineReady = engine != null && !engine.getEngineToken().isEmpty();
        roleStatus.setText(role ? R.string.coomi_assistant_configured : R.string.coomi_assistant_role_missing);
        micStatus.setText(mic ? R.string.coomi_assistant_configured : R.string.coomi_assistant_mic_missing);
        accessibilityStatus.setText(accessibility ? R.string.coomi_assistant_configured : R.string.coomi_assistant_accessibility_missing);
        overlayStatus.setText(overlay ? R.string.coomi_assistant_configured : R.string.coomi_assistant_overlay_missing);
        autostartStatus.setText(autostart ? R.string.coomi_assistant_configured : R.string.coomi_assistant_autostart_missing);
        engineStatus.setText(engineReady ? R.string.coomi_assistant_engine_ready : R.string.coomi_assistant_engine_missing);
        roleButton.setEnabled(!role);
        roleButton.setText(role ? R.string.coomi_enabled : R.string.coomi_go_grant);
        micButton.setEnabled(!mic);
        micButton.setText(mic ? R.string.coomi_enabled : R.string.coomi_allow);
        boolean speechReady = (!store.speechProvider().isEmpty() && !store.speechModel().isEmpty()) || store.useSystemFallback();
        summary.setText(role && mic && speechReady
            ? R.string.coomi_assistant_ready_summary : R.string.coomi_assistant_summary);
    }
}
