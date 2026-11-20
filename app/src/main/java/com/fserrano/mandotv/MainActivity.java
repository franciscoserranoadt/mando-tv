package com.fserrano.mandotv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.hardware.ConsumerIrManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

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
    private TextView modeIr, modeSmart;
    private int mode;          // 0 = infrarrojos, 1 = Smart TV por wifi
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
        brand = prefs.getInt("brand", 0);
        if (brand < 0 || brand >= IrCodes.BRANDS.length) brand = 0;
        mode = prefs.getInt("mode", 0);
        smartType = prefs.getInt("smartType", -1);
        smartIp = prefs.getString("smartIp", "");
        smartPsk = prefs.getString("smartPsk", "");
        smart = new SmartTv(new SmartTv.Listener() {
            @Override public void onStatus(String msg) { handler.post(() -> toast(msg)); }
            @Override public void onSaveKey(int type, String key) {
                prefs.edit().putString("smartKey" + type, key).apply();
            }
        });
        applySmartConfig();

        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        setContentView(buildUi());

        if (mode == 0 && (ir == null || !ir.hasIrEmitter())) {
            new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("Sin infrarrojos")
                    .setMessage("Este móvil no tiene emisor de infrarrojos. Puedes usar el modo «Smart TV (wifi)».")
                    .setPositiveButton("Vale", null)
                    .show();
        } else if (mode == 0 && !prefs.getBoolean("brandChosen", false)) {
            chooseBrand();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        sender.execute(() -> smart.close());
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
        TextView title = label("Mando TV", 24, TXT, true);
        title.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        brandBtn = label("", 15, TXT, true);
        brandBtn.setPadding(px(16), px(10), px(16), px(10));
        brandBtn.setBackground(bg(BLUE, px(22)));
        brandBtn.setOnClickListener(v -> { if (mode == 0) chooseBrand(); else smartSetup(); });
        header.addView(brandBtn);
        root.addView(header);
        root.addView(gap(14));

        // Selector de modo
        LinearLayout modes = hbox();
        modes.setBackground(bg(PANEL, px(22)));
        modes.setPadding(px(4), px(4), px(4), px(4));
        modeIr = label("Infrarrojos", 14, TXT, true);
        modeSmart = label("Smart TV (wifi)", 14, TXT, true);
        modeIr.setOnClickListener(v -> setMode(0));
        modeSmart.setOnClickListener(v -> setMode(1));
        modes.addView(modeIr, new LinearLayout.LayoutParams(0, px(42), 1));
        modes.addView(modeSmart, new LinearLayout.LayoutParams(0, px(42), 1));
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
        nums.addView(row(px(56), key("Salir", "EXIT", BTN, false), key("0", "D0", BTN, false), blank()));
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
        TextView b = label(text, text.length() <= 2 ? 22 : 15, TXT, true);
        b.setBackground(bg(color, px(18)));
        b.setClickable(true);
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
                    prefs.edit().putInt("brand", brand).putBoolean("brandChosen", true).apply();
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
        } else {
            brandBtn.setText((smartType < 0 ? "Configurar" : SmartTv.TYPES[smartType].split(" ")[0]) + "  ▾");
            hintView.setText(smartType < 0 || smartIp.isEmpty()
                    ? "Toca «Configurar» para buscar tu tele en el wifi."
                    : "Por wifi a " + smartIp + ". No hace falta apuntar.");
        }
        modeIr.setBackground(bg(mode == 0 ? BLUE : PANEL, px(18)));
        modeSmart.setBackground(bg(mode == 1 ? BLUE : PANEL, px(18)));
    }

    private void setMode(int m) {
        mode = m;
        prefs.edit().putInt("mode", m).apply();
        updateBrandLabel();
        if (m == 1 && (smartType < 0 || smartIp.isEmpty())) smartSetup();
    }

    private void applySmartConfig() {
        final int t = smartType;
        final String ipv = smartIp, pskv = smartPsk;
        final String key = t >= 0 ? prefs.getString("smartKey" + t, "") : "";
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
                if (type != smartType) prefs.edit().remove("smartKey" + type).apply();
                smartType = type;
                smartIp = ipv;
                smartPsk = pskField.getText().toString().trim();
                prefs.edit().putInt("smartType", smartType).putString("smartIp", smartIp)
                        .putString("smartPsk", smartPsk).apply();
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
        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        send(code);
    }

    private void send(String code) {
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
