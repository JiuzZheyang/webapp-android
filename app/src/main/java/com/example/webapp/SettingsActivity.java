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
    private EditText editUrl;
    private SharedPreferences prefs;
    private static final String PREFS_NAME = "WebAppPrefs";
    private static final String KEY_URL = "web_url";
    private static final String DEFAULT_URL = "http://192.168.1.121:12345/";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        setTitle("设置");

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        editUrl = findViewById(R.id.editUrl);
        Button btnSave = findViewById(R.id.btnSave);
        Button btnReset = findViewById(R.id.btnReset);

        String savedUrl = prefs.getString(KEY_URL, DEFAULT_URL);
        editUrl.setText(savedUrl);

        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String url = editUrl.getText().toString().trim();
                if (url.isEmpty()) {
                    Toast.makeText(SettingsActivity.this, "网址不能为空", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    url = "http://" + url;
                }
                if (!url.endsWith("/")) {
                    url = url + "/";
                }
                prefs.edit().putString(KEY_URL, url).apply();
                Toast.makeText(SettingsActivity.this, "已保存：" + url, Toast.LENGTH_SHORT).show();
            }
        });

        btnReset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editUrl.setText(DEFAULT_URL);
                prefs.edit().putString(KEY_URL, DEFAULT_URL).apply();
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
