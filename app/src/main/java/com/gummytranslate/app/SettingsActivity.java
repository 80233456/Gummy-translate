package com.gummytranslate.app;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Spinner;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class SettingsActivity extends PaperActivity {
    private EditText apiKeyInput;
    private TextView keyStatus;
    private Button testButton;
    private final Handler handler = new Handler();
    private OkHttpClient client;
    private WebSocket activeSocket;
    private boolean testFinished;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        applyPaperInsets();
        apiKeyInput = findViewById(R.id.apiKeyInput);
        keyStatus = findViewById(R.id.keyStatusText);
        testButton = findViewById(R.id.testKeyButton);
        CheckBox showKey = findViewById(R.id.showKeyCheck);
        CheckBox keepScreen = findViewById(R.id.keepScreenCheck);
        CheckBox noiseSuppression = findViewById(R.id.noiseSuppressionCheck);
        CheckBox echoCancellation = findViewById(R.id.echoCancellationCheck);
        CheckBox autoGain = findViewById(R.id.autoGainCheck);
        Spinner silenceSpinner = findViewById(R.id.silenceSpinner);
        Spinner textSizeSpinner = findViewById(R.id.textSizeSpinner);
        Spinner micGainSpinner = findViewById(R.id.micGainSpinner);

        migrateAudioDefaults();
        migrateVisualDefaults();

        apiKeyInput.setText(settings().getString("dashscope_api_key", ""));
        keepScreen.setChecked(settings().getBoolean("keep_screen_on", true));
        keepScreen.setOnCheckedChangeListener((button, checked) -> settings().edit().putBoolean("keep_screen_on", checked).apply());
        bindAudioOption(noiseSuppression, "noise_suppression", true, NoiseSuppressor.isAvailable());
        bindAudioOption(echoCancellation, "echo_cancellation", false, AcousticEchoCanceler.isAvailable());
        bindAudioOption(autoGain, "auto_gain", false, AutomaticGainControl.isAvailable());
        ((TextView) findViewById(R.id.audioSupportText)).setText(audioSupportDescription());
        bindSpinner(silenceSpinner,
                new String[]{"灵敏 · 400ms", "均衡 · 600ms", "稳健 · 800ms", "长句 · 1200ms", "弱音保护 · 1800ms"},
                new int[]{400, 600, 800, 1200, 1800}, "end_silence_ms", 1200);
        bindSpinner(textSizeSpinner,
                new String[]{"紧凑 · 18sp", "标准 · 20sp（推荐）", "大字 · 23sp"},
                new int[]{18, 20, 23}, "translation_text_sp", 20);
        bindScenePreset(micGainSpinner, noiseSuppression, echoCancellation, silenceSpinner);
        showKey.setOnCheckedChangeListener((button, checked) -> {
            apiKeyInput.setInputType(checked
                    ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            apiKeyInput.setSelection(apiKeyInput.length());
        });
        testButton.setOnClickListener(v -> testConnection());
        findViewById(R.id.homeNav).setOnClickListener(v -> open(MainActivity.class));
        findViewById(R.id.historyNav).setOnClickListener(v -> open(HistoryActivity.class));
    }

    private void migrateAudioDefaults() {
        if (settings().getInt("audio_profile_version", 0) >= 3) return;
        settings().edit()
                .putInt("audio_profile_version", 3)
                .putInt("audio_scene", 1)
                .putBoolean("noise_suppression", true)
                .putBoolean("echo_cancellation", false)
                .putInt("software_gain_max", 4)
                .putInt("end_silence_ms", 1200)
                .apply();
    }

    private void bindScenePreset(Spinner spinner, CheckBox noiseSuppression,
                                 CheckBox echoCancellation, Spinner silenceSpinner) {
        String[] labels = {"近距离 · 清晰低延迟", "普通课堂 · 均衡增强（推荐）", "远距离 / 视频 · 弱音优先"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(settings().getInt("audio_scene", 1), false);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int gain = position == 0 ? 1 : position == 1 ? 4 : 12;
                int silence = position == 0 ? 600 : position == 1 ? 1200 : 1800;
                boolean noise = position != 2;
                settings().edit()
                        .putInt("audio_scene", position)
                        .putInt("software_gain_max", gain)
                        .putInt("end_silence_ms", silence)
                        .putBoolean("noise_suppression", noise)
                        .putBoolean("echo_cancellation", false)
                        .apply();
                noiseSuppression.setChecked(noise && noiseSuppression.isEnabled());
                echoCancellation.setChecked(false);
                silenceSpinner.setSelection(position == 0 ? 1 : position == 1 ? 3 : 4);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    private void migrateVisualDefaults() {
        if (settings().getInt("visual_profile_version", 0) >= 2) return;
        settings().edit()
                .putInt("visual_profile_version", 2)
                .putInt("translation_text_sp", 20)
                .apply();
    }

    private void bindAudioOption(CheckBox checkBox, String key, boolean defaultValue, boolean available) {
        checkBox.setEnabled(available);
        checkBox.setChecked(available && settings().getBoolean(key, defaultValue));
        if (!available) checkBox.setText(checkBox.getText() + " · 本机不支持");
        checkBox.setOnCheckedChangeListener((button, checked) -> settings().edit().putBoolean(key, checked).apply());
    }

    private void bindSpinner(Spinner spinner, String[] labels, int[] values, String key, int defaultValue) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        int saved = settings().getInt(key, defaultValue);
        int selection = 0;
        for (int i = 0; i < values.length; i++) if (values[i] == saved) selection = i;
        spinner.setSelection(selection, false);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                settings().edit().putInt(key, values[position]).apply();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    private String audioSupportDescription() {
        int supported = 0;
        if (NoiseSuppressor.isAvailable()) supported++;
        if (AcousticEchoCanceler.isAvailable()) supported++;
        if (AutomaticGainControl.isAvailable()) supported++;
        return supported == 3 ? "本机支持全部三项增强" : "本机支持 " + supported + "/3 项增强；不可用项已自动关闭";
    }

    private android.content.SharedPreferences settings() {
        return getSharedPreferences("settings", MODE_PRIVATE);
    }

    private void testConnection() {
        String key = apiKeyInput.getText().toString().trim();
        if (key.isEmpty()) {
            showResult("请输入 API Key", false);
            return;
        }
        settings().edit().putString("dashscope_api_key", key).apply();
        testFinished = false;
        testButton.setEnabled(false);
        keyStatus.setText("正在连接 Gummy…");
        keyStatus.setTextColor(getColor(R.color.text_secondary));

        client = new OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).build();
        Request request = new Request.Builder()
                .url("wss://dashscope.aliyuncs.com/api-ws/v1/inference/")
                .header("Authorization", "bearer " + key).build();
        String taskId = UUID.randomUUID().toString().replace("-", "");
        activeSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(WebSocket socket, Response response) {
                try {
                    JSONObject parameters = new JSONObject();
                    parameters.put("sample_rate", 16000);
                    parameters.put("format", "pcm");
                    parameters.put("source_language", "en");
                    parameters.put("transcription_enabled", true);
                    parameters.put("translation_enabled", true);
                    parameters.put("translation_target_languages", new JSONArray().put("zh"));
                    JSONObject payload = new JSONObject();
                    payload.put("model", "gummy-realtime-v1");
                    payload.put("parameters", parameters);
                    payload.put("input", new JSONObject());
                    payload.put("task", "asr");
                    payload.put("task_group", "audio");
                    payload.put("function", "recognition");
                    JSONObject header = new JSONObject();
                    header.put("streaming", "duplex");
                    header.put("task_id", taskId);
                    header.put("action", "run-task");
                    socket.send(new JSONObject().put("header", header).put("payload", payload).toString());
                } catch (Exception e) {
                    completeTest("请求创建失败", false);
                }
            }

            @Override public void onMessage(WebSocket socket, String text) {
                try {
                    JSONObject header = new JSONObject(text).optJSONObject("header");
                    String event = header == null ? "" : header.optString("event");
                    if ("task-started".equals(event)) {
                        JSONObject finishHeader = new JSONObject();
                        finishHeader.put("streaming", "duplex");
                        finishHeader.put("task_id", taskId);
                        finishHeader.put("action", "finish-task");
                        socket.send(new JSONObject().put("header", finishHeader)
                                .put("payload", new JSONObject().put("input", new JSONObject())).toString());
                        completeTest("✓ Key 与 Gummy 服务均可用", true);
                    } else if ("task-failed".equals(event)) {
                        completeTest("连接失败：" + header.optString("error_message", "服务拒绝请求"), false);
                    }
                } catch (Exception ignored) {
                }
            }

            @Override public void onFailure(WebSocket socket, Throwable error, Response response) {
                completeTest("连接失败，请检查 Key 和网络", false);
            }
        });
        handler.postDelayed(() -> {
            if (!testFinished) completeTest("连接超时，请检查网络", false);
        }, 12000);
    }

    private synchronized void completeTest(String text, boolean success) {
        if (testFinished) return;
        testFinished = true;
        if (activeSocket != null) activeSocket.close(1000, "test complete");
        runOnUiThread(() -> showResult(text, success));
    }

    private void showResult(String text, boolean success) {
        keyStatus.setText(text);
        keyStatus.setTextColor(getColor(success ? R.color.success : R.color.error));
        testButton.setEnabled(true);
    }

    private void open(Class<?> target) {
        startActivity(new Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
        overridePendingTransition(R.anim.tab_enter, R.anim.tab_exit);
        finish();
        overridePendingTransition(R.anim.tab_enter, R.anim.tab_exit);
    }

    @Override protected void onDestroy() {
        if (activeSocket != null) activeSocket.cancel();
        if (client != null) client.dispatcher().executorService().shutdown();
        super.onDestroy();
    }
}
