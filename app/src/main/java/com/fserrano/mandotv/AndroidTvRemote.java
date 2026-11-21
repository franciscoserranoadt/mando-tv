package com.fserrano.mandotv;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Mando para teles Android TV / Google TV (Xiaomi, TCL, Philips, Sony nuevas…)
 * usando el protocolo oficial «Android TV Remote» v2 (puertos 6466 y 6467).
 */
final class AndroidTvRemote {

    interface Ui {
        void status(String msg);
        /** Pide el código de 6 caracteres que sale en la tele. Bloquea; null si se cancela. */
        String askPairingCode();
    }

    private static final char[] PW = "mandotv".toCharArray();
    private static final int SHORT = 3;

    private final File dir;
    private final Ui ui;
    private String ip = "";
    private KeyStore ks;
    private SSLSocket sock;
    private OutputStream out;
    private volatile boolean ready;

    AndroidTvRemote(File dir, Ui ui) {
        this.dir = dir;
        this.ui = ui;
    }

    synchronized void setIp(String newIp) {
        String n = newIp == null ? "" : newIp.trim();
        if (!n.equals(ip)) { close(); ip = n; }
    }

    synchronized void close() {
        ready = false;
        try { if (sock != null) sock.close(); } catch (Exception ignored) { }
        sock = null;
        out = null;
    }

    // ---------------- Teclas ----------------

    private static final Map<String, Integer> KEYS = new HashMap<>();
    static {
        Object[] k = {"POWER", 26, "INPUT", 178, "MUTE", 164, "VOLUP", 24, "VOLDOWN", 25, "CHUP", 166,
                "CHDOWN", 167, "UP", 19, "DOWN", 20, "LEFT", 21, "RIGHT", 22, "OK", 23, "BACK", 4,
                "EXIT", 4, "HOME", 3, "MENU", 82};
        for (int i = 0; i < k.length; i += 2) KEYS.put((String) k[i], (Integer) k[i + 1]);
        for (int d = 0; d <= 9; d++) KEYS.put("D" + d, 7 + d);
    }

    /** Llamar desde un hilo secundario. */
    void send(String code) {
        Integer kc = KEYS.get(code);
        if (kc == null) return;
        key(kc);
    }

    void key(int keycode) {
        if (!ensure()) return;
        writeRemote(field(10, msg(varint(1, keycode), varint(2, SHORT))));
    }

    void launch(String app) {
        String link;
        switch (app) {
            case "APP_YOUTUBE": link = "https://www.youtube.com"; break;
            case "APP_NETFLIX": link = "https://www.netflix.com/title"; break;
            case "APP_DISNEY": link = "https://www.disneyplus.com"; break;
            default: return;
        }
        if (!ensure()) return;
        writeRemote(field(90, msg(str(1, link))));
    }

    /** Escribe texto pulsando teclas (letras sin tilde, números, espacio y algún signo). */
    void text(String text, boolean enter) {
        if (!ensure()) return;
        String plain = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        try {
            for (int i = 0; i < plain.length(); i++) {
                int kc = charKey(plain.charAt(i));
                if (kc > 0) {
                    writeRemote(field(10, msg(varint(1, kc), varint(2, SHORT))));
                    Thread.sleep(40);
                }
            }
            if (enter) writeRemote(field(10, msg(varint(1, 66), varint(2, SHORT))));
        } catch (InterruptedException ignored) { }
    }

    private static int charKey(char c) {
        if (c >= 'a' && c <= 'z') return 29 + (c - 'a');
        if (c >= '0' && c <= '9') return 7 + (c - '0');
        switch (c) {
            case ' ': return 62;
            case '.': return 56;
            case ',': return 55;
            case '-': return 69;
            case '@': return 77;
            case '/': return 76;
            default: return 0;
        }
    }

    // ---------------- Conexión ----------------

    private boolean ensure() {
        synchronized (this) {
            if (ready && sock != null) return true;
        }
        if (ip.isEmpty()) { ui.status("Configura primero la IP de la tele"); return false; }
        try {
            try {
                connectRemote();
            } catch (ConnectException | NoRouteToHostException | SocketTimeoutException e) {
                throw e;
            } catch (IOException e) {
                // La tele aún no conoce este móvil: hay que emparejar
                if (!pair()) return false;
                connectRemote();
            }
            if (!ready) {
                // Conectó pero la tele no nos acepta: emparejamos de nuevo
                close();
                if (!pair()) return false;
                connectRemote();
            }
            if (ready) return true;
            ui.status("La tele no responde. Prueba otra vez");
        } catch (ConnectException | NoRouteToHostException | SocketTimeoutException e) {
            ui.status("No conecta con la tele. Revisa la IP, que esté encendida y en el mismo wifi");
        } catch (Exception e) {
            ui.status("Error con la tele: " + e.getMessage());
        }
        return false;
    }

