package com.blink.buddy;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Entry point when Sweety is the phone's default assistant: holding the power or home button (or the assistant
 * gesture) lands here, and Sweety's talk card opens and listens. It is the only part other apps can start, so it
 * passes nothing through: whatever extras arrive are ignored.
 */
public class AssistActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        startActivity(new Intent(this, TalkActivity.class).setAction(Intent.ACTION_ASSIST)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        finish();
        overridePendingTransition(0, 0);
    }
}
