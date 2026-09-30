package com.aomcoffee.pos;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {

    private WebView web;
    private PrinterBridge printer;

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        printer = new PrinterBridge(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);

        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // ปุ่มเครื่องพิมพ์มุมซ้ายล่าง
        TextView btn = new TextView(this);
        btn.setText("\uD83D\uDDA8");
        btn.setTextSize(22);
        btn.setGravity(Gravity.CENTER);
        btn.setTextColor(Color.WHITE);
        btn.setBackgroundResource(R.drawable.printer_button);
        btn.setAlpha(0.85f);
        btn.setElevation(dp(4));
        int size = dp(52);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size, Gravity.BOTTOM | Gravity.START);
        lp.setMargins(dp(12), dp(12), dp(12), dp(12));
        root.addView(btn, lp);
        btn.setOnClickListener(v -> showPrinterDialog());

        setContentView(root);
        hideBars();

        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.addJavascriptInterface(printer, "AomPrinter");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                String scheme = u.getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) return false;
                try {
                    Intent i = "intent".equals(scheme)
                            ? Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME)
                            : new Intent(Intent.ACTION_VIEW, u);
                    startActivity(i);
                } catch (Exception e) {
                    toast("เปิดลิงก์นี้ไม่ได้");
                }
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) {
                    view.loadDataWithBaseURL(null, errorPage(), "text/html", "UTF-8", null);
                }
            }
        });

        web.loadUrl(getString(R.string.gas_url));
        requestBtPermission();
    }

    private String errorPage() {
        String url = getString(R.string.gas_url);
        return "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>"
                + "<body style='font-family:sans-serif;text-align:center;padding:60px 20px;color:#3b2417'>"
                + "<h2>เชื่อมอินเทอร์เน็ตไม่ได้</h2><p>ตรวจสอบ Wi-Fi แล้วกดลองใหม่</p>"
                + "<button style='font-size:20px;padding:14px 40px;border:0;border-radius:12px;background:#3b2417;color:#fff'"
                + " onclick=\"location.href='" + url + "'\">ลองใหม่</button></body></html>";
    }

    // ---------- เลือกเครื่องพิมพ์ ----------
    void showPrinterDialog() {
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null) {
            toast("แท็บเล็ตนี้ไม่มี Bluetooth");
            return;
        }
        if (!ad.isEnabled()) {
            new AlertDialog.Builder(this)
                    .setTitle("Bluetooth ปิดอยู่")
                    .setMessage("เปิด Bluetooth ก่อน แล้วแตะปุ่มเครื่องพิมพ์อีกครั้ง")
                    .setPositiveButton("เปิดตั้งค่า Bluetooth", (d, w) -> openBtSettings())
                    .setNegativeButton("ปิด", null)
                    .show();
            return;
        }

        Set<BluetoothDevice> bonded;
        try {
            bonded = ad.getBondedDevices();
        } catch (SecurityException e) {
            requestBtPermission();
            return;
        }

        final List<BluetoothDevice> list = new ArrayList<>(bonded);
        final String[] names = new String[list.size()];
        String current = printer.getMac();
        for (int i = 0; i < list.size(); i++) {
            BluetoothDevice dev = list.get(i);
            String n;
            try { n = dev.getName(); } catch (SecurityException e) { n = null; }
            if (n == null || n.isEmpty()) n = dev.getAddress();
            names[i] = n + (dev.getAddress().equals(current) ? "   ✓ ใช้อยู่" : "");
        }

        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle("เลือกเครื่องพิมพ์บิล");
        if (list.isEmpty()) {
            b.setMessage("ยังไม่มีเครื่องที่จับคู่ไว้\nกด \"จับคู่เครื่องใหม่\" แล้วจับคู่เครื่องพิมพ์ในหน้า Bluetooth ก่อน");
        } else {
            b.setItems(names, (d, w) -> {
                printer.setPrinter(list.get(w));
                toast("เลือกเครื่องพิมพ์แล้ว กำลังพิมพ์ทดสอบ…");
                printer.testPrint();
            });
        }
        b.setNeutralButton("จับคู่เครื่องใหม่", (d, w) -> openBtSettings());
        if (current != null) b.setPositiveButton("พิมพ์ทดสอบ", (d, w) -> printer.testPrint());
        b.setNegativeButton("ปิด", null);
        b.show();
    }

    private void openBtSettings() {
        try { startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); } catch (Exception ignored) { }
    }

    private void requestBtPermission() {
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission("android.permission.BLUETOOTH_CONNECT") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.BLUETOOTH_CONNECT"}, 1);
        }
    }

    // ---------- ช่วยเหลือ ----------
    void toast(final String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @SuppressWarnings("deprecation")
    private void hideBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideBars();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        printer.close();
        super.onDestroy();
    }
}
