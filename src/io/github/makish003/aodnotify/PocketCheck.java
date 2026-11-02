package io.github.makish003.aodnotify;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;

/**
 * One-shot "is the phone in a pocket" probe using the same sensor ColorOS uses for its
 * pocket detection (OPlus pocket sensor, falling back to the proximity sensor).
 * The sensor is on for at most {@link #TIMEOUT_MS} per notification.
 */
final class PocketCheck {
    interface Callback {
        void onResult(boolean inPocket);
    }

    private static final int TYPE_OPLUS_POCKET = 33171075;
    private static final int TIMEOUT_MS = 400;

    private final SensorManager mSensorManager;
    private final Handler mHandler;
    private Sensor mSensor;
    private SensorEventListener mActive;
    private Runnable mTimeout;

    PocketCheck(Context context, Handler handler) {
        mSensorManager = context.getSystemService(SensorManager.class);
        mHandler = handler;
    }

    /** Runs {@code callback} on the handler thread; a newer request cancels an older one. */
    void check(Callback callback) {
        cancel();
        Sensor sensor = sensor();
        if (sensor == null) {
            callback.onResult(false);
            return;
        }
        final boolean[] done = {false};
        mActive = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                if (done[0] || event.values == null || event.values.length == 0) {
                    return;
                }
                done[0] = true;
                float v = event.values[0];
                // Proximity reports a distance; the OPlus pocket sensor reports 0 while covered.
                boolean near = sensor.getType() == Sensor.TYPE_PROXIMITY ? v < sensor.getMaximumRange() : v <= 0f;
                finish(callback, near);
            }

            @Override
            public void onAccuracyChanged(Sensor s, int accuracy) {
            }
        };
        mTimeout = () -> {
            if (!done[0]) {
                done[0] = true;
                finish(callback, false); // no answer: don't block the notification
            }
        };
        mSensorManager.registerListener(mActive, sensor, SensorManager.SENSOR_DELAY_NORMAL, mHandler);
        mHandler.postDelayed(mTimeout, TIMEOUT_MS);
    }

    void cancel() {
        if (mActive != null) {
            mSensorManager.unregisterListener(mActive);
            mActive = null;
        }
        if (mTimeout != null) {
            mHandler.removeCallbacks(mTimeout);
            mTimeout = null;
        }
    }

    private void finish(Callback callback, boolean near) {
        cancel();
        callback.onResult(near);
    }

    private Sensor sensor() {
        if (mSensor == null && mSensorManager != null) {
            mSensor = mSensorManager.getDefaultSensor(TYPE_OPLUS_POCKET, true);
            if (mSensor == null) {
                mSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY, true);
            }
            if (mSensor == null) {
                mSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
            }
        }
        return mSensor;
    }
}
