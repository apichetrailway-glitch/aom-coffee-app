package com.aomcoffee.pos;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** ตัวกลางให้หน้าเว็บ POS สั่งพิมพ์ผ่าน window.AomPrinter */
public class PrinterBridge {

    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final MainActivity act;
    private final SharedPreferences prefs;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final ConcurrentHashMap<String, String> results = new ConcurrentHashMap<>();

    private BluetoothSocket socket;
    private OutputStream out;

    PrinterBridge(MainActivity a) {
        act = a;
        prefs = a.getSharedPreferences("printer", Context.MODE_PRIVATE);
    }

    String getMac() {
        return prefs.getString("mac", null);
    }

    void setPrinter(BluetoothDevice d) {
        close();
        String n;
        try { n = d.getName(); } catch (SecurityException e) { n = ""; }
        prefs.edit().putString("mac", d.getAddress()).putString("name", n == null ? "" : n).apply();
    }

    // ---------- เรียกจากหน้าเว็บ ----------
    @JavascriptInterface
    public boolean isReady() {
        return getMac() != null;
    }

    @JavascriptInterface
    public String printerName() {
        return prefs.getString("name", "");
    }

    @JavascriptInterface
    public void openSettings() {
        act.runOnUiThread(act::showPrinterDialog);
    }

    /** images = JSON array ของรูปบิล (data:image/png;base64,...) คืนค่าเลขงาน */
    @JavascriptInterface
    public String print(final String imagesJson, final int copies) {
        final String id = UUID.randomUUID().toString();
        if (getMac() == null) {
            results.put(id, res(false, "ยังไม่ได้เลือกเครื่องพิมพ์ แตะปุ่มเครื่องพิมพ์มุมจอ"));
            act.runOnUiThread(act::showPrinterDialog);
            return id;
        }
        exec.execute(() -> {
            try {
                JSONArray arr = new JSONArray(imagesJson);
                List<byte[]> jobs = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    jobs.add(EscPos.fromBitmap(decode(arr.getString(i))));
                }
                int n = Math.max(1, copies);
                for (int c = 0; c < n; c++) {
                    for (byte[] j : jobs) send(j);
                }
                results.put(id, res(true, "พิมพ์บิลแล้ว"));
            } catch (Exception e) {
                close();
                results.put(id, res(false, "พิมพ์ไม่สำเร็จ: " + friendly(e)));
            }
        });
        return id;
    }

    /** ถามผลงานพิมพ์ คืน "" ถ้ายังพิมพ์ไม่เสร็จ */
    @JavascriptInterface
    public String result(String id) {
        String r = results.remove(id);
        return r == null ? "" : r;
    }

    // ---------- พิมพ์ทดสอบ ----------
    void testPrint() {
        exec.execute(() -> {
            try {
                send(EscPos.fromBitmap(EscPos.testBitmap()));
                act.toast("พิมพ์ทดสอบแล้ว");
            } catch (Exception e) {
                close();
                act.toast("พิมพ์ไม่สำเร็จ: " + friendly(e));
            }
        });
    }

    // ---------- ภายใน ----------
    private Bitmap decode(String dataUrl) {
        int k = dataUrl.indexOf(',');
        byte[] b = Base64.decode(k >= 0 ? dataUrl.substring(k + 1) : dataUrl, Base64.DEFAULT);
        Bitmap bm = BitmapFactory.decodeByteArray(b, 0, b.length);
        if (bm == null) throw new IllegalArgumentException("อ่านภาพบิลไม่ได้");
        return bm;
    }

    private synchronized void send(byte[] data) throws Exception {
        try {
            ensureConnected();
            write(data);
        } catch (IOException first) {
            close();              // ลองต่อใหม่อีกครั้ง
            ensureConnected();
            write(data);
        }
    }

    private void write(byte[] data) throws IOException, InterruptedException {
        for (int i = 0; i < data.length; i += 2048) {
            out.write(data, i, Math.min(2048, data.length - i));
            out.flush();
            Thread.sleep(8);
        }
    }

    private void ensureConnected() throws Exception {
        if (socket != null && socket.isConnected() && out != null) return;
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null) throw new IOException("แท็บเล็ตไม่มี Bluetooth");
        if (!ad.isEnabled()) throw new IOException("Bluetooth ปิดอยู่");
        String mac = getMac();
        if (mac == null) throw new IOException("ยังไม่ได้เลือกเครื่องพิมพ์");
        BluetoothDevice d = ad.getRemoteDevice(mac);
        try { ad.cancelDiscovery(); } catch (SecurityException ignored) { }

        BluetoothSocket s = tryConnect(d.createRfcommSocketToServiceRecord(SPP));
        if (s == null) s = tryConnect(d.createInsecureRfcommSocketToServiceRecord(SPP));
        if (s == null) {
            BluetoothSocket fb = (BluetoothSocket) d.getClass()
                    .getMethod("createRfcommSocket", int.class).invoke(d, 1);
            s = tryConnect(fb);
        }
        if (s == null) throw new IOException("connect");
        socket = s;
        out = s.getOutputStream();
    }

    private BluetoothSocket tryConnect(BluetoothSocket s) {
        try {
            s.connect();
            return s;
        } catch (Exception e) {
            try { s.close(); } catch (Exception ignored) { }
            return null;
        }
    }

    void close() {
        try { if (socket != null) socket.close(); } catch (Exception ignored) { }
        socket = null;
        out = null;
    }

    private String friendly(Exception e) {
        String m = e.getMessage();
        if (e instanceof SecurityException) return "แอปยังไม่ได้รับสิทธิ์ใช้ Bluetooth";
        if (m == null) return e.getClass().getSimpleName();
        if (m.equals("connect") || m.contains("socket") || m.contains("read failed") || m.contains("Broken pipe")) {
            return "ต่อเครื่องพิมพ์ไม่ได้ ตรวจว่าเปิดเครื่องอยู่ และไม่มีมือถือเครื่องอื่นเชื่อมค้างไว้";
        }
        return m;
    }

    private String res(boolean ok, String msg) {
        try {
            return new JSONObject().put("ok", ok).put("msg", msg).toString();
        } catch (Exception e) {
            return "{\"ok\":" + ok + "}";
        }
    }
}
