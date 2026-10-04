package com.example.geminilegacy;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

public class SettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        final EditText etKey = (EditText) findViewById(R.id.etApiKey);
        final EditText etPro = (EditText) findViewById(R.id.etPro);
        final EditText etFlash = (EditText) findViewById(R.id.etFlash);
        final EditText etLite = (EditText) findViewById(R.id.etLite);

        etKey.setText(Settings.getApiKey(this));
        etPro.setText(Settings.getTierModel(this, Settings.TIER_PRO));
        etFlash.setText(Settings.getTierModel(this, Settings.TIER_FLASH));
        etLite.setText(Settings.getTierModel(this, Settings.TIER_LITE));
        final EditText etSystem = (EditText) findViewById(R.id.etSystemPrompt);
        etSystem.setText(Settings.getSystemPrompt(this));

        final EditText etUpdate = (EditText) findViewById(R.id.etUpdateUrl);
        etUpdate.setText(Settings.getUpdateRepo(this));
        ((TextView) findViewById(R.id.tvVersion)).setText("Gemini Legacy " + BuildConfig.VERSION_NAME);
        findViewById(R.id.btnCheckUpdate).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // Use the repo as typed, even before Save.
                Settings.save(SettingsActivity.this, etKey.getText().toString(),
                        etPro.getText().toString(), etFlash.getText().toString(),
                        etLite.getText().toString(), etUpdate.getText().toString(),
                        etSystem.getText().toString());
                UpdateFlow.check(SettingsActivity.this, false);
            }
        });

        findViewById(R.id.btnSave).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Settings.save(SettingsActivity.this, etKey.getText().toString(),
                        etPro.getText().toString(), etFlash.getText().toString(),
                        etLite.getText().toString(), etUpdate.getText().toString(),
                        etSystem.getText().toString());
                Toast.makeText(SettingsActivity.this, "Saved", Toast.LENGTH_SHORT).show();
                finish();
            }
        });
    }
}
