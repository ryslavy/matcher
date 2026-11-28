package com.gathin.btconnect;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.HashSet;
import java.util.Set;

/**
 * The tile and module card open this: the paired speakers and headphones, each with its state, and OK on one
 * connects it or, when it is connected, disconnects it. The TV's own Bluetooth settings only offer Connect and Unpair.
 */
public class MainActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private GlassKit.Modal modal;
    private LinearLayout list;
    private TextView status;
    private BluetoothAdapter adapter;
    private BluetoothA2dp a2dp;
    private boolean proxyRequested;
    /** Addresses asked to change a moment ago, shown as "Working…" until the state change arrives. */
    private final Set<String> pending = new HashSet<>();
    private final BroadcastReceiver changed = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (device != null) pending.remove(device.getAddress());
            refresh();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        modal = GlassKit.installModal(this, 680, 440, false, 0x163852);
        LinearLayout page = modal.panel;

        TextView title = GlassKit.makeTitle(this, getString(R.string.app_name));
        title.setPadding(GlassKit.dp(this, 6), 0, GlassKit.dp(this, 6), 0);
        status = GlassKit.makeSubtitle(this, "");
        status.setPadding(GlassKit.dp(this, 6), GlassKit.dp(this, 3), GlassKit.dp(this, 6), 0);
        page.addView(title);
        page.addView(status);
        page.addView(GlassKit.makeDivider(this, 10, 4));

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(ScrollView.OVER_SCROLL_NEVER);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(GlassKit.dp(this, 20));
        scroll.setClipChildren(true);
        scroll.setClipToPadding(true);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false);
        list.setClipToPadding(false);
        list.setPadding(GlassKit.dp(this, 6), GlassKit.dp(this, 6), GlassKit.dp(this, 6), GlassKit.dp(this, 10));
        scroll.addView(list);
        page.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        BluetoothManager bm = getSystemService(BluetoothManager.class);
        adapter = bm == null ? null : bm.getAdapter();
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
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 1);
            return;
        }
        start();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) start();
        else status.setText(R.string.perm_needed_install);
    }

    private void start() {
        if (adapter == null || !adapter.isEnabled()) {
            status.setText(R.string.bt_off);
            return;
        }
        registerReceiver(changed, new IntentFilter(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED));
        if (a2dp != null) {
            refresh();
            return;
        }
        if (proxyRequested) return;
        proxyRequested = true;
        adapter.getProfileProxy(getApplicationContext(), new BluetoothProfile.ServiceListener() {
            @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                if (isDestroyed() || isFinishing()) {
                    adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy);
                    proxyRequested = false;
                    return;
                }
                a2dp = (BluetoothA2dp) proxy;
                refresh();
            }
            @Override public void onServiceDisconnected(int profile) {
                a2dp = null;
                proxyRequested = false;
            }
        }, BluetoothProfile.A2DP);
    }

    @Override
    protected void onPause() {
        try {
            unregisterReceiver(changed);
        } catch (IllegalArgumentException notRegistered) {
        }
        main.removeCallbacksAndMessages(null);
        pending.clear();
        if (modal != null) modal.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (a2dp != null && adapter != null) {
            adapter.closeProfileProxy(BluetoothProfile.A2DP, a2dp);
            a2dp = null;
        }
        proxyRequested = false;
        super.onDestroy();
    }

    /** Only runs after onResume has checked Nearby devices on Android 12+. */
    @SuppressLint("MissingPermission")
    private void refresh() {
        if (a2dp == null || adapter == null || isDestroyed()) return;
        int focus = 0;
        for (int i = 0; i < list.getChildCount(); i++) if (list.getChildAt(i).isFocused()) focus = i;
        list.removeAllViews();
        Set<String> on = new HashSet<>();
        Set<BluetoothDevice> bonded = null;
        try {
            java.util.List<BluetoothDevice> connectedList = a2dp.getConnectedDevices();
            if (connectedList != null) {
                for (BluetoothDevice d : connectedList) on.add(d.getAddress());
            }
            bonded = adapter.getBondedDevices();
        } catch (SecurityException ignored) {
            status.setText(R.string.perm_needed);
            return;
        }
        if (bonded != null) {
            for (BluetoothDevice device : bonded) {
                if (!Link.isAudio(device.getBluetoothClass())) continue;
                String address = device.getAddress();
                boolean connected = on.contains(address);
                Row row = new Row(this, device.getName() == null ? address : device.getName(),
                        getString(pending.contains(address) ? R.string.state_working
                                : connected ? R.string.state_connected : R.string.state_not_connected),
                        () -> toggle(device, connected));
                row.setValue(getString(connected ? R.string.action_disconnect : R.string.action_connect));
                row.setConnectedStyle(connected);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (list.getChildCount() > 0) rlp.topMargin = GlassKit.dp(this, 8);
                list.addView(row, rlp);
            }
        }
        status.setText(list.getChildCount() == 0 ? R.string.status_none : R.string.status_hint);
        if (list.getChildCount() > 0) list.getChildAt(Math.min(focus, list.getChildCount() - 1)).requestFocus();
        if (modal != null) modal.refreshFocus();
    }

    private void toggle(BluetoothDevice device, boolean connected) {
        if (a2dp == null || pending.contains(device.getAddress())) return;
        if (!Link.set(a2dp, device, !connected)) {
            status.setText(getString(R.string.status_refused,
                    Settings.ACTION_BLUETOOTH_SETTINGS.substring(Settings.ACTION_BLUETOOTH_SETTINGS.lastIndexOf('.') + 1)));
            return;
        }
        pending.add(device.getAddress());
        refresh();
        // A state change normally clears it; if none arrives (a speaker that is off), stop saying Working.
        main.postDelayed(() -> {
            pending.remove(device.getAddress());
            refresh();
        }, 12000);
    }
}
