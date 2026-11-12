package com.paltech.salahos;

import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;

/**
 * Hands out an address to the one phone that joins the frame's hotspot.
 *
 * Android raises the access point but stops there: `cmd wifi start-softap` says so itself, and the
 * frame was watched doing exactly that — the radio came up on wlan1 with no address on it and
 * nothing answering DHCP. Real tethering would do this part, but it lives inside a system module
 * that an app cannot ask for. So the frame does it: this is a DHCP server cut down to the one case
 * that matters, a single phone asking for an address so it can open one page.
 *
 * Port 67 belongs to root, which this app is not, so the frame's own firewall sends port 67 here
 * and rewrites the replies as if they came from there (see {@link CaptivePortal}).
 */
final class DhcpServer {
    private static final String TAG = "SalahHotspot";
    static final int PORT = 6767;            /* 67 after the firewall rule */
    static final String NET = "192.168.49";
    static final String SELF = NET + ".1";
    private static final int LEASE_SECONDS = 3600;

    private DatagramSocket sock;
    private Thread thread;
    private volatile boolean running;
    private final Map<String, Integer> leases = new HashMap<String, Integer>();
    private int next = 20;

    synchronized void start() {
        if (running) return;
        try {
            sock = new DatagramSocket(PORT);
            sock.setBroadcast(true);
            sock.setSoTimeout(1000);
            running = true;
            thread = new Thread(new Runnable() { @Override public void run() { loop(); } }, "salah-dhcp");
            thread.setDaemon(true);
            thread.start();
            Log.i(TAG, "dhcp listening on " + PORT);
        } catch (Throwable t) {
            Log.w(TAG, "dhcp could not start: " + t);
            stop();
        }
    }

    synchronized void stop() {
        running = false;
        try { if (sock != null) sock.close(); } catch (Throwable ignored) { }
        sock = null;
        thread = null;
        leases.clear();
    }

    boolean isRunning() { return running; }

    private void loop() {
        byte[] buf = new byte[1500];
        while (running) {
            try {
                DatagramPacket in = new DatagramPacket(buf, buf.length);
                sock.receive(in);
                byte[] out = reply(in.getData(), in.getLength());
                if (out != null) {
                    /* The phone has no address yet, so the answer can only be shouted. */
                    sock.send(new DatagramPacket(out, out.length, InetAddress.getByName("255.255.255.255"), 68));
                }
            } catch (java.net.SocketTimeoutException e) {
                /* nobody asked this second */
            } catch (Throwable t) {
                if (running) Log.w(TAG, "dhcp: " + t);
            }
        }
    }

    /** DISCOVER gets an OFFER, REQUEST gets an ACK, and nothing else is answered. */
    private byte[] reply(byte[] p, int len) {
        if (len < 240) return null;
        if ((p[0] & 255) != 1) return null;                 /* not a request */
        if (!(p[236] == (byte) 0x63 && p[237] == (byte) 0x82
           && p[238] == (byte) 0x53 && p[239] == (byte) 0x63)) return null;

        int type = 0;
        for (int i = 240; i + 1 < len; ) {
            int opt = p[i] & 255;
            if (opt == 255) break;
            if (opt == 0) { i++; continue; }
            int l = p[i + 1] & 255;
            if (opt == 53 && l == 1 && i + 2 < len) type = p[i + 2] & 255;
            i += 2 + l;
        }
        if (type != 1 && type != 3) return null;            /* DISCOVER or REQUEST only */

        StringBuilder mac = new StringBuilder();
        for (int i = 28; i < 34; i++) mac.append(String.format("%02x", p[i] & 255));
        int host = lease(mac.toString());

        byte[] r = new byte[300];
        r[0] = 2; r[1] = 1; r[2] = 6; r[3] = 0;             /* reply, ethernet, 6-byte address */
        System.arraycopy(p, 4, r, 4, 4);                    /* the same transaction id */
        System.arraycopy(p, 10, r, 10, 2);                  /* and flags */
        put4(r, 16, NET + "." + host);                      /* yours */
        put4(r, 20, SELF);                                  /* ours */
        System.arraycopy(p, 28, r, 28, 16);                 /* their hardware address */
        r[236] = (byte) 0x63; r[237] = (byte) 0x82; r[238] = (byte) 0x53; r[239] = (byte) 0x63;

        int i = 240;
        r[i++] = 53; r[i++] = 1; r[i++] = (byte) (type == 1 ? 2 : 5);       /* offer or ack */
        r[i++] = 54; r[i++] = 4; i = put4(r, i, SELF);                      /* which server */
        r[i++] = 51; r[i++] = 4;
        r[i++] = (byte) (LEASE_SECONDS >> 24); r[i++] = (byte) (LEASE_SECONDS >> 16);
        r[i++] = (byte) (LEASE_SECONDS >> 8);  r[i++] = (byte) LEASE_SECONDS;
        r[i++] = 1; r[i++] = 4; i = put4(r, i, "255.255.255.0");            /* the subnet */
        r[i++] = 3; r[i++] = 4; i = put4(r, i, SELF);                       /* the way out */
        r[i++] = 6; r[i++] = 4; i = put4(r, i, SELF);                       /* and who to ask */
        r[i++] = (byte) 255;

        byte[] out = new byte[Math.max(300, i)];
        System.arraycopy(r, 0, out, 0, Math.min(r.length, out.length));
        return out;
    }

    /** The same phone keeps the same address for as long as the hotspot is up. */
    private synchronized int lease(String mac) {
        Integer had = leases.get(mac);
        if (had != null) return had;
        int host = next++;
        if (next > 200) next = 20;
        leases.put(mac, host);
        return host;
    }

    private static int put4(byte[] b, int at, String ip) {
        String[] p = ip.split("\\.");
        for (int i = 0; i < 4; i++) b[at + i] = (byte) Integer.parseInt(p[i]);
        return at + 4;
    }
}
