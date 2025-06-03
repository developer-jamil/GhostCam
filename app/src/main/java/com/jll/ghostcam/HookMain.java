package com.jll.ghostcam;

import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class HookMain implements IXposedHookLoadPackage {
    private static final String TAG = "GhostCamXposed";
    private static final String VIDEO_PATH = "/sdcard/DCIM/Camera1/ghost.mp4";
    private static final String DISABLE_PATH = "/sdcard/DCIM/Camera1/disable.jpg";
    private static final String NO_SILENT_PATH = "/sdcard/DCIM/Camera1/no_silent.jpg";

    private static Surface c2_fake_surface = null;
    private static MediaPlayer c2_mediaPlayer = null;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // Only hook user apps (skip system processes)
        if (lpparam.packageName == null ||
                lpparam.packageName.startsWith("android.") ||
                lpparam.packageName.startsWith("com.android.") ||
                lpparam.packageName.equals("de.robv.android.xposed.installer")) {
            return;
        }
        XposedBridge.log(TAG + ": Loaded app = " + lpparam.packageName);

        try {
            // Camera2: Hook createCaptureSession
            XposedHelpers.findAndHookMethod(
                    "android.hardware.camera2.CameraDevice",
                    lpparam.classLoader,
                    "createCaptureSession",
                    List.class,
                    CameraCaptureSession.StateCallback.class,
                    android.os.Handler.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            XposedBridge.log(TAG + ": createCaptureSession hook triggered in " + lpparam.packageName);

                            // Toast the video path for debugging
                            try {
                                // Get any Context (safe for Toast)
                                Class<?> ActivityThread = Class.forName("android.app.ActivityThread");
                                Object currentActivityThread = XposedHelpers.callStaticMethod(ActivityThread, "currentActivityThread");
                                final android.content.Context context = (android.content.Context) XposedHelpers.callMethod(currentActivityThread, "getSystemContext");

                                if (context != null) {
                                    Handler handler = new Handler(Looper.getMainLooper());
                                    handler.post(() -> {
                                        Toast.makeText(context,
                                                "GhostCam\nPackage: " + lpparam.packageName + "\nPath: " + VIDEO_PATH,
                                                Toast.LENGTH_LONG).show();
                                    });
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + ": Toast failed: " + t.getMessage());
                            }

                            File videoFile = new File(VIDEO_PATH);
                            if (!videoFile.exists() || new File(DISABLE_PATH).exists()) {
                                XposedBridge.log(TAG + ": Skipping hook (video file missing or disable flag present).");
                                return;
                            }

                            @SuppressWarnings("unchecked")
                            List<Surface> surfaces = (List<Surface>) param.args[0];
                            XposedBridge.log(TAG + ": Original surfaces count: " + surfaces.size());

                            // Create or reuse fake surface
                            if (c2_fake_surface == null || !c2_fake_surface.isValid()) {
                                SurfaceTexture fakeTexture = new SurfaceTexture(100);
                                fakeTexture.setDefaultBufferSize(1920, 1080); // Set a high enough resolution
                                c2_fake_surface = new Surface(fakeTexture);
                                XposedBridge.log(TAG + ": Created new fake SurfaceTexture/Surface.");
                            }

                            // Aggressively replace all surfaces with the fake surface
                            List<Surface> newSurfaces = new ArrayList<>(surfaces);
                            for (int i = 0; i < newSurfaces.size(); i++) {
                                newSurfaces.set(i, c2_fake_surface);
                            }
                            param.args[0] = newSurfaces;
                            XposedBridge.log(TAG + ": Replaced all surfaces with fake surface.");

                            // Setup MediaPlayer to play video on our fake surface
                            if (c2_mediaPlayer != null) {
                                c2_mediaPlayer.release();
                                c2_mediaPlayer = null;
                                XposedBridge.log(TAG + ": Released previous MediaPlayer.");
                            }
                            c2_mediaPlayer = new MediaPlayer();
                            c2_mediaPlayer.setSurface(c2_fake_surface);
                            try {
                                c2_mediaPlayer.setDataSource(VIDEO_PATH);
                                c2_mediaPlayer.setLooping(true);
                                if (new File(NO_SILENT_PATH).exists()) {
                                    XposedBridge.log(TAG + ": Video audio enabled (no_silent.jpg exists).");
                                } else {
                                    c2_mediaPlayer.setVolume(0f, 0f);
                                    XposedBridge.log(TAG + ": Video audio muted.");
                                }
                                c2_mediaPlayer.setOnPreparedListener(mp -> {
                                    XposedBridge.log(TAG + ": MediaPlayer prepared, starting playback.");
                                    mp.start();
                                });
                                c2_mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                                    XposedBridge.log(TAG + ": MediaPlayer error: " + what + ", " + extra);
                                    return false;
                                });
                                c2_mediaPlayer.prepareAsync();
                                XposedBridge.log(TAG + ": MediaPlayer prepareAsync called for fake surface.");
                            } catch (IOException e) {
                                XposedBridge.log(TAG + ": Error preparing MediaPlayer: " + e.getMessage());
                                e.printStackTrace();
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Error in Camera2 hook: " + t.getMessage());
        }
    }
}