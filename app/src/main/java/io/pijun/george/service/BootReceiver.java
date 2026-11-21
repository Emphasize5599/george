package io.pijun.george.service;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import io.pijun.george.L;

public class BootReceiver extends BroadcastReceiver {
    @SuppressLint("UnsafeProtectedBroadcastReceiver")
    @Override
    public void onReceive(Context context, Intent intent) {
        L.i("BootReceiver.onReceive");
        // Resume listening for family location requests (allowed at boot when background location is granted).
        xyz.zood.george.service.LiveRequestService.startIfEnabled(context);
    }
}
