package com.fserrano.mandotv;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/** Control de Smart TV por wifi: Samsung (Tizen), LG (webOS), Sony Bravia y Roku. */
final class SmartTv {

    static final String[] TYPES = {"Samsung Smart TV", "LG Smart TV (webOS)", "Sony Bravia", "Roku / TCL Roku",
            "Xiaomi / Android TV / Google TV"};

    interface Listener {
        void onStatus(String msg);
        String askPairingCode();
        void onSaveKey(int type, String key);
    }

    private final Listener listener;
    private final OkHttpClient wsClient;
    private final OkHttpClient httpClient;

    private int type = -1;
    private String ip = "";
    private String psk = "";
    private String savedKey = "";

    // Samsung
    private WebSocket samsungWs;
    private boolean samsungReady;
    private boolean samsungTriedPlain;

    // LG
    private WebSocket lgWs;
    private WebSocket lgPointer;
    private boolean lgRegistered;
    private boolean lgTriedSecure;
    private String lgBase = "";
    private int lgId = 0;

    private final List<String> pending = new ArrayList<>();

    private final AndroidTvRemote atv;

    SmartTv(Listener listener, java.io.File dir) {
        this.listener = listener;
        this.atv = new AndroidTvRemote(dir, new AndroidTvRemote.Ui() {
            @Override public void status(String msg) { listener.onStatus(msg); }
            @Override public String askPairingCode() { return listener.askPairingCode(); }
        });
        OkHttpClient base;
        try {
            final X509TrustManager tm = new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] c, String a) {}
                @Override public void checkServerTrusted(X509Certificate[] c, String a) {}
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            };
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{tm}, new SecureRandom());
            // Las teles usan certificados propios en la red de casa, por eso se aceptan.
            base = new OkHttpClient.Builder()
                    .sslSocketFactory(ctx.getSocketFactory(), tm)
                    .hostnameVerifier((h, s) -> true)
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .build();
        } catch (Exception e) {
            base = new OkHttpClient();
        }
        wsClient = base.newBuilder().readTimeout(0, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS).build();
        httpClient = base.newBuilder().readTimeout(5, TimeUnit.SECONDS).build();
    }

    synchronized void configure(int type, String ip, String psk, String savedKey) {
        boolean changed = type != this.type || !ip.equals(this.ip);
        this.type = type;
        this.ip = ip.trim();
        this.psk = psk == null ? "" : psk.trim();
        this.savedKey = savedKey == null ? "" : savedKey;
        if (changed) close();
        atv.setIp(type == 4 ? this.ip : "");
    }

    synchronized void close() {
        if (samsungWs != null) samsungWs.close(1000, null);
        if (lgWs != null) lgWs.close(1000, null);
        if (lgPointer != null) lgPointer.close(1000, null);
        samsungWs = null; lgWs = null; lgPointer = null;
        samsungReady = false; lgRegistered = false;
        samsungTriedPlain = false; lgTriedSecure = false;
        pending.clear();
        atv.close();
    }

    /** Envía una tecla. Llamar desde un hilo secundario. */
    void send(String code) {
        if (ip.isEmpty()) { listener.onStatus("Configura primero la IP de la tele"); return; }
        try {
            switch (type) {
                case 0: samsung(code); break;
                case 1: lg(code); break;
                case 2: sony(code); break;
                case 3: roku(code); break;
                case 4: atv.send(code); break;
                default: listener.onStatus("Elige el tipo de Smart TV");
            }
        } catch (Exception e) {
            listener.onStatus("Error: " + e.getMessage());
        }
    }

    // ===================== APPS Y TEXTO =====================

    /** Abre YouTube, Netflix, Prime Video o Disney+. Llamar desde un hilo secundario. */
    void launch(String app) {
        if (ip.isEmpty()) { listener.onStatus("Configura primero la IP de la tele"); return; }
        try {
            switch (type) {
                case 0: {
                    String id = pick(app, "111299001912", "3201907018807", "3201910019365", "3201901017640");
                    JSONObject data = new JSONObject().put("appId", id).put("action_type", "DEEP_LINK");
                    JSONObject params = new JSONObject().put("event", "ed.apps.launch").put("to", "host").put("data", data);
                    samsungRaw(new JSONObject().put("method", "ms.channel.emit").put("params", params).toString());
                    break;
                }
                case 1: {
                    String id = pick(app, "youtube.leanback.v4", "netflix", "amazon", "com.disney.disneyplus-prod");
                    if (!lgReady()) return;
                    synchronized (this) { lgRequest("ssap://system.launcher/launch", new JSONObject().put("id", id)); }
                    break;
                }
                case 3: {
                    String id = pick(app, "837", "12", "13", "291097");
                    rokuPost("/launch/" + id);
                    break;
                }
                case 4:
                    atv.launch(app);
                    break;
                default:
                    listener.onStatus("Abrir apps no está disponible en " + TYPES[type]);
            }
        } catch (Exception e) {
            listener.onStatus("Error: " + e.getMessage());
        }
    }

    private static String pick(String app, String yt, String nf, String prime, String disney) {
        switch (app) {
            case "APP_YOUTUBE": return yt;
            case "APP_NETFLIX": return nf;
            case "APP_PRIME": return prime;
            default: return disney;
        }
    }

    /** Escribe texto en el buscador abierto en la tele. Llamar desde un hilo secundario. */
    void sendText(String text, boolean enter) {
        if (ip.isEmpty()) { listener.onStatus("Configura primero la IP de la tele"); return; }
        try {
            switch (type) {
                case 0: {
                    if (!text.isEmpty()) {
                        String b64 = Base64.encodeToString(text.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                        JSONObject p = new JSONObject().put("Cmd", b64).put("DataOfCmd", "base64")
                                .put("TypeOfRemote", "SendInputString");
                        samsungRaw(new JSONObject().put("method", "ms.remote.control").put("params", p).toString());
                    }
                    if (enter) samsungRaw(samsungMsg("KEY_ENTER"));
                    break;
                }
                case 1: {
                    if (!lgReady()) return;
                    synchronized (this) {
                        if (!text.isEmpty()) lgRequest("ssap://com.webos.service.ime/insertText",
                                new JSONObject().put("text", text).put("replace", 0));
                        if (enter) lgRequest("ssap://com.webos.service.ime/sendEnterKey", null);
                    }
                    break;
                }
                case 2: {
                    if (!text.isEmpty()) {
                        String body = new JSONObject().put("method", "setTextForm").put("id", 601)
                                .put("params", new JSONArray().put(text)).put("version", "1.0").toString();
                        Request req = new Request.Builder()
                                .url("http://" + ip + "/sony/appControl")
                                .header("X-Auth-PSK", psk.isEmpty() ? "0000" : psk)
                                .post(RequestBody.create(body, MediaType.parse("application/json")))
                                .build();
                        try (Response r = httpClient.newCall(req).execute()) {
                            if (!r.isSuccessful()) listener.onStatus("La tele Sony no acepta texto ahora (error " + r.code() + ")");
                        }
                    }
                    if (enter) sony("OK");
                    break;
                }
                case 3: {
                    for (int i = 0; i < text.length(); ) {
                        int cp = text.codePointAt(i);
                        String ch = new String(Character.toChars(cp));
                        rokuPost("/keypress/Lit_" + URLEncoder.encode(ch, "UTF-8"));
                        i += Character.charCount(cp);
                    }
                    if (enter) rokuPost("/keypress/Enter");
                    break;
                }
                case 4:
                    atv.text(text, enter);
                    break;
                default:
                    listener.onStatus("Elige el tipo de Smart TV");
            }
        } catch (java.io.IOException e) {
            listener.onStatus("No conecta con la tele. Revisa la IP y que estéis en el mismo wifi");
        } catch (Exception e) {
            listener.onStatus("Error: " + e.getMessage());
        }
    }

    /** Borra una letra en el buscador de la tele. */
    void backspace() {
        if (ip.isEmpty()) return;
        try {
            switch (type) {
                case 1:
                    if (!lgReady()) return;
                    synchronized (this) {
                        lgRequest("ssap://com.webos.service.ime/deleteCharacters", new JSONObject().put("count", 1));
                    }
                    break;
                case 3:
                    rokuPost("/keypress/Backspace");
                    break;
                case 4:
                    atv.key(67);
                    break;
                default:
                    listener.onStatus("Borrar no está disponible en " + TYPES[type] + ". Usa el teclado de la tele");
            }
        } catch (Exception e) {
            listener.onStatus("Error: " + e.getMessage());
        }
    }

    /** true si la LG ya está conectada; si no, empieza a conectar. */
    private synchronized boolean lgReady() {
        if (lgRegistered && lgWs != null) return true;
        if (lgWs == null) lgConnect(false);
        listener.onStatus("Conectando con la tele LG… vuelve a pulsar en un momento");
        return false;
    }

    private void rokuPost(String path) throws java.io.IOException {
        Request req = new Request.Builder()
                .url("http://" + ip + ":8060" + path)
                .post(RequestBody.create(new byte[0], null))
                .build();
        try (Response r = httpClient.newCall(req).execute()) {
            if (!r.isSuccessful()) listener.onStatus("La tele Roku respondió con error " + r.code());
        }
    }

    // ===================== SAMSUNG =====================

    private static final Map<String, String> SAMSUNG = new HashMap<>();
    static {
        String[] k = {"POWER", "KEY_POWER", "INPUT", "KEY_SOURCE", "MUTE", "KEY_MUTE", "VOLUP", "KEY_VOLUP",
                "VOLDOWN", "KEY_VOLDOWN", "CHUP", "KEY_CHUP", "CHDOWN", "KEY_CHDOWN", "UP", "KEY_UP",
                "DOWN", "KEY_DOWN", "LEFT", "KEY_LEFT", "RIGHT", "KEY_RIGHT", "OK", "KEY_ENTER",
                "BACK", "KEY_RETURN", "EXIT", "KEY_EXIT", "HOME", "KEY_HOME", "MENU", "KEY_MENU"};
        for (int i = 0; i < k.length; i += 2) SAMSUNG.put(k[i], k[i + 1]);
        for (int d = 0; d <= 9; d++) SAMSUNG.put("D" + d, "KEY_" + d);
    }

    private synchronized void samsung(String code) {
        String key = SAMSUNG.get(code);
        if (key == null) return;
        samsungRaw(samsungMsg(key));
    }

    private synchronized void samsungRaw(String msg) {
        if (samsungReady && samsungWs != null) {
            samsungWs.send(msg);
            return;
        }
        if (pending.size() < 8) pending.add(msg);
        if (samsungWs == null) samsungConnect(false);
    }

    private String samsungMsg(String key) {
        try {
            JSONObject p = new JSONObject();
            p.put("Cmd", "Click");
            p.put("DataOfCmd", key);
            p.put("Option", "false");
            p.put("TypeOfRemote", "SendRemoteKey");
            JSONObject o = new JSONObject();
            o.put("method", "ms.remote.control");
            o.put("params", p);
            return o.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void samsungConnect(boolean plain) {
        String name = Base64.encodeToString("Mando TV".getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        String url = plain
                ? "ws://" + ip + ":8001/api/v2/channels/samsung.remote.control?name=" + name
                : "wss://" + ip + ":8002/api/v2/channels/samsung.remote.control?name=" + name
                  + (savedKey.isEmpty() ? "" : "&token=" + savedKey);
        listener.onStatus(savedKey.isEmpty() ? "Conectando… acepta el aviso en la tele" : "Conectando con la tele…");
        samsungWs = wsClient.newWebSocket(new Request.Builder().url(url).build(), new WebSocketListener() {
            @Override public void onMessage(WebSocket ws, String text) {
                try {
                    JSONObject o = new JSONObject(text);
                    String ev = o.optString("event");
                    if ("ms.channel.connect".equals(ev)) {
                        JSONObject d = o.optJSONObject("data");
                        String token = d == null ? "" : d.optString("token", "");
                        synchronized (SmartTv.this) {
                            samsungReady = true;
                            if (!token.isEmpty()) { savedKey = token; listener.onSaveKey(0, token); }
                            for (String m : pending) ws.send(m);
                            pending.clear();
                        }
                        listener.onStatus("Conectado a la tele Samsung");
                    } else if ("ms.channel.unauthorized".equals(ev)) {
                        listener.onStatus("La tele ha rechazado la conexión. Permite «Mando TV» en la tele");
                    }
                } catch (Exception ignored) { }
            }

            @Override public void onClosed(WebSocket ws, int code, String reason) {
                synchronized (SmartTv.this) { if (samsungWs == ws) { samsungWs = null; samsungReady = false; } }
            }

            @Override public void onFailure(WebSocket ws, Throwable t, Response r) {
                boolean retry;
                synchronized (SmartTv.this) {
                    if (samsungWs != ws) return;
                    samsungWs = null; samsungReady = false;
                    retry = !samsungTriedPlain;
                    samsungTriedPlain = true;
                    if (retry) samsungConnect(true); // teles antiguas (2016-2017)
                    else pending.clear();
                }
                if (!retry) listener.onStatus("No conecta con la tele. Revisa la IP y que estéis en el mismo wifi");
            }
        });
    }

    // ===================== LG webOS =====================

    private static final Map<String, String> LG_BTN = new HashMap<>();
    static {
        String[] k = {"MUTE", "MUTE", "VOLUP", "VOLUMEUP", "VOLDOWN", "VOLUMEDOWN", "CHUP", "CHANNELUP",
                "CHDOWN", "CHANNELDOWN", "UP", "UP", "DOWN", "DOWN", "LEFT", "LEFT", "RIGHT", "RIGHT",
                "OK", "ENTER", "BACK", "BACK", "EXIT", "EXIT", "HOME", "HOME", "MENU", "MENU"};
        for (int i = 0; i < k.length; i += 2) LG_BTN.put(k[i], k[i + 1]);
        for (int d = 0; d <= 9; d++) LG_BTN.put("D" + d, String.valueOf(d));
    }

    private synchronized void lg(String code) {
        if (lgRegistered && lgWs != null) {
            if ("POWER".equals(code)) { lgRequest("ssap://system/turnOff", null); return; }
            if ("INPUT".equals(code)) {
                try { lgRequest("ssap://system.launcher/launch", new JSONObject().put("id", "com.webos.app.inputpicker")); }
                catch (Exception ignored) { }
                return;
            }
            String b = LG_BTN.get(code);
            if (b == null) return;
            if (lgPointer != null) { lgPointer.send("type:button\nname:" + b + "\n\n"); return; }
            if (pending.size() < 5) pending.add(code);
            return;
        }
        if (pending.size() < 5) pending.add(code);
        if (lgWs == null) lgConnect(false);
    }

    private void lgRequest(String uri, JSONObject payload) {
        try {
            JSONObject o = new JSONObject();
            o.put("type", "request");
            o.put("id", "req" + (++lgId));
            o.put("uri", uri);
            if (payload != null) o.put("payload", payload);
            lgWs.send(o.toString());
        } catch (Exception ignored) { }
    }

    private void lgConnect(boolean secure) {
        lgBase = secure ? "wss://" + ip + ":3001/" : "ws://" + ip + ":3000/";
        listener.onStatus(savedKey.isEmpty() ? "Conectando… acepta el aviso en la tele" : "Conectando con la tele…");
        lgWs = wsClient.newWebSocket(new Request.Builder().url(lgBase).build(), new WebSocketListener() {
            @Override public void onOpen(WebSocket ws, Response r) {
                try {
                    String[] perms = {"LAUNCH", "LAUNCH_WEBAPP", "APP_TO_APP", "CONTROL_AUDIO", "CONTROL_DISPLAY",
                            "CONTROL_INPUT_JOYSTICK", "CONTROL_INPUT_MEDIA_PLAYBACK", "CONTROL_INPUT_TV",
                            "CONTROL_POWER", "CONTROL_MOUSE_AND_KEYBOARD", "READ_INSTALLED_APPS",
                            "READ_INPUT_DEVICE_LIST", "READ_CURRENT_CHANNEL", "READ_POWER_STATE",
                            "WRITE_NOTIFICATION_TOAST"};
                    JSONObject manifest = new JSONObject();
                    manifest.put("manifestVersion", 1);
                    manifest.put("appVersion", "1.0");
                    manifest.put("permissions", new JSONArray(perms));
                    JSONObject payload = new JSONObject();
                    payload.put("forcePairing", false);
                    payload.put("pairingType", "PROMPT");
                    if (!savedKey.isEmpty()) payload.put("client-key", savedKey);
                    payload.put("manifest", manifest);
                    JSONObject o = new JSONObject();
                    o.put("type", "register");
                    o.put("id", "register0");
                    o.put("payload", payload);
                    ws.send(o.toString());
                } catch (Exception ignored) { }
            }

            @Override public void onMessage(WebSocket ws, String text) {
                try {
                    JSONObject o = new JSONObject(text);
                    String t = o.optString("type");
                    JSONObject p = o.optJSONObject("payload");
                    if ("registered".equals(t)) {
                        String key = p == null ? "" : p.optString("client-key", "");
                        synchronized (SmartTv.this) {
                            lgRegistered = true;
                            if (!key.isEmpty()) { savedKey = key; listener.onSaveKey(1, key); }
                            JSONObject req = new JSONObject();
                            req.put("type", "request");
                            req.put("id", "pointer");
                            req.put("uri", "ssap://com.webos.service.networkinput/getPointerInputSocket");
                            ws.send(req.toString());
                        }
                        listener.onStatus("Conectado a la tele LG");
                    } else if ("pointer".equals(o.optString("id")) && p != null) {
                        String path = p.optString("socketPath", "");
                        if (!path.isEmpty()) lgOpenPointer(path);
                    } else if ("error".equals(t)) {
                        listener.onStatus("La tele LG dice: " + o.optString("error"));
                    }
                } catch (Exception ignored) { }
            }

            @Override public void onClosed(WebSocket ws, int code, String reason) {
                synchronized (SmartTv.this) {
                    if (lgWs == ws) {
                        lgWs = null; lgRegistered = false;
                        if (lgPointer != null) lgPointer.close(1000, null);
                        lgPointer = null;
                    }
                }
            }

            @Override public void onFailure(WebSocket ws, Throwable t, Response r) {
                boolean retry;
                synchronized (SmartTv.this) {
                    if (lgWs != ws) return;
                    lgWs = null; lgRegistered = false;
                    if (lgPointer != null) lgPointer.close(1000, null);
                    lgPointer = null;
                    retry = !lgTriedSecure;
                    lgTriedSecure = true;
                    if (retry) lgConnect(true); // firmware nuevo: puerto seguro 3001
                    else pending.clear();
                }
                if (!retry) listener.onStatus("No conecta con la tele. Revisa la IP y que estéis en el mismo wifi");
            }
        });
    }

    private void lgOpenPointer(String path) {
        synchronized (this) {
            lgPointer = wsClient.newWebSocket(new Request.Builder().url(path).build(), new WebSocketListener() {
                @Override public void onOpen(WebSocket ws, Response r) {
                    synchronized (SmartTv.this) {
                        List<String> copy = new ArrayList<>(pending);
                        pending.clear();
                        for (String c : copy) {
                            String b = LG_BTN.get(c);
                            if (b != null) ws.send("type:button\nname:" + b + "\n\n");
                        }
                    }
                }

                @Override public void onFailure(WebSocket ws, Throwable t, Response r) {
                    synchronized (SmartTv.this) { if (lgPointer == ws) lgPointer = null; }
                }

                @Override public void onClosed(WebSocket ws, int code, String reason) {
                    synchronized (SmartTv.this) { if (lgPointer == ws) lgPointer = null; }
                }
            });
        }
    }

    // ===================== SONY BRAVIA (IRCC) =====================

    private static final Map<String, Integer> SONY = new HashMap<>();
    static {
        Object[] k = {"POWER", 21, "INPUT", 37, "MUTE", 20, "VOLUP", 18, "VOLDOWN", 19, "CHUP", 16,
                "CHDOWN", 17, "UP", 116, "DOWN", 117, "LEFT", 52, "RIGHT", 51, "OK", 101, "EXIT", 99,
                "HOME", 96, "MENU", 96, "D1", 0, "D2", 1, "D3", 2, "D4", 3, "D5", 4, "D6", 5, "D7", 6,
                "D8", 7, "D9", 8, "D0", 9};
        for (int i = 0; i < k.length; i += 2) SONY.put((String) k[i], (Integer) k[i + 1]);
    }

    private void sony(String code) throws Exception {
        String ircc;
        if ("BACK".equals(code)) {
            ircc = "AAAAAgAAAJcAAAAjAw==";
        } else {
            Integer c = SONY.get(code);
            if (c == null) return;
            byte[] b = {0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, (byte) (int) c, 3};
            ircc = Base64.encodeToString(b, Base64.NO_WRAP);
        }
        String body = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
                + "<u:X_SendIRCC xmlns:u=\"urn:schemas-sony-com:service:IRCC:1\"><IRCCCode>" + ircc
                + "</IRCCCode></u:X_SendIRCC></s:Body></s:Envelope>";
        Request req = new Request.Builder()
                .url("http://" + ip + "/sony/IRCC")
                .header("SOAPACTION", "\"urn:schemas-sony-com:service:IRCC:1#X_SendIRCC\"")
                .header("X-Auth-PSK", psk.isEmpty() ? "0000" : psk)
                .post(RequestBody.create(body, MediaType.parse("text/xml; charset=UTF-8")))
                .build();
        try (Response r = httpClient.newCall(req).execute()) {
            if (r.code() == 403 || r.code() == 401) listener.onStatus("La tele Sony pide la clave (PSK). Revísala en Configurar");
            else if (!r.isSuccessful()) listener.onStatus("La tele Sony respondió con error " + r.code());
        } catch (java.io.IOException e) {
            listener.onStatus("No conecta con la tele. Revisa la IP y que estéis en el mismo wifi");
        }
    }

    // ===================== ROKU =====================

    private static final Map<String, String> ROKU = new HashMap<>();
    static {
        String[] k = {"POWER", "Power", "MUTE", "VolumeMute", "VOLUP", "VolumeUp", "VOLDOWN", "VolumeDown",
                "CHUP", "ChannelUp", "CHDOWN", "ChannelDown", "UP", "Up", "DOWN", "Down", "LEFT", "Left",
                "RIGHT", "Right", "OK", "Select", "BACK", "Back", "HOME", "Home", "EXIT", "Home",
                "MENU", "Info", "INPUT", "InputTuner"};
        for (int i = 0; i < k.length; i += 2) ROKU.put(k[i], k[i + 1]);
        for (int d = 0; d <= 9; d++) ROKU.put("D" + d, "Lit_" + d);
    }

    private void roku(String code) {
        String k = ROKU.get(code);
        if (k == null) return;
        Request req = new Request.Builder()
                .url("http://" + ip + ":8060/keypress/" + k)
                .post(RequestBody.create(new byte[0], null))
                .build();
        try (Response r = httpClient.newCall(req).execute()) {
            if (!r.isSuccessful()) listener.onStatus("La tele Roku respondió con error " + r.code());
        } catch (java.io.IOException e) {
            listener.onStatus("No conecta con la tele. Revisa la IP y que estéis en el mismo wifi");
        }
    }
}
