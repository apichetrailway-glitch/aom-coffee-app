package com.aomcoffee.pos;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;

import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** แปลงรูปเป็นคำสั่งเครื่องพิมพ์ความร้อน (ESC/POS) กระดาษ 80 มม. */
final class EscPos {

    static final int WIDTH = 576;

    private EscPos() { }

    static byte[] fromBitmap(Bitmap src) {
        int w = WIDTH;
        int h = Math.max(1, Math.round(src.getHeight() * (w / (float) src.getWidth())));

        Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        c.drawColor(Color.WHITE);
        c.drawBitmap(src, null, new Rect(0, 0, w, h), new Paint(Paint.FILTER_BITMAP_FLAG));

        int[] px = new int[w * h];
        bm.getPixels(px, 0, w, 0, 0, w, h);
        bm.recycle();

        int bw = (w + 7) / 8;
        ByteArrayOutputStream o = new ByteArrayOutputStream(bw * h + 64);
        o.write(0x1B); o.write(0x40);                       // เริ่มต้นเครื่อง

        for (int y0 = 0; y0 < h; y0 += 200) {
            int bh = Math.min(200, h - y0);
            o.write(0x1D); o.write(0x76); o.write(0x30); o.write(0x00);
            o.write(bw & 255); o.write((bw >> 8) & 255);
            o.write(bh & 255); o.write((bh >> 8) & 255);
            for (int y = y0; y < y0 + bh; y++) {
                for (int bx = 0; bx < bw; bx++) {
                    int b = 0;
                    for (int bit = 0; bit < 8; bit++) {
                        int x = bx * 8 + bit;
                        if (x < w) {
                            int p = px[y * w + x];
                            int lum = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000;
                            if (lum < 160) b |= (0x80 >> bit);
                        }
                    }
                    o.write(b);
                }
            }
        }

        o.write(0x1B); o.write(0x64); o.write(4);            // เลื่อนกระดาษ
        o.write(0x1D); o.write(0x56); o.write(0x42); o.write(0); // ตัดกระดาษ
        return o.toByteArray();
    }

    static Bitmap testBitmap() {
        Bitmap b = Bitmap.createBitmap(WIDTH, 290, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(Color.WHITE);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.BLACK);
        p.setTextAlign(Paint.Align.CENTER);

        p.setFakeBoldText(true);
        p.setTextSize(54);
        c.drawText("อ้อม coffee", WIDTH / 2f, 75, p);

        p.setFakeBoldText(false);
        p.setTextSize(36);
        c.drawText("เครื่องพิมพ์ใช้งานได้ ✓", WIDTH / 2f, 150, p);

        p.setTextSize(28);
        String now = new SimpleDateFormat("d/M/yyyy HH:mm", Locale.US).format(new Date());
        c.drawText(now, WIDTH / 2f, 205, p);

        c.drawRect(40, 245, WIDTH - 40, 249, p);
        return b;
    }
}
