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

    private void loop() {
        byte[] buf = new byte[512];
        while (running) {
            try {
                DatagramPacket in = new DatagramPacket(buf, buf.length);
                sock.receive(in);
                byte[] out = reply(in.getData(), in.getLength());
                if (asked < 8) { asked++; Log.i(TAG, "name asked for: " + nameIn(in.getData(), in.getLength())); }
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
