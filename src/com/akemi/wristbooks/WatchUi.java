package com.akemi.wristbooks;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

public class WatchUi {
    public final Activity act;
    public final ScrollView scroll;
    public final LinearLayout box;
    public final float density;
    public final int widthPx;

    public WatchUi(Activity act) {
        this.act = act;
        DisplayMetrics dm = act.getResources().getDisplayMetrics();
        this.widthPx = dm.widthPixels;
        this.density = dm.density;

        FrameLayout root = new FrameLayout(act);
        root.setBackgroundColor(Color.BLACK);

        scroll = new ScrollView(act);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFocusable(true);
        scroll.setFocusableInTouchMode(true);

        int side = Math.max(dp(18), Math.round(widthPx * 0.11f));
        int top = Math.max(dp(26), Math.round(dm.heightPixels * 0.12f));
        int bot = Math.max(dp(44), Math.round(dm.heightPixels * 0.20f));

        box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(side, top, side, bot);
        scroll.addView(box);

        root.addView(scroll);
        act.setContentView(root);
        scroll.requestFocus();
    }

    public int dp(int v) { return Math.round(v * density); }

    public void clear() {
        box.removeAllViews();
        scroll.scrollTo(0, 0);
        scroll.requestFocus();
    }

    public boolean onRotary(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_SCROLL && ev.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
            float d = -ev.getAxisValue(MotionEvent.AXIS_SCROLL)
                    * ViewConfiguration.get(act).getScaledVerticalScrollFactor();
            scroll.scrollBy(0, Math.round(d));
            return true;
        }
        return false;
    }

    public Drawable roundBox(String fill, String stroke, int radDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor(fill));
        gd.setCornerRadius(dp(radDp));
        if (stroke != null) gd.setStroke(dp(1), Color.parseColor(stroke));
        return gd;
    }

    public Drawable pillBg(String norm, String press, String stroke) {
        StateListDrawable st = new StateListDrawable();
        st.addState(new int[]{android.R.attr.state_pressed}, roundBox(press, stroke, 999));
        st.addState(new int[]{}, roundBox(norm, stroke, 999));
        return st;
    }

    public LinearLayout card() {
        LinearLayout c = new LinearLayout(act);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(roundBox("#111318", "#222631", 16));
        c.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(5), 0, dp(5));
        c.setLayoutParams(lp);
        return c;
    }

    public Button chip(String text, String norm, String press, String stroke, String fg, float sp) {
        Button b = new Button(act);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(sp);
        b.setTypeface(null, Typeface.BOLD);
        b.setTextColor(Color.parseColor(fg));
        b.setBackground(pillBg(norm, press, stroke));
        b.setPadding(dp(8), dp(4), dp(8), dp(4));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        return b;
    }

    public TextView text(LinearLayout parent, String t, float sp, String hex, boolean bold) {
        TextView tv = new TextView(act);
        tv.setText(t);
        tv.setTextColor(Color.parseColor(hex));
        tv.setTextSize(sp);
        if (bold) tv.setTypeface(null, Typeface.BOLD);
        parent.addView(tv);
        return tv;
    }

    public ProgressBar makeRedBookProgress() {
        ProgressBar pb = new ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(1000);
        pb.setProgressDrawable(makeRedBarDrawable(dp(3)));
        pb.setMinimumHeight(dp(3));
        pb.setMaxHeight(dp(3));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3));
        lp.setMargins(0, dp(4), 0, dp(4));
        pb.setLayoutParams(lp);
        return pb;
    }

    public SeekBar makeRedSeekBar() {
        SeekBar sb = new SeekBar(act);
        sb.setMax(1000);
        sb.setSplitTrack(false);
        sb.setProgressDrawable(makeRedBarDrawable(dp(4)));
        sb.setMinimumHeight(dp(16));
        sb.setMaxHeight(dp(16));
        GradientDrawable thumb = new GradientDrawable();
        thumb.setShape(GradientDrawable.OVAL);
        thumb.setColor(Color.parseColor("#EF4444"));
        thumb.setSize(dp(12), dp(12));
        sb.setThumb(thumb);
        sb.setPadding(dp(6), dp(2), dp(6), dp(2));
        return sb;
    }

    private Drawable makeRedBarDrawable(int heightPx) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#27272A"));
        bg.setCornerRadius(heightPx);

        GradientDrawable fg = new GradientDrawable();
        fg.setColor(Color.parseColor("#EF4444"));
        fg.setCornerRadius(heightPx);

        ClipDrawable clip = new ClipDrawable(fg, Gravity.START, ClipDrawable.HORIZONTAL);
        LayerDrawable ld = new LayerDrawable(new Drawable[]{bg, clip});
        ld.setId(0, android.R.id.background);
        ld.setId(1, android.R.id.progress);
        ld.setLayerHeight(0, heightPx);
        ld.setLayerHeight(1, heightPx);
        ld.setLayerGravity(0, Gravity.CENTER_VERTICAL);
        ld.setLayerGravity(1, Gravity.CENTER_VERTICAL);
        return ld;
    }
}
