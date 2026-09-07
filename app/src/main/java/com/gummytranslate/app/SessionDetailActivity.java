package com.gummytranslate.app;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class SessionDetailActivity extends PaperActivity {
    private AppStorage storage;
    private AppStorage.SessionSummary session;
    private String content;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_session_detail);
        applyPaperInsets();
        storage = new AppStorage(this);
        long id = getIntent().getLongExtra("session_id", 0);
        session = storage.getSession(id);
        if (session == null) {
            Toast.makeText(this, "这条课程记录已不存在", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        content = storage.exportText(id);
        updateHeader();
        ((TextView) findViewById(R.id.detailText)).setText(content);
        findViewById(R.id.backButton).setOnClickListener(v -> finish());
        findViewById(R.id.copyButton).setOnClickListener(v -> copyAll());
        findViewById(R.id.renameButton).setOnClickListener(v -> rename());
        findViewById(R.id.shareButton).setOnClickListener(v -> share());
        findViewById(R.id.deleteButton).setOnClickListener(v -> confirmDelete());
    }

    private String formatDate(long timestamp) {
        return new SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(new Date(timestamp));
    }

    private String formatDuration(AppStorage.SessionSummary value) {
        return AppStorage.formatDuration(value.startedAt, value.endedAt);
    }

    private void updateHeader() {
        ((TextView) findViewById(R.id.detailTitle)).setText(session.title.isEmpty() ? formatDate(session.startedAt) : session.title);
        ((TextView) findViewById(R.id.detailMeta)).setText(formatDuration(session) + " · " + session.captionCount + " 条字幕 · 长按正文可选择");
    }

    private void rename() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("例如：周二口语课");
        input.setText(session.title);
        input.setSelection(input.length());
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        FrameLayout container = new FrameLayout(this);
        container.setPadding(padding, 0, padding, 0);
        container.addView(input, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this).setTitle("课程名称")
                .setView(container)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    storage.renameSession(session.id, input.getText().toString());
                    session = storage.getSession(session.id);
                    content = storage.exportText(session.id);
                    ((TextView) findViewById(R.id.detailText)).setText(content);
                    updateHeader();
                }).show();
    }

    private void copyAll() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("外教课双语记录", content));
        Toast.makeText(this, "已复制全部双语文本", Toast.LENGTH_SHORT).show();
    }

    private void share() {
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_SUBJECT, "外教课记录 " + formatDate(session.startedAt));
        share.putExtra(Intent.EXTRA_TEXT, content);
        startActivity(Intent.createChooser(share, "分享课程记录"));
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this).setTitle("删除这条课程记录？")
                .setMessage("课程和全部双语字幕将被永久删除。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    storage.deleteSession(session.id);
                    Toast.makeText(this, "课程记录已删除", Toast.LENGTH_SHORT).show();
                    finish();
                }).show();
    }

    @Override public void finish() {
        super.finish();
        overridePendingTransition(R.anim.tab_enter, R.anim.tab_exit);
    }

    @Override protected void onDestroy() {
        if (storage != null) storage.close();
        super.onDestroy();
    }
}