    private void connectRemote() throws Exception {
        close();
        final SSLSocket s = open(6466);
        final InputStream in = s.getInputStream();
        synchronized (this) { sock = s; out = s.getOutputStream(); }
        Thread reader = new Thread(() -> {
            try {
                while (true) handleRemote(readFrame(in));
            } catch (Exception e) {
                synchronized (AndroidTvRemote.this) {
                    if (sock == s) { ready = false; sock = null; out = null; }
                }
            }
        }, "atv-reader");
        reader.setDaemon(true);
        reader.start();
        long end = System.currentTimeMillis() + 6000;
        while (!ready && System.currentTimeMillis() < end) {
            synchronized (this) { if (sock != s) break; }
            Thread.sleep(50);
        }
    }

    private void handleRemote(byte[] m) {
        Map<Integer, List<Object>> f = parse(m);
        if (f.containsKey(1)) {
            byte[] info = msg(str(1, "Mando TV"), str(2, "Francisco"), varint(3, 1), str(4, "1"),
                    str(5, "atvremote"), str(6, "1.0.0"));
            writeRemote(field(1, msg(varint(1, 622), bytes(2, info))));
        }
        if (f.containsKey(2)) {
            writeRemote(field(2, msg(varint(1, 622))));
            ready = true;
        }
        if (f.containsKey(8)) {
            long v = 0;
            Object o = f.get(8).get(0);
            if (o instanceof byte[]) {
                Map<Integer, List<Object>> p = parse((byte[]) o);
                if (p.containsKey(1) && p.get(1).get(0) instanceof Long) v = (Long) p.get(1).get(0);
            }
            writeRemote(field(9, msg(varint(1, v))));
        }
        if (f.containsKey(40)) ready = true;
    }

    private synchronized void writeRemote(byte[] m) {
        if (out == null) return;
        try {
            writeFrame(out, m);
        } catch (IOException e) {
            ready = false;
            ui.status("Se perdió la conexión con la tele, vuelve a pulsar");
        }
    }

    // ---------------- Emparejamiento ----------------

    private boolean pair() throws Exception {
        ui.status("Emparejando… mira la pantalla de la tele");
        try (SSLSocket s = open(6467)) {
            s.setSoTimeout(30000);
            InputStream in = s.getInputStream();
            OutputStream o = s.getOutputStream();

            writeFrame(o, outer(10, msg(str(1, "atvremote"), str(2, "Mando TV"))));
            checkStatus(readFrame(in));

            byte[] enc = msg(varint(1, 3), varint(2, 6)); // hexadecimal, 6 caracteres
            writeFrame(o, outer(20, msg(bytes(1, enc), varint(3, 1))));
            checkStatus(readFrame(in));

            writeFrame(o, outer(30, msg(bytes(1, enc), varint(2, 1))));
            checkStatus(readFrame(in));

            s.setSoTimeout(0);
            String code = ui.askPairingCode();
            if (code == null) { ui.status("Emparejamiento cancelado"); return false; }
            code = code.trim().toUpperCase(Locale.ROOT);
            if (!code.matches("[0-9A-F]{6}")) { ui.status("El código son 6 letras o números de la tele"); return false; }

            X509Certificate client = (X509Certificate) keyStore().getCertificate("atv");
            X509Certificate server = (X509Certificate) s.getSession().getPeerCertificates()[0];
            RSAPublicKey cp = (RSAPublicKey) client.getPublicKey();
            RSAPublicKey sp = (RSAPublicKey) server.getPublicKey();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(unsigned(cp.getModulus()));
            md.update(unsigned(cp.getPublicExponent()));
            md.update(unsigned(sp.getModulus()));
            md.update(unsigned(sp.getPublicExponent()));
            md.update(hex(code.substring(2)));
            byte[] h = md.digest();
            if ((h[0] & 0xFF) != Integer.parseInt(code.substring(0, 2), 16)) {
                ui.status("Código incorrecto. Vuelve a pulsar un botón para intentarlo");
                return false;
            }
            s.setSoTimeout(15000);
            writeFrame(o, outer(40, msg(bytes(1, h))));
            checkStatus(readFrame(in));
            ui.status("¡Emparejado con la tele!");
            return true;
        }
    }

    private static void checkStatus(byte[] m) throws IOException {
        Map<Integer, List<Object>> f = parse(m);
        long st = f.containsKey(2) && f.get(2).get(0) instanceof Long ? (Long) f.get(2).get(0) : 0;
        if (st != 200) throw new IOException("la tele rechazó el emparejamiento (" + st + ")");
    }

    private static byte[] outer(int field, byte[] body) {
        return msg(varint(1, 2), varint(2, 200), bytes(field, body));
    }

    private static byte[] unsigned(BigInteger b) {
        byte[] a = b.toByteArray();
        if (a.length > 1 && a[0] == 0) {
            byte[] r = new byte[a.length - 1];
            System.arraycopy(a, 1, r, 0, r.length);
            return r;
        }
        return a;
    }

