package com.gummytranslate.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.AbsListView;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.alibaba.idst.nui.AsrResult;
import com.alibaba.idst.nui.Constants;
import com.alibaba.idst.nui.INativeNuiCallback;
import com.alibaba.idst.nui.KwsResult;
import com.alibaba.idst.nui.NativeNui;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class LiveActivity extends PaperActivity implements INativeNuiCallback {
    private static final String TAG = "GummyLive";
    private static final int RECORD_REQUEST = 1101;
    private static final int NOTIFICATION_REQUEST = 1102;
    private static final int SAMPLE_RATE = 16000;

    private final NativeNui nui = new NativeNui();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService polishWorker = Executors.newSingleThreadExecutor();
    private final QwenMtClient qwenMtClient = new QwenMtClient();
    private final List<AppStorage.Caption> translationMemory = new ArrayList<>();
    private final AtomicInteger pendingPolishCount = new AtomicInteger();
    private final Handler ui = new Handler();
    private final List<AppStorage.Caption> captions = new ArrayList<>();
    private AudioRecord recorder;
    private NoiseSuppressor noiseSuppressor;
    private AcousticEchoCanceler echoCanceler;
    private AutomaticGainControl automaticGainControl;
    private float smoothedSoftwareGain = 1f;
    private long speechGainHoldUntil;
    private boolean initialized;
    private boolean running;
    private boolean paused;
    private boolean ending;
    private boolean connecting;
    private boolean retryScheduled;
    private boolean finishAfterPolishing;
    private int reconnectAttempts;
    private long sessionId;
    private final Map<Integer, PendingSentence> pendingSentences = new HashMap<>();
    private int currentSentenceId = -1;
    private int displayedSentenceId = -1;
    private String lastSavedEnglish = "";
    private String lastSavedChinese = "";

    private AppStorage storage;
    private TextView statusText;
    private View statusDot;
    private TextView timerText;
    private TextView englishText;
    private TextView chineseText;
    private Button pauseButton;
    private ListView captionList;
    private View jumpToLatestButton;
    private CaptionAdapter adapter;
    private boolean followLatest = true;
    private int captionScrollState = AbsListView.OnScrollListener.SCROLL_STATE_IDLE;
    private long lastEnglishRevealAt;
    private long lastChineseRevealAt;
    private final Runnable connectionTimeout = () -> {
        if (connecting && !ending && !paused) handleConnectionFailure("连接超时");
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_live);
        applyPaperInsets();
        if (getSharedPreferences("settings", MODE_PRIVATE).getBoolean("keep_screen_on", true)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        storage = new AppStorage(this);
        migrateAudioDefaults();
        migrateVisualDefaults();
        bindUi();

        updateTimer();
        requestAudioAndStart();
    }

    private void bindUi() {
        statusText = findViewById(R.id.statusText);
        statusDot = findViewById(R.id.statusDot);
        timerText = findViewById(R.id.timerText);
        englishText = findViewById(R.id.englishText);
        chineseText = findViewById(R.id.chineseText);
        int translationTextSize = getSharedPreferences("settings", MODE_PRIVATE).getInt("translation_text_sp", 20);
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        float chineseSize = landscape ? Math.min(18f, translationTextSize * 0.86f) : translationTextSize;
        float englishSize = landscape ? Math.max(12f, Math.min(15f, chineseSize * 0.78f))
                : Math.max(14, Math.round(translationTextSize * 0.76f));
        chineseText.setTextSize(chineseSize);
        englishText.setTextSize(englishSize);
        englishText.setMovementMethod(new ScrollingMovementMethod());
        chineseText.setMovementMethod(new ScrollingMovementMethod());
        pauseButton = findViewById(R.id.pauseButton);
        captionList = findViewById(R.id.captionList);
        jumpToLatestButton = findViewById(R.id.jumpToLatestButton);
        adapter = new CaptionAdapter();
        captionList.setAdapter(adapter);
        captionList.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int state) {
                captionScrollState = state;
            }

            @Override public void onScroll(AbsListView view, int first, int visible, int total) {
                boolean atBottom = total == 0 || first + visible >= total;
                if (atBottom) {
                    followLatest = true;
                    jumpToLatestButton.setVisibility(View.GONE);
                } else if (captionScrollState != SCROLL_STATE_IDLE) {
                    followLatest = false;
                    jumpToLatestButton.setVisibility(View.VISIBLE);
                }
            }
        });
        jumpToLatestButton.setOnClickListener(v -> {
            followLatest = true;
            jumpToLatestButton.setVisibility(View.GONE);
            scrollCaptionListToBottom();
        });

        findViewById(R.id.backButton).setOnClickListener(v -> confirmEnd());
        findViewById(R.id.endButton).setOnClickListener(v -> endClass());
        pauseButton.setOnClickListener(v -> togglePause());
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        String status = statusText.getText().toString();
        int statusColor = statusDot.getBackgroundTintList() == null ? getColor(R.color.text_secondary)
                : statusDot.getBackgroundTintList().getDefaultColor();
        String english = englishText.getText().toString();
        String chinese = chineseText.getText().toString();
        String pauseLabel = pauseButton.getText().toString();
        boolean pauseEnabled = pauseButton.isEnabled();
        super.onConfigurationChanged(newConfig);
        setContentView(R.layout.activity_live);
        applyPaperInsets();
        bindUi();
        statusText.setText(status);
        statusDot.setBackgroundTintList(ColorStateList.valueOf(statusColor));
        englishText.setText(english);
        chineseText.setText(chinese);
        pauseButton.setText(pauseLabel);
        pauseButton.setEnabled(pauseEnabled);
        if ("暂停".equals(pauseLabel)) setPauseButtonIcon(R.drawable.ic_pause);
        else if ("继续".equals(pauseLabel)) setPauseButtonIcon(R.drawable.ic_play);
        else setPauseButtonIcon(R.drawable.ic_refresh);
        renderTimer();
        if (!followLatest) jumpToLatestButton.setVisibility(View.VISIBLE);
        else if (!captions.isEmpty()) scrollCaptionListToBottom();
    }

    private void requestAudioAndStart() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.POST_NOTIFICATIONS}, RECORD_REQUEST);
            } else {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, RECORD_REQUEST);
            }
        } else {
            requestNotificationPermissionIfNeeded();
            beginSession();
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_REQUEST);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == RECORD_REQUEST && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            beginSession();
        } else if (requestCode == RECORD_REQUEST) {
            Toast.makeText(this, "需要麦克风权限才能开始课堂", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void beginSession() {
        try {
            ClassroomSessionService.start(this);
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to start classroom foreground service", error);
        }
        startTranslation();
    }

    private void migrateAudioDefaults() {
        android.content.SharedPreferences preferences = getSharedPreferences("settings", MODE_PRIVATE);
        if (preferences.getInt("audio_profile_version", 0) >= 3) return;
        preferences.edit()
                .putInt("audio_profile_version", 3)
                .putInt("audio_scene", 1)
                .putBoolean("noise_suppression", true)
                .putBoolean("echo_cancellation", false)
                .putInt("software_gain_max", 4)
                .putInt("end_silence_ms", 1200)
                .apply();
    }

    private void migrateVisualDefaults() {
        android.content.SharedPreferences preferences = getSharedPreferences("settings", MODE_PRIVATE);
        if (preferences.getInt("visual_profile_version", 0) >= 2) return;
        preferences.edit()
                .putInt("visual_profile_version", 2)
                .putInt("translation_text_sp", 20)
                .apply();
    }

    private void startTranslation() {
        String key = getSharedPreferences("settings", MODE_PRIVATE).getString("dashscope_api_key", "");
        if (key.isEmpty()) {
            showError("没有配置 API Key");
            return;
        }
        connecting = true;
        retryScheduled = false;
        setStatus(reconnectAttempts == 0 ? "正在连接…" : "正在重新连接…", R.color.text_secondary);
        pauseButton.setEnabled(false);
        worker.execute(() -> {
            try {
                if (!initialized) {
                    JSONObject init = new JSONObject();
                    init.put("url", "wss://dashscope.aliyuncs.com/api-ws/v1/inference");
                    String id = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
                    init.put("device_id", id == null ? UUID.randomUUID().toString() : id);
                    init.put("service_mode", "1");
                    init.put("enable_reconnection", "true");
                    init.put("log_track_level", "4");
                    int result = nui.initialize(this, init.toString(), Constants.LogLevel.LOG_LEVEL_WARNING, false);
                    if (result != Constants.NuiResultCode.SUCCESS) {
                        showError("初始化失败（" + result + "）");
                        return;
                    }
                    initialized = true;
                }
                JSONObject nls = new JSONObject();
                nls.put("model", "gummy-realtime-v1");
                nls.put("sr_format", "pcm");
                nls.put("sample_rate", SAMPLE_RATE);
                nls.put("source_language", "en");
                nls.put("transcription_enabled", true);
                nls.put("translation_enabled", true);
                nls.put("translation_target_languages", new JSONArray().put("zh").toString());
                nls.put("max_end_silence", getSharedPreferences("settings", MODE_PRIVATE).getInt("end_silence_ms", 600));
                JSONObject params = new JSONObject();
                params.put("service_type", Constants.kServiceTypeSpeechTranscriber);
                params.put("nls_config", nls);
                int setResult = nui.setParams(params.toString());
                if (setResult != Constants.NuiResultCode.SUCCESS) {
                    showError("参数设置失败（" + setResult + "）");
                    return;
                }
                int result = nui.startDialog(Constants.VadMode.TYPE_P2T,
                        new JSONObject().put("apikey", key).toString());
                if (result != Constants.NuiResultCode.SUCCESS) {
                    handleConnectionFailure("启动失败（" + result + "）");
                } else {
                    runOnUiThread(() -> {
                        ui.removeCallbacks(connectionTimeout);
                        ui.postDelayed(connectionTimeout, 12000);
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Unable to start", e);
                showError("启动失败，请检查网络和 Key");
            }
        });
    }

    private void togglePause() {
        if (running) {
            paused = true;
            ClassroomSessionService.markNotTranslating();
            pauseButton.setEnabled(false);
            setStatus("正在暂停…", R.color.text_secondary);
            worker.execute(nui::stopDialog);
        } else if (paused) {
            paused = false;
            reconnectAttempts = 0;
            prepareForNewStream();
            startTranslation();
        }
    }

    private void confirmEnd() {
        new AlertDialog.Builder(this).setTitle("结束这堂课？")
                .setMessage("你可以保存本次双语字幕，或不留记录直接退出。")
                .setNegativeButton("继续上课", null)
                .setNeutralButton("不保存退出", (dialog, which) -> discardAndExit())
                .setPositiveButton("保存并退出", (dialog, which) -> endClass()).show();
    }

    private void discardAndExit() {
        if (ending) return;
        ending = true;
        ClassroomSessionService.markNotTranslating();
        if (running && initialized) nui.cancelDialog();
        if (sessionId != 0) storage.deleteSession(sessionId);
        sessionId = 0;
        returnToMain();
    }

    private void endClass() {
        if (ending) return;
        ending = true;
        ClassroomSessionService.markNotTranslating();
        if (running && initialized) {
            setStatus("正在保存…", R.color.text_secondary);
            worker.execute(nui::stopDialog);
            ui.postDelayed(this::finalizeSessionAndFinish, 1800);
        } else {
            finalizeSessionAndFinish();
        }
    }

    private void finalizeSessionAndFinish() {
        if (isFinishing()) return;
        flushPendingSentences();
        finishAfterPolishing = true;
        int remaining = pendingPolishCount.get();
        if (remaining > 0) {
            setStatus("正在优化最后 " + remaining + " 条字幕…", R.color.text_secondary);
            pauseButton.setEnabled(false);
            ui.postDelayed(this::completeSessionAndFinish, 12000);
            return;
        }
        completeSessionAndFinish();
    }

    private void completeSessionAndFinish() {
        if (isFinishing() || !finishAfterPolishing) return;
        finishAfterPolishing = false;
        if (sessionId != 0) {
            storage.finishSession(sessionId);
            sessionId = 0;
        }
        returnToMain();
    }

    private void returnToMain() {
        Intent home = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(home);
        finish();
    }

    private void updateTimer() {
        renderTimer();
        if (!isFinishing()) ui.postDelayed(this::updateTimer, 1000);
    }

    private void renderTimer() {
        long seconds = ClassroomSessionService.getActiveElapsedMs() / 1000;
        timerText.setText(String.format(java.util.Locale.US, "%02d:%02d", seconds / 60, seconds % 60));
    }

    private void setStatus(String text, int color) {
        ClassroomSessionService.updateStatus(text);
        runOnUiThread(() -> {
            statusText.setText(text);
            statusDot.setBackgroundTintList(ColorStateList.valueOf(getColor(color)));
        });
    }

    private void showError(String message) {
        running = false;
        connecting = false;
        retryScheduled = false;
        ui.removeCallbacks(connectionTimeout);
        setStatus(message, R.color.error);
        runOnUiThread(() -> {
            pauseButton.setText("重试");
            setPauseButtonIcon(R.drawable.ic_refresh);
            pauseButton.setEnabled(true);
            paused = true;
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        });
    }

    @Override public void onNuiEventCallback(Constants.NuiEvent event, int resultCode, int arg2, KwsResult kws, AsrResult result) {
        if (event == Constants.NuiEvent.EVENT_TRANSCRIBER_STARTED) {
            if (sessionId == 0) sessionId = storage.startSession();
            running = true;
            ClassroomSessionService.markTranslating();
            connecting = false;
            retryScheduled = false;
            reconnectAttempts = 0;
            ui.removeCallbacks(connectionTimeout);
            paused = false;
            setStatus("正在实时翻译", R.color.success);
            runOnUiThread(() -> {
                pauseButton.setText("暂停");
                setPauseButtonIcon(R.drawable.ic_pause);
                pauseButton.setEnabled(true);
            });
        } else if (event == Constants.NuiEvent.EVENT_ASR_PARTIAL_RESULT || event == Constants.NuiEvent.EVENT_SENTENCE_END) {
            if (result != null) updateCaptions(result.allResponse);
        } else if (event == Constants.NuiEvent.EVENT_TRANSCRIBER_COMPLETE) {
            running = false;
            if (ending) runOnUiThread(this::finalizeSessionAndFinish);
            else if (paused) {
                flushPendingSentences();
                resetSentenceState();
                setStatus("已暂停", R.color.text_secondary);
                runOnUiThread(() -> {
                    pauseButton.setText("继续");
                    setPauseButtonIcon(R.drawable.ic_play);
                    pauseButton.setEnabled(true);
                });
            } else if (!retryScheduled) {
                handleConnectionFailure("连接意外结束");
            }
        } else if (event == Constants.NuiEvent.EVENT_ASR_ERROR || event == Constants.NuiEvent.EVENT_MIC_ERROR) {
            handleConnectionFailure("翻译中断，错误码 " + resultCode);
        }
    }

    private synchronized void handleConnectionFailure(String reason) {
        if (ending || paused || retryScheduled) return;
        running = false;
        ClassroomSessionService.markNotTranslating();
        connecting = false;
        ui.removeCallbacks(connectionTimeout);
        if (reconnectAttempts >= 3) {
            showError(reason + "；点击重试");
            return;
        }
        retryScheduled = true;
        int attempt = ++reconnectAttempts;
        flushPendingSentences();
        resetSentenceState();
        setStatus(reason + "，重连 " + attempt + "/3…", R.color.text_secondary);
        runOnUiThread(() -> {
            pauseButton.setText("重连中");
            setPauseButtonIcon(R.drawable.ic_refresh);
            pauseButton.setEnabled(false);
        });
        worker.execute(() -> {
            try { if (initialized) nui.cancelDialog(); }
            catch (RuntimeException error) { Log.w(TAG, "Unable to cancel failed dialog", error); }
            ui.postDelayed(() -> {
                if (ending || paused || isFinishing()) return;
                prepareForNewStream();
                startTranslation();
            }, Math.min(2400, 600L * attempt));
        });
    }

    private void setPauseButtonIcon(int drawableId) {
        pauseButton.setCompoundDrawablesRelativeWithIntrinsicBounds(drawableId, 0, 0, 0);
    }

    private synchronized void updateCaptions(String response) {
        try {
            JSONObject payload = new JSONObject(response).optJSONObject("payload");
            JSONObject output = payload == null ? null : payload.optJSONObject("output");
            if (output == null) return;
            Set<Integer> touched = new HashSet<>();
            JSONObject transcription = output.optJSONObject("transcription");
            if (transcription != null) {
                int id = transcription.optInt("sentence_id", currentSentenceId < 0 ? 0 : currentSentenceId);
                PendingSentence sentence = pending(id);
                String text = transcription.optString("text", "");
                if (!text.isEmpty()) sentence.english = text;
                sentence.transcriptionEnd |= transcription.optBoolean("sentence_end", false);
                touched.add(id);
            }
            JSONArray translations = output.optJSONArray("translations");
            if (translations != null) {
                for (int i = 0; i < translations.length(); i++) {
                    JSONObject item = translations.optJSONObject(i);
                    if (item == null) continue;
                    int id = item.optInt("sentence_id", currentSentenceId < 0 ? 0 : currentSentenceId);
                    PendingSentence sentence = pending(id);
                    String text = item.optString("text", "");
                    if (!text.isEmpty()) sentence.chinese = text;
                    sentence.translationEnd |= item.optBoolean("sentence_end", false);
                    touched.add(id);
                }
            }
            for (int id : touched) {
                PendingSentence sentence = pendingSentences.get(id);
                if (sentence != null && sentence.transcriptionEnd && sentence.translationEnd) {
                    finishSentence(id);
                } else if (sentence != null && sentence.transcriptionEnd && !sentence.fallbackScheduled) {
                    sentence.fallbackScheduled = true;
                    ui.postDelayed(() -> finishSentence(id), 1800);
                }
            }
            PendingSentence current = pendingSentences.get(currentSentenceId);
            String english = current == null ? "" : current.english;
            String chinese = current == null ? "" : current.chinese;
            boolean newSentence = current != null && currentSentenceId != displayedSentenceId;
            if (newSentence) displayedSentenceId = currentSentenceId;
            runOnUiThread(() -> {
                if (newSentence) {
                    englishText.setAlpha(0.55f);
                    chineseText.setAlpha(0.55f);
                }
                if (!english.isEmpty()) updateStreamingText(englishText, english);
                if (!chinese.isEmpty()) updateStreamingText(chineseText, chinese);
                if (newSentence) {
                    englishText.animate().alpha(1f).setDuration(180).start();
                    chineseText.animate().alpha(1f).setDuration(220).start();
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "Unexpected result", e);
        }
    }

    /** Keep the newest part of an in-progress long sentence visible after TextView relayout. */
    private void updateStreamingText(TextView view, String text) {
        if (text.contentEquals(view.getText())) return;
        view.setText(text);
        long now = android.os.SystemClock.uptimeMillis();
        long lastReveal = view == englishText ? lastEnglishRevealAt : lastChineseRevealAt;
        if (now - lastReveal >= 150) {
            if (view == englishText) lastEnglishRevealAt = now;
            else lastChineseRevealAt = now;
            float offset = 3f * getResources().getDisplayMetrics().density;
            view.animate().cancel();
            view.setAlpha(0.72f);
            view.setTranslationY(offset);
            view.animate().alpha(1f).translationY(0f).setDuration(170).start();
        }
        view.postOnAnimation(() -> scrollTextToBottom(view));
    }

    private void scrollTextToBottom(TextView view) {
        if (view.getLayout() == null) return;
        int contentHeight = view.getLayout().getLineTop(view.getLineCount());
        int viewportHeight = view.getHeight() - view.getCompoundPaddingTop() - view.getCompoundPaddingBottom();
        view.scrollTo(0, Math.max(0, contentHeight - viewportHeight));
    }

    private PendingSentence pending(int id) {
        PendingSentence sentence = pendingSentences.get(id);
        if (sentence == null) {
            sentence = new PendingSentence();
            pendingSentences.put(id, sentence);
        }
        if (id >= currentSentenceId) currentSentenceId = id;
        return sentence;
    }

    private synchronized void resetSentenceState() {
        pendingSentences.clear();
        currentSentenceId = -1;
        displayedSentenceId = -1;
        lastSavedEnglish = "";
        lastSavedChinese = "";
    }

    private void prepareForNewStream() {
        resetSentenceState();
        englishText.setText("等待外教继续说话…");
        chineseText.setText("等待翻译…");
        englishText.scrollTo(0, 0);
        chineseText.scrollTo(0, 0);
    }

    private synchronized void finishSentence(int id) {
        PendingSentence sentence = pendingSentences.remove(id);
        if (sentence == null) return;
        commitCaption(sentence.english, sentence.chinese);
    }

    private synchronized void flushPendingSentences() {
        for (PendingSentence sentence : new ArrayList<>(pendingSentences.values())) {
            commitCaption(sentence.english, sentence.chinese);
        }
        pendingSentences.clear();
    }

    private synchronized void commitCaption(String english, String chinese) {
        if (sessionId == 0) return;
        if ((english == null || english.isEmpty()) && (chinese == null || chinese.isEmpty())) return;
        if (english.equals(lastSavedEnglish) && chinese.equals(lastSavedChinese)) return;
        lastSavedEnglish = english;
        lastSavedChinese = chinese;
        long captionId = storage.addCaption(sessionId, english, chinese);
        AppStorage.Caption caption = new AppStorage.Caption(captionId, english, chinese);
        String apiKey = getSharedPreferences("settings", MODE_PRIVATE).getString("dashscope_api_key", "");
        boolean shouldOptimize = captionId > 0 && english != null && !english.trim().isEmpty()
                && !apiKey.isEmpty();
        caption.optimizing = shouldOptimize;
        runOnUiThread(() -> {
            captions.add(caption);
            adapter.notifyDataSetChanged();
            if (followLatest) scrollCaptionListToBottom();
            else jumpToLatestButton.setVisibility(View.VISIBLE);
        });
        if (shouldOptimize) polishCaption(caption, chinese, apiKey);
    }

    private void polishCaption(AppStorage.Caption caption, String gummyTranslation, String apiKey) {
        pendingPolishCount.incrementAndGet();
        polishWorker.execute(() -> {
            try {
                List<AppStorage.Caption> memory = new ArrayList<>(translationMemory);
                String optimized = qwenMtClient.translate(apiKey, caption.english, memory);
                storage.updateCaptionChinese(caption.id, optimized);
                caption.chinese = optimized;
                caption.optimizing = false;
                caption.optimizationFailed = false;
                translationMemory.add(new AppStorage.Caption(caption.id, caption.english, optimized));
                while (translationMemory.size() > 2) translationMemory.remove(0);
            } catch (Exception error) {
                Log.w(TAG, "Qwen-MT optimization failed", error);
                caption.chinese = gummyTranslation == null ? "" : gummyTranslation;
                caption.optimizing = false;
                caption.optimizationFailed = true;
            }
            int remaining = pendingPolishCount.decrementAndGet();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                adapter.notifyDataSetChanged();
                if (followLatest) scrollCaptionListToBottom();
                if (finishAfterPolishing) {
                    if (remaining == 0) completeSessionAndFinish();
                    else setStatus("正在优化最后 " + remaining + " 条字幕…", R.color.text_secondary);
                }
            });
        });
    }

    /**
     * A long final row changes height during ListView's next layout pass. Waiting for that pass avoids
     * smoothScrollToPosition stopping above the true bottom because it used the previous row height.
     */
    private void scrollCaptionListToBottom() {
        int last = captions.size() - 1;
        if (last < 0) return;
        captionList.postOnAnimation(() -> {
            captionList.setSelection(last);
            captionList.postOnAnimation(() -> captionList.setSelection(last));
        });
    }

    @Override public int onNuiNeedAudioData(byte[] buffer, int len) {
        AudioRecord active = recorder;
        if (active == null || active.getState() != AudioRecord.STATE_INITIALIZED) return -1;
        int read = active.read(buffer, 0, len, AudioRecord.READ_BLOCKING);
        if (read > 1) applyAdaptiveGain(buffer, read);
        return read;
    }

    /** Boost quiet 16-bit PCM speech while keeping loud syllables below clipping. */
    private void applyAdaptiveGain(byte[] pcm, int length) {
        int maxGain = getSharedPreferences("settings", MODE_PRIVATE).getInt("software_gain_max", 4);
        if (maxGain <= 1) {
            smoothedSoftwareGain = 1f;
            return;
        }
        long energy = 0;
        int peak = 0;
        int samples = length / 2;
        for (int i = 0; i + 1 < length; i += 2) {
            short sample = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8));
            int absolute = Math.abs((int) sample);
            peak = Math.max(peak, absolute);
            energy += (long) sample * sample;
        }
        if (samples == 0) return;
        double rms = Math.sqrt((double) energy / samples);
        int voiceThreshold = maxGain >= 12 ? 35 : 70;
        long now = android.os.SystemClock.uptimeMillis();
        if (rms >= voiceThreshold) speechGainHoldUntil = now + 1000;
        // Keep the last speech gain briefly so soft word endings are not dropped after a stressed syllable.
        float desired;
        if (rms < voiceThreshold) {
            desired = now < speechGainHoldUntil ? smoothedSoftwareGain : 1f;
        } else {
            desired = (float) Math.min(maxGain, 3400d / rms);
        }
        if (peak > 0) desired = Math.min(desired, 30000f / peak);
        desired = Math.max(1f, desired);
        float smoothing = desired > smoothedSoftwareGain ? 0.38f
                : now < speechGainHoldUntil ? 0.025f : 0.12f;
        smoothedSoftwareGain += (desired - smoothedSoftwareGain) * smoothing;
        if (smoothedSoftwareGain < 1.03f) return;
        for (int i = 0; i + 1 < length; i += 2) {
            short sample = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8));
            int amplified = Math.round(sample * smoothedSoftwareGain);
            amplified = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, amplified));
            pcm[i] = (byte) (amplified & 0xff);
            pcm[i + 1] = (byte) ((amplified >>> 8) & 0xff);
        }
    }

    @Override public void onNuiAudioStateChanged(Constants.AudioState state) {
        if (state == Constants.AudioState.STATE_OPEN) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
            int minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(minimum, 2560));
            if (recorder.getState() == AudioRecord.STATE_INITIALIZED) {
                attachAudioEffects(recorder.getAudioSessionId());
                recorder.startRecording();
            }
        } else if (state == Constants.AudioState.STATE_PAUSE || state == Constants.AudioState.STATE_CLOSE) {
            releaseRecorder();
        }
    }

    private synchronized void releaseRecorder() {
        if (recorder != null) {
            try { if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) recorder.stop(); }
            catch (IllegalStateException ignored) { }
            recorder.release();
            recorder = null;
        }
        releaseAudioEffects();
    }

    private void attachAudioEffects(int audioSessionId) {
        android.content.SharedPreferences preferences = getSharedPreferences("settings", MODE_PRIVATE);
        try {
            if (NoiseSuppressor.isAvailable() && preferences.getBoolean("noise_suppression", true)) {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId);
                if (noiseSuppressor != null) noiseSuppressor.setEnabled(true);
            }
            if (AcousticEchoCanceler.isAvailable() && preferences.getBoolean("echo_cancellation", false)) {
                echoCanceler = AcousticEchoCanceler.create(audioSessionId);
                if (echoCanceler != null) echoCanceler.setEnabled(true);
            }
            if (AutomaticGainControl.isAvailable() && preferences.getBoolean("auto_gain", false)) {
                automaticGainControl = AutomaticGainControl.create(audioSessionId);
                if (automaticGainControl != null) automaticGainControl.setEnabled(true);
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "Device rejected an audio effect", error);
            releaseAudioEffects();
        }
    }

    private void releaseAudioEffects() {
        if (noiseSuppressor != null) { noiseSuppressor.release(); noiseSuppressor = null; }
        if (echoCanceler != null) { echoCanceler.release(); echoCanceler = null; }
        if (automaticGainControl != null) { automaticGainControl.release(); automaticGainControl = null; }
        smoothedSoftwareGain = 1f;
        speechGainHoldUntil = 0;
    }

    @Override public void onNuiAudioRMSChanged(float value) { }
    @Override public void onNuiVprEventCallback(Constants.NuiVprEvent event) { }
    @Override public void onNuiLogTrackCallback(Constants.LogLevel level, String log) { }

    @Override public void onBackPressed() { confirmEnd(); }

    @Override public void finish() {
        super.finish();
        overridePendingTransition(R.anim.class_enter, R.anim.class_exit);
    }

    @Override protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (!ending && sessionId != 0) storage.finishSession(sessionId);
        if (running && initialized) nui.cancelDialog();
        releaseRecorder();
        if (initialized) nui.release();
        worker.shutdownNow();
        qwenMtClient.shutdown();
        polishWorker.shutdownNow();
        storage.close();
        ClassroomSessionService.stop(this);
        super.onDestroy();
    }

    private final class CaptionAdapter extends ArrayAdapter<AppStorage.Caption> {
        CaptionAdapter() { super(LiveActivity.this, R.layout.row_caption, captions); }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView != null ? convertView : LayoutInflater.from(getContext()).inflate(R.layout.row_caption, parent, false);
            AppStorage.Caption caption = getItem(position);
            ((TextView) row.findViewById(R.id.rowEnglish)).setText(caption.english);
            TextView chinese = row.findViewById(R.id.rowChinese);
            TextView state = row.findViewById(R.id.rowTranslationState);
            chinese.setText(caption.optimizing ? "" : caption.chinese);
            state.setVisibility(caption.optimizing || caption.optimizationFailed ? View.VISIBLE : View.GONE);
            state.setText(caption.optimizing ? "Qwen 正在优化…" : "优化失败 · 已显示原译");
            state.setTextColor(getColor(caption.optimizing ? R.color.text_secondary : R.color.error));
            return row;
        }
    }

    private static final class PendingSentence {
        String english = "";
        String chinese = "";
        boolean transcriptionEnd;
        boolean translationEnd;
        boolean fallbackScheduled;
    }
}
