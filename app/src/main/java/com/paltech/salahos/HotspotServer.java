package com.paltech.salahos;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The setup page the buyer's phone opens over the frame's own hotspot.
 *
 * A frame out of its box knows no network, so this page cannot come from the web — the whole
 * point of the moment is that there is no web yet. It is served from inside the app instead, by
 * the smallest HTTP server that will do the job: one thread, one request at a time, loopback and
 * hotspot only. It lives exactly as long as the hotspot does.
 *
 *   GET  /           the setup page itself (assets/wifi-setup.html)
 *   GET  /networks   what the frame can see right now, strongest first
 *   POST /join       ssid, security and password; starts the attempt and returns at once
 *   GET  /state      how that attempt is going, polled by the page
 *   anything else    sent back to /, so a phone's captive-portal probe lands somewhere useful
 */
final class HotspotServer {
    private static final String TAG = "SalahHotspot";
    static final int PORT = 80;      /* where a phone looks when it checks for a way out */
    static final int ALT  = 8080;    /* and where the firewall sends it if 80 is barred */

    private final MainActivity act;
    private final HostControl host;
    private ServerSocket socket;
    private Thread thread;
    private volatile boolean running;
    private volatile boolean fellBack;
    private volatile int port = PORT;

    /** idle, scanning, joining, connected or failed. Read by the phone page through /state. */
    private volatile String state = "idle";
    private volatile String stateNote = "";
    private volatile String joined = "";

    HotspotServer(MainActivity a, HostControl h) { act = a; host = h; }

    boolean isRunning() { return running; }

    String state() { return state; }

    /** The network the phone asked the frame to join, so the frame can name it on its own screen. */
    String joining() { return joined; }

    /** True when the frame had to fall back and the firewall has to carry port 80 here. */
    boolean fellBack() { return fellBack; }

    /** The port the page is really being served on, which the address shown has to match. */
    int port() { return port; }

    synchronized void start() throws IOException {
        if (running) return;
        try {
            socket = new ServerSocket(PORT);
            port = PORT;
            fellBack = false;
        } catch (Throwable low) {
            Log.w(TAG, "port 80 is barred (" + low + "), falling back to " + ALT);
            socket = new ServerSocket(ALT);
            port = ALT;
            fellBack = true;
        }
        socket.setSoTimeout(1000);
        running = true;
        state = "idle";
        stateNote = "";
        thread = new Thread(new Runnable() {
            @Override public void run() { loop(); }
        }, "salah-hotspot");
        thread.setDaemon(true);
        thread.start();
        Log.i(TAG, "setup server listening on " + port);
    }

    synchronized void stop() {
        running = false;
        try { if (socket != null) socket.close(); } catch (Throwable ignored) { }
        socket = null;
        thread = null;
    }

    private void loop() {
        while (running) {
            Socket c = null;
            try {
                c = socket.accept();
                c.setSoTimeout(10000);
                serve(c);
            } catch (java.net.SocketTimeoutException e) {
                /* nobody knocked this second; go round again so stop() is noticed */
            } catch (Throwable t) {
                if (running) Log.w(TAG, "request failed: " + t);
            } finally {
                try { if (c != null) c.close(); } catch (Throwable ignored) { }
            }
        }
    }

    // ---- one request ---------------------------------------------------------------------

    private void serve(Socket c) throws IOException {
        InputStream in = c.getInputStream();
        OutputStream out = c.getOutputStream();

        /* Read the request line and headers. Small by definition: this server has one form. */
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int last4 = 0, b;
        while ((b = in.read()) >= 0) {
            head.write(b);
            last4 = ((last4 << 8) | b) & 0xffffffff;
            if (last4 == 0x0d0a0d0a) break;
            if (head.size() > 8192) break;
        }
        String text = head.toString("UTF-8");
        if (text.length() == 0) return;
        String[] lines = text.split("\r?\n");
        String[] first = lines[0].split(" ");
        if (first.length < 2) return;
        String method = first[0], path = first[1];
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);

