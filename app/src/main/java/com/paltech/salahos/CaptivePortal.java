package com.paltech.salahos;

import android.util.Log;

import java.util.ArrayList;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * Makes the frame's hotspot behave like hotel Wi-Fi, so that one QR code does the whole job.
 *
 * The buyer scans a single code and their phone joins the frame. A phone that has just joined a
 * network immediately checks whether it leads anywhere: Android asks for
 * http://connectivitycheck.gstatic.com/generate_204 and iOS for http://captive.apple.com. If that
 * check comes back as anything other than the expected answer, the phone decides it is behind a
 * sign-in page and opens it by itself. That is the whole trick — the second QR code becomes the
 * phone's own doing.
 *
 * Two pieces make it work. Every name the phone looks up has to resolve to the frame, which is
 * what the small DNS responder below does. And the phone asks on port 80, which an ordinary app
 * may not listen on, so the frame's own firewall sends port 80 and port 53 to the ports this app
 * does hold. Both are undone when the hotspot stops.
 */
final class CaptivePortal {
    private static final String TAG = "SalahHotspot";
    /* Not 5353: Android's own mDNS service already holds that one, and the bind failed, which
       took the firewall rules down with it and left the phone with no address at all. */
    private static final int DNS_PORT = 53;     /* if the kernel allows it */
    private static final int DNS_ALT  = 15353;

    private final HostControl host;
    private DhcpServer dhcp;
    private HotspotServer web;
    private boolean dnsFellBack;
    private DatagramSocket sock;
    private Thread thread;
    private volatile boolean running;
    private String iface = "";
    private byte[] answer = new byte[4];

    CaptivePortal(HostControl h, DhcpServer d, HotspotServer w) { host = h; dhcp = d; web = w; }

    /** @param ip the frame's own address on the hotspot, which every lookup will be answered with. */
    synchronized void start(String ip, String ifaceName) {
        if (running) return;
        iface = ifaceName == null ? "" : ifaceName;
        try {
            String[] p = ip.split("\\.");
            for (int i = 0; i < 4; i++) answer[i] = (byte) Integer.parseInt(p[i]);
        } catch (Throwable t) {
            Log.w(TAG, "bad access point address " + ip + ": " + t);
            return;
        }

        /*
          The firewall rules go in first and on their own. They are what carry DHCP to the app,
          which is how the phone gets an address at all; the name answering below is only what
          makes the setup page open by itself. Tying the two together once meant a failure in the
          second quietly cancelled the first, and the phone joined a network that gave it nothing.
        */
        try {
            sock = new DatagramSocket(DNS_PORT);
            dnsFellBack = false;
        } catch (Throwable low) {
            try { sock = new DatagramSocket(DNS_ALT); dnsFellBack = true; } catch (Throwable t2) { sock = null; }
        }

        running = true;
        rules(true);

        try {
            if (sock == null) throw new java.net.BindException("no port for names");
            sock.setSoTimeout(1000);
            thread = new Thread(new Runnable() { @Override public void run() { loop(); } }, "salah-dns");
            thread.setDaemon(true);
            thread.start();
            Log.i(TAG, "captive portal up on " + ip + " via " + iface);
        } catch (Throwable t) {
            /* The phone still gets an address and the page still answers; it just has to be
               opened by hand. Worth knowing about, not worth taking the hotspot down for. */
            Log.w(TAG, "names will not be answered, so setup will not open by itself: " + t);
            try { if (sock != null) sock.close(); } catch (Throwable ignored) { }
            sock = null;
        }
    }

    synchronized void stop() {
        if (running) { try { rules(false); } catch (Throwable ignored) { } }
        running = false;
        sharing = false;
        try { if (pool != null) pool.shutdownNow(); } catch (Throwable ignored) { }
        pool = null;
        try { if (sock != null) sock.close(); } catch (Throwable ignored) { }
        sock = null;
        thread = null;
    }

    boolean isRunning() { return running; }

