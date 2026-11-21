package com.fserrano.mandotv;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * El móvil se hace pasar por un mando/teclado Bluetooth.
 * Sirve para cualquier stick o caja Android que acepte mandos Bluetooth.
 */
@TargetApi(28)
@SuppressLint("MissingPermission")
final class BtHid {

    interface Listener {
        void onStatus(String msg);
        void onConnected(String name, String address);
    }

    // Descriptor HID: teclado (informe 1) + teclas multimedia (informe 2)
    private static final byte[] DESCRIPTOR = {
            0x05, 0x01, 0x09, 0x06, (byte) 0xA1, 0x01, (byte) 0x85, 0x01,
            0x05, 0x07, 0x19, (byte) 0xE0, 0x29, (byte) 0xE7, 0x15, 0x00, 0x25, 0x01,
            0x75, 0x01, (byte) 0x95, 0x08, (byte) 0x81, 0x02,
            (byte) 0x95, 0x01, 0x75, 0x08, (byte) 0x81, 0x01,
            (byte) 0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65,
            0x05, 0x07, 0x19, 0x00, 0x29, 0x65, (byte) 0x81, 0x00,
            (byte) 0xC0,
            0x05, 0x0C, 0x09, 0x01, (byte) 0xA1, 0x01, (byte) 0x85, 0x02,
            0x15, 0x00, 0x26, (byte) 0xFF, 0x03, 0x19, 0x00, 0x2A, (byte) 0xFF, 0x03,
            0x75, 0x10, (byte) 0x95, 0x01, (byte) 0x81, 0x00,
            (byte) 0xC0
    };

    private static final Map<String, Integer> KEYBOARD = new HashMap<>();
    private static final Map<String, Integer> CONSUMER = new HashMap<>();
    static {
        KEYBOARD.put("UP", 0x52); KEYBOARD.put("DOWN", 0x51);
        KEYBOARD.put("LEFT", 0x50); KEYBOARD.put("RIGHT", 0x4F);
        KEYBOARD.put("OK", 0x28);
        for (int d = 1; d <= 9; d++) KEYBOARD.put("D" + d, 0x1E + d - 1);
        KEYBOARD.put("D0", 0x27);

        CONSUMER.put("VOLUP", 0xE9); CONSUMER.put("VOLDOWN", 0xEA); CONSUMER.put("MUTE", 0xE2);
        CONSUMER.put("HOME", 0x223); CONSUMER.put("BACK", 0x224); CONSUMER.put("EXIT", 0x224);
        CONSUMER.put("MENU", 0x40); CONSUMER.put("POWER", 0x30);
        CONSUMER.put("CHUP", 0x9C); CONSUMER.put("CHDOWN", 0x9D);
        CONSUMER.put("INPUT", 0x223);
    }

    private final Context ctx;
    private final Listener listener;
    private final Executor exec = Executors.newSingleThreadExecutor();
    private BluetoothAdapter adapter;
    private volatile BluetoothHidDevice hid;
    private volatile BluetoothDevice host;
    private volatile boolean registered;
    private volatile String wantAddress = "";
    private boolean started;

    BtHid(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
    }

    void setSavedAddress(String a) { wantAddress = a == null ? "" : a; }

    boolean isConnected() { return host != null; }

    BluetoothAdapter adapter() {
        if (adapter == null) {
            BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            adapter = bm == null ? null : bm.getAdapter();
        }
        return adapter;
    }

