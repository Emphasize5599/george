package xyz.zood.george;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.pijun.george.database.MovementType;

/**
 * Works out how you're moving without Google Play Services (so it works on GrapheneOS and other phones
 * without them): the phone's step counter gives the step rate since the last location, and the location's
 * speed covers the rest.
 *
 * Steps at a running pace mean running; at a walking pace, walking. Without steps, a car's speed means
 * driving, a bike's speed means cycling, and barely moving means still. Anything unclear is left out rather
 * than guessed. The step counter needs the Physical activity permission; without it only speed is used.
 */
public final class MovementDetector {

    private MovementDetector() {}

    private static final String PREFS = "movement_detector";
    private static final String KEY_STEPS = "steps";
    private static final String KEY_STEPS_AT = "steps_at";  // elapsedRealtime of that reading

    /** Step rates (steps per second): about 1.0+ is walking, 2.4+ is running. */
    private static final float WALKING_CADENCE = 0.8f;
    private static final float RUNNING_CADENCE = 2.4f;
    /** Speeds (meters per second). */
    private static final float STILL_SPEED = 0.4f;
    private static final float CYCLING_SPEED = 2.5f;   // ~9 km/h
    private static final float DRIVING_SPEED = 8.5f;   // ~30 km/h, faster than nearly anyone cycles
    /** Step history older than this says nothing about now. */
    private static final long MAX_STEP_WINDOW_MS = 5 * 60 * 1000;

    private static final Object lock = new Object();
    @Nullable private static Handler sensorHandler;

    @WorkerThread
    @Nullable
    public static MovementType detect(@NonNull Context context, @NonNull Location location) {
        return classify(stepCadence(context), location.hasSpeed() ? location.getSpeed() : -1f);
    }

    /** @param cadence steps per second, or null if unknown; @param speed meters per second, or negative if unknown */
    @Nullable
    static MovementType classify(@Nullable Float cadence, float speed) {
        if (cadence != null && cadence >= RUNNING_CADENCE) return MovementType.Running;
        if (cadence != null && cadence >= WALKING_CADENCE && (speed < 0 || speed < CYCLING_SPEED * 2)) {
            return MovementType.Walking;
        }
        if (speed >= DRIVING_SPEED) return MovementType.Vehicle;
        boolean noSteps = cadence != null && cadence < 0.2f;
        if (speed >= CYCLING_SPEED && noSteps) return MovementType.Bicycle;
        if (speed >= 0 && speed < STILL_SPEED && (cadence == null || noSteps)) return MovementType.Stationary;
        return null;
    }

    /**
     * Steps per second since the previous reading (if that was recent), or null when the step counter is
     * unavailable, not permitted, or there's no recent earlier reading to compare with.
     */
    @WorkerThread
    @Nullable
    private static Float stepCadence(@NonNull Context context) {
        if (!Permissions.checkActivityRecognitionPermission(context)) return null;
        SensorManager sensors = context.getSystemService(SensorManager.class);
        Sensor counter = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        if (counter == null) return null;
        Float steps = readCounter(sensors, counter);
        if (steps == null) return null;
        long now = SystemClock.elapsedRealtime();

        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        float previous = prefs.getFloat(KEY_STEPS, -1f);
        long previousAt = prefs.getLong(KEY_STEPS_AT, -1L);
        prefs.edit().putFloat(KEY_STEPS, steps).putLong(KEY_STEPS_AT, now).apply();

        long elapsed = now - previousAt;
        // A reboot resets the counter (and elapsedRealtime), and a long gap averages away what's happening now.
        if (previous < 0 || previousAt < 0 || steps < previous || elapsed <= 5_000 || elapsed > MAX_STEP_WINDOW_MS) {
            return null;
        }
        return (steps - previous) / (elapsed / 1000f);
    }

    /** The counter only reports when registered; take its first reading and stop listening. */
    @WorkerThread
    @Nullable
    private static Float readCounter(@NonNull SensorManager sensors, @NonNull Sensor counter) {
        CountDownLatch latch = new CountDownLatch(1);
        float[] value = {-1f};
        SensorEventListener listener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                value[0] = event.values[0];
                latch.countDown();
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {}
        };
        if (!sensors.registerListener(listener, counter, SensorManager.SENSOR_DELAY_NORMAL, handler())) return null;
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            sensors.unregisterListener(listener);
        }
        return value[0] < 0 ? null : value[0];
    }

    @NonNull
    private static Handler handler() {
        synchronized (lock) {
            if (sensorHandler == null) {
                HandlerThread thread = new HandlerThread("movement-sensor");
                thread.start();
                sensorHandler = new Handler(thread.getLooper());
            }
            return sensorHandler;
        }
    }
}
