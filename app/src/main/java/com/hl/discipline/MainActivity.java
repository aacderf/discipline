package com.hl.discipline;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Keeps notifications created before the Compose upgrade able to open the app. */
@Deprecated
public final class MainActivity extends Activity {
  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    startActivity(new Intent(this, ComposeMainActivity.class));
    finish();
  }
}
