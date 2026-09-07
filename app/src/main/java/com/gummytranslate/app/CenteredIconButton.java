package com.gummytranslate.app;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.Button;

/** A native Button that centers its leading icon and label as one visual unit. */
public class CenteredIconButton extends Button {
    public CenteredIconButton(Context context) { super(context); init(); }
    public CenteredIconButton(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public CenteredIconButton(Context context, AttributeSet attrs, int style) { super(context, attrs, style); init(); }

    private void init() {
        setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        setTextAlignment(TEXT_ALIGNMENT_GRAVITY);
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        Drawable leading = getCompoundDrawablesRelative()[0];
        if (leading == null || getMeasuredWidth() == 0) return;
        float labelWidth = getPaint().measureText(getText() == null ? "" : getText().toString());
        int contentWidth = leading.getIntrinsicWidth() + getCompoundDrawablePadding() + Math.round(labelWidth);
        int horizontalSpace = Math.max(0, getMeasuredWidth() - contentWidth);
        int left = horizontalSpace / 2;
        int right = horizontalSpace - left;
        if (getPaddingLeft() != left || getPaddingRight() != right) {
            setPadding(left, getPaddingTop(), right, getPaddingBottom());
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }
}
