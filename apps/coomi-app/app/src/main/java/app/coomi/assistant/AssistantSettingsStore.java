package app.coomi.assistant;

import android.content.Context;
import android.content.SharedPreferences;

import app.coomi.CoomiConstants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/** Local-only configuration for the Android system assistant surface. */
public final class AssistantSettingsStore {
    private static final String PREFS = "coomi_phone_assistant";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_PROVIDER = "speech_provider";
    private static final String KEY_MODEL = "speech_model";
    private static final String KEY_LANGUAGE = "speech_language";
    private static final String KEY_SYSTEM_FALLBACK = "system_speech_fallback";
    private static final String KEY_SESSION = "control_session";

    private final Context context;
    private final SharedPreferences preferences;

    public AssistantSettingsStore(Context context) {
        this.context = context.getApplicationContext();
        this.preferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean isEnabled() {
        return preferences.getBoolean(KEY_ENABLED, false);
    }

    public void setEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public String speechProvider() {
        return preferences.getString(KEY_PROVIDER, "");
    }

    public String speechModel() {
        return preferences.getString(KEY_MODEL, "");
    }

    public String language() {
        String value = preferences.getString(KEY_LANGUAGE, "zh");
        return value == null || value.trim().isEmpty() ? "zh" : value.trim();
    }

    public boolean useSystemFallback() {
        return preferences.getBoolean(KEY_SYSTEM_FALLBACK, true);
    }

    public void saveSpeech(String provider, String model, String language, boolean fallback) {
        preferences.edit()
            .putString(KEY_PROVIDER, provider == null ? "" : provider.trim())
            .putString(KEY_MODEL, model == null ? "" : model.trim())
            .putString(KEY_LANGUAGE, language == null || language.trim().isEmpty() ? "zh" : language.trim())
            .putBoolean(KEY_SYSTEM_FALLBACK, fallback)
            .apply();
    }

    public String controlSessionId() {
        String current = preferences.getString(KEY_SESSION, "");
        if (current != null && !current.isEmpty()) return current;
        String generated = UUID.randomUUID().toString();
        preferences.edit().putString(KEY_SESSION, generated).commit();
        return generated;
    }

    public List<ProviderOption> configuredProviders() {
        ArrayList<ProviderOption> result = new ArrayList<>();
        JSONObject document = readProviderDocument();
        JSONObject providers = document.optJSONObject("providers");
        if (providers == null) return result;
        Iterator<String> ids = providers.keys();
        while (ids.hasNext()) {
            String id = ids.next();
            JSONObject provider = providers.optJSONObject(id);
            if (provider == null || provider.optString("base_url", "").trim().isEmpty()) continue;
            String display = provider.optString("display", id).trim();
            LinkedHashSet<String> models = new LinkedHashSet<>();
            String selected = provider.optString("model", "").trim();
            if (!selected.isEmpty()) models.add(selected);
            JSONArray declared = provider.optJSONArray("models");
            if (declared != null) {
                for (int index = 0; index < declared.length(); index++) {
                    String model = declared.optString(index, "").trim();
                    if (!model.isEmpty()) models.add(model);
                }
            }
            // Older provider files keep arbitrary fields under an extra object.
            JSONObject extra = provider.optJSONObject("extra");
            JSONArray extraModels = extra == null ? null : extra.optJSONArray("models");
            if (extraModels != null) {
                for (int index = 0; index < extraModels.length(); index++) {
                    String model = extraModels.optString(index, "").trim();
                    if (!model.isEmpty()) models.add(model);
                }
            }
            result.add(new ProviderOption(id, display.isEmpty() ? id : display, new ArrayList<>(models)));
        }
        return result;
    }

    public ControlModel activeControlModel() {
        JSONObject document = readProviderDocument();
        String providerId = document.optString("active", "").trim();
        JSONObject providers = document.optJSONObject("providers");
        JSONObject provider = providers == null ? null : providers.optJSONObject(providerId);
        String model = provider == null ? "" : provider.optString("model", "").trim();
        return new ControlModel(providerId, model);
    }

    private JSONObject readProviderDocument() {
        File file = new File(CoomiConstants.COOMI_PROVIDER_FILE);
        if (!file.isFile() || file.length() > 4L * 1024L * 1024L) return new JSONObject();
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) > 0) output.write(buffer, 0, read);
            return new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    public static final class ProviderOption {
        public final String id;
        public final String display;
        public final List<String> models;

        ProviderOption(String id, String display, List<String> models) {
            this.id = id;
            this.display = display;
            this.models = models;
        }

        @Override public String toString() { return display; }
    }

    public static final class ControlModel {
        public final String providerId;
        public final String model;

        ControlModel(String providerId, String model) {
            this.providerId = providerId;
            this.model = model;
        }

        public boolean isReady() { return !providerId.isEmpty() && !model.isEmpty(); }
    }
}
