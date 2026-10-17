package com.nartya.app.proxy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Non-exported receiver: notification actions also work while the WebView is paused. */
public class DownloadActionReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if ("pause".equals(intent.getAction())) NartyaProxyPlugin.pauseTransfers("En pause");
    }
}