    /**
     * Ports 53, 67 and 80 belong to root, which this app is not, so the frame's own firewall sends
     * them to the ports the app does hold, and makes the DHCP answers look as though they left
     * port 67 — a phone will ignore them otherwise.
     */
    /**
     * Ports 53, 67 and 80 belong to root. Where the app could not take one for itself, the frame's
     * own firewall carries it instead.
     *
     * The rules are INSERTED at the top of the chain, not appended to the bottom. This ROM jumps to
     * an OEM chain as its first rule, and whatever that chain decides, anything appended after it
     * may never be reached — which is exactly what the frame did: the rules were listed, their
     * counters stayed at zero, and the phone's address request was never seen by the app at all.
     * Old copies are removed first, because the app can be restarted without ever getting the
     * chance to tidy up, and they were piling up one pair per run.
     */
    private void rules(boolean add) {
        String in = iface.length() > 0 ? "-i " + iface + " " : "";
        String out = iface.length() > 0 ? "-o " + iface + " " : "";
        java.util.List<String[]> want = new ArrayList<String[]>();
        /*
          Everything arriving from the hotspot is let through the filter table first. Android keeps
          its own chains in there and a packet that survives the redirect can still be dropped
          before any socket sees it, which looks identical to the redirect not working: the rules
          are listed, the app hears nothing, the phone gives up. While the hotspot is up this
          interface carries nothing but one phone doing setup, so accepting it outright costs
          nothing and removes a whole class of silent failure.
        */
        if (iface.length() > 0) want.add(new String[] { "filter:INPUT", "-i " + iface + " -j ACCEPT" });
        if (dnsFellBack) {
            want.add(new String[] { "PREROUTING", in + "-p udp --dport 53 -j REDIRECT --to-ports " + DNS_ALT });
        }
        if (dhcp != null && dhcp.fellBack()) {
            /*
              DNAT to the broadcast address rather than REDIRECT. REDIRECT rewrites the
              destination to an ordinary address, and a packet from 0.0.0.0 to an ordinary
              address is a martian the kernel throws away - which is why the redirect rule
              counted the phone's requests while nothing ever reached the socket. Sending it to
              255.255.255.255 on another port keeps the destination a broadcast, which a source
              of 0.0.0.0 is allowed to talk to.
            */
            want.add(new String[] { "PREROUTING", in + "-p udp --dport 67 -j DNAT --to-destination 255.255.255.255:" + DhcpServer.ALT });
            want.add(new String[] { "POSTROUTING", out + "-p udp --sport " + DhcpServer.ALT
                                    + " -j SNAT --to-source " + DhcpServer.SELF + ":67" });
        }
        /*
          Everything the phone sends to port 80 comes here, wherever it was addressed.

          This used to be done only when the server had been pushed off port 80, on the reasoning
          that a server already on 80 needs no help. It does. Nothing was catching port 80 when
          the bind succeeded, and the phone's probe arrived only because our DNS had answered
          every name with this address - which is luck, not design. A phone using private DNS
          (DoT or DoH) never asks us, and one with the probe host already cached does not ask
          either; both then send port 80 to an address with no route, hear nothing back, and
          decide this network is simply broken.

          A phone that is already addressing this frame is unaffected: rewriting a destination to
          the value it already holds does nothing.
        */
        int webPort = (web != null && web.fellBack()) ? HotspotServer.ALT : HotspotServer.PORT;
        want.add(new String[] { "PREROUTING", in + "-p tcp --dport 80 -j DNAT --to-destination "
                                + DhcpServer.SELF + ":" + webPort });

        /*
          Every rule this app has ever installed is removed first, not only the ones it wants
          now. A build that managed to hold port 67 by itself left an earlier build's redirect
          sitting in the chain, and that redirect went on sending the phone's requests to a port
          nobody was listening on any more. From the outside it looked like a brand new fault.
        */
        String[][] known = {
            { "PREROUTING",  in + "-p udp --dport 53 -j REDIRECT --to-ports " + DNS_ALT },
            { "PREROUTING",  in + "-p udp --dport 67 -j DNAT --to-destination 255.255.255.255:" + DhcpServer.ALT },
            { "PREROUTING",  in + "-p udp --dport 67 -j REDIRECT --to-ports " + DhcpServer.ALT },
            { "POSTROUTING", out + "-p udp --sport " + DhcpServer.ALT + " -j SNAT --to-source " + DhcpServer.SELF + ":67" },
            { "PREROUTING",  in + "-p tcp --dport 80 -j REDIRECT --to-ports " + HotspotServer.ALT },
            { "PREROUTING",  in + "-p tcp --dport 80 -j DNAT --to-destination " + DhcpServer.SELF + ":" + HotspotServer.ALT },
            { "PREROUTING",  in + "-p tcp --dport 80 -j DNAT --to-destination " + DhcpServer.SELF + ":" + HotspotServer.PORT },
            { "filter:INPUT", "-i " + iface + " -j ACCEPT" }
        };
        StringBuilder cmd = new StringBuilder();
        for (String[] r : known) {
            String table = "nat", chain = r[0];
            int colon = chain.indexOf(':');
            if (colon > 0) { table = chain.substring(0, colon); chain = chain.substring(colon + 1); }
            for (int k = 0; k < 6; k++) {
                cmd.append("iptables -t ").append(table).append(" -D ").append(chain).append(' ')
                   .append(r[1]).append(" 2>/dev/null; ");
            }
        }
        if (add) {
            for (String[] r : want) {
                String table = "nat", chain = r[0];
                int colon = chain.indexOf(':');
                if (colon > 0) { table = chain.substring(0, colon); chain = chain.substring(colon + 1); }
                cmd.append("iptables -t ").append(table).append(" -I ").append(chain).append(" 1 ")
                   .append(r[1]).append(" 2>&1; ");
            }
        }
        String said = host.outShell(cmd.toString());
        if (said != null && said.trim().length() > 0) Log.w(TAG, "iptables said: " + said.trim());
        if (add) {
            Log.i(TAG, "rules in: " + host.outShell(
                    "iptables -t nat -L PREROUTING -n -v --line-numbers 2>&1; "
                  + "iptables -t nat -L POSTROUTING -n -v --line-numbers 2>&1; "
                  + "iptables -L INPUT -n -v --line-numbers 2>&1 | head -12"));
            /* and who already holds the ports we wanted, since that is why we are here at all */
            Log.i(TAG, "port 67 held by: " + host.outShell(
                    "netstat -lnup 2>/dev/null | grep ':67 ' ; grep -i ' 0043 ' /proc/net/udp 2>/dev/null").trim());
        }
    }

