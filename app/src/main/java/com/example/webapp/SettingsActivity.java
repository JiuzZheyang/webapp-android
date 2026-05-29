package com.example.webapp;

import androidx.appcompat.app.AppCompatActivity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

public class SettingsActivity extends AppCompatActivity {
    private EditText editLanUrl;
    private EditText editPublicUrl;
    private SharedPreferences prefs;
    private static final String PREFS_NAME = "WebAppPrefs";
    private static final String KEY_LAN_URL = "lan_url";
    private static final String KEY_PUBLIC_URL = "public_url";
    private static final String DEFAULT_LAN_URL = "192.168.1.121:12345";
    private static final String DEFAULT_PUBLIC_URL = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        setTitle("设置");

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        editLanUrl = findViewById(R.id.editLanUrl);
        editPublicUrl = findViewById(R.id.editPublicUrl);
        Button btnSave = findViewById(R.id.btnSave);
        Button btnReset = findViewById(R.id.btnReset);

        String savedLanUrl = prefs.getString(KEY_LAN_URL, DEFAULT_LAN_URL);
        String savedPublicUrl = prefs.getString(KEY_PUBLIC_URL, DEFAULT_PUBLIC_URL);
        editLanUrl.setText(savedLanUrl);
        editPublicUrl.setText(savedPublicUrl);

        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String lanUrl = editLanUrl.getText().toString().trim();
                String publicUrl = editPublicUrl.getText().toString().trim();

                if (lanUrl.isEmpty()) {
                    Toast.makeText(SettingsActivity.this, "局域网地址不能为空", Toast.LENGTH_SHORT).show();
                    return;
                }

                // Save LAN URL
                prefs.edit().putString(KEY_LAN_URL, lanUrl).apply();

                // Save Public URL (can be empty)
                prefs.edit().putString(KEY_PUBLIC_URL, publicUrl).apply();

                Toast.makeText(SettingsActivity.this, "已保存", Toast.LENGTH_SHORT).show();
                finish();
            }
        });

        btnReset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editLanUrl.setText(DEFAULT_LAN_URL);
                editPublicUrl.setText(DEFAULT_PUBLIC_URL);
                prefs.edit().putString(KEY_LAN_URL, DEFAULT_LAN_URL).apply();
                prefs.edit().putString(KEY_PUBLIC_URL, DEFAULT_PUBLIC_URL).apply();
                Toast.makeText(SettingsActivity.this, "已恢复默认", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}