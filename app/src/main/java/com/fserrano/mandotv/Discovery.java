package com.fserrano.mandotv;

import android.content.Context;
import android.net.wifi.WifiManager;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Busca televisores en la red wifi de casa (SSDP / UPnP). */
final class Discovery {

    static final class Found {
        final String ip;
        final int type;      // índice en SmartTv.TYPES, o -1 si no se reconoce
        final String label;
        Found(String ip, int type, String label) { this.ip = ip; this.type = type; this.label = label; }
    }

    static List<Found> search(Context ctx) {
        Map<String, Found> map = new LinkedHashMap<>();
        WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock lock = null;
        DatagramSocket s = null;
        try {
            if (wm != null) {
                lock = wm.createMulticastLock("mandotv");
                lock.setReferenceCounted(false);
                lock.acquire();
            }
            s = new DatagramSocket();
            s.setSoTimeout(700);
            InetAddress group = InetAddress.getByName("239.255.255.250");
            String[] targets = {
                    "urn:samsung.com:device:RemoteControlReceiver:1",
                    "urn:lge-com:service:webos-second-screen:1",
                    "urn:schemas-sony-com:service:IRCC:1",
                    "roku:ecp",
                    "urn:dial-multiscreen-org:service:dial:1",
                    "ssdp:all"};
            for (int round = 0; round < 2; round++) {
                for (String st : targets) {
                    String msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: "
                            + st + "\r\n\r\n";
                    byte[] b = msg.getBytes(StandardCharsets.US_ASCII);
                    s.send(new DatagramPacket(b, b.length, group, 1900));
                }
            }
            long end = System.currentTimeMillis() + 4000;
            byte[] buf = new byte[4096];
            while (System.currentTimeMillis() < end) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try { s.receive(p); } catch (SocketTimeoutException e) { continue; }
                String ip = p.getAddress().getHostAddress();
                String r = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                int type = -1;
                String label = "Otro aparato";
                if (r.contains("samsung")) { type = 0; label = "Samsung"; }
                else if (r.contains("webos") || r.contains("lge")) { type = 1; label = "LG"; }
                else if (r.contains("sony") || r.contains("bravia")) { type = 2; label = "Sony"; }
                else if (r.contains("roku")) { type = 3; label = "Roku"; }
                else if (r.contains("dial")) { label = "Smart TV / Android TV"; }
                Found prev = map.get(ip);
                if (prev == null || (prev.type < 0 && type >= 0)) map.put(ip, new Found(ip, type, label));
            }
        } catch (Exception ignored) {
        } finally {
            if (s != null) s.close();
            if (lock != null && lock.isHeld()) lock.release();
        }
        List<Found> known = new ArrayList<>(), other = new ArrayList<>();
        for (Found f : map.values()) (f.type >= 0 ? known : other).add(f);
        known.addAll(other);
        return known;
    }

    private Discovery() {}
}