    void start() {
        try {
            BluetoothAdapter a = adapter();
            if (a == null) { listener.onStatus("Este móvil no tiene Bluetooth"); return; }
            if (!a.isEnabled()) { listener.onStatus("Activa el Bluetooth del móvil"); return; }
            if (started) { if (registered) connectSaved(); return; }
            started = true;
            boolean ok = a.getProfileProxy(ctx, new BluetoothProfile.ServiceListener() {
                @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    hid = (BluetoothHidDevice) proxy;
                    register();
                }
                @Override public void onServiceDisconnected(int profile) {
                    hid = null; registered = false; host = null;
                }
            }, BluetoothProfile.HID_DEVICE);
            if (!ok) {
                started = false;
                listener.onStatus("Tu móvil no permite hacer de mando Bluetooth");
            }
        } catch (SecurityException e) {
            started = false;
            listener.onStatus("Falta el permiso de Bluetooth (Dispositivos cercanos)");
        }
    }

    private void register() {
        try {
            BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                    "Mando TV", "Mando a distancia", "Mando TV",
                    BluetoothHidDevice.SUBCLASS1_COMBO, DESCRIPTOR);
            hid.registerApp(sdp, null, null, exec, new BluetoothHidDevice.Callback() {
                @Override public void onAppStatusChanged(BluetoothDevice device, boolean reg) {
                    registered = reg;
                    if (reg) connectSaved();
                }

                @Override public void onConnectionStateChanged(BluetoothDevice device, int state) {
                    if (state == BluetoothProfile.STATE_CONNECTED) {
                        host = device;
                        wantAddress = device.getAddress();
                        listener.onConnected(name(device), device.getAddress());
                    } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                        if (host != null && host.equals(device)) {
                            host = null;
                            listener.onStatus("Stick desconectado");
                        }
                    }
                }
            });
        } catch (Exception e) {
            listener.onStatus("No se pudo activar el mando Bluetooth: " + e.getMessage());
        }
    }

    private void connectSaved() {
        if (hid == null || !registered || wantAddress.isEmpty() || host != null) return;
        try {
            BluetoothDevice d = adapter().getRemoteDevice(wantAddress);
            hid.connect(d);
        } catch (Exception ignored) { }
    }

    /** Dispositivos ya emparejados con el móvil. */
    List<BluetoothDevice> bonded() {
        List<BluetoothDevice> out = new ArrayList<>();
        try {
            BluetoothAdapter a = adapter();
            if (a != null) out.addAll(a.getBondedDevices());
        } catch (SecurityException ignored) { }
        return out;
    }

    static String name(BluetoothDevice d) {
        try {
            String n = d.getName();
            return n == null || n.isEmpty() ? d.getAddress() : n;
        } catch (SecurityException e) {
            return d.getAddress();
        }
    }

    void connect(BluetoothDevice d) {
        wantAddress = d.getAddress();
        if (hid == null || !registered) { start(); listener.onStatus("Preparando el Bluetooth… vuelve a intentarlo en unos segundos"); return; }
        try {
            if (host != null && !host.equals(d)) hid.disconnect(host);
            listener.onStatus("Conectando con " + name(d) + "…");
            hid.connect(d);
        } catch (Exception e) {
            listener.onStatus("No se pudo conectar: " + e.getMessage());
        }
    }

    /** Envía una tecla (pulsar y soltar). Llamar desde un hilo secundario. */
    void send(String code) {
        BluetoothDevice h = host;
        if (hid == null || h == null) {
            if (!wantAddress.isEmpty()) { start(); connectSaved(); listener.onStatus("Conectando con el stick…"); }
            else listener.onStatus("Primero empareja el stick (botón de arriba)");
            return;
        }
        try {
            Integer k = KEYBOARD.get(code);
            if (k != null) {
                hid.sendReport(h, 1, new byte[]{0, 0, (byte) (int) k, 0, 0, 0, 0, 0});
                Thread.sleep(25);
                hid.sendReport(h, 1, new byte[8]);
                return;
            }
            Integer c = CONSUMER.get(code);
            if (c != null) {
                hid.sendReport(h, 2, new byte[]{(byte) (c & 0xFF), (byte) ((c >> 8) & 0xFF)});
                Thread.sleep(25);
                hid.sendReport(h, 2, new byte[2]);
            }
        } catch (Exception e) {
            listener.onStatus("Error Bluetooth: " + e.getMessage());
        }
    }

    /** Cambia a otro stick guardado. */
    void switchTo(String address) {
        String a = address == null ? "" : address;
        BluetoothDevice h = host;
        if (h != null && h.getAddress().equals(a)) return;
        try { if (h != null && hid != null) hid.disconnect(h); } catch (Exception ignored) { }
        host = null;
        wantAddress = a;
        connectSaved();
    }

    // ---------- Escribir texto como un teclado (distribución estándar) ----------

    private static int[] usageFor(char c) {
        if (c >= 'a' && c <= 'z') return new int[]{0, 0x04 + (c - 'a')};
        if (c >= 'A' && c <= 'Z') return new int[]{0x02, 0x04 + (c - 'A')};
        if (c >= '1' && c <= '9') return new int[]{0, 0x1E + (c - '1')};
        switch (c) {
            case '0': return new int[]{0, 0x27};
            case ' ': return new int[]{0, 0x2C};
            case '-': return new int[]{0, 0x2D};
            case '_': return new int[]{0x02, 0x2D};
            case '.': return new int[]{0, 0x37};
            case ',': return new int[]{0, 0x36};
            case '/': return new int[]{0, 0x38};
            case '?': return new int[]{0x02, 0x38};
            case '!': return new int[]{0x02, 0x1E};
            case '@': return new int[]{0x02, 0x1F};
            case '&': return new int[]{0x02, 0x24};
            case '(': return new int[]{0x02, 0x26};
            case ')': return new int[]{0x02, 0x27};
            case ':': return new int[]{0x02, 0x33};
            case ';': return new int[]{0, 0x33};
            case '\'': return new int[]{0, 0x34};
            case '"': return new int[]{0x02, 0x34};
            default: return null;
        }
    }

    private boolean keyTap(BluetoothDevice h, int mod, int usage) throws InterruptedException {
        boolean ok = hid.sendReport(h, 1, new byte[]{(byte) mod, 0, (byte) usage, 0, 0, 0, 0, 0});
        Thread.sleep(18);
        hid.sendReport(h, 1, new byte[8]);
        Thread.sleep(12);
        return ok;
    }

    void typeText(String text, boolean enter) {
        BluetoothDevice h = host;
        if (hid == null || h == null) { send("OK_DUMMY"); return; }
        // Quita tildes (á → a, ñ → n) para que salga bien en cualquier stick
        String plain = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        try {
            for (int i = 0; i < plain.length(); i++) {
                int[] u = usageFor(plain.charAt(i));
                if (u != null) keyTap(h, u[0], u[1]);
            }
            if (enter) keyTap(h, 0, 0x28);
        } catch (Exception e) {
            listener.onStatus("Error Bluetooth: " + e.getMessage());
        }
    }

    void backspace() {
        BluetoothDevice h = host;
        if (hid == null || h == null) { send("OK_DUMMY"); return; }
        try { keyTap(h, 0, 0x2A); } catch (Exception e) { listener.onStatus("Error Bluetooth: " + e.getMessage()); }
    }

    void stop() {
        try {
            if (hid != null) {
                if (host != null) hid.disconnect(host);
                hid.unregisterApp();
                adapter().closeProfileProxy(BluetoothProfile.HID_DEVICE, hid);
            }
        } catch (Exception ignored) { }
        hid = null; host = null; registered = false; started = false;
    }
}
