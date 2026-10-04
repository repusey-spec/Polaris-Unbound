package com.note3.powermanager;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final String KEY_AP = "note3_power_ap_limit";
    private static final String KEY_SAVER = "note3_power_saver_limit";
    private static final String KEY_CHARGE = "note3_power_charge_limit";

    private SeekBar apBar;
    private SeekBar saverBar;
    private SeekBar chargeBar;

    private TextView apValue;
    private TextView saverValue;
    private TextView chargeValue;
    private TextView status;

    private int apMin = 1;
    private int saverMin = 1;
    private int chargeMin = 3;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(28));
        root.setBackgroundColor(Color.rgb(244, 246, 248));
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("Note3 Power");
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(Color.rgb(28, 32, 36));
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Galaxy Note 3 전원 관리 설정");
        subtitle.setTextSize(15);
        subtitle.setTextColor(Color.rgb(90, 98, 108));
        subtitle.setPadding(0, dp(4), 0, dp(18));
        root.addView(subtitle);

        status = new TextView(this);
        status.setTextSize(14);
        status.setTextColor(Color.rgb(60, 66, 74));
        status.setTextIsSelectable(true);
        status.setPadding(dp(14), dp(12), dp(14), dp(12));
        status.setBackground(cardBackground(Color.rgb(232, 238, 245), 12));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        statusParams.bottomMargin = dp(16);
        root.addView(status, statusParams);

        Control ap = addControl(
                root,
                "AP 깨어있음 기준",
                "SOC가 이 값보다 높으면 Application Processor(AP) suspend를 막습니다. 100%는 사실상 해제입니다.",
                1,
                100);
        apBar = ap.bar;
        apValue = ap.value;

        Control saver = addControl(
                root,
                "절전모드 기준",
                "외부전원이 없고 SOC가 이 값 미만이면 Android 절전모드를 켭니다.",
                1,
                100);
        saverBar = saver.bar;
        saverValue = saver.value;

        Control charge = addControl(
                root,
                "충전 상한",
                "SOC가 이 값 이상이면 충전을 끊고, 2% 낮아지면 다시 충전합니다.",
                3,
                100);
        chargeBar = charge.bar;
        chargeValue = charge.value;

        Button apply = new Button(this);
        apply.setText("설정 적용");
        apply.setTextSize(17);
        apply.setAllCaps(false);
        apply.setTextColor(Color.WHITE);
        apply.setBackground(cardBackground(Color.rgb(31, 111, 235), 12));
        apply.setPadding(dp(12), dp(14), dp(12), dp(14));
        LinearLayout.LayoutParams applyParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(58));
        applyParams.topMargin = dp(4);
        root.addView(apply, applyParams);

        apply.setOnClickListener(v -> saveSettings());

        TextView note = new TextView(this);
        note.setText("daemon이 약 10초 간격으로 값을 읽어 자동 반영합니다.");
        note.setTextSize(13);
        note.setTextColor(Color.rgb(100, 106, 114));
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, dp(12), 0, 0);
        root.addView(note);

        setContentView(scroll);
        loadValues();
        updateStatus(null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (apBar != null) {
            loadValues();
            updateStatus(null);
        }
    }

    private Control addControl(LinearLayout root, String titleText, String desc, int min, int max) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(cardBackground(Color.WHITE, 14));

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dp(14);
        root.addView(card, cardParams);

        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(Color.rgb(32, 36, 40));
        card.addView(title);

        TextView description = new TextView(this);
        description.setText(desc);
        description.setTextSize(13);
        description.setTextColor(Color.rgb(92, 99, 108));
        description.setPadding(0, dp(6), 0, dp(12));
        card.addView(description);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        Button minus = smallButton("−");
        row.addView(minus, new LinearLayout.LayoutParams(dp(48), dp(48)));

        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
        barParams.leftMargin = dp(6);
        barParams.rightMargin = dp(6);
        row.addView(bar, barParams);

        Button plus = smallButton("+");
        row.addView(plus, new LinearLayout.LayoutParams(dp(48), dp(48)));

        TextView value = new TextView(this);
        value.setTextSize(18);
        value.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        value.setGravity(Gravity.CENTER);
        value.setTextColor(Color.rgb(31, 111, 235));
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(dp(68), dp(48));
        valueParams.leftMargin = dp(6);
        row.addView(value, valueParams);

        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                value.setText((progress + min) + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });

        minus.setOnClickListener(v -> {
            if (bar.getProgress() > 0) {
                bar.setProgress(bar.getProgress() - 1);
            }
        });

        plus.setOnClickListener(v -> {
            if (bar.getProgress() < bar.getMax()) {
                bar.setProgress(bar.getProgress() + 1);
            }
        });

        return new Control(bar, value);
    }

    private Button smallButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(20);
        b.setAllCaps(false);
        b.setPadding(0, 0, 0, 0);
        return b;
    }

    private void loadValues() {
        int ap = clamp(Settings.Global.getInt(getContentResolver(), KEY_AP, 50), 1, 100);
        int saver = clamp(Settings.Global.getInt(getContentResolver(), KEY_SAVER, 40), 1, 100);
        int charge = clamp(Settings.Global.getInt(getContentResolver(), KEY_CHARGE, 90), 3, 100);

        setBar(apBar, apValue, ap, apMin);
        setBar(saverBar, saverValue, saver, saverMin);
        setBar(chargeBar, chargeValue, charge, chargeMin);
    }

    private void setBar(SeekBar bar, TextView value, int actual, int min) {
        bar.setProgress(actual - min);
        value.setText(actual + "%");
    }

    private int barValue(SeekBar bar, int min) {
        return bar.getProgress() + min;
    }

    private void saveSettings() {
        int ap = barValue(apBar, apMin);
        int saver = barValue(saverBar, saverMin);
        int charge = barValue(chargeBar, chargeMin);

        if (checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) {
            updateStatus("권한이 없습니다. 아래 ADB 명령을 한 번 실행하세요.");
            Toast.makeText(this, "WRITE_SECURE_SETTINGS 권한이 필요합니다.", Toast.LENGTH_LONG).show();
            return;
        }

        try {
            boolean a = Settings.Global.putInt(getContentResolver(), KEY_AP, ap);
            boolean s = Settings.Global.putInt(getContentResolver(), KEY_SAVER, saver);
            boolean c = Settings.Global.putInt(getContentResolver(), KEY_CHARGE, charge);

            if (a && s && c) {
                updateStatus("저장 완료: AP " + ap + "% / 절전 " + saver + "% / 충전 " + charge + "%");
                Toast.makeText(this, "설정이 저장되었습니다.", Toast.LENGTH_SHORT).show();
            } else {
                updateStatus("설정 저장에 실패했습니다.");
            }
        } catch (SecurityException e) {
            updateStatus("권한 오류: WRITE_SECURE_SETTINGS 권한을 다시 확인하세요.");
        }
    }

    private void updateStatus(String message) {
        boolean granted = checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;

        String permission = granted ? "설정 권한: 허용됨" :
                "설정 권한: 필요\nadb shell pm grant com.note3.powermanager android.permission.WRITE_SECURE_SETTINGS";

        if (TextUtils.isEmpty(message)) {
            status.setText(permission);
        } else {
            status.setText(message + "\n\n" + permission);
        }
    }

    private GradientDrawable cardBackground(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static class Control {
        final SeekBar bar;
        final TextView value;
        Control(SeekBar bar, TextView value) {
            this.bar = bar;
            this.value = value;
        }
    }
}
