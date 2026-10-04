package com.note3.powermanager;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final String KEY_AP = "note3_power_ap_limit";
    private static final String KEY_SAVER = "note3_power_saver_limit";
    private static final String KEY_CHARGE = "note3_power_charge_limit";

    private EditText apInput;
    private EditText saverInput;
    private EditText chargeInput;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(28));
        root.setBackgroundColor(Color.rgb(244, 246, 248));
        scroll.addView(root);

        TextView title = text("Note3 Power", 28, true, Color.rgb(28,32,36));
        root.addView(title);

        TextView subtitle = text("Galaxy Note 3 전원 관리 설정", 15, false, Color.rgb(90,98,108));
        subtitle.setPadding(0, dp(4), 0, dp(16));
        root.addView(subtitle);

        status = text("", 14, false, Color.rgb(60,66,74));
        status.setPadding(dp(14), dp(12), dp(14), dp(12));
        status.setBackground(bg(Color.rgb(232,238,245), 12));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.bottomMargin = dp(16);
        root.addView(status, sp);

        apInput = addField(root,
                "AP 깨어있음 기준",
                "SOC가 이 값보다 높으면 Application Processor(AP) suspend를 막습니다. 100%는 사실상 해제입니다.");

        saverInput = addField(root,
                "절전모드 기준",
                "외부전원이 없고 SOC가 이 값 미만이면 Android 절전모드를 켭니다.");

        chargeInput = addField(root,
                "충전 상한",
                "SOC가 이 값 이상이면 충전을 끊고, 2% 낮아지면 다시 충전합니다.");

        Button save = new Button(this);
        save.setText("설정 적용");
        save.setTextSize(17);
        save.setAllCaps(false);
        save.setTextColor(Color.WHITE);
        save.setBackground(bg(Color.rgb(31,111,235), 12));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(58));
        bp.topMargin = dp(2);
        root.addView(save, bp);

        save.setOnClickListener(v -> saveValues());

        Button reload = new Button(this);
        reload.setText("현재값 다시 읽기");
        reload.setTextSize(15);
        reload.setAllCaps(false);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, dp(52));
        rp.topMargin = dp(10);
        root.addView(reload, rp);
        reload.setOnClickListener(v -> {
            loadValues();
            updateStatus("현재 Global Settings 값을 다시 읽었습니다.");
        });

        TextView note = text("daemon은 약 10초 간격으로 값을 읽어 자동 반영합니다.", 13, false, Color.rgb(100,106,114));
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, dp(12), 0, 0);
        root.addView(note);

        setContentView(scroll);
        loadValues();
        updateStatus(null);
    }

    private EditText addField(LinearLayout root, String label, String desc) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(bg(Color.WHITE, 14));

        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.bottomMargin = dp(14);
        root.addView(card, cp);

        card.addView(text(label, 18, true, Color.rgb(32,36,40)));

        TextView d = text(desc, 13, false, Color.rgb(92,99,108));
        d.setPadding(0, dp(6), 0, dp(10));
        card.addView(d);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setGravity(Gravity.CENTER);
        input.setTextSize(22);
        input.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSelectAllOnFocus(true);

        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, dp(58), 1f);
        row.addView(input, ip);

        TextView pct = text("%", 22, true, Color.rgb(31,111,235));
        pct.setGravity(Gravity.CENTER);
        row.addView(pct, new LinearLayout.LayoutParams(dp(48), dp(58)));

        return input;
    }

    private void loadValues() {
        apInput.setText(String.valueOf(Settings.Global.getInt(getContentResolver(), KEY_AP, 50)));
        saverInput.setText(String.valueOf(Settings.Global.getInt(getContentResolver(), KEY_SAVER, 40)));
        chargeInput.setText(String.valueOf(Settings.Global.getInt(getContentResolver(), KEY_CHARGE, 90)));
    }

    private void saveValues() {
        if (checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
            updateStatus("WRITE_SECURE_SETTINGS 권한이 필요합니다.");
            Toast.makeText(this, "권한이 필요합니다.", Toast.LENGTH_LONG).show();
            return;
        }

        Integer ap = parse(apInput, 1, 100);
        Integer saver = parse(saverInput, 1, 100);
        Integer charge = parse(chargeInput, 3, 100);

        if (ap == null || saver == null || charge == null) {
            updateStatus("입력값을 확인하세요. AP 1~100, 절전 1~100, 충전 3~100");
            return;
        }

        try {
            boolean ok1 = Settings.Global.putInt(getContentResolver(), KEY_AP, ap);
            boolean ok2 = Settings.Global.putInt(getContentResolver(), KEY_SAVER, saver);
            boolean ok3 = Settings.Global.putInt(getContentResolver(), KEY_CHARGE, charge);

            int readAp = Settings.Global.getInt(getContentResolver(), KEY_AP, -1);
            int readSaver = Settings.Global.getInt(getContentResolver(), KEY_SAVER, -1);
            int readCharge = Settings.Global.getInt(getContentResolver(), KEY_CHARGE, -1);

            if (ok1 && ok2 && ok3 && readAp == ap && readSaver == saver && readCharge == charge) {
                updateStatus("저장 확인 완료: AP " + readAp + "% / 절전 " + readSaver + "% / 충전 " + readCharge + "%");
                Toast.makeText(this, "설정 저장 완료", Toast.LENGTH_SHORT).show();
            } else {
                updateStatus("저장 검증 실패: 실제값 " + readAp + " / " + readSaver + " / " + readCharge);
            }
        } catch (Exception e) {
            updateStatus("저장 오류: " + e.getClass().getSimpleName());
        }
    }

    private Integer parse(EditText input, int min, int max) {
        try {
            int v = Integer.parseInt(input.getText().toString().trim());
            return (v >= min && v <= max) ? v : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void updateStatus(String msg) {
        boolean granted = checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
        String p = granted
                ? "설정 권한: 허용됨"
                : "설정 권한: 필요\nadb shell pm grant com.note3.powermanager android.permission.WRITE_SECURE_SETTINGS";
        status.setText(msg == null ? p : msg + "\n\n" + p);
    }

    private TextView text(String s, int size, boolean bold, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private GradientDrawable bg(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
