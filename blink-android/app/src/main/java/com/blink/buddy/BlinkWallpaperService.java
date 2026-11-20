package com.blink.buddy;

import android.app.KeyguardManager;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.service.wallpaper.WallpaperService;
import android.view.MotionEvent;
import android.view.SurfaceHolder;

/** Live wallpaper: Blink on the home screen and (where the phone allows live wallpapers there) the lock screen. */
public class BlinkWallpaperService extends WallpaperService {
    @Override
    public Engine onCreateEngine() { return new BlinkEngine(); }

    private class BlinkEngine extends Engine implements SensorEventListener, Bus.Listener {
        private final Handler handler = new Handler(Looper.getMainLooper());
        private Blink blink;
        private boolean visible;
        private long lastFrame;
        private SensorManager sensors;
        private Sensor accel;
        private AudioManager audio;
        private float shakeLast;
        private long shakeAt;
        private boolean batterySeen;

        private final Runnable frame = this::drawFrame;

        private final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent i) {
                String a = i.getAction();
                if (a == null || blink == null) return;
                switch (a) {
                    case Intent.ACTION_SCREEN_OFF: blink.onScreenOff(); break;
                    case Intent.ACTION_SCREEN_ON: blink.onScreenOn(isLocked()); break;
                    case Intent.ACTION_USER_PRESENT: blink.onUnlock(); break;
                    case Intent.ACTION_POWER_CONNECTED: blink.onPower(true); break;
                    case Intent.ACTION_POWER_DISCONNECTED: blink.onPower(false); break;
                    case Intent.ACTION_BATTERY_CHANGED: {
                        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                        int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                        if (!batterySeen) { batterySeen = true; blink.charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL; }
                        if (level >= 0 && scale > 0) blink.onBattery(level * 100 / scale);
                        break;
                    }
                    default: break;
                }
                schedule(0);
            }
        };

        private final AudioDeviceCallback audioCallback = new AudioDeviceCallback() {
            @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added) { refreshHeadphones(); }
            @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) { refreshHeadphones(); }
        };

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            setTouchEventsEnabled(true);
            blink = new Blink(BlinkWallpaperService.this);
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_SCREEN_OFF);
            f.addAction(Intent.ACTION_SCREEN_ON);
            f.addAction(Intent.ACTION_USER_PRESENT);
            f.addAction(Intent.ACTION_POWER_CONNECTED);
            f.addAction(Intent.ACTION_POWER_DISCONNECTED);
            f.addAction(Intent.ACTION_BATTERY_CHANGED);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(receiver, f);
            sensors = (SensorManager) getSystemService(SENSOR_SERVICE);
            accel = sensors != null ? sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) : null;
            audio = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (audio != null) audio.registerAudioDeviceCallback(audioCallback, handler);
            Bus.add(this);
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) blink.onScreenOff();
        }

        @Override
        public void onDestroy() {
            handler.removeCallbacks(frame);
            try { unregisterReceiver(receiver); } catch (Exception ignored) { }
            if (audio != null) audio.unregisterAudioDeviceCallback(audioCallback);
            if (sensors != null) sensors.unregisterListener(this);
            Bus.remove(this);
            super.onDestroy();
        }

        private boolean isLocked() {
            KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            return km != null && km.isKeyguardLocked();
        }

        private void refreshHeadphones() {
            if (audio == null || blink == null) return;
            boolean any = false;
            for (AudioDeviceInfo d : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int ty = d.getType();
                if (ty == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || ty == AudioDeviceInfo.TYPE_WIRED_HEADSET || ty == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                        || ty == AudioDeviceInfo.TYPE_USB_HEADSET || (Build.VERSION.SDK_INT >= 31 && ty == AudioDeviceInfo.TYPE_BLE_HEADSET)) any = true;
            }
            blink.onHeadphones(any);
            schedule(0);
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            blink.setSize(width, height);
            schedule(0);
        }

        @Override
        public void onVisibilityChanged(boolean v) {
            visible = v;
            blink.onVisibility(v, isLocked());
            if (v) {
                if (accel != null) sensors.registerListener(this, accel, SensorManager.SENSOR_DELAY_UI);
                lastFrame = 0;
                schedule(0);
            } else {
                if (sensors != null) sensors.unregisterListener(this);
                handler.removeCallbacks(frame);
            }
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            visible = false;
            handler.removeCallbacks(frame);
            super.onSurfaceDestroyed(holder);
        }

        @Override
        public void onOffsetsChanged(float xOffset, float yOffset, float xStep, float yStep, int xPixels, int yPixels) {
            blink.onOffsets(xOffset, xStep);
            schedule(0);
        }

        @Override
        public Bundle onCommand(String action, int x, int y, int z, Bundle extras, boolean resultRequested) {
            if (WallpaperManager.COMMAND_TAP.equals(action)) { blink.onTap(x, y); schedule(0); }
            return super.onCommand(action, x, y, z, extras, resultRequested);
        }

        @Override
        public void onTouchEvent(MotionEvent e) {
            blink.onTouch(e.getActionMasked(), e.getX(), e.getY());
        }

        @Override
        public void onNotificationPosted() { handler.post(() -> { blink.onNotification(); schedule(0); }); }

        @Override
        public void onSensorChanged(SensorEvent e) {
            float gx = e.values[0], gy = e.values[1], gz = e.values[2];
            float g = (float) Math.sqrt(gx * gx + gy * gy + gz * gz) / SensorManager.GRAVITY_EARTH;
            long now = SystemClock.uptimeMillis();
            if (g > 2.3f && Math.abs(g - shakeLast) > .8f && now - shakeAt > 2500) { shakeAt = now; blink.onShake(); schedule(0); }
            shakeLast = g;
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) { }

        private void schedule(long delay) {
            handler.removeCallbacks(frame);
            if (visible) handler.postDelayed(frame, delay);
        }

        private void drawFrame() {
            if (!visible) return;
            long now = SystemClock.uptimeMillis();
            float dt = lastFrame == 0 ? 1 / 30f : Math.min(.1f, (now - lastFrame) / 1000f);
            lastFrame = now;
            blink.step(dt);
            SurfaceHolder holder = getSurfaceHolder();
            Canvas c = null;
            try {
                c = Build.VERSION.SDK_INT >= 26 ? holder.lockHardwareCanvas() : holder.lockCanvas();
                if (c != null) blink.draw(c);
            } catch (Exception ignored) {
            } finally {
                if (c != null) try { holder.unlockCanvasAndPost(c); } catch (Exception ignored) { }
            }
            schedule(blink.busy() ? 33 : 66);
        }
    }
}
