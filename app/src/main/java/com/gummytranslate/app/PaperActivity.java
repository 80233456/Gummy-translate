package com.gummytranslate.app;

import android.app.Activity;
import android.view.View;

public abstract class PaperActivity extends Activity {
    protected void applyPaperInsets() {
        View root = ((android.view.ViewGroup) findViewById(android.R.id.content)).getChildAt(0);
        if (root == null) return;
        final int left = root.getPaddingLeft();
        final int top = root.getPaddingTop();
        final int right = root.getPaddingRight();
        final int bottom = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(left + insets.getSystemWindowInsetLeft(),
                    top + insets.getSystemWindowInsetTop(),
                    right + insets.getSystemWindowInsetRight(),
                    bottom + insets.getSystemWindowInsetBottom());
            return insets;
        });
        root.requestApplyInsets();
    }
}
