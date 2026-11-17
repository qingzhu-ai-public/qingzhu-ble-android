// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.sim.eddystone;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Eddystone 从机模拟器主界面（程序化 UI，无资源文件）。
 *
 * 进入页面自动：申请权限 -> 开启 Eddystone 广播（UID 主包 + TLM 扫描响应）-> 启动 GATT Server。
 * 屏幕日志实时显示：广播状态、主设备连接/断开、收到的命令、发出的应答。
 */
public class MainActivity extends android.app.Activity {

    private static final int REQ_PERMISSIONS = 2001;

    private TextView logView;
    private TextView statusView;
    private Button btnToggle;
    private ScrollView scrollView;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final StringBuilder logBuffer = new StringBuilder();
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private EddystoneBeacon beacon;
    private EddystoneGattServer gattServer;
    private boolean running = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();

        if (ensurePermissions()) {
            startAll();
        }
    }

    private void buildUi() {
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (18 * density);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(getColor(R.color.bg_page));

        android.widget.TextView title = new android.widget.TextView(this);
        title.setText("Eddystone 从机模拟");
        title.setTextSize(22f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(getColor(R.color.ink));
        root.addView(title);

        android.widget.TextView subtitle = new android.widget.TextView(this);
        subtitle.setText("UID 主包 + TLM 扫描响应 · 可连接 GATT");
        subtitle.setTextSize(12f);
        subtitle.setTextColor(getColor(R.color.ink_3));
        subtitle.setPadding(0, (int) (4 * density), 0, (int) (14 * density));
        root.addView(subtitle);

        // 青竹环形状态：虚线外环 + 中心实点，替代纯文字状态
        android.widget.FrameLayout ringWrap = new android.widget.FrameLayout(this);
        int ringSize = (int) (58 * density);
        android.widget.LinearLayout.LayoutParams ringWrapLp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, ringSize);
        ringWrapLp.bottomMargin = (int) (8 * density);
        ringWrap.setLayoutParams(ringWrapLp);

        android.graphics.drawable.GradientDrawable ringBg =
                new android.graphics.drawable.GradientDrawable();
        ringBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        ringBg.setColor(getColor(R.color.ok_soft));
        ringBg.setStroke((int) (2 * density), getColor(R.color.ok),
                (int) (4 * density), (int) (3 * density));
        android.view.View ring = new android.view.View(this);
        ring.setBackground(ringBg);
        android.widget.FrameLayout.LayoutParams ringLp =
                new android.widget.FrameLayout.LayoutParams(ringSize, ringSize);
        ringLp.gravity = android.view.Gravity.CENTER;
        ringWrap.addView(ring, ringLp);

        android.graphics.drawable.GradientDrawable coreBg =
                new android.graphics.drawable.GradientDrawable();
        coreBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        coreBg.setColor(getColor(R.color.ok));
        android.view.View core = new android.view.View(this);
        core.setBackground(coreBg);
        int coreSize = (int) (22 * density);
        android.widget.FrameLayout.LayoutParams coreLp =
                new android.widget.FrameLayout.LayoutParams(coreSize, coreSize);
        coreLp.gravity = android.view.Gravity.CENTER;
        ringWrap.addView(core, coreLp);
        root.addView(ringWrap);

        statusView = new android.widget.TextView(this);
        statusView.setText("● 广播中");
        statusView.setTextSize(13f);
        statusView.setGravity(android.view.Gravity.CENTER);
        statusView.setTypeface(null, android.graphics.Typeface.BOLD);
        statusView.setTextColor(getColor(R.color.ok));
        statusView.setPadding(0, 0, 0, (int) (12 * density));
        root.addView(statusView);

        btnToggle = new Button(this);
        btnToggle.setText("停止");
        btnToggle.setAllCaps(false);
        btnToggle.setTextColor(0xFFFFFFFF);
        btnToggle.setBackgroundResource(R.drawable.bg_button);
        btnToggle.setOnClickListener(v -> {
            if (running) {
                stopAll();
                btnToggle.setText("开始");
                statusView.setText("● 已停止");
                statusView.setTextColor(getColor(R.color.ink_3));
            } else if (ensurePermissions()) {
                startAll();
                btnToggle.setText("停止");
                statusView.setText("● 广播中");
                statusView.setTextColor(getColor(R.color.ok));
            }
        });
        root.addView(btnToggle);

        logView = new TextView(this);
        logView.setTextSize(11f);
        logView.setTextColor(getColor(R.color.ink_2));
        logView.setTextIsSelectable(true);
        logView.setBackgroundResource(R.drawable.bg_card);
        logView.setPadding((int) (12 * density), (int) (12 * density),
                (int) (12 * density), (int) (12 * density));
        logView.setLineSpacing(0, 1.35f);

        scrollView = new ScrollView(this);
        scrollView.addView(logView);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT);
        lp.topMargin = pad;
        root.addView(scrollView, lp);

        setContentView(root);
    }

    private boolean ensurePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            String[] need = {Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT};
            boolean allGranted = true;
            for (String p : need) {
                if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                }
            }
            if (!allGranted) {
                requestPermissions(need, REQ_PERMISSIONS);
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMISSIONS) {
            return;
        }
        for (int r : grantResults) {
            if (r != PackageManager.PERMISSION_GRANTED) {
                log("权限被拒绝，无法运行");
                return;
            }
        }
        startAll();
        btnToggle.setText("停止");
    }

    private void startAll() {
        BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager != null ? manager.getAdapter() : null;
        if (adapter == null || !adapter.isEnabled()) {
            log("蓝牙未开启，请先打开系统蓝牙");
            return;
        }

        beacon = new EddystoneBeacon(adapter);
        beacon.startAdvertising();
        log("Eddystone 广播已启动（UID 主包 + TLM 扫描响应）");
        log("设备身份 eddystoneId = " + beacon.eddystoneId());

        gattServer = new EddystoneGattServer(this, this::log);
        boolean ok = gattServer.start();
        log(ok ? "GATT Server 已启动，等待主设备连接..." : "GATT Server 启动失败");
        running = ok;
        btnToggle.setText(running ? "停止" : "开始");
    }

    private void stopAll() {
        if (beacon != null) {
            beacon.stopAdvertising();
        }
        if (gattServer != null) {
            gattServer.stop();
        }
        running = false;
        log("已停止广播和 GATT Server");
    }

    private void log(String line) {
        String entry = "[" + timeFmt.format(new Date()) + "] " + line + "\n";
        main.post(() -> {
            logBuffer.append(entry);
            if (logBuffer.length() > 20000) {
                logBuffer.delete(0, logBuffer.length() - 20000);
            }
            logView.setText(logBuffer.toString());
            scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }
}
