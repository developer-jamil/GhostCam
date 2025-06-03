package com.jll.ghostcam;


import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraCaptureSession;
import android.media.MediaPlayer;
import android.os.Handler;
import android.view.Surface;

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

    // Reuse the same surface and MediaPlayer
    private static Surface c2_fake_surface = null;
    private static MediaPlayer c2_mediaPlayer = null;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // (Camera1 hooks go here)

        // Camera2 hook:
        try {
            // Hook createCaptureSession for Camera2
            XposedHelpers.findAndHookMethod(
                    "android.hardware.camera2.CameraDevice",
                    lpparam.classLoader,
                    "createCaptureSession",
                    List.class,
                    CameraCaptureSession.StateCallback.class,
                    Handler.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            XposedBridge.log(TAG + " Camera2: createCaptureSession hook triggered.");
                            File videoFile = new File(VIDEO_PATH);
                            if (!videoFile.exists() || new File(DISABLE_PATH).exists()) {
                                XposedBridge.log(TAG + " Camera2: Skipping hook due to video/disable file status. VIDEO_PATH: " + VIDEO_PATH);
                                return;
                            }

                            @SuppressWarnings("unchecked")
                            List<Surface> surfaces = (List<Surface>) param.args[0];
                            if (c2_fake_surface == null || !c2_fake_surface.isValid()) {
                                // Create a new SurfaceTexture (with an arbitrary texture ID)
                                SurfaceTexture fakeTexture = new SurfaceTexture(100);
                                c2_fake_surface = new Surface(fakeTexture);
                                XposedBridge.log(TAG + " Camera2: Created new c2_fake_surface.");
                            }

                            // Replace the first surface (usually the preview surface) with our fake surface
                            List<Surface> newSurfaces = new ArrayList<>(surfaces);
                            if (!newSurfaces.isEmpty()) {
                                newSurfaces.set(0, c2_fake_surface);
                                param.args[0] = newSurfaces;
                                XposedBridge.log(TAG + " Camera2: Replaced preview surface with fake surface.");
                            }

                            // Setup MediaPlayer to play video on our fake surface
                            if (c2_mediaPlayer != null) {
                                c2_mediaPlayer.release();
                                c2_mediaPlayer = null;
                            }
                            c2_mediaPlayer = new MediaPlayer();
                            c2_mediaPlayer.setSurface(c2_fake_surface);
                            try {
                                c2_mediaPlayer.setDataSource(VIDEO_PATH);
                                c2_mediaPlayer.setLooping(true);
                                if (new File(NO_SILENT_PATH).exists()) {
                                    XposedBridge.log(TAG + " Camera2: Video audio enabled (no_silent.jpg exists).");
                                } else {
                                    c2_mediaPlayer.setVolume(0.0f, 0.0f);
                                    XposedBridge.log(TAG + " Camera2: Video audio muted.");
                                }
                                c2_mediaPlayer.setOnPreparedListener(mp -> {
                                    XposedBridge.log(TAG + " Camera2: MediaPlayer prepared, starting playback.");
                                    mp.start();
                                });
                                c2_mediaPlayer.prepareAsync();
                                XposedBridge.log(TAG + " Camera2: MediaPlayer prepareAsync called for c2_fake_surface.");
                            } catch (IOException e) {
                                XposedBridge.log(TAG + " Camera2: Error preparing MediaPlayer: " + e.getMessage());
                                e.printStackTrace();
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error in Camera2 GhostCam hook: " + t.getMessage());
        }
    }
}