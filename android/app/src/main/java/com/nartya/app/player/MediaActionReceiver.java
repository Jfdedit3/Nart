package com.nartya.app.player;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class MediaActionReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        PlayerPlugin.dispatchControl(intent.getAction());
    }
}
