/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android;

import android.content.Intent;
import android.os.Bundle;
import android.os.Process;
import android.os.RemoteException;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;
import android.util.Log;
import java.util.ArrayList;

/**
 * Runs in the test APK's standalone process, outside instrumentation's merged classpath.
 * Keep this fixture Java + Android SDK only: target-app Kotlin/dependency classes are
 * available to instrumentation but are not dependencies of this service process.
 * Never opens a microphone or network connection; logs lifecycle markers only.
 */
public final class SpeechFakeRecognitionService extends RecognitionService {
    private static final String TAG = "SpeechFakeService";

    @Override public void onCreate() {
        super.onCreate();
        Log.i(TAG, "onCreate package=" + getPackageName() + " pid=" + Process.myPid());
    }

    // RecognitionService.onBind is final. System binding is recorded by dumpsys activity services.
    @Override protected void onStartListening(Intent intent, Callback listener) {
        boolean errorFive = intent.getBooleanExtra("test.error5", false);
        Log.i(TAG, "onStartListening errorCase=" + errorFive);
        try {
            if (errorFive) {
                listener.error(SpeechRecognizer.ERROR_CLIENT);
            } else {
                listener.readyForSpeech(new Bundle());
                ArrayList<String> results = new ArrayList<>();
                results.add("测试成功");
                Bundle bundle = new Bundle();
                bundle.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, results);
                listener.results(bundle);
            }
            Log.i(TAG, "terminal callback sent");
        } catch (RemoteException e) {
            Log.e(TAG, "callback binder disconnected", e);
        }
    }

    @Override protected void onStopListening(Callback listener) { Log.i(TAG, "onStopListening"); }
    @Override protected void onCancel(Callback listener) { Log.i(TAG, "onCancel"); }
    @Override public void onDestroy() {
        Log.i(TAG, "onDestroy");
        super.onDestroy();
    }
}
