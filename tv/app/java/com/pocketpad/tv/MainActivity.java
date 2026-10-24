package com.pocketpad.tv;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.Collections;

/**
 * The TV counterpart of the Windows window: address, code and pairing QR,
 * plus who is connected. The controllers themselves come from the
 * pocketpad-tv receiver (started over adb); this screen polls its
 * localhost status port.
 */
public class MainActivity extends Activity {
    private static final int GROUND = 0xFF16161E, PANEL = 0xFF191B25, RULE = 0xFF262938;
    private static final int INK = 0xFFC6CEE8, INK_SOFT = 0xFF98A2C0, INK_FAINT = 0xFF6B7392;
    private static final int GOOD = 0xFF9ECE6A, BAD = 0xFFF7768E, ADDR = 0xFFE0AF68, CODE = 0xFFF787B7;
    private static final int STATUS_PORT = 46823;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean running;

    private TextView status, addr, code, delay, inputs, players, qrCaption, offline;
    private ImageView qr;
    private View qrCard;
    private String shownQr = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(build());
    }

    @Override
    protected void onResume() {
        super.onResume();
        running = true;
        new Thread(this::pollLoop, "status").start();
    }

    @Override
    protected void onPause() {
        running = false;
        super.onPause();
    }

    // ---------- layout ----------

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView label(String s) {
        TextView t = text(s, 12, INK_FAINT, true);
        t.setLetterSpacing(0.12f);
        t.setPadding(0, dp(18), 0, dp(2));
        return t;
    }

    private GradientDrawable rounded(int color, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(r));
        return g;
    }

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(GROUND);
        root.setPadding(dp(48), dp(32), dp(48), dp(24));

        TextView title = text("PocketPad for TV", 26, INK, true);
        root.addView(title);
        status = text("Waiting for your phone…", 16, INK_SOFT, false);
        status.setPadding(0, dp(4), 0, dp(20));
        root.addView(status);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(row, new LinearLayout.LayoutParams(-1, 0, 1f));

        // Left: QR on a white card, like the PC window.
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout card = new LinearLayout(this);
        card.setBackground(rounded(Color.WHITE, 14));
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        qr = new ImageView(this);
        card.addView(qr, new LinearLayout.LayoutParams(dp(260), dp(260)));
        qrCard = card;
        left.addView(card);
        qrCaption = text("Scan with PocketPad on your phone", 14, INK_SOFT, false);
        qrCaption.setGravity(Gravity.CENTER);
        qrCaption.setPadding(0, dp(12), 0, 0);
        left.addView(qrCaption, new LinearLayout.LayoutParams(dp(290), -2));
        row.addView(left);

        // Right: address, code, live stats.
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setBackground(rounded(PANEL, 14));
        right.setPadding(dp(28), dp(8), dp(28), dp(20));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, -1, 1f);
        rp.leftMargin = dp(36);
        row.addView(right, rp);

        right.addView(label("TV ADDRESS"));
        addr = text("—", 30, ADDR, true);
        addr.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        right.addView(addr);
        right.addView(label("CODE"));
        code = text("—", 30, CODE, true);
        code.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        code.setLetterSpacing(0.1f);
        right.addView(code);

        View rule = new View(this);
        rule.setBackgroundColor(RULE);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, dp(1));
        rl.topMargin = dp(18);
        right.addView(rule, rl);

        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        right.addView(stats);
        LinearLayout d = new LinearLayout(this);
        d.setOrientation(LinearLayout.VERTICAL);
        d.addView(label("DELAY RIGHT NOW"));
        delay = text("—", 26, INK, true);
        d.addView(delay);
        stats.addView(d, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout in = new LinearLayout(this);
        in.setOrientation(LinearLayout.VERTICAL);
        in.addView(label("INPUTS THIS SESSION"));
        inputs = text("0", 26, INK, true);
        in.addView(inputs);
        stats.addView(in, new LinearLayout.LayoutParams(0, -2, 1f));

        right.addView(label("PLAYERS"));
        players = text("No phones yet", 16, INK_SOFT, false);
        players.setLineSpacing(dp(4), 1f);
        right.addView(players);

        offline = text("", 14, BAD, false);
        offline.setPadding(0, dp(16), 0, 0);
        right.addView(offline);
        return root;
    }

    // ---------- status ----------

    private void pollLoop() {
        while (running) {
            JSONObject s = null;
            try (Socket sock = new Socket()) {
                sock.connect(new InetSocketAddress("127.0.0.1", STATUS_PORT), 400);
                sock.setSoTimeout(800);
                String line = new BufferedReader(new InputStreamReader(sock.getInputStream(), "UTF-8")).readLine();
                if (line != null) s = new JSONObject(line);
            } catch (Exception ignored) {
            }
            final JSONObject st = s;
            final String ip = localIp();
            ui.post(() -> render(st, ip));
            try { Thread.sleep(700); } catch (InterruptedException e) { return; }
        }
    }

    private static String localIp() {
        try {
            String fallback = null;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                    String n = ni.getName();
                    if (n.startsWith("wlan") || n.startsWith("eth")) return a.getHostAddress();
                    if (fallback == null) fallback = a.getHostAddress();
                }
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }

    private void render(JSONObject s, String ip) {
        addr.setText(ip != null ? ip : "no network");
        if (s == null) {
            status.setText("Controller service not running");
            status.setTextColor(BAD);
            code.setText("—");
            qrCard.setAlpha(0.15f);
            offline.setText("The TV restarted, so the controller service stopped.\n"
                    + "Start it again over ADB:  ~/PocketPad/tv/start-on-tv.sh " + (ip != null ? ip : "<tv-ip>"));
            return;
        }
        offline.setText("");
        qrCard.setAlpha(1f);
        String token = s.optString("token");
        int tcp = s.optInt("tcp", 46822);
        code.setText(token);
        inputs.setText(String.valueOf(s.optLong("inputs")));

        if (ip != null) {
            String url = "pocketpad://pair?host=" + ip + "&tcp=" + tcp + "&token=" + token;
            if (!url.equals(shownQr)) {
                qr.setImageBitmap(qrBitmap(url));
                shownQr = url;
            }
        }

        JSONArray ps = s.optJSONArray("players");
        int n = ps == null ? 0 : ps.length();
        if (n == 0) {
            status.setText("Waiting for your phone…");
            status.setTextColor(INK_SOFT);
            players.setText("No phones yet");
            delay.setText("—");
            qrCaption.setText("Scan with PocketPad on your phone");
            return;
        }
        status.setText("✓  Phone connected — start your game, each phone is an Xbox controller");
        status.setTextColor(GOOD);
        qrCaption.setText(n < 4 ? "Add another phone — scan\n(up to 4 players)" : "All 4 players connected");
        StringBuilder sb = new StringBuilder();
        int best = -1;
        for (int i = 0; i < n; i++) {
            JSONObject p = ps.optJSONObject(i);
            int ms = p.optInt("ms", -1);
            if (sb.length() > 0) sb.append('\n');
            sb.append("Player ").append(p.optInt("slot") + 1).append("   ").append(p.optString("name"));
            if (ms >= 0) sb.append("   ").append(ms).append(" ms");
            if (ms >= 0 && (best < 0 || ms > best)) best = ms;
        }
        players.setText(sb);
        delay.setText(best >= 0 ? best + " ms" : "—");
    }

    private static Bitmap qrBitmap(String text) {
        QrCode q = QrCode.encode(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        int quiet = 2, scale = 8, side = (q.size + quiet * 2) * scale;
        int[] px = new int[side * side];
        for (int y = 0; y < side; y++)
            for (int x = 0; x < side; x++) {
                int mx = x / scale - quiet, my = y / scale - quiet;
                boolean dark = mx >= 0 && my >= 0 && mx < q.size && my < q.size && q.get(mx, my);
                px[y * side + x] = dark ? Color.BLACK : Color.WHITE;
            }
        return Bitmap.createBitmap(px, side, side, Bitmap.Config.ARGB_8888);
    }
}
