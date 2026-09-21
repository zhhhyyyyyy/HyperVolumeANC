package io.github.hypervolumeanc;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Launcher entry: sends the user to the first-run guide until it is finished,
 * afterwards straight to the settings screen.
 */
public final class SplashActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeHelper.applyStored(this);
        LocaleHelper.applyStored(this);
        Intent next = Settings.isOobeDone(this)
                ? new Intent(this, MainActivity.class)
                : new Intent(this, OobeActivity.class);
        startActivity(next);
        finish();
    }
}