    /* ---- once the frame is online, names resolve for real - all but these ------------------

       These are the names a phone uses to decide whether a network leads anywhere. They keep
       pointing at the frame even after the frame has a connection to share, and that is on
       purpose: the sign-in window holding the buyer's setup page is alive only for as long as
       Android still believes there is a sign-in to do. Let the probe succeed and Android calls
       the network good, closes the window, and takes the setup with it.

       www.google.com is on the list for the same reason. The phone checks over https as well as
       http, and a successful https check alone is enough for Android to call the network good -
       so that one has to keep failing too. Nothing in setup asks for it. Firebase, the fonts and
       the site itself live on other names and resolve normally. */
    private static final String[] PROBES = {
        "connectivitycheck.gstatic.com", "connectivitycheck.android.com",
        "clients3.google.com", "clients1.google.com", "android.clients.google.com",
        "www.google.com", "google.com",
        "captive.apple.com", "www.apple.com", "gsp1.apple.com",
        "www.msftconnecttest.com", "msftconnecttest.com", "www.msftncsi.com",
        "detectportal.firefox.com"
    };
    private static final String[] UPSTREAM = { "8.8.8.8", "1.1.1.1" };
    private volatile boolean sharing;
    private java.util.concurrent.ExecutorService pool;

    /** Called when the frame has joined the house network and is carrying the phone's traffic. */
    synchronized void share(boolean on) {
        if (on && pool == null) pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        sharing = on;
        Log.i(TAG, on ? "names now resolved for real, bar the probe hosts"
                      : "every name answered with the frame again");
    }

    private static boolean isProbe(String name) {
        if (name == null || name.length() == 0 || "?".equals(name)) return true;
        String n = name.toLowerCase(java.util.Locale.US);
        for (String p : PROBES) if (n.equals(p)) return true;
        return false;
    }