        String body = "";
        if ("POST".equals(method)) {
            int len = 0;
            for (String l : lines) {
                int colon = l.indexOf(':');
                if (colon > 0 && l.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                    try { len = Integer.parseInt(l.substring(colon + 1).trim()); } catch (Throwable ignored) { }
                }
            }
            if (len > 0 && len < 8192) {
                byte[] buf = new byte[len];
                int got = 0;
                while (got < len) {
                    int n = in.read(buf, got, len - got);
                    if (n < 0) break;
                    got += n;
                }
                body = new String(buf, 0, got, "UTF-8");
            }
        }

        Log.i(TAG, method + " " + path + " from " + c.getInetAddress());
        if ("/networks".equals(path)) { send(out, 200, "application/json", networks()); return; }
        if ("/state".equals(path))    { send(out, 200, "application/json", stateJson()); return; }
        if ("/join".equals(path) && "POST".equals(method)) { send(out, 200, "application/json", join(body)); return; }
        if ("/".equals(path) || "/index.html".equals(path)) { sendAsset(out, "wifi-setup.html"); return; }
        if ("/qr.js".equals(path)) { sendAsset(out, "qr.js"); return; }

        /*
          Android's way out of a network it has just joined.

          It fetches one of these and expects an empty 204. Anything else means a sign-in network -
          but knowing that is not the same as having somewhere to go. Android takes the address to
          open from the Location header of this very answer, so a page returned here, however
          correct it looks, leaves it with nothing to launch: it raises the notification and waits
          to be tapped. That tap was the one the buyer kept having to make. A redirect gives it the
          address, and it opens the page itself.

          iPhones never needed this - they judge by whether the answer is their own success page
          and open their sheet when it is not, which is why the iPhone worked while the Pixel did
          not - but a redirect suits them as well, so everything else still gets the page.
        */
        if ("/generate_204".equals(path) || "/gen_204".equals(path)) { redirect(out, HOME); return; }

