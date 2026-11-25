package io.github.luyzfernando08.sleeptimer;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The last minute: a frosted-glass countdown modal over whatever is playing. */
public class WarningActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private GlassKit.Modal modal;
    private TextView seconds;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long end = Timer.end(WarningActivity.this);
            if (end == 0) {
                modal.dismiss();
                return;
            }
            long left = (end - System.currentTimeMillis() + 999) / 1000;
            if (left <= 0) {
                Timer.sleepNow(WarningActivity.this);
                finish();
                return;
            }
            seconds.setText(getString(R.string.warning_seconds, left));
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        modal = GlassKit.installModal(this, 500, 340, true, 0x3B1D36);
        LinearLayout panel = modal.panel;

        TextView title = GlassKit.makeTitle(this, getString(R.string.warning_title));
        title.setGravity(Gravity.CENTER);
        panel.addView(title, new LinearLayout.LayoutParams(-1, -2));

        seconds = Ui.text(this, 20f, 0xFFFDA4AF);
        seconds.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        seconds.setGravity(Gravity.CENTER);
        seconds.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        panel.addView(seconds, new LinearLayout.LayoutParams(-1, -2));

        panel.addView(GlassKit.makeDivider(this, 6, 8));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setClipChildren(false);
        actions.setClipToPadding(false);
        actions.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), Ui.dp(this, 6));

        TextView more = Ui.row(actions, 0, 14f, Ui.ACCENT_EMERALD);
        more.setGravity(Gravity.CENTER);
        more.setText(R.string.more);
        more.setOnClickListener(v -> {
            GlassKit.pulseClick(v, 1.015f);
            Timer.start(this, 15);
            modal.dismiss();
        });

        TextView now = Ui.row(actions, 0, 14f, Ui.ACCENT_CORAL);
        now.setGravity(Gravity.CENTER);
        now.setText(R.string.now);
        now.setOnClickListener(v -> {
            Timer.sleepNow(this);
            finish();
        });

        panel.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        modal.reveal();
        more.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (modal != null) modal.onResume();
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        // Back or Home leaves the warning, not the timer: the sleep alarm still fires at the end.
        handler.removeCallbacks(tick);
        if (modal != null) modal.onPause();
        super.onPause();
    }
}