    /** Asks a real resolver and hands the answer straight back, untouched. */
    private void forward(final byte[] q, final int len, final InetAddress who, final int port) {
        try {
            pool.execute(new Runnable() { @Override public void run() {
                for (String up : UPSTREAM) {
                    DatagramSocket s = null;
                    try {
                        s = new DatagramSocket();
                        s.setSoTimeout(2000);
                        s.send(new DatagramPacket(q, len, InetAddress.getByName(up), 53));
                        byte[] buf = new byte[1500];
                        DatagramPacket in = new DatagramPacket(buf, buf.length);
                        s.receive(in);
                        sock.send(new DatagramPacket(buf, in.getLength(), who, port));
                        return;
                    } catch (Throwable ignored) {
                    } finally {
                        if (s != null) { try { s.close(); } catch (Throwable ignored2) { } }
                    }
                }
                /* Nothing upstream answered. The frame is better than silence: the phone lands on
                   a page that can at least say so. */
                try {
                    byte[] r = reply(q, len);
                    if (r != null) sock.send(new DatagramPacket(r, r.length, who, port));
                } catch (Throwable ignored) { }
            }});
        } catch (Throwable t) {
            Log.w(TAG, "forward: " + t);
        }
    }

    private void loop() {
        byte[] buf = new byte[512];
        while (running) {
            try {
                DatagramPacket in = new DatagramPacket(buf, buf.length);
                sock.receive(in);
                int n = in.getLength();
                String name = nameIn(in.getData(), n);
                if (asked < 12) { asked++; Log.i(TAG, "name asked for: " + name
                        + (sharing && !isProbe(name) ? " (passed through)" : " -> the frame")); }
                if (sharing && !isProbe(name)) {
                    byte[] copy = new byte[n];
                    System.arraycopy(in.getData(), 0, copy, 0, n);
                    forward(copy, n, in.getAddress(), in.getPort());
                    continue;
                }
                byte[] out = reply(in.getData(), n);
                if (out != null) sock.send(new DatagramPacket(out, out.length, in.getAddress(), in.getPort()));
            } catch (java.net.SocketTimeoutException e) {
                /* nothing asked this second */
            } catch (Throwable t) {
                if (running) Log.w(TAG, "dns: " + t);
            }
        }
    }

    /**
     * Answers an A question with the frame's address, and anything else with "no such name" so the
     * phone stops asking. Only one question per message is handled, which is all any phone sends.
     */
    private int asked;

    /** Just for the log: the name out of a query, so a run shows what the phone looked up. */
    private static String nameIn(byte[] q, int len) {
        StringBuilder b = new StringBuilder();
        int i = 12;
        while (i < len && q[i] != 0) {
            int l = q[i] & 255;
            if (l > 63 || i + l + 1 > len) return "?";
            if (b.length() > 0) b.append('.');
            for (int k = 1; k <= l; k++) b.append((char) (q[i + k] & 255));
            i += l + 1;
        }
        return b.length() == 0 ? "?" : b.toString();
    }

    private byte[] reply(byte[] q, int len) {
        if (len < 18) return null;
        if ((q[2] & 0x80) != 0) return null;                 /* already an answer */
        if (((q[4] & 255) << 8 | (q[5] & 255)) != 1) return null;   /* exactly one question */

        int i = 12;
        while (i < len && q[i] != 0) {
            int l = q[i] & 255;
            if (l > 63 || i + l + 1 > len) return null;      /* compressed or malformed */
            i += l + 1;
        }
        if (i + 5 > len) return null;
        int end = i + 5;                                     /* past the root label, type and class */
        int type = (q[i + 1] & 255) << 8 | (q[i + 2] & 255);

        boolean a = type == 1;
        byte[] r = new byte[a ? end + 16 : end];
        System.arraycopy(q, 0, r, 0, end);
        r[2] = (byte) 0x81;                                  /* answer, recursion desired */
        r[3] = (byte) (a ? 0x80 : 0x83);                     /* ok, or "no such name" */
        r[4] = 0; r[5] = 1;                                  /* one question */
        r[6] = 0; r[7] = (byte) (a ? 1 : 0);                 /* one answer, or none */
        r[8] = 0; r[9] = 0; r[10] = 0; r[11] = 0;
        if (!a) return r;

        int p = end;
        r[p++] = (byte) 0xc0; r[p++] = 0x0c;                 /* the name, pointing back at the question */
        r[p++] = 0; r[p++] = 1;                              /* type A */
        r[p++] = 0; r[p++] = 1;                              /* class IN */
        r[p++] = 0; r[p++] = 0; r[p++] = 0; r[p++] = 30;     /* half a minute */
        r[p++] = 0; r[p++] = 4;
        System.arraycopy(answer, 0, r, p, 4);
        return r;
    }
}
