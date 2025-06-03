package com.jll.ghostcam;

import android.app.Application;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.view.Surface;
import android.view.SurfaceHolder;

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
    private static final String TAG = "GhostCamHook";
    public static final String VIDEO_PATH = Environment.getExternalStorageDirectory().getAbsolutePath() + "/DCIM/Camera1/ghost.mp4";
    public static final String DISABLE_PATH = Environment.getExternalStorageDirectory().getAbsolutePath() + "/DCIM/Camera1/disable.jpg";
    public static final String NO_TOAST_PATH = Environment.getExternalStorageDirectory().getAbsolutePath() + "/DCIM/Camera1/no_toast.jpg";
    public static final String NO_SILENT_PATH = Environment.getExternalStorageDirectory().getAbsolutePath() + "/DCIM/Camera1/no-silent.jpg";

    // Global variables for Camera1
    public static Surface c1_fake_surface = null;
    public static SurfaceTexture c1_fake_texture = null;
    public static MediaPlayer c1_mediaPlayer = null; // Renamed to avoid clash
    public static Camera mcamera1 = null;

    // Global variables for Camera2
    public static Surface c2_virtual_surface = null;
    public static SurfaceTexture c2_virtual_surfaceTexture = null;
    public static MediaPlayer c2_mediaPlayer = null; // Renamed to avoid clash

    public Context toast_content;
    public boolean need_to_show_toast = true;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        XposedBridge.log(TAG + " Loaded package: " + lpparam.packageName);

        // Hook app context to get a Context for Toast, file access, etc.
        XposedHelpers.findAndHookMethod("android.app.Instrumentation", lpparam.classLoader, "callApplicationOnCreate", Application.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.args[0] instanceof Application) {
                    toast_content = ((Application) param.args[0]).getApplicationContext();
                    XposedBridge.log(TAG + " Application context obtained for package: " + lpparam.packageName);
                }
            }
        });

        // --- Camera1 API Hooks ---

        // Camera1 setPreviewTexture: Intercepts where the app tells the camera to send its preview.
        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "setPreviewTexture", SurfaceTexture.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                XposedBridge.log(TAG + " Camera1: setPreviewTexture hook triggered.");
                if (!new File(VIDEO_PATH).exists() || new File(DISABLE_PATH).exists()) {
                    XposedBridge.log(TAG + " Camera1: setPreviewTexture skipped due to video/disable file status.");
                    return;
                }
                if (c1_fake_texture == null) {
                    // Create a new SurfaceTexture to redirect camera output to
                    c1_fake_texture = new SurfaceTexture(10);
                    c1_fake_surface = new Surface(c1_fake_texture); // Create a Surface from it
                    XposedBridge.log(TAG + " Camera1: Created c1_fake_texture and c1_fake_surface.");
                }
                // Redirect the app's camera preview to your fake texture
                param.args[0] = c1_fake_texture;
                XposedBridge.log(TAG + " Camera1: setPreviewTexture redirected to c1_fake_texture.");
            }
        });

        // Camera1 setPreviewDisplay: Older method, also intercepts where the app tells the camera to send its preview.
        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "setPreviewDisplay", SurfaceHolder.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                XposedBridge.log(TAG + " Camera1: setPreviewDisplay hook triggered.");
                if (!new File(VIDEO_PATH).exists() || new File(DISABLE_PATH).exists()) {
                    XposedBridge.log(TAG + " Camera1: setPreviewDisplay skipped due to video/disable file status.");
                    return;
                }
                mcamera1 = (Camera) param.thisObject;
                // Store the original holder if needed, but we'll redirect the Camera object itself
                // ori_holder = (SurfaceHolder) param.args[0]; // Not directly used for playback now

                if (c1_fake_texture == null) {
                    c1_fake_texture = new SurfaceTexture(11);
                    c1_fake_surface = new Surface(c1_fake_texture);
                    XposedBridge.log(TAG + " Camera1: Created c1_fake_texture and c1_fake_surface for setPreviewDisplay.");
                }

                try {
                    // Tell the Camera object to send its preview to YOUR fake texture
                    mcamera1.setPreviewTexture(c1_fake_texture);
                    XposedBridge.log(TAG + " Camera1: mcamera1.setPreviewTexture called with c1_fake_texture.");
                } catch (IOException e) {
                    XposedBridge.log(TAG + " Camera1: Error setting fake preview texture: " + e.getMessage());
                    throw new RuntimeException("GhostCam: Failed to set fake preview texture for Camera1", e);
                }
                // Prevent the original method from being called, as we've already redirected
                param.setResult(null);
            }
        });

        // Camera1 startPreview: When the camera officially starts sending preview data.
        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "startPreview", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                XposedBridge.log(TAG + " Camera1: startPreview hook triggered.");
                if (!new File(VIDEO_PATH).exists() || new File(DISABLE_PATH).exists()) {
                    XposedBridge.log(TAG + " Camera1: startPreview skipped due to video/disable file status.");
                    return;
                }

                // Crucially: Initialize and start MediaPlayer here, playing to YOUR fake surface
                if (c1_fake_surface != null && c1_fake_surface.isValid()) {
                    if (c1_mediaPlayer != null) {
                        c1_mediaPlayer.release();
                        c1_mediaPlayer = null; // Ensure it's nulled after release
                        XposedBridge.log(TAG + " Camera1: Releasing existing MediaPlayer for startPreview.");
                    }
                    c1_mediaPlayer = new MediaPlayer();
                    // Set the MediaPlayer to play on YOUR fake surface, which the app thinks is camera output
                    c1_mediaPlayer.setSurface(c1_fake_surface);
                    try {
                        c1_mediaPlayer.setDataSource(VIDEO_PATH);
                        c1_mediaPlayer.setLooping(true);
                        if (new File(NO_SILENT_PATH).exists()) { // Check if 'no_silent' file exists
                            XposedBridge.log(TAG + " Camera1: Video audio enabled (no_silent.jpg exists).");
                        } else {
                            c1_mediaPlayer.setVolume(0.0f, 0.0f); // Mute audio
                            XposedBridge.log(TAG + " Camera1: Video audio muted.");
                        }
                        c1_mediaPlayer.setOnPreparedListener(MediaPlayer::start);
                        c1_mediaPlayer.prepareAsync(); // Use prepareAsync to avoid blocking the thread
                        XposedBridge.log(TAG + " Camera1: MediaPlayer preparedAsync for c1_fake_surface.");
                    } catch (IOException e) {
                        XposedBridge.log(TAG + " Camera1: Error preparing MediaPlayer in startPreview: " + e.getMessage());
                        e.printStackTrace();
                    }
                } else {
                    XposedBridge.log(TAG + " Camera1: c1_fake_surface is null or invalid during startPreview. Video will not play.");
                }
            }
        });

        // Camera1 stopPreview: Release MediaPlayer resources when camera preview stops.
        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "stopPreview", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                XposedBridge.log(TAG + " Camera1: stopPreview hook triggered.");
                if (c1_mediaPlayer != null) {
                    c1_mediaPlayer.stop();
                    c1_mediaPlayer.release();
                    c1_mediaPlayer = null;
                    XposedBridge.log(TAG + " Camera1: MediaPlayer released due to stopPreview.");
                }
            }
        });

        // --- Camera1 setPreviewCallback - (Temporarily commented out) ---
        // This method is for injecting raw frame data (e.g., NV21).
        // It requires a fully working VideoToFrames.getDataFromImage conversion,
        // and careful handling of frame rates/buffering to avoid ANRs.
        // For simple video preview replacement, playing to a Surface is more efficient.
        /*
        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "setPreviewCallback", Camera.PreviewCallback.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args[0] != null && new File(VIDEO_PATH).exists() && !new File(DISABLE_PATH).exists()) {
                    XposedBridge.log(TAG + " Camera1: setPreviewCallback hook triggered. Attempting frame injection.");
                    // process_callback(param); // This needs to be carefully managed in a separate thread
                    // to prevent blocking the camera thread and causing ANRs.
                    // Also relies heavily on VideoToFrames.getDataFromImage being correct.
                }
            }
        });
        */


        // --- Camera2 API Hooks ---

        // Camera2 openCamera: Hook to setup virtual surfaces and wrap the original StateCallback.
        XposedHelpers.findAndHookMethod("android.hardware.camera2.CameraManager", lpparam.classLoader, "openCamera",
                String.class, CameraDevice.StateCallback.class, Handler.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        XposedBridge.log(TAG + " Camera2: openCamera hook triggered.");
                        if (new File(DISABLE_PATH).exists()) {
                            XposedBridge.log(TAG + " Camera2: openCamera skipped due to disable file status.");
                            return;
                        }

                        // Setup a virtual surface for Camera2 if not already done
                        if (c2_virtual_surfaceTexture == null) {
                            c2_virtual_surfaceTexture = new SurfaceTexture(15);
                            c2_virtual_surface = new Surface(c2_virtual_surfaceTexture);
                            XposedBridge.log(TAG + " Camera2: Initialized c2_virtual_surface and texture.");
                        }

                        // Wrap the original CameraDevice.StateCallback to control MediaPlayer
                        final CameraDevice.StateCallback originalCallback = (CameraDevice.StateCallback) param.args[1];
                        param.args[1] = new CameraDevice.StateCallback() {
                            @Override
                            public void onOpened(CameraDevice camera) {
                                XposedBridge.log(TAG + " Camera2: onOpened called for Camera ID: " + camera.getId());
                                // Call original callback first
                                originalCallback.onOpened(camera);

                                // Start MediaPlayer to play to our virtual surface when the camera is opened
                                if (c2_virtual_surface != null && c2_virtual_surface.isValid()) {
                                    if (c2_mediaPlayer != null) {
                                        c2_mediaPlayer.release();
                                        c2_mediaPlayer = null;
                                        XposedBridge.log(TAG + " Camera2: Releasing existing c2_mediaPlayer.");
                                    }
                                    c2_mediaPlayer = new MediaPlayer();
                                    c2_mediaPlayer.setSurface(c2_virtual_surface);
                                    try {
                                        c2_mediaPlayer.setDataSource(VIDEO_PATH);
                                        c2_mediaPlayer.setLooping(true);
                                        if (new File(NO_SILENT_PATH).exists()) {
                                            XposedBridge.log(TAG + " Camera2: Video audio enabled (no_silent.jpg exists).");
                                        } else {
                                            c2_mediaPlayer.setVolume(0.0f, 0.0f);
                                            XposedBridge.log(TAG + " Camera2: Video audio muted.");
                                        }
                                        c2_mediaPlayer.setOnPreparedListener(MediaPlayer::start);
                                        c2_mediaPlayer.prepareAsync(); // Use prepareAsync
                                        XposedBridge.log(TAG + " Camera2: MediaPlayer preparedAsync for virtual surface.");
                                    } catch (IOException e) {
                                        XposedBridge.log(TAG + " Camera2: Error setting up MediaPlayer in onOpened: " + e.getMessage());
                                        e.printStackTrace();
                                    }
                                } else {
                                    XposedBridge.log(TAG + " Camera2: c2_virtual_surface is null or invalid during onOpened. Video will not play.");
                                }
                            }

                            @Override
                            public void onDisconnected(CameraDevice camera) {
                                XposedBridge.log(TAG + " Camera2: onDisconnected called.");
                                originalCallback.onDisconnected(camera);
                                if (c2_mediaPlayer != null) {
                                    c2_mediaPlayer.release();
                                    c2_mediaPlayer = null;
                                    XposedBridge.log(TAG + " Camera2: MediaPlayer released on disconnect.");
                                }
                            }

                            @Override
                            public void onError(CameraDevice camera, int error) {
                                XposedBridge.log(TAG + " Camera2: onError called, error code: " + error);
                                originalCallback.onError(camera, error);
                                if (c2_mediaPlayer != null) {
                                    c2_mediaPlayer.release();
                                    c2_mediaPlayer = null;
                                    XposedBridge.log(TAG + " Camera2: MediaPlayer released on error.");
                                }
                            }

                            // Implement other methods as needed, e.g., onClosed
                            @Override
                            public void onClosed(CameraDevice camera) {
                                XposedBridge.log(TAG + " Camera2: onClosed called.");
                                originalCallback.onClosed(camera);
                                if (c2_mediaPlayer != null) {
                                    c2_mediaPlayer.release();
                                    c2_mediaPlayer = null;
                                    XposedBridge.log(TAG + " Camera2: MediaPlayer released on closed.");
                                }
                            }
                        };
                    }
                });

        // Camera2 createCaptureSession (for older API <= P)
        XposedHelpers.findAndHookMethod("android.hardware.camera2.CameraDevice", lpparam.classLoader,
                "createCaptureSession", List.class, CameraCaptureSession.StateCallback.class, Handler.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        XposedBridge.log(TAG + " Camera2: createCaptureSession (old) hook triggered.");
                        if (new File(DISABLE_PATH).exists() || c2_virtual_surface == null) {
                            XposedBridge.log(TAG + " Camera2: createCaptureSession (old) skipped.");
                            return;
                        }

                        List<Surface> originalOutputs = (List<Surface>) param.args[0];
                        List<Surface> newOutputs = new ArrayList<>(); // Use ArrayList

                        if (originalOutputs != null) {
                            // Replace all original surfaces with our virtual surface
                            for (Surface surface : originalOutputs) {
                                // You might want to be more selective, e.g., only replace preview surfaces
                                // based on their characteristics (size, format, etc.).
                                // For a general "GhostCam", replacing all might be acceptable.
                                newOutputs.add(c2_virtual_surface);
                                XposedBridge.log(TAG + " Camera2: Replaced a surface in createCaptureSession (old).");
                            }
                        } else {
                            XposedBridge.log(TAG + " Camera2: originalOutputs list is null in createCaptureSession (old).");
                        }
                        param.args[0] = newOutputs; // Set the modified list back

                        // Wrap the CaptureSession.StateCallback if needed (e.g., for further logging or cleanup)
                        final CameraCaptureSession.StateCallback originalSessionCallback = (CameraCaptureSession.StateCallback) param.args[1];
                        param.args[1] = new CameraCaptureSession.StateCallback() {
                            @Override
                            public void onConfigured(CameraCaptureSession session) {
                                XposedBridge.log(TAG + " Camera2 (old): Capture session configured.");
                                originalSessionCallback.onConfigured(session);
                                // MediaPlayer should already be playing to c2_virtual_surface from onOpened.
                            }

                            @Override
                            public void onConfigureFailed(CameraCaptureSession session) {
                                XposedBridge.log(TAG + " Camera2 (old): Capture session configuration failed.");
                                originalSessionCallback.onConfigureFailed(session);
                            }

                            @Override
                            public void onReady(CameraCaptureSession session) {
                                XposedBridge.log(TAG + " Camera2 (old): Capture session ready.");
                                originalSessionCallback.onReady(session);
                            }

                            @Override
                            public void onActive(CameraCaptureSession session) {
                                XposedBridge.log(TAG + " Camera2 (old): Capture session active.");
                                originalSessionCallback.onActive(session);
                            }

                            @Override
                            public void onClosed(CameraCaptureSession session) {
                                XposedBridge.log(TAG + " Camera2 (old): Capture session closed.");
                                originalSessionCallback.onClosed(session);
                                // MediaPlayer resources are released in CameraDevice.StateCallback.onClosed
                                // or onDisconnected/onError if those were triggered.
                            }
                        };
                    }
                });

        // Camera2 createCaptureSession (for API P (28) and above using SessionConfiguration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            XposedHelpers.findAndHookMethod("android.hardware.camera2.CameraDevice", lpparam.classLoader,
                    "createCaptureSession", SessionConfiguration.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            XposedBridge.log(TAG + " Camera2: createCaptureSession (P+) hook triggered.");
                            if (new File(DISABLE_PATH).exists() || c2_virtual_surface == null) {
                                XposedBridge.log(TAG + " Camera2: createCaptureSession (P+) skipped.");
                                return;
                            }

                            SessionConfiguration originalConfig = (SessionConfiguration) param.args[0];
                            List<OutputConfiguration> originalOutputs = originalConfig.getOutputConfigurations();
                            List<OutputConfiguration> newOutputs = new ArrayList<>(); // Use ArrayList

                            if (originalOutputs != null) {
                                for (OutputConfiguration oc : originalOutputs) {
                                    // Create a new OutputConfiguration with your virtual surface
                                    // For simplicity, just replace with new OutputConfiguration(c2_virtual_surface)
                                    // In a real scenario, you might want to preserve stream properties from `oc`.
                                    // However, `OutputConfiguration` doesn't expose its original Surface or its properties easily.
                                    // So, a direct replacement is often the only way without deeper reflection.
                                    OutputConfiguration newOc = new OutputConfiguration(c2_virtual_surface);
                                    // If oc.getPhysicalCameraId() is available and needed, copy it too.
                                    // newOc.setPhysicalCameraId(oc.getPhysicalCameraId()); // Requires API 28+ for this method
                                    newOutputs.add(newOc);
                                    XposedBridge.log(TAG + " Camera2: Replaced an OutputConfiguration surface (P+).");
                                }
                            } else {
                                XposedBridge.log(TAG + " Camera2: originalOutputs list is null in createCaptureSession (P+).");
                            }

                            // Wrap the StateCallback as well, similar to the older createCaptureSession hook
                            final CameraCaptureSession.StateCallback originalSessionCallback = originalConfig.getStateCallback();
                            SessionConfiguration newConfig = new SessionConfiguration(
                                    originalConfig.getSessionType(),
                                    newOutputs,
                                    originalConfig.getExecutor(),
                                    new CameraCaptureSession.StateCallback() {
                                        @Override
                                        public void onConfigured(CameraCaptureSession session) {
                                            XposedBridge.log(TAG + " Camera2 (P+): Capture session configured.");
                                            originalSessionCallback.onConfigured(session);
                                        }

                                        @Override
                                        public void onConfigureFailed(CameraCaptureSession session) {
                                            XposedBridge.log(TAG + " Camera2 (P+): Capture session configuration failed.");
                                            originalSessionCallback.onConfigureFailed(session);
                                        }
                                        // Implement other methods like onReady, onActive, onClosed
                                        @Override
                                        public void onReady(CameraCaptureSession session) {
                                            XposedBridge.log(TAG + " Camera2 (P+): Capture session ready.");
                                            originalSessionCallback.onReady(session);
                                        }

                                        @Override
                                        public void onActive(CameraCaptureSession session) {
                                            XposedBridge.log(TAG + " Camera2 (P+): Capture session active.");
                                            originalSessionCallback.onActive(session);
                                        }

                                        @Override
                                        public void onClosed(CameraCaptureSession session) {
                                            XposedBridge.log(TAG + " Camera2 (P+): Capture session closed.");
                                            originalSessionCallback.onClosed(session);
                                            // MediaPlayer resources are released in CameraDevice.StateCallback.onClosed
                                            // or onDisconnected/onError if those were triggered.
                                        }
                                    }
                            );
                            param.args[0] = newConfig; // Set the modified configuration back
                        }
                    });
        }

        // Camera2 addTarget: Redirects the target surface for individual CaptureRequests.
        // This is still useful even with createCaptureSession hooks to ensure specific requests
        // (like preview requests) are directed to the virtual surface.
        XposedHelpers.findAndHookMethod("android.hardware.camera2.CaptureRequest$Builder", lpparam.classLoader, "addTarget", Surface.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                XposedBridge.log(TAG + " Camera2: CaptureRequest.Builder addTarget hook triggered.");
                if (c2_virtual_surface != null && new File(VIDEO_PATH).exists() && !new File(DISABLE_PATH).exists()) {
                    param.args[0] = c2_virtual_surface;
                    XposedBridge.log(TAG + " Camera2: CaptureRequest.Builder addTarget redirected to virtual surface.");
                } else {
                    XposedBridge.log(TAG + " Camera2: CaptureRequest.Builder addTarget skipped.");
                }
            }
        });

        // Camera2 build: (Removed MediaPlayer initialization from here)
        // MediaPlayer should now be initialized and started in CameraDevice.StateCallback.onOpened.
        // This hook is no longer necessary for MediaPlayer playback.
        /*
        XposedHelpers.findAndHookMethod("android.hardware.camera2.CaptureRequest$Builder", lpparam.classLoader, "build", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (c2_virtual_surface != null && new File(VIDEO_PATH).exists() && !new File(DISABLE_PATH).exists()) {
                    // This logic is moved to CameraDevice.StateCallback.onOpened
                    // XposedBridge.log(TAG + " Camera2: CaptureRequest.Builder build hook triggered (MediaPlayer logic removed).");
                }
            }
        });
        */
    }
}