package com.gummytranslate.app;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends PaperActivity {
    private TextView readinessText;
    private TextView latestTitle;
    private TextView latestDetail;
    private long latestSessionId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        applyPaperInsets();
        readinessText = findViewById(R.id.readinessText);
        latestTitle = findViewById(R.id.latestTitle);
        latestDetail = findViewById(R.id.latestDetail);

        Button start = findViewById(R.id.startClassButton);
        start.setOnClickListener(v -> {
            if (getSharedPreferences("settings", MODE_PRIVATE).getString("dashscope_api_key", "").isEmpty()) {
                Toast.makeText(this, "请先在设置中填写 API Key", Toast.LENGTH_LONG).show();
                openTab(SettingsActivity.class);
            } else {
                startActivity(new Intent(this, LiveActivity.class));
                overridePendingTransition(R.anim.class_enter, R.anim.class_exit);
            }
        });
        findViewById(R.id.latestCard).setOnClickListener(v -> {
            if (latestSessionId == 0) return;
            startActivity(new Intent(this, SessionDetailActivity.class).putExtra("session_id", latestSessionId));
            overridePendingTransition(R.anim.tab_enter, R.anim.tab_exit);
        });
        findViewById(R.id.historyNav).setOnClickListener(v -> openTab(HistoryActivity.class));
        findViewById(R.id.settingsNav).setOnClickListener(v -> openTab(SettingsActivity.class));
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean configured = !getSharedPreferences("settings", MODE_PRIVATE)
                .getString("dashscope_api_key", "").isEmpty();
        readinessText.setText(configured ? "● 麦克风已就绪 · Key 已配置" : "需要先配置 API Key");
        readinessText.setTextColor(getColor(configured ? R.color.success : R.color.error));

        try (AppStorage storage = new AppStorage(this)) {
            List<AppStorage.SessionSummary> sessions = storage.listSessions();
            if (!sessions.isEmpty()) {
                AppStorage.SessionSummary latest = sessions.get(0);
                latestSessionId = latest.id;
                latestTitle.setText(latest.title.isEmpty() ? new SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(new Date(latest.startedAt)) : latest.title);
                latestDetail.setText(AppStorage.formatDuration(latest.startedAt, latest.endedAt) + " · " + latest.captionCount + " 条字幕\n" + latest.preview);
            } else {
                latestSessionId = 0;
                latestTitle.setText("还没有课程记录");
                latestDetail.setText("完成第一节课后会显示在这里");
            }
        }
    }

    private void openTab(Class<?> destination) {
        startActivity(new Intent(this, destination));
        overridePendingTransition(R.anim.tab_enter, R.anim.tab_exit);
    }
}
