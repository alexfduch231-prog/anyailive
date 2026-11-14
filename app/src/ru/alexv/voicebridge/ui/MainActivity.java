package ru.alexv.voicebridge.ui;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

import ru.alexv.voicebridge.R;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        TextView t = (TextView) findViewById(R.id.main_text);
        t.setText(getString(R.string.app_name) + "\n" + getString(R.string.main_hint));
    }
}