        sendAsset(out, "wifi-setup.html");
    }

    /** Where a phone is sent to sign in. Port 80 whatever the server fell back to: the firewall. */
    private static final String HOME = "http://" + DhcpServer.SELF + "/";

    private void redirect(OutputStream out, String where) throws IOException {
        String headers = "HTTP/1.1 302 Found\r\n"
                + "Location: " + where + "\r\n"
                + "Content-Length: 0\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(headers.getBytes("UTF-8"));
        out.flush();
    }

    private void send(OutputStream out, int code, String type, String payload) throws IOException {
        byte[] data = payload.getBytes("UTF-8");
        String headers = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Error") + "\r\n"
                + "Content-Type: " + type + "; charset=utf-8\r\n"
                + "Content-Length: " + data.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(headers.getBytes("UTF-8"));
        out.write(data);
        out.flush();
    }

    private void sendAsset(OutputStream out, String name) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        InputStream in = null;
        try {
            in = act.getAssets().open(name);
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
        }
        String type = name.endsWith(".js") ? "application/javascript" : "text/html";
        String text = buf.toString("UTF-8");
        /* The buyer chose a language on the frame before any of this began. The phone page is
           handed that answer here rather than guessing from the phone's own settings, which is
           how an Arabic phone ended up offering English to someone who had already said Arabic -
           and why the page needs no language button of its own at all. */
        if (name.endsWith(".html")) text = text.replace("__LANG__", host.lang().length() > 0 ? host.lang() : "");
        send(out, 200, type, text);
    }

    // ---- what the page asks for ----------------------------------------------------------

    /** The networks the frame can see. The hotspot runs on its own interface, so this still works. */
    private String networks() {
        /*
          What the frame saw just before it raised its own network. It cannot look while it is
          broadcasting - one radio - so a scan asked for now comes back empty, which is what the
          buyer saw: "No networks found" on a phone standing next to a house full of them.
          A live scan is still attempted, in case a frame some day has a radio to spare.
        */
        String raw = host.preScan();
        if (parseScan(raw == null ? "" : raw).isEmpty()) {
            state = "scanning";
            raw = host.outShell("cmd wifi start-scan >/dev/null 2>&1; sleep 4; cmd wifi list-scan-results");
            if (state.equals("scanning")) state = "idle";
        }
        JSONArray arr = new JSONArray();
        try {
            for (Map.Entry<String, int[]> e : parseScan(raw).entrySet()) {
                JSONObject o = new JSONObject();
                o.put("ssid", e.getKey());
                o.put("rssi", e.getValue()[0]);
                o.put("sec", SEC[e.getValue()[1]]);
                arr.put(o);
            }
        } catch (Throwable t) {
            Log.w(TAG, "scan could not be read: " + t);
        }
        JSONObject o = new JSONObject();
        try {
            o.put("networks", arr);
            if (arr.length() == 0) o.put("note", raw.length() > 300 ? raw.substring(0, 300) : raw);
        } catch (Throwable ignored) { }
        return o.toString();
    }

    private static final String[] SEC = { "open", "owe", "wep", "wpa2", "wpa3" };

    /**
     * Reads `cmd wifi list-scan-results`, whichever way round this ROM prints it.
     *
     * There are two layouts in the wild and the frame uses the second:
     *
     *   SSID                 BSSID              RSSI  FREQ ...
     *   BSSID                FREQ   RSSI  AGE   SSID             FLAGS
     *
     * The BSSID is the landmark either way. With nothing before it the row is the second kind,
     * and the name sits after three numbers and before the flags, which is the only part of the
     * line that can hold spaces. Reading it the first way only cost us every network in the
     * house: the frame reported none in range while standing in the middle of seven.
     */
    static LinkedHashMap<String, int[]> parseScan(String raw) {
        Pattern mac = Pattern.compile("\\b([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}\\b");
        Map<String, int[]> best = new LinkedHashMap<String, int[]>();
        for (String line : raw.split("\r?\n")) {
            Matcher m = mac.matcher(line);
            if (!m.find()) continue;
            String before = line.substring(0, m.start()).trim();
            String after = line.substring(m.end());

            String ssid, caps;
            int rssi = -99;
            if (before.length() == 0) {
                int flags = after.indexOf('[');
                caps = flags >= 0 ? after.substring(flags) : "";
                String mid = (flags >= 0 ? after.substring(0, flags) : after);
                /* three numbers, then the name: walk past them so a name with spaces survives */
                int at = 0, taken = 0;
                while (taken < 3 && at < mid.length()) {
                    while (at < mid.length() && Character.isWhitespace(mid.charAt(at))) at++;
                    int from = at;
                    while (at < mid.length() && !Character.isWhitespace(mid.charAt(at))) at++;
                    if (at > from) {
                        if (taken == 1) { try { rssi = Integer.parseInt(mid.substring(from, at)); } catch (Throwable ignored) { } }
                        taken++;
                    }
                }
                if (taken < 3 || at >= mid.length()) continue;
                ssid = mid.substring(at).trim();
            } else {
                ssid = before;
                caps = after;
                for (String x : after.trim().split("\\s+")) {
                    try {
                        int v = Integer.parseInt(x);
                        if (v < 0 && v > -120) { rssi = v; break; }
                    } catch (Throwable ignored) { }
                }
            }
            if (ssid.length() == 0 || ssid.length() > 32) continue;

            int sec = 0;
            String up = caps.toUpperCase();
            if (up.contains("WEP")) sec = 2;
            if (up.contains("OWE")) sec = 1;
            if (up.contains("PSK") || up.contains("WPA2") || up.contains("RSN")) sec = 3;
            if (up.contains("SAE") || up.contains("WPA3")) sec = 4;
            int[] had = best.get(ssid);
            if (had == null || rssi > had[0]) best.put(ssid, new int[] { rssi, sec });
        }
        List<Map.Entry<String, int[]>> rows = new ArrayList<Map.Entry<String, int[]>>(best.entrySet());
        Collections.sort(rows, new java.util.Comparator<Map.Entry<String, int[]>>() {
            @Override public int compare(Map.Entry<String, int[]> a, Map.Entry<String, int[]> b) {
                return b.getValue()[0] - a.getValue()[0];
            }
        });
        LinkedHashMap<String, int[]> out = new LinkedHashMap<String, int[]>();
        for (Map.Entry<String, int[]> e : rows) out.put(e.getKey(), e.getValue());
        return out;
    }

    private String join(String body) {
        Map<String, String> f = form(body);
        final String ssid = f.containsKey("ssid") ? f.get("ssid") : "";
        String s = f.containsKey("sec") ? f.get("sec") : "wpa2";
        final String sec = s.matches("open|owe|wep|wpa2|wpa3") ? s : "wpa2";
        final String pass = f.containsKey("pass") ? f.get("pass") : "";
        JSONObject o = new JSONObject();
        try {
            if (ssid.length() == 0) { o.put("ok", false); o.put("why", "no network chosen"); return o.toString(); }
            if (!"idle".equals(state) && !"failed".equals(state) && !"scanning".equals(state)) {
                o.put("ok", false); o.put("why", "already trying"); return o.toString();
            }
            state = "joining";
            stateNote = "";
            joined = ssid;
            new Thread(new Runnable() {
                @Override public void run() { attempt(ssid, sec, pass); }
            }, "salah-join").start();
            o.put("ok", true);
        } catch (Throwable ignored) { }
        return o.toString();
    }

    private void attempt(String ssid, String sec, String pass) {
        try {
            String cmd = "cmd wifi connect-network " + HostControl.quote(ssid) + " " + sec
                    + (pass.length() > 0 && !sec.equals("open") && !sec.equals("owe") ? " " + HostControl.quote(pass) : "")
                    + "; sleep 8; cmd wifi status";
            String out = host.outShell(cmd);
            boolean ok = out.contains("Wifi is connected to") || out.contains("\"" + ssid + "\"");
            if (!ok) {
                /* One retry: the first attempt often lands while the radio is still associating. */
                out = host.outShell("sleep 6; cmd wifi status");
                ok = out.contains("Wifi is connected to");
            }
            if (ok) {
                state = "connected";
                act.onHotspotJoined();
            } else {
                state = "failed";
                stateNote = out.contains("Failed") || out.contains("error") ? "wrong password, or the network refused us" : "the frame could not join that network";
            }
        } catch (Throwable t) {
            state = "failed";
            stateNote = "the frame could not be reached";
            Log.w(TAG, "join failed: " + t);
        }
    }

    private String stateJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("state", state);
            o.put("ssid", joined);
            o.put("note", stateNote);
            o.put("online", act.isOnline());
            /* Handed over on every answer, not only the last one: the hotspot can disappear from
               under the phone the moment the frame joins, and a phone that already holds the link
               can carry on at the site under the same session regardless. */
            o.put("go", act.pairLink());
        } catch (Throwable ignored) { }
        return o.toString();
    }

    private static Map<String, String> form(String body) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (String pair : body.split("&")) {
            int i = pair.indexOf('=');
            if (i <= 0) continue;
            try {
                m.put(java.net.URLDecoder.decode(pair.substring(0, i), "UTF-8"),
                      java.net.URLDecoder.decode(pair.substring(i + 1), "UTF-8"));
            } catch (Throwable ignored) { }
        }
        return m;
    }

    // ---- where the phone should point its browser ------------------------------------------

    /**
     * The address the frame answers on over its hotspot. Android hands the access point an
     * address of its own (192.168.43.1 on these builds, but that is not promised), so it is read
     * off the interfaces rather than assumed, skipping loopback and the frame's own uplink.
     */
    static String apAddress() {
        try {
            Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
            String fallback = null;
            while (all != null && all.hasMoreElements()) {
                NetworkInterface ni = all.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName();
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                    String ip = a.getHostAddress();
                    /* An access point is the gateway on its own subnet, so its own address ends in .1 */
                    if (ip.endsWith(".1") && (name.startsWith("ap") || name.startsWith("wlan1") || name.startsWith("swlan"))) return ip;
                    if (ip.endsWith(".1") && fallback == null) fallback = ip;
                }
            }
            return fallback;
        } catch (Throwable t) {
            Log.w(TAG, "could not read the interfaces: " + t);
            return null;
        }
    }
}
