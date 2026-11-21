package com.fserrano.mandotv;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Códigos infrarrojos de las principales marcas de televisores. */
final class IrCodes {

    static final String[] BRANDS = {"Samsung", "LG", "Sony", "Philips", "Panasonic", "Xiaomi"};

    static final class Signal {
        final int freq;
        final int[] pattern;
        Signal(int freq, int[] pattern) { this.freq = freq; this.pattern = pattern; }
    }

    private static final Map<String, Integer> SAMSUNG = new HashMap<>();
    private static final Map<String, Integer> LG = new HashMap<>();
    private static final Map<String, Integer> SONY = new HashMap<>();
    private static final Map<String, Integer> PHILIPS = new HashMap<>();
    private static final Map<String, Integer> PANASONIC = new HashMap<>();
    private static final Map<String, Integer> XIAOMI = new HashMap<>();

    static {
        // Samsung (protocolo Samsung32, dirección 0x07)
        put(SAMSUNG, "POWER", 2, "INPUT", 1, "MUTE", 15, "VOLUP", 7, "VOLDOWN", 11,
                "CHUP", 18, "CHDOWN", 16, "UP", 96, "DOWN", 97, "LEFT", 101, "RIGHT", 98,
                "OK", 104, "BACK", 88, "EXIT", 45, "HOME", 121, "MENU", 26,
                "D1", 4, "D2", 5, "D3", 6, "D4", 8, "D5", 9, "D6", 10, "D7", 12, "D8", 13,
                "D9", 14, "D0", 17);

        // LG (protocolo NEC, dirección 0x04)
        put(LG, "POWER", 0x08, "INPUT", 0x0B, "MUTE", 0x09, "VOLUP", 0x02, "VOLDOWN", 0x03,
                "CHUP", 0x00, "CHDOWN", 0x01, "UP", 0x40, "DOWN", 0x41, "LEFT", 0x07, "RIGHT", 0x06,
                "OK", 0x44, "BACK", 0x28, "EXIT", 0x5B, "HOME", 0x7C, "MENU", 0x43,
                "D0", 0x10, "D1", 0x11, "D2", 0x12, "D3", 0x13, "D4", 0x14, "D5", 0x15,
                "D6", 0x16, "D7", 0x17, "D8", 0x18, "D9", 0x19);

        // Sony (protocolo SIRC 12 bits, dirección 1)
        put(SONY, "POWER", 21, "INPUT", 37, "MUTE", 20, "VOLUP", 18, "VOLDOWN", 19,
                "CHUP", 16, "CHDOWN", 17, "UP", 116, "DOWN", 117, "LEFT", 52, "RIGHT", 51,
                "OK", 101, "BACK", 35, "EXIT", 99, "HOME", 96, "MENU", 96,
                "D1", 0, "D2", 1, "D3", 2, "D4", 3, "D5", 4, "D6", 5, "D7", 6, "D8", 7,
                "D9", 8, "D0", 9);

        // Philips (protocolo RC5, dirección 0)
        put(PHILIPS, "POWER", 12, "INPUT", 56, "MUTE", 13, "VOLUP", 16, "VOLDOWN", 17,
                "CHUP", 32, "CHDOWN", 33, "UP", 80, "DOWN", 81, "LEFT", 85, "RIGHT", 86,
                "OK", 87, "BACK", 10, "MENU", 82, "HOME", 82,
                "D0", 0, "D1", 1, "D2", 2, "D3", 3, "D4", 4, "D5", 5, "D6", 6, "D7", 7,
                "D8", 8, "D9", 9);

        // Panasonic (protocolo Kaseikyo, 0x40040100 + 16 bits)
        put(PANASONIC, "POWER", 0xBCBD, "INPUT", 0xA0A1, "MUTE", 0x4C4D, "VOLUP", 0x0405,
                "VOLDOWN", 0x8485, "CHUP", 0x2C2D, "CHDOWN", 0xACAD, "UP", 0x5253,
                "DOWN", 0xD2D3, "LEFT", 0x7273, "RIGHT", 0xF2F3, "OK", 0x9293,
                "BACK", 0x2B2A, "EXIT", 0xCBCA, "MENU", 0x4A4B,
                "D1", 0x0809, "D2", 0x8889, "D3", 0x4849, "D4", 0xC8C9, "D5", 0x2829,
                "D6", 0xA8A9, "D7", 0x6869, "D8", 0xE8E9, "D9", 0x1819, "D0", 0x9899);
    }

    static {
        // Xiaomi Mi TV / Mi Box (protocolo propio Xiaomi, dispositivo 0x86; encender usa 0x3C)
        put(XIAOMI, "INPUT", 0x01, "MENU", 0x04, "UP", 0x05, "DOWN", 0x06, "BACK", 0x07, "EXIT", 0x07,
                "HOME", 0x08, "LEFT", 0x0B, "RIGHT", 0x0C, "OK", 0x0D, "VOLUP", 0x0E, "VOLDOWN", 0x0F,
                "MUTE", 0xA1, "D1", 0xC1, "D2", 0xC2, "D3", 0xC3, "D4", 0xC4, "D5", 0xC5, "D6", 0xC6,
                "D7", 0xC7, "D8", 0xC8, "D9", 0xC9, "D0", 0xCA);
    }

