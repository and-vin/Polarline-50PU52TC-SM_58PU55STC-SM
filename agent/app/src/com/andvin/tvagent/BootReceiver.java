package com.andvin.tvagent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the agent after boot; it then keeps polling on its own. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        context.startService(new Intent(context, AgentService.class));
    }
}
