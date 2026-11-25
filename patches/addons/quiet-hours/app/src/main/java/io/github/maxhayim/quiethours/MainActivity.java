package io.github.maxhayim.quiethours;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The tile and module card open this: one list worked with the remote. The first row switches Quiet Hours off,
 * on a schedule, or always; Left and Right change values. No text fields and nothing to type.
 */
public class MainActivity extends Activity {
    private GlassKit.Modal modal;
    private Quiet.Settings s;
    private AudioManager audio;
    private TextView status;
    private Row modeRow, capRow, startRow, endRow, headphonesRow;
    /** The volume line follows the remote's volume keys while this screen is open. */
    private final BroadcastReceiver volume = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refresh();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        audio = getSystemService(AudioManager.class);
        modal = GlassKit.installModal(this, 740, 470, false, 0x2A1F47);
        LinearLayout page = modal.panel;

        TextView title = GlassKit.makeTitle(this, getString(R.string.app_name));
        title.setPadding(px(6), 0, px(6), 0);
        status = GlassKit.makeSubtitle(this, "");
        status.setPadding(px(6), px(3), px(6), 0);
        page.addView(title);
        page.addView(status);
        page.addView(GlassKit.makeDivider(this, 10, 4));

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(ScrollView.OVER_SCROLL_NEVER);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(px(20));
        scroll.setClipChildren(true);
        scroll.setClipToPadding(true);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setClipChildren(false);
        rows.setClipToPadding(false);
        rows.setPadding(px(6), px(6), px(6), px(10));
        scroll.addView(rows);
        page.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        s = Prefs.load(this);
        modeRow = add(rows, new Row(this, "Quiet hours", null, false, () -> {
            s.mode = Quiet.Mode.values()[(s.mode.ordinal() + 1) % 3];
            changed();
        }, d -> {
            s.mode = Quiet.Mode.values()[(s.mode.ordinal() + d + 3) % 3];
            changed();
        }));
        capRow = add(rows, new Row(this, "Volume cap", "The loudest the TV may play during quiet hours.", true, null,
                d -> {
                    s.cap += d * Quiet.CAP_STEP;
                    changed();
                }));
        startRow = add(rows, new Row(this, "Starts", "◀ ▶ moves 15 minutes.", false, null, d -> {
            s.start += d * Quiet.TIME_STEP;
            changed();
        }));
        endRow = add(rows, new Row(this, "Ends", null, false, null, d -> {
            s.end += d * Quiet.TIME_STEP;
            changed();
        }));
        headphonesRow = add(rows, new Row(this, "Headphones", "Headphones don't wake the room, so they can go louder.",
                false, () -> {
                    s.headphonesFree = !s.headphonesFree;
                    changed();
                }, null));
        modeRow.requestFocus();
        modal.reveal();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (modal != null) modal.onNewIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (modal != null) modal.onResume();
        s = Prefs.load(this);
        registerReceiver(volume, new IntentFilter("android.media.VOLUME_CHANGED_ACTION"));
        refresh();
        CapService.start(this);
    }

    @Override
    protected void onPause() {
        try {
            unregisterReceiver(volume);
        } catch (IllegalArgumentException ignored) {
        }
        if (modal != null) modal.onPause();
        super.onPause();
    }

    private Row add(LinearLayout rows, Row r) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (rows.getChildCount() > 0) lp.topMargin = px(8);
        rows.addView(r, lp);
        return r;
    }

    /** Saves, which the running service sees at once; starts it when Quiet Hours is on. */
    private void changed() {
        Prefs.save(this, s);
        refresh();
        CapService.start(this);
    }

    private void refresh() {
        boolean h24 = DateFormat.is24HourFormat(this);
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int vol = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        int cap = Quiet.capIndex(s.cap, max);
        modeRow.setValue(s.mode == Quiet.Mode.OFF ? "Off" : s.mode == Quiet.Mode.ALWAYS ? "Always" : "Scheduled");
        modeRow.setAccentActive(s.mode != Quiet.Mode.OFF);
        modeRow.setDetail(s.mode == Quiet.Mode.SCHEDULED ? "Every day between the times below."
                : s.mode == Quiet.Mode.ALWAYS ? "The cap applies all day." : "No cap.");
        capRow.setValue(s.cap + " %");
        capRow.setDetail("The loudest the TV may play during quiet hours: step " + cap + " of " + max + ".");
        capRow.setBar(s.cap / 100f);
        startRow.setValue(Quiet.clock(s.start, h24));
        endRow.setValue(Quiet.clock(s.end, h24));
        boolean scheduled = s.mode == Quiet.Mode.SCHEDULED;
        startRow.setAlpha(scheduled ? 1f : 0.5f);
        endRow.setAlpha(scheduled ? 1f : 0.5f);
        headphonesRow.setValue(s.headphonesFree ? "No cap" : "Capped too");
        headphonesRow.setAccentActive(s.headphonesFree);

        String now = "Volume " + vol + " of " + max + ". ";
        if (audio.isVolumeFixed()) {
            status.setText("This TV's volume is fixed (HDMI ARC or an amplifier), so Android can't cap it.");
        } else if (s.mode == Quiet.Mode.OFF) {
            status.setText(now + "Quiet hours are off.");
        } else if (Quiet.capNow(s, CapService.minuteNow())) {
            String until = scheduled ? " until " + Quiet.clock(s.end, h24) : "";
            String free = CapService.onHeadphones(audio) && s.headphonesFree ? " Headphones: no cap." : "";
            status.setText(now + "Capped at " + cap + until + "." + free);
        } else {
            status.setText(now + "Quiet hours from " + Quiet.clock(s.start, h24) + ".");
        }
        if (modal != null) modal.refreshFocus();
    }

    private int px(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