    private static void put(Map<String, Integer> m, Object... kv) {
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], (Integer) kv[i + 1]);
    }

    /** Devuelve la señal para una tecla, o null si la marca no la tiene. */
    static Signal get(int brand, String key) {
        Integer c;
        switch (brand) {
            case 0: c = SAMSUNG.get(key); return c == null ? null : samsung(0x07, c);
            case 1: c = LG.get(key); return c == null ? null : nec(0x04, c);
            case 2: c = SONY.get(key); return c == null ? null : sony(1, c);
            case 3: c = PHILIPS.get(key); return c == null ? null : rc5(0, c);
            case 4: c = PANASONIC.get(key); return c == null ? null : panasonic(c);
            case 5:
                if ("POWER".equals(key)) return xiaomi(0x3C, 0xCC);
                c = XIAOMI.get(key); return c == null ? null : xiaomi(0x86, c);
            default: return null;
        }
    }

    // ---------- Codificadores de protocolo (tiempos en microsegundos) ----------

    private static int[] arr(List<Integer> l) {
        int[] a = new int[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }

    private static Signal nec(int addr, int cmd) {
        List<Integer> p = new ArrayList<>();
        p.add(9000); p.add(4500);
        int[] bytes = {addr & 0xFF, ~addr & 0xFF, cmd & 0xFF, ~cmd & 0xFF};
        for (int b : bytes) {
            for (int i = 0; i < 8; i++) { p.add(560); p.add(((b >> i) & 1) == 1 ? 1690 : 560); }
        }
        p.add(560);
        return new Signal(38000, arr(p));
    }

    private static Signal samsung(int addr, int cmd) {
        List<Integer> p = new ArrayList<>();
        p.add(4500); p.add(4500);
        int[] bytes = {addr & 0xFF, addr & 0xFF, cmd & 0xFF, ~cmd & 0xFF};
        for (int b : bytes) {
            for (int i = 0; i < 8; i++) { p.add(560); p.add(((b >> i) & 1) == 1 ? 1690 : 560); }
        }
        p.add(560);
        return new Signal(38000, arr(p));
    }

    private static Signal sony(int addr, int cmd) {
        List<Integer> frame = new ArrayList<>();
        frame.add(2400); frame.add(600);
        int data = (cmd & 0x7F) | ((addr & 0x1F) << 7);
        for (int i = 0; i < 12; i++) { frame.add(((data >> i) & 1) == 1 ? 1200 : 600); frame.add(600); }
        frame.remove(frame.size() - 1); // la trama acaba en pulso
        int dur = 0;
        for (int v : frame) dur += v;
        List<Integer> p = new ArrayList<>();
        for (int r = 0; r < 3; r++) { // Sony exige repetir la trama 3 veces
            p.addAll(frame);
            if (r < 2) p.add(Math.max(600, 45000 - dur));
        }
        return new Signal(40000, arr(p));
    }

    private static int rc5Toggle = 0;

    private static Signal rc5(int addr, int cmd) {
        rc5Toggle ^= 1;
        int s2 = (cmd & 0x40) == 0 ? 1 : 0;
        int bits = (1 << 13) | (s2 << 12) | (rc5Toggle << 11) | ((addr & 0x1F) << 6) | (cmd & 0x3F);
        List<Integer> frame = new ArrayList<>();
        boolean started = false, cur = true;
        int dur = 0;
        for (int i = 13; i >= 0; i--) {
            boolean one = ((bits >> i) & 1) == 1;
            boolean[] halves = one ? new boolean[]{false, true} : new boolean[]{true, false};
            for (boolean mark : halves) {
                if (!started) {
                    if (!mark) continue;
                    started = true; cur = true; dur = 0;
                }
                if (mark == cur) dur += 889;
                else { frame.add(dur); cur = mark; dur = 889; }
            }
        }
        if (cur) frame.add(dur); // si acaba en silencio se descarta
        List<Integer> p = new ArrayList<>(frame);
        p.add(90000);
        p.addAll(frame);
        return new Signal(36000, arr(p));
    }

    private static Signal panasonic(int lo16) {
        long code = 0x400401000000L | (lo16 & 0xFFFFL);
        List<Integer> p = new ArrayList<>();
        p.add(3456); p.add(1728);
        for (int i = 47; i >= 0; i--) { p.add(432); p.add(((code >> i) & 1) == 1 ? 1296 : 432); }
        p.add(432);
        return new Signal(37000, arr(p));
    }

    /** Protocolo Xiaomi: {36k,290,msb}<2,-2|2,-3|2,-4|2,-5>(1000u,-2,D:8,F:8,C:4,2,^30m) */
    static Signal xiaomi(int dev, int fn) {
        int c = ((dev >> 4) ^ dev ^ (fn >> 4) ^ fn) & 0xF;
        int data = ((dev & 0xFF) << 12) | ((fn & 0xFF) << 4) | c;
        List<Integer> frame = new ArrayList<>();
        frame.add(1000); frame.add(580);
        for (int i = 9; i >= 0; i--) {
            int sym = (data >> (2 * i)) & 0x3;
            frame.add(580); frame.add((2 + sym) * 290);
        }
        frame.add(580);
        int dur = 0;
        for (int v : frame) dur += v;
        List<Integer> p = new ArrayList<>(frame);
        p.add(Math.max(580, 30000 - dur));
        p.addAll(frame);
        return new Signal(36000, arr(p));
    }

    private IrCodes() {}
}
