package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.net.InetAddress;
import java.util.function.Consumer;

/**
 * Frosted Liquid Glass dialog that spins up {@link QrConfigServer}, displays a crisp scannable
 * QR code (generated via {@link QrBitmap}), shows live submission status, and tears down the
 * ephemeral LAN HTTP server immediately on dismiss.
 */
final class QrSetupDialog {

    private QrSetupDialog() { }

    @Nullable
    static Dialog show(Activity activity, @Nullable Runnable onSavedCallback) {
        return show(activity, onSavedCallback, null);
    }

    @Nullable
    static Dialog show(Activity activity, @Nullable Runnable onSavedCallback,
                       @Nullable Consumer<Boolean> onBlurParent) {
        final float d = activity.getResources().getDisplayMetrics().density;
        final boolean reducedMotion = Settings.Global.getFloat(
                activity.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
                || !Config.load(activity).animations;
        final Handler main = new Handler(Looper.getMainLooper());
        InetAddress lan = QrConfigServer.findLanAddress(activity);

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);
        int pad = Math.round(22 * d);
        box.setPadding(pad, pad, pad, Math.round(18 * d));
        ExpressiveGlass.applyPanelSurface(box, d, 24f, true, false);

        TextView title = new TextView(activity);
        title.setText(R.string.qr_setup_title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(20f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        ExpressiveGlass.applyLuminousTitle(title, d, false);
        box.addView(title);

        TextView subtitle = new TextView(activity);
        subtitle.setText(R.string.qr_setup_subtitle);
        subtitle.setTextColor(0xC8DCE8F8);
        subtitle.setTextSize(13.5f);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
        subLp.topMargin = Math.round(6 * d);
        subLp.bottomMargin = Math.round(14 * d);
        box.addView(subtitle, subLp);

        ImageView qrView = new ImageView(activity);
        ExpressiveGlass.markPreserveImage(qrView);
        int qrPx = Math.round(212 * d);
        GradientDrawable qrFrame = new GradientDrawable();
        qrFrame.setColor(Color.WHITE);
        qrFrame.setCornerRadius(16f * d);
        qrView.setBackground(qrFrame);
        int qrPad = Math.round(8 * d);
        qrView.setPadding(qrPad, qrPad, qrPad, qrPad);
        qrView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        box.addView(qrView, new LinearLayout.LayoutParams(qrPx, qrPx));

        TextView urlText = new TextView(activity);
        urlText.setTextColor(0xE8FFFFFF);
        urlText.setTextSize(13f);
        urlText.setTypeface(Typeface.MONOSPACE);
        urlText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams urlLp = new LinearLayout.LayoutParams(-1, -2);
        urlLp.topMargin = Math.round(12 * d);
        box.addView(urlText, urlLp);

        TextView status = new TextView(activity);
        status.setTextColor(0xFF8BE2A8);
        status.setTextSize(13.5f);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(-1, -2);
        stLp.topMargin = Math.round(6 * d);
        stLp.bottomMargin = Math.round(14 * d);
        box.addView(status, stLp);

        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        final int dialogPad = FocusRingController.wrapInsetPx(d);
        final FrameLayout ringHost = FocusRingController.wrapPopup(box, d, !reducedMotion, reducedMotion);
        final boolean[] dismissing = {false};
        final Runnable[] dismissRunner = new Runnable[1];
        final FrameLayout keyHost = new FrameLayout(activity) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled() && dismissRunner[0] != null) {
                        dismissRunner[0].run();
                    }
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
        };
        keyHost.setClipChildren(false);
        keyHost.setClipToPadding(false);
        keyHost.addView(ringHost, new FrameLayout.LayoutParams(-1, -2));
        PointerSupport.install(keyHost);
        UiClickSound.attachFocusSound(keyHost);
        dialog.setContentView(keyHost);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            ExpressiveGlass.clearDialogShadow(w);
            ExpressiveGlass.setWindowDimOnly(w, 0f);
            w.setLayout(Math.round(470 * d) + 2 * dialogPad, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setWindowAnimations(0);
        }

        dismissRunner[0] = () -> {
            if (dismissing[0]) return;
            dismissing[0] = true;
            if (onBlurParent != null) onBlurParent.accept(false);
            if (!dialog.isShowing()) return;
            keyHost.setPivotX(keyHost.getWidth() / 2f);
            keyHost.setPivotY(keyHost.getHeight() / 2f);
            Motion.dismissModal(keyHost, d, reducedMotion, () -> {
                if (dialog.isShowing()) dialog.dismiss();
            });
        };

        TextView closeBtn = new TextView(activity);
        closeBtn.setText(R.string.close);
        closeBtn.setTextColor(Color.WHITE);
        closeBtn.setTextSize(15f);
        closeBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        closeBtn.setGravity(Gravity.CENTER);
        closeBtn.setFocusable(true);
        closeBtn.setFocusableInTouchMode(true);
        closeBtn.setClickable(true);
        closeBtn.setPadding(Math.round(28 * d), Math.round(10 * d), Math.round(28 * d), Math.round(10 * d));
        closeBtn.setBackground(ExpressiveGlass.makeAdaptiveTileBg(d, 18f, 0));
        Motion.attachPillFocus(closeBtn, 1.06f, 1.5f, d, reducedMotion);
        FocusRingController.configure(closeBtn, 1.06f, 1.5f, 18f, 0);
        closeBtn.setOnClickListener(v -> dismissRunner[0].run());
        box.addView(closeBtn, new LinearLayout.LayoutParams(-2, -2));
        ExpressiveGlass.applyAdaptiveContentTheme(box);

        final QrConfigServer[] serverHolder = new QrConfigServer[1];
        if (lan == null) {
            urlText.setText(R.string.qr_setup_no_wifi);
            status.setTextColor(ExpressiveGlass.adaptiveAccentTextColor(0xFFFCA5A5));
            status.setText("");
        } else {
            try {
                QrConfigServer srv = new QrConfigServer(activity, lan, new QrConfigServer.Listener() {
                    @Override
                    public void onSaved(String summary) {
                        main.post(() -> {
                            status.setTextColor(ExpressiveGlass.adaptiveAccentTextColor(0xFF8BE2A8));
                            status.setText(summary);
                            if (onSavedCallback != null) onSavedCallback.run();
                        });
                    }

                    @Override
                    public void onClosed(boolean expired) {
                        if (!expired) return;
                        main.post(() -> {
                            status.setTextColor(ExpressiveGlass.adaptiveAccentTextColor(0xFFFCD34D));
                            status.setText(R.string.qr_setup_expired);
                        });
                    }
                });
                serverHolder[0] = srv;
                urlText.setText(srv.url);
                status.setText(R.string.qr_setup_waiting);
                Bitmap bmp = QrBitmap.renderBitmap(srv.url, qrPx);
                qrView.setImageBitmap(bmp);
            } catch (IOException e) {
                urlText.setText(e.getMessage());
                status.setTextColor(ExpressiveGlass.adaptiveAccentTextColor(0xFFFCA5A5));
                status.setText(R.string.not_available);
            }
        }

        dialog.setOnDismissListener(d1 -> {
            Motion.cancelPanelAnim(keyHost);
            if (onBlurParent != null) onBlurParent.accept(false);
            if (serverHolder[0] != null) serverHolder[0].close();
        });

        keyHost.setAlpha(0f);
        if (!reducedMotion) {
            keyHost.setScaleX(0.94f);
            keyHost.setScaleY(0.94f);
            keyHost.setTranslationY(84f * d);
        }
        dialog.show();
        closeBtn.requestFocus();
        keyHost.post(() -> keyHost.postOnAnimation(() -> {
            if (!dialog.isShowing() || dismissing[0] || activity.isFinishing() || activity.isDestroyed()) return;
            keyHost.setPivotX(keyHost.getWidth() / 2f);
            keyHost.setPivotY(keyHost.getHeight() / 2f);
            if (onBlurParent != null) onBlurParent.accept(true);
            Motion.revealModal(keyHost, d, reducedMotion);
        }));
        return dialog;
    }
}
