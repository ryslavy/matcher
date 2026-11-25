package com.gathin.tvhome;

import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Adds seamless USB/Bluetooth mouse & Air-Mouse support to TV views:
 * hovering moves D-pad focus (driving the Liquid Glass focus ring) and scroll wheels
 * smoothly scroll {@link ScrollView}, {@link HorizontalScrollView}, and {@link RecyclerView}.
 */
final class PointerSupport {

    private static final float SCROLL_STEP_DP = 76f;

    private PointerSupport() { }

    /** Recursively enables hover-to-focus and wheel-scrolling on {@code root} and its descendants. */
    static void install(@Nullable View root) {
        if (root == null) return;
        attachView(root);
        if (root instanceof RecyclerView) {
            RecyclerView rv = (RecyclerView) root;
            rv.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
                @Override
                public void onChildViewAttachedToWindow(@NonNull View view) {
                    install(view);
                }

                @Override
                public void onChildViewDetachedFromWindow(@NonNull View view) { }
            });
            for (int i = 0; i < rv.getChildCount(); i++) {
                install(rv.getChildAt(i));
            }
            return;
        }
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                install(vg.getChildAt(i));
            }
            vg.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
                @Override
                public void onChildViewAdded(View parent, View child) {
                    install(child);
                }

                @Override
                public void onChildViewRemoved(View parent, View child) { }
            });
        }
    }

    static void attachHoverFocus(@Nullable View view) {
        if (view == null) return;
        view.setOnHoverListener((v, event) -> {
            int action = event.getActionMasked();
            if ((action == MotionEvent.ACTION_HOVER_ENTER || action == MotionEvent.ACTION_HOVER_MOVE)
                    && v.isFocusable() && v.isEnabled() && !v.isFocused()) {
                v.requestFocus();
            }
            return false;
        });
    }

    private static void attachView(View view) {
        if (view.isFocusable()) {
            attachHoverFocus(view);
        }
        if (view instanceof ScrollView || view instanceof HorizontalScrollView || view instanceof RecyclerView) {
            attachWheelScroll(view);
        }
    }

    static void attachWheelScroll(@Nullable View scrollable) {
        if (scrollable == null) return;
        scrollable.setOnGenericMotionListener((v, event) -> {
            if (event.getAction() != MotionEvent.ACTION_SCROLL
                    || (event.getSource() & InputDevice.SOURCE_CLASS_POINTER) == 0) {
                return false;
            }
            float vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
            float hScroll = event.getAxisValue(MotionEvent.AXIS_HSCROLL);
            if (vScroll == 0f && hScroll == 0f) return false;
            float density = v.getResources().getDisplayMetrics().density;
            int step = Math.round(SCROLL_STEP_DP * density);
            if (v instanceof ScrollView) {
                ((ScrollView) v).smoothScrollBy(0, Math.round(-vScroll * step));
                return true;
            }
            if (v instanceof HorizontalScrollView) {
                float delta = hScroll != 0f ? hScroll : -vScroll;
                ((HorizontalScrollView) v).smoothScrollBy(Math.round(delta * step), 0);
                return true;
            }
            if (v instanceof RecyclerView) {
                RecyclerView rv = (RecyclerView) v;
                RecyclerView.LayoutManager lm = rv.getLayoutManager();
                if (lm != null && lm.canScrollHorizontally() && !lm.canScrollVertically()) {
                    float delta = hScroll != 0f ? hScroll : -vScroll;
                    rv.smoothScrollBy(Math.round(delta * step), 0);
                } else {
                    rv.smoothScrollBy(0, Math.round(-vScroll * step));
                }
                return true;
            }
            return false;
        });
    }
}
