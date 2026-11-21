package com.fserrano.mandotv;

import android.app.Activity;
import android.app.AlertDialog;
import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.hardware.ConsumerIrManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.text.InputType;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.graphics.drawable.Drawable;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int BG = 0xFF101114;
    private static final int PANEL = 0xFF1A1C22;
    private static final int BTN = 0xFF2A2D35;
    private static final int TXT = 0xFFECECEC;
    private static final int SUB = 0xFF9A9DA6;
    private static final int RED = 0xFFE53935;
    private static final int BLUE = 0xFF3D7BFD;

    private ConsumerIrManager ir;
    private SharedPreferences prefs;
    private int brand;
    private TextView brandBtn;
    private TextView modeIr, modeSmart, modeBt;
    private int mode;          // 0 = infrarrojos, 1 = Smart TV por wifi, 2 = stick Bluetooth
    private BtHid bt;
    private String btName = "";
    private String btAddr = "";
    private JSONArray tvs;
    private int currentTv;
    private TextView tvBtn;
    private int smartType;
    private String smartIp, smartPsk;
    private SmartTv smart;
    private float dp;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService sender = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dp = getResources().getDisplayMetrics().density;
        ir = (ConsumerIrManager) getSystemService(CONSUMER_IR_SERVICE);
        prefs = getSharedPreferences("mando", MODE_PRIVATE);
        loadTvs();
        smart = new SmartTv(new SmartTv.Listener() {
            @Override public void onStatus(String msg) { handler.post(() -> toast(msg)); }
            @Override public void onSaveKey(int type, String key) {
                prefs.edit().putString("smartKey_" + type + "_" + smartIp, key).apply();
            }
        });
        applySmartConfig();
        if (Build.VERSION.SDK_INT >= 28) {
            bt = new BtHid(this, new BtHid.Listener() {
                @Override public void onStatus(String msg) { handler.post(() -> toast(msg)); }
                @Override public void onConnected(String name, String address) {
                    handler.post(() -> {
                        btName = name; btAddr = address; persist();
                        updateBrandLabel(); toast("Conectado a " + name);
                    });
                }
            });
            bt.setSavedAddress(btAddr);
        }
        if (mode == 2) ensureBt(false);

        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        setContentView(buildUi());

        if (mode == 0 && (ir == null || !ir.hasIrEmitter())) {
            new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("Sin infrarrojos")
                    .setMessage("Este móvil no tiene emisor de infrarrojos. Puedes usar el modo «Smart TV (wifi)».")
                    .setPositiveButton("Vale", null)
                    .show();
        } else if (mode == 0 && !brandChosen) {
            chooseBrand();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        sender.execute(() -> smart.close());
        if (bt != null) bt.stop();
        sender.shutdown();
    }

    // ---------------- Interfaz ----------------

    private View buildUi() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.setFillViewport(true);

        LinearLayout root = vbox();
        root.setPadding(px(18), px(22), px(18), px(28));
        sv.addView(root, new ViewGroup.LayoutParams(-1, -2));

        // Cabecera: título + selector de marca
        LinearLayout header = hbox();
        header.setGravity(Gravity.CENTER_VERTICAL);
        tvBtn = label("", 20, TXT, true);
        tvBtn.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        tvBtn.setSingleLine(true);
        tvBtn.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tvBtn.setOnClickListener(v -> chooseTv());
        header.addView(tvBtn, new LinearLayout.LayoutParams(0, -2, 1));
        updateTvLabel();
        brandBtn = label("", 15, TXT, true);
        brandBtn.setPadding(px(16), px(10), px(16), px(10));
        brandBtn.setBackground(bg(BLUE, px(22)));
        brandBtn.setOnClickListener(v -> { if (mode == 0) chooseBrand(); else if (mode == 1) smartSetup(); else btSetup(); });
        header.addView(brandBtn);
        root.addView(header);
        root.addView(gap(14));

        // Selector de modo
        LinearLayout modes = hbox();
        modes.setBackground(bg(PANEL, px(22)));
        modes.setPadding(px(4), px(4), px(4), px(4));
        modeIr = label("Infrarrojos", 13, TXT, true);
        modeSmart = label("Smart TV", 13, TXT, true);
        modeBt = label("Stick BT", 13, TXT, true);
        modeIr.setOnClickListener(v -> setMode(0));
        modeSmart.setOnClickListener(v -> setMode(1));
        modeBt.setOnClickListener(v -> setMode(2));
        modes.addView(modeIr, new LinearLayout.LayoutParams(0, px(42), 1));
        modes.addView(modeSmart, new LinearLayout.LayoutParams(0, px(42), 1));
        modes.addView(modeBt, new LinearLayout.LayoutParams(0, px(42), 1));
        root.addView(modes);

        hintView = label("", 13, SUB, false);
        hintView.setPadding(px(4), px(8), 0, px(16));
        hintView.setGravity(Gravity.START);
        root.addView(hintView);
        updateBrandLabel();

        // Fila superior
        root.addView(row(px(64),
                key("Encender", "POWER", RED, false),
                key("Fuente", "INPUT", BTN, false),
                key("Silencio", "MUTE", BTN, false)));
        root.addView(row(px(56),
                appKey("YouTube", "APP_YOUTUBE", 0xFFC4302B, "com.google.android.youtube"),
                appKey("Netflix", "APP_NETFLIX", 0xFF8E0E13, "com.netflix.mediaclient"),
                appKey("Disney+", "APP_DISNEY", 0xFF1B2A6B, "com.disney.disneyplus")));
        root.addView(gap(16));

        // Cruceta
        LinearLayout pad = vbox();
        pad.setBackground(bg(PANEL, px(28)));
        pad.setPadding(px(14), px(14), px(14), px(14));
        pad.addView(row(px(68), blank(), key("▲", "UP", BTN, true), blank()));
        pad.addView(row(px(68), key("◀", "LEFT", BTN, true), key("OK", "OK", BLUE, false), key("▶", "RIGHT", BTN, true)));
        pad.addView(row(px(68), blank(), key("▼", "DOWN", BTN, true), blank()));
        root.addView(pad);
        root.addView(gap(16));

        // Volumen / centro / canales
        LinearLayout mid = hbox();
        mid.addView(rocker("VOL", "VOLUP", "VOLDOWN"), new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout centre = vbox();
        centre.addView(cell(key("Inicio", "HOME", BTN, false), px(54)));
        centre.addView(cell(key("Menú", "MENU", BTN, false), px(54)));
        centre.addView(cell(key("Atrás", "BACK", BTN, false), px(54)));
        mid.addView(centre, new LinearLayout.LayoutParams(0, -2, 1.2f));
        mid.addView(rocker("CANAL", "CHUP", "CHDOWN"), new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(mid);
        root.addView(gap(16));

        // Teclado numérico
        LinearLayout nums = vbox();
        nums.setBackground(bg(PANEL, px(28)));
        nums.setPadding(px(10), px(10), px(10), px(10));
        nums.addView(row(px(56), key("1", "D1", BTN, false), key("2", "D2", BTN, false), key("3", "D3", BTN, false)));
        nums.addView(row(px(56), key("4", "D4", BTN, false), key("5", "D5", BTN, false), key("6", "D6", BTN, false)));
        nums.addView(row(px(56), key("7", "D7", BTN, false), key("8", "D8", BTN, false), key("9", "D9", BTN, false)));
        nums.addView(row(px(56), key("Salir", "EXIT", BTN, false), key("0", "D0", BTN, false), writeKey()));
        root.addView(nums);

        return sv;
    }

    private View rocker(String name, String up, String down) {
        LinearLayout col = vbox();
        col.setBackground(bg(PANEL, px(28)));
        col.setPadding(px(8), px(8), px(8), px(8));
        col.addView(cell(key("+", up, BTN, true), px(66)));
        TextView t = label(name, 13, SUB, true);
        t.setPadding(0, px(6), 0, px(6));
        col.addView(t);
        col.addView(cell(key("−", down, BTN, true), px(66)));
        LinearLayout wrap = vbox();
        wrap.setPadding(px(4), 0, px(4), 0);
        wrap.addView(col);
        return wrap;
    }

    private View key(String text, String code, int color, boolean repeat) {
        TextView b = label(text, text.length() <= 2 ? 22 : (text.length() > 6 ? 13 : 15), TXT, true);
        b.setBackground(bg(color, px(18)));
        b.setClickable(true);
        b.setHapticFeedbackEnabled(false);
        b.setSoundEffectsEnabled(false);
        if (repeat) {
            b.setOnTouchListener((v, e) -> {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: {
                        v.setPressed(true);
                        press(v, code);
                        Runnable r = new Runnable() {
                            @Override public void run() {
                                send(code);
                                handler.postDelayed(this, 170);
                            }
                        };
                        v.setTag(r);
                        handler.postDelayed(r, 450);
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        v.setPressed(false);
                        Object tag = v.getTag();
                        if (tag instanceof Runnable) handler.removeCallbacks((Runnable) tag);
                        v.setTag(null);
                        return true;
                    }
                    default:
                        return true;
                }
            });
        } else {
            b.setOnClickListener(v -> press(v, code));
        }
        return b;
    }

    private void chooseBrand() {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("¿De qué marca es la tele?")
                .setSingleChoiceItems(IrCodes.BRANDS, brand, (d, which) -> {
                    brand = which;
                    brandChosen = true;
                    persist();
                    updateBrandLabel();
                    d.dismiss();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private TextView hintView;

    private void updateBrandLabel() {
        if (brandBtn == null) return;
        if (mode == 0) {
            brandBtn.setText(IrCodes.BRANDS[brand] + "  ▾");
            hintView.setText("Toca la marca para cambiarla. Apunta el móvil a la tele.");
        } else if (mode == 2) {
            brandBtn.setText((btName.isEmpty() ? "Emparejar" : btName) + "  ▾");
            hintView.setText(btName.isEmpty()
                    ? "Toca «Emparejar» para conectar el móvil a tu stick por Bluetooth."
                    : "Por Bluetooth a " + btName + ". No hace falta apuntar.");
        } else {
            brandBtn.setText((smartType < 0 ? "Configurar" : SmartTv.TYPES[smartType].split(" ")[0]) + "  ▾");
            hintView.setText(smartType < 0 || smartIp.isEmpty()
                    ? "Toca «Configurar» para buscar tu tele en el wifi."
                    : "Por wifi a " + smartIp + ". No hace falta apuntar.");
        }
        modeIr.setBackground(bg(mode == 0 ? BLUE : PANEL, px(18)));
        modeSmart.setBackground(bg(mode == 1 ? BLUE : PANEL, px(18)));
        modeBt.setBackground(bg(mode == 2 ? BLUE : PANEL, px(18)));
    }

    private void setMode(int m) {
        mode = m;
        persist();
        updateBrandLabel();
        if (m == 1 && (smartType < 0 || smartIp.isEmpty())) smartSetup();
        if (m == 2) ensureBt(btName.isEmpty());
    }

    // ---------------- Stick por Bluetooth ----------------

    private boolean openSetupAfterPermission;

    private void ensureBt(boolean openSetup) {
        if (bt == null) { toast("Hace falta Android 9 o superior"); return; }
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            openSetupAfterPermission = openSetup;
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_SCAN}, 7);
            return;
        }
        BluetoothAdapter a = bt.adapter();
        if (a != null && !a.isEnabled()) {
            try { startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); } catch (Exception ignored) { }
            toast("Activa el Bluetooth y vuelve a tocar «Stick BT»");
            return;
        }
        bt.start();
        if (openSetup) btSetup();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != 7) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) ensureBt(openSetupAfterPermission);
        else toast("Sin el permiso «Dispositivos cercanos» no puedo usar el Bluetooth");
    }

    private void btSetup() {
        if (bt == null) { toast("Hace falta Android 9 o superior"); return; }
        final List<BluetoothDevice> devs = bt.bonded();
        String[] items = new String[devs.size() + 1];
        items[0] = "📡  Hacer visible el móvil (para emparejar)";
        for (int i = 0; i < devs.size(); i++) items[i + 1] = "Conectar con: " + BtHid.name(devs.get(i));
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Conectar con el stick")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        Intent i = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
                        i.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 180);
                        try { startActivity(i); } catch (Exception ignored) { }
                        btHelp();
                    } else {
                        final BluetoothDevice dev = devs.get(which - 1);
                        sender.execute(() -> bt.connect(dev));
                    }
                })
                .setNeutralButton("¿Cómo se hace?", (d, w) -> btHelp())
                .setNegativeButton("Cerrar", null)
                .show();
    }

    private void btHelp() {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Emparejar con el stick")
                .setMessage("1. Deja esta app abierta y el móvil visible.\n\n"
                        + "2. En el stick ve a Ajustes → Mandos y accesorios (o Bluetooth) → Añadir accesorio / Buscar.\n\n"
                        + "3. Elige tu móvil en la lista y acepta el código en los dos aparatos.\n\n"
                        + "4. Si no se conecta solo, vuelve a «Emparejar» y toca «Conectar con: (tu stick)».")
                .setPositiveButton("Entendido", null)
                .show();
    }

    private void applySmartConfig() {
        final int t = smartType;
        final String ipv = smartIp, pskv = smartPsk;
        final String key = t >= 0 ? prefs.getString("smartKey_" + t + "_" + ipv,
                prefs.getString("smartKey" + t, "")) : "";
        sender.execute(() -> smart.configure(t, ipv, pskv, key));
    }

    // ---------------- Configuración Smart TV ----------------

    private void smartSetup() {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("¿Qué Smart TV tienes?")
                .setSingleChoiceItems(SmartTv.TYPES, smartType, (d, which) -> {
                    d.dismiss();
                    smartAddress(which);
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void smartAddress(final int type) {
        LinearLayout box = vbox();
        box.setPadding(px(22), px(8), px(22), 0);
        TextView info = label(type == 2
                ? "Escribe la IP de la tele y la clave PSK que pusiste en la tele Sony (por defecto 0000)."
                : "Escribe la IP de la tele o pulsa «Buscar». El móvil y la tele tienen que estar en el mismo wifi.",
                14, SUB, false);
        info.setGravity(Gravity.START);
        box.addView(info);
        final EditText ipField = new EditText(this);
        ipField.setHint("Ej.: 192.168.1.40");
        ipField.setInputType(InputType.TYPE_CLASS_PHONE);
        ipField.setTextColor(TXT);
        ipField.setHintTextColor(SUB);
        ipField.setText(type == smartType ? smartIp : "");
        box.addView(ipField);
        final EditText pskField = new EditText(this);
        pskField.setHint("Clave PSK (ej.: 0000)");
        pskField.setInputType(InputType.TYPE_CLASS_NUMBER);
        pskField.setTextColor(TXT);
        pskField.setHintTextColor(SUB);
        pskField.setText(smartPsk);
        if (type == 2) box.addView(pskField);

        final AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(SmartTv.TYPES[type])
                .setView(box)
                .setPositiveButton("Guardar", null)
                .setNeutralButton("Buscar", null)
                .setNegativeButton("Cancelar", null)
                .create();
        dlg.setOnShowListener(x -> {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String ipv = ipField.getText().toString().trim();
                if (!ipv.matches("\\d{1,3}(\\.\\d{1,3}){3}")) { toast("Escribe una IP válida, como 192.168.1.40"); return; }
                smartType = type;
                smartIp = ipv;
                smartPsk = pskField.getText().toString().trim();
                persist();
                applySmartConfig();
                updateBrandLabel();
                dlg.dismiss();
                toast("Guardado. Pulsa cualquier botón para conectar");
            });
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                toast("Buscando teles en el wifi…");
                new Thread(() -> {
                    final List<Discovery.Found> found = Discovery.search(getApplicationContext());
                    handler.post(() -> showFound(found, type, ipField));
                }).start();
            });
        });
        dlg.show();
    }

    private void showFound(List<Discovery.Found> found, int type, EditText ipField) {
        if (isFinishing()) return;
        if (found.isEmpty()) {
            toast("No he encontrado ninguna tele. Enciéndela y comprueba el wifi");
            return;
        }
        String[] items = new String[found.size()];
        for (int i = 0; i < items.length; i++) {
            Discovery.Found f = found.get(i);
            items[i] = f.label + "  ·  " + f.ip + (f.type == type ? "  ✓" : "");
        }
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Aparatos encontrados")
                .setItems(items, (d, which) -> ipField.setText(found.get(which).ip))
                .show();
    }

    // ---------------- Envío IR ----------------

    private void press(View v, String code) {
        send(code);
    }

    private void send(String code) {
        if (code.startsWith("APP_")) {
            if (mode == 1) {
                if (smartType < 0 || smartIp.isEmpty()) { toast("Configura primero tu Smart TV"); return; }
                sender.execute(() -> smart.launch(code));
            } else if (mode == 2) {
                toast("En el stick abre las apps desde Inicio");
            } else {
                toast("Las apps solo se abren en modo Smart TV (wifi)");
            }
            return;
        }
        if (mode == 2) {
            if (bt == null) { toast("Hace falta Android 9 o superior"); return; }
            sender.execute(() -> bt.send(code));
            return;
        }
        if (mode == 1) {
            if (smartType < 0 || smartIp.isEmpty()) { toast("Configura primero tu Smart TV"); return; }
            sender.execute(() -> smart.send(code));
            return;
        }
        final IrCodes.Signal s = IrCodes.get(brand, code);
        if (s == null) {
            toast("Esa tecla no existe en las teles " + IrCodes.BRANDS[brand]);
            return;
        }
        if (ir == null || !ir.hasIrEmitter()) {
            toast("Este móvil no tiene emisor de infrarrojos");
            return;
        }
        sender.execute(() -> {
            try {
                ir.transmit(s.freq, s.pattern);
            } catch (Exception ex) {
                handler.post(() -> toast("No se pudo enviar: " + ex.getMessage()));
            }
        });
    }

    private Toast lastToast;

    private void toast(String msg) {
        if (lastToast != null) lastToast.cancel();
        lastToast = Toast.makeText(this, msg, Toast.LENGTH_SHORT);
        lastToast.show();
    }

    // ---------------- Botones de apps con su icono ----------------

    /** Usa el icono oficial de la app instalada en el móvil; si no está, muestra el nombre. */
    private View appKey(String text, String code, int color, String pkg) {
        Drawable icon = null;
        try { icon = getPackageManager().getApplicationIcon(pkg); } catch (Exception ignored) { }
        if (icon == null) return key(text, code, color, false);
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(icon);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setPadding(px(6), px(6), px(6), px(6));
        iv.setBackground(bg(BTN, px(18)));
        iv.setContentDescription(text);
        iv.setClickable(true);
        iv.setHapticFeedbackEnabled(false);
        iv.setSoundEffectsEnabled(false);
        iv.setOnClickListener(v -> press(v, code));
        return iv;
    }

    // ---------------- Escribir con el teclado ----------------

    private View writeKey() {
        TextView b = label("Escribir", 13, TXT, true);
        b.setBackground(bg(BLUE, px(18)));
        b.setClickable(true);
        b.setHapticFeedbackEnabled(false);
        b.setSoundEffectsEnabled(false);
        b.setOnClickListener(v -> openKeyboard());
        return b;
    }

    private void openKeyboard() {
        if (mode == 0) { toast("Para escribir usa el modo Smart TV o Stick BT"); return; }
        if (mode == 1 && (smartType < 0 || smartIp.isEmpty())) { toast("Configura primero tu Smart TV"); return; }
        if (mode == 2 && bt == null) { toast("Hace falta Android 9 o superior"); return; }
        LinearLayout box = vbox();
        box.setPadding(px(22), px(8), px(22), 0);
        TextView info = label("Primero pon el cursor en el buscador de la tele (YouTube, Netflix…). "
                + "Luego escribe aquí y pulsa Enviar.", 14, SUB, false);
        info.setGravity(Gravity.START);
        box.addView(info);
        final EditText field = new EditText(this);
        field.setHint("Escribe aquí…");
        field.setTextColor(TXT);
        field.setHintTextColor(SUB);
        field.setSingleLine(true);
        box.addView(field);
        final CheckBox enter = new CheckBox(this);
        enter.setText("Pulsar Intro al terminar");
        enter.setTextColor(TXT);
        enter.setChecked(true);
        box.addView(enter);

        final AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Escribir en la tele")
                .setView(box)
                .setPositiveButton("Enviar", null)
                .setNeutralButton("⌫ Borrar", null)
                .setNegativeButton("Cerrar", null)
                .create();
        dlg.setOnShowListener(x -> {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                final String text = field.getText().toString();
                final boolean withEnter = enter.isChecked();
                if (text.isEmpty() && !withEnter) return;
                field.setText("");
                if (mode == 2) sender.execute(() -> bt.typeText(text, withEnter));
                else sender.execute(() -> smart.sendText(text, withEnter));
            });
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                if (mode == 2) sender.execute(() -> bt.backspace());
                else sender.execute(() -> smart.backspace());
            });
        });
        dlg.show();
        field.requestFocus();
        if (dlg.getWindow() != null) {
            dlg.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
    }

    // ---------------- Varias teles guardadas ----------------

    private boolean brandChosen;

    private void loadTvs() {
        try {
            String raw = prefs.getString("tvs", null);
            if (raw != null) tvs = new JSONArray(raw);
        } catch (Exception e) { tvs = null; }
        if (tvs == null || tvs.length() == 0) {
            // Primera vez: se crea «Mi tele» con lo que ya estaba configurado
            tvs = new JSONArray();
            JSONObject o = new JSONObject();
            try {
                o.put("name", "Mi tele");
                o.put("mode", prefs.getInt("mode", 0));
                o.put("brand", prefs.getInt("brand", 0));
                o.put("brandChosen", prefs.getBoolean("brandChosen", false));
                o.put("smartType", prefs.getInt("smartType", -1));
                o.put("smartIp", prefs.getString("smartIp", ""));
                o.put("smartPsk", prefs.getString("smartPsk", ""));
                o.put("btAddr", prefs.getString("btAddr", ""));
                o.put("btName", prefs.getString("btName", ""));
            } catch (Exception ignored) { }
            tvs.put(o);
            prefs.edit().putString("tvs", tvs.toString()).apply();
        }
        currentTv = prefs.getInt("currentTv", 0);
        if (currentTv < 0 || currentTv >= tvs.length()) currentTv = 0;
        loadFields();
    }

    private void loadFields() {
        JSONObject o = tvs.optJSONObject(currentTv);
        if (o == null) o = new JSONObject();
        mode = o.optInt("mode", 0);
        brand = o.optInt("brand", 0);
        if (brand < 0 || brand >= IrCodes.BRANDS.length) brand = 0;
        brandChosen = o.optBoolean("brandChosen", false);
        smartType = o.optInt("smartType", -1);
        smartIp = o.optString("smartIp", "");
        smartPsk = o.optString("smartPsk", "");
        btAddr = o.optString("btAddr", "");
        btName = o.optString("btName", "");
    }

    private void persist() {
        try {
            JSONObject o = tvs.optJSONObject(currentTv);
            if (o == null) { o = new JSONObject(); tvs.put(currentTv, o); }
            o.put("mode", mode);
            o.put("brand", brand);
            o.put("brandChosen", brandChosen);
            o.put("smartType", smartType);
            o.put("smartIp", smartIp);
            o.put("smartPsk", smartPsk);
            o.put("btAddr", btAddr);
            o.put("btName", btName);
        } catch (Exception ignored) { }
        prefs.edit().putString("tvs", tvs.toString()).putInt("currentTv", currentTv).apply();
    }

    private String tvName(int i) {
        JSONObject o = tvs.optJSONObject(i);
        return o == null ? "Tele" : o.optString("name", "Tele");
    }

    private void updateTvLabel() {
        if (tvBtn != null) tvBtn.setText("📺 " + tvName(currentTv) + " ▾");
    }

    private void chooseTv() {
        String[] items = new String[tvs.length() + 1];
        for (int i = 0; i < tvs.length(); i++) items[i] = (i == currentTv ? "✓  " : "     ") + tvName(i);
        items[tvs.length()] = "➕  Añadir otra tele";
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("¿Qué tele quieres manejar?")
                .setItems(items, (d, which) -> {
                    if (which == tvs.length()) askTvName("Nueva tele", "", name -> addTv(name));
                    else switchTv(which);
                })
                .setNeutralButton("Renombrar / borrar", (d, w) -> editTv())
                .setNegativeButton("Cerrar", null)
                .show();
    }

    private interface NameCallback { void done(String name); }

    private void askTvName(String title, String current, NameCallback cb) {
        LinearLayout box = vbox();
        box.setPadding(px(22), px(8), px(22), 0);
        final EditText field = new EditText(this);
        field.setHint("Ej.: Salón, Dormitorio, Cafetería");
        field.setTextColor(TXT);
        field.setHintTextColor(SUB);
        field.setSingleLine(true);
        field.setText(current);
        box.addView(field);
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("Guardar", (d, w) -> {
                    String n = field.getText().toString().trim();
                    if (n.isEmpty()) n = "Tele " + (tvs.length() + 1);
                    cb.done(n);
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void addTv(String name) {
        persist();
        JSONObject o = new JSONObject();
        try {
            o.put("name", name);
            o.put("mode", 0);
            o.put("brand", 0);
            o.put("brandChosen", false);
            o.put("smartType", -1);
        } catch (Exception ignored) { }
        tvs.put(o);
        switchTv(tvs.length() - 1);
        toast("Elige cómo se maneja «" + name + "»: Infrarrojos, Smart TV o Stick BT");
    }

    private void switchTv(int i) { switchTv(i, true); }

    private void switchTv(int i, boolean saveCurrent) {
        if (saveCurrent) persist();
        currentTv = i;
        loadFields();
        prefs.edit().putInt("currentTv", currentTv).apply();
        applySmartConfig();
        if (bt != null) {
            final String addr = btAddr;
            sender.execute(() -> bt.switchTo(addr));
        }
        updateTvLabel();
        updateBrandLabel();
        if (mode == 2) ensureBt(false);
    }

    private void editTv() {
        final int idx = currentTv;
        String[] opts = tvs.length() > 1
                ? new String[]{"Renombrar «" + tvName(idx) + "»", "Borrar «" + tvName(idx) + "»"}
                : new String[]{"Renombrar «" + tvName(idx) + "»"};
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(tvName(idx))
                .setItems(opts, (d, which) -> {
                    if (which == 0) {
                        askTvName("Renombrar tele", tvName(idx), name -> {
                            try { tvs.getJSONObject(idx).put("name", name); } catch (Exception ignored) { }
                            persist();
                            updateTvLabel();
                        });
                    } else {
                        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                                .setTitle("¿Borrar «" + tvName(idx) + "»?")
                                .setPositiveButton("Borrar", (d2, w2) -> {
                                    tvs.remove(idx);
                                    currentTv = 0;
                                    prefs.edit().putString("tvs", tvs.toString()).putInt("currentTv", 0).apply();
                                    switchTv(0, false);
                                })
                                .setNegativeButton("Cancelar", null)
                                .show();
                    }
                })
                .setNegativeButton("Cerrar", null)
                .show();
    }

    // ---------------- Utilidades de diseño ----------------

    private int px(float v) { return Math.round(v * dp); }

    private LinearLayout vbox() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout hbox() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private TextView label(String text, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private RippleDrawable bg(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(radius);
        return new RippleDrawable(ColorStateList.valueOf(0x55FFFFFF), g, mask);
    }

    private View blank() { return new View(this); }

    private View gap(int dpH) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, px(dpH)));
        return v;
    }

    private LinearLayout row(int height, View... views) {
        LinearLayout r = hbox();
        for (View v : views) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, height, 1);
            lp.setMargins(px(5), px(5), px(5), px(5));
            r.addView(v, lp);
        }
        return r;
    }

    private View cell(View v, int height) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, height);
        lp.setMargins(px(4), px(4), px(4), px(4));
        v.setLayoutParams(lp);
        return v;
    }
}