    private static byte[] hex(String s) {
        byte[] r = new byte[s.length() / 2];
        for (int i = 0; i < r.length; i++) r[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return r;
    }

    // ---------------- TLS y certificado propio ----------------

    private SSLSocket open(int port) throws Exception {
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore(), PW);
        TrustManager[] tm = {new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] c, String a) { }
            @Override public void checkServerTrusted(X509Certificate[] c, String a) { }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }};
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), tm, new SecureRandom());
        SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket();
        s.connect(new InetSocketAddress(ip, port), 5000);
        s.setSoTimeout(0);
        s.startHandshake();
        return s;
    }

    private synchronized KeyStore keyStore() throws Exception {
        if (ks != null) return ks;
        File f = new File(dir, "atv.p12");
        KeyStore k = KeyStore.getInstance("PKCS12");
        if (f.exists()) {
            try (FileInputStream in = new FileInputStream(f)) { k.load(in, PW); }
            if (k.getCertificate("atv") != null) { ks = k; return k; }
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        X500Name name = new X500Name("CN=atvremote/Mando TV");
        Date from = new Date(System.currentTimeMillis() - 86400000L);
        Date to = new Date(System.currentTimeMillis() + 3650L * 86400000L);
        JcaX509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(name,
                BigInteger.valueOf(System.currentTimeMillis()), from, to, name, kp.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate());
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(b.build(signer));
        k = KeyStore.getInstance("PKCS12");
        k.load(null, null);
        k.setKeyEntry("atv", kp.getPrivate(), PW, new Certificate[]{cert});
        try (FileOutputStream o = new FileOutputStream(f)) { k.store(o, PW); }
        ks = k;
        return k;
    }

    // ---------------- Protobuf mínimo ----------------

    private static void writeVarint(ByteArrayOutputStream o, long v) {
        while ((v & ~0x7FL) != 0) { o.write((int) ((v & 0x7F) | 0x80)); v >>>= 7; }
        o.write((int) v);
    }

    private static byte[] varint(int field, long v) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        writeVarint(o, ((long) field << 3));
        writeVarint(o, v);
        return o.toByteArray();
    }

    private static byte[] bytes(int field, byte[] data) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        writeVarint(o, ((long) field << 3) | 2);
        writeVarint(o, data.length);
        o.write(data, 0, data.length);
        return o.toByteArray();
    }

    private static byte[] str(int field, String s) {
        return bytes(field, s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static byte[] field(int field, byte[] body) { return bytes(field, body); }

    private static byte[] msg(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }

    private static Map<Integer, List<Object>> parse(byte[] b) {
        Map<Integer, List<Object>> r = new HashMap<>();
        int[] pos = {0};
        try {
            while (pos[0] < b.length) {
                long tag = readVarint(b, pos);
                int field = (int) (tag >>> 3);
                int wire = (int) (tag & 7);
                Object val;
                if (wire == 0) {
                    val = readVarint(b, pos);
                } else if (wire == 2) {
                    int len = (int) readVarint(b, pos);
                    byte[] d = new byte[len];
                    System.arraycopy(b, pos[0], d, 0, len);
                    pos[0] += len;
                    val = d;
                } else if (wire == 5) {
                    pos[0] += 4; continue;
                } else if (wire == 1) {
                    pos[0] += 8; continue;
                } else {
                    break;
                }
                List<Object> l = r.get(field);
                if (l == null) { l = new ArrayList<>(); r.put(field, l); }
                l.add(val);
            }
        } catch (Exception ignored) { }
        return r;
    }

    private static long readVarint(byte[] b, int[] pos) {
        long v = 0;
        int shift = 0;
        while (true) {
            int x = b[pos[0]++] & 0xFF;
            v |= (long) (x & 0x7F) << shift;
            if ((x & 0x80) == 0) return v;
            shift += 7;
        }
    }

    private static void writeFrame(OutputStream o, byte[] m) throws IOException {
        ByteArrayOutputStream h = new ByteArrayOutputStream();
        writeVarint(h, m.length);
        h.write(m, 0, m.length);
        o.write(h.toByteArray());
        o.flush();
    }

    private static byte[] readFrame(InputStream in) throws IOException {
        long len = 0;
        int shift = 0;
        while (true) {
            int x = in.read();
            if (x < 0) throw new EOFException();
            len |= (long) (x & 0x7F) << shift;
            if ((x & 0x80) == 0) break;
            shift += 7;
        }
        if (len > 65536) throw new IOException("mensaje demasiado grande");
        byte[] d = new byte[(int) len];
        int off = 0;
        while (off < d.length) {
            int n = in.read(d, off, d.length - off);
            if (n < 0) throw new EOFException();
            off += n;
        }
        return d;
    }
}
