package com.gummytranslate.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class HistoryActivity extends PaperActivity {
    private AppStorage storage;
    private List<AppStorage.SessionSummary> sessions;
    private ListView list;
    private TextView empty;
    private SessionAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);
        applyPaperInsets();
        storage = new AppStorage(this);
        list = findViewById(R.id.historyList);
        empty = findViewById(R.id.emptyText);
        sessions = new ArrayList<>(storage.listSessions());
        adapter = new SessionAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> showSession(sessions.get(position)));
        refreshList();
        findViewById(R.id.clearHistoryButton).setOnClickListener(v -> confirmClear());
        findViewById(R.id.homeNav).setOnClickListener(v -> open(MainActivity.class));
        findViewById(R.id.settingsNav).setOnClickListener(v -> open(SettingsActivity.class));
        long requestedSession = getIntent().getLongExtra("session_id", 0);
        if (requestedSession != 0) {
            AppStorage.SessionSummary selected = storage.getSession(requestedSession);
            if (selected != null) list.post(() -> showSession(selected));
        }
    }

    private void showSession(AppStorage.SessionSummary session) {
        startActivity(new Intent(this, SessionDetailActivity.class).putExtra("session_id", session.id));
        overridePendingTransition(R.anim.tab_enter, R.anim.tab_exit);
    }

    private void confirmDelete(AppStorage.SessionSummary session) {
        new AlertDialog.Builder(this).setTitle("删除这条课程记录？")
                .setMessage("课程和全部双语字幕将被永久删除。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    storage.deleteSession(session.id);
                    refreshList();
                }).show();
    }

    private void confirmClear() {
        if (sessions.isEmpty()) return;
        new AlertDialog.Builder(this).setTitle("清空全部课程记录？")
                .setMessage("此操作无法撤销。")
                .setNegativeButton("取消", null)
                .setPositiveButton("全部删除", (dialog, which) -> {
                    storage.clearSessions();
                    refreshList();
                }).show();
    }

    private void refreshList() {
        sessions.clear();
        sessions.addAll(storage.listSessions());
        adapter.notifyDataSetChanged();
        empty.setVisibility(sessions.isEmpty() ? View.VISIBLE : View.GONE);
        list.setVisibility(sessions.isEmpty() ? View.GONE : View.VISIBLE);
        findViewById(R.id.clearHistoryButton).setVisibility(sessions.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private String formatDate(long timestamp) {
        return new SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.CHINA).format(new Date(timestamp));
    }

    private void open(Class<?> target) {
        startActivity(new Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
        overridePendingTransition(R.anim.tab_enter, R.anim.tab_exit);
        finish();
        overridePendingTransition(R.anim.tab_enter, R.anim.tab_exit);
    }

    @Override protected void onResume() {
        super.onResume();
        if (adapter != null) refreshList();
    }

    @Override protected void onDestroy() {
        storage.close();
        super.onDestroy();
    }

    private final class SessionAdapter extends ArrayAdapter<AppStorage.SessionSummary> {
        SessionAdapter() { super(HistoryActivity.this, R.layout.row_session, sessions); }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView != null ? convertView : LayoutInflater.from(getContext()).inflate(R.layout.row_session, parent, false);
            AppStorage.SessionSummary session = getItem(position);
            TextView title = row.findViewById(R.id.sessionTitle);
            TextView detail = row.findViewById(R.id.sessionDetail);
            TextView preview = row.findViewById(R.id.sessionPreview);
            title.setText(session.title.isEmpty() ? formatDate(session.startedAt) : session.title);
            detail.setText(formatDate(session.startedAt) + " · " + AppStorage.formatDuration(session.startedAt, session.endedAt) + " · " + session.captionCount + " 条字幕");
            preview.setText(session.preview.isEmpty() ? "这堂课还没有完整字幕" : session.preview);
            row.findViewById(R.id.deleteSessionButton).setOnClickListener(v -> confirmDelete(session));
            return row;
        }
    }
}
