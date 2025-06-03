package com.jll.ghostcam;

import android.annotation.SuppressLint;
import android.graphics.ImageFormat;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.view.Surface;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.XposedBridge;

public class VideoToFrames implements Runnable {
    private static final String TAG = "GhostCamVideoToFrames";
    private static final long DEFAULT_TIMEOUT_US = 10000;
    private String videoFilePath;
    private OutputImageFormat outputImageFormat;
    private Surface play_surf;
    private boolean stopDecode = false;
    private Thread childThread;
    private ExecutorService executorService = Executors.newCachedThreadPool();

    // HookMain.data_buffer should ideally be updated from a continuous stream
    // instead of blocking a while loop.
    // However, keeping the structure as it was for now, with focus on getDataFromImage.
    public volatile static byte[] data_buffer = null; // Changed to public static volatile for HookMain access

    public void setSaveFrames(String dummy, OutputImageFormat outputImageFormat) {
        this.outputImageFormat = outputImageFormat;
    }

    public void set_surfcae(Surface surface) {
        if (surface != null) {
            this.play_surf = surface;
        }
    }

    public void decode(String path) {
        this.videoFilePath = path;
        XposedBridge.log(TAG + " Starting video decode for: " + path);
        if (childThread == null || !childThread.isAlive()) { // Ensure thread is only started once
            childThread = new Thread(this, "decode");
            childThread.start();
        } else {
            XposedBridge.log(TAG + " Decode thread already running.");
        }
    }

    public void stopDecode() {
        stopDecode = true;
        if (childThread != null) {
            childThread.interrupt(); // Interrupt the thread to break out of blocking calls
            XposedBridge.log(TAG + " Decode thread interrupted.");
        }
    }

    @SuppressLint("WrongConstant") // For MediaFormat.KEY_COLOR_FORMAT
    @Override
    public void run() {
        MediaExtractor extractor = null;
        MediaCodec decoder = null;
        try {
            File videoFile = new File(videoFilePath);
            if (!videoFile.exists()) {
                XposedBridge.log(TAG + " Video file not found: " + videoFilePath);
                return;
            }

            extractor = new MediaExtractor();
            extractor.setDataSource(videoFilePath);

            int trackIndex = selectTrack(extractor);
            if (trackIndex < 0) {
                XposedBridge.log(TAG + " No video track found in " + videoFilePath);
                return;
            }

            extractor.selectTrack(trackIndex);
            MediaFormat format = extractor.getTrackFormat(trackIndex);
            String mime = format.getString(MediaFormat.KEY_MIME);

            decoder = MediaCodec.createDecoderByType(mime);

            // Configure decoder to output to a Surface, if available and desired
            // If you are using this for onPreviewFrame injection, you might decode to a ByteBuffer or ImageReader
            // For general playback or getting Image objects, using a Surface is common.
            // If you want to get Image objects to convert to NV21, you'd typically use MediaCodec.configure(format, null, ...)
            // and then retrieve frames from the output buffer.
            decoder.configure(format, null, null, 0); // null surface if we want to get raw image data
            decoder.start();
            XposedBridge.log(TAG + " MediaCodec decoder started.");

            ByteBuffer[] inputBuffers = decoder.getInputBuffers();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean sawInputEOS = false;
            long presentationTimeUs = 0;

            while (!Thread.interrupted() && !stopDecode) {
                if (!sawInputEOS) {
                    int inIndex = decoder.dequeueInputBuffer(DEFAULT_TIMEOUT_US);
                    if (inIndex >= 0) {
                        ByteBuffer buffer = inputBuffers[inIndex];
                        int sampleSize = extractor.readSampleData(buffer, 0);
                        if (sampleSize < 0) {
                            XposedBridge.log(TAG + " Input EOS reached.");
                            sawInputEOS = true;
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        } else {
                            presentationTimeUs = extractor.getSampleTime();
                            decoder.queueInputBuffer(inIndex, 0, sampleSize, presentationTimeUs, 0);
                            extractor.advance();
                        }
                    }
                }

                int outIndex = decoder.dequeueOutputBuffer(info, DEFAULT_TIMEOUT_US);
                switch (outIndex) {
                    case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                        XposedBridge.log(TAG + " Output buffers changed.");
                        // inputBuffers = decoder.getOutputBuffers(); // No longer used post API 21
                        break;
                    case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                        MediaFormat newFormat = decoder.getOutputFormat();
                        XposedBridge.log(TAG + " Output format changed to " + newFormat);
                        break;
                    case MediaCodec.INFO_TRY_AGAIN_LATER:
                        // XposedBridge.log(TAG + " Dequeue output buffer timed out.");
                        break;
                    default:
                        // Get the output image
                        Image image = decoder.getOutputImage(outIndex);
                        if (image != null) {
                            // Convert Image to NV21 format and update data_buffer
                            data_buffer = getDataFromImage(image, ImageFormat.NV21); // Pass ImageFormat.NV21 as target
                            image.close(); // Important to close the image
                        } else {
                            XposedBridge.log(TAG + " Decoder output image is null.");
                        }
                        decoder.releaseOutputBuffer(outIndex, false);

                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            XposedBridge.log(TAG + " Output EOS reached.");
                            stopDecode = true; // Stop decoding loop
                        }
                        break;
                }
            }
        } catch (Exception e) {
            XposedBridge.log(TAG + " Error during video decoding: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (decoder != null) {
                decoder.stop();
                decoder.release();
                XposedBridge.log(TAG + " MediaCodec decoder stopped and released.");
            }
            if (extractor != null) {
                extractor.release();
                XposedBridge.log(TAG + " MediaExtractor released.");
            }
            // Reset data_buffer when decoding stops
            data_buffer = null;
            XposedBridge.log(TAG + " Video decoding finished.");
        }
    }

    private int selectTrack(MediaExtractor extractor) {
        int trackCount = extractor.getTrackCount();
        for (int i = 0; i < trackCount; i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Converts a YUV_420_888 Image to an NV21 byte array.
     * THIS IS A CRITICAL METHOD THAT NEEDS A PROPER IMPLEMENTATION.
     * The current implementation is a placeholder.
     *
     * @param image The Image object to convert. Expected to be YUV_420_888.
     * @param colorFormat The target color format, typically ImageFormat.NV21.
     * @return A byte array in NV21 format, or null if conversion fails/not implemented.
     */
    private static byte[] getDataFromImage(Image image, int colorFormat) {
        if (image == null || image.getFormat() != ImageFormat.YUV_420_888) {
            XposedBridge.log(TAG + " getDataFromImage: Invalid image format or null image. Expected YUV_420_888.");
            return null;
        }

        // --- PLACEHOLDER IMPLEMENTATION ---
        // You MUST replace this with actual YUV_420_888 to NV21 conversion logic.
        // This is complex due to plane strides, pixel strides, and UV interleaving.
        // Search for "YUV_420_888 to NV21 conversion Android" for reliable implementations.
        // Example resources:
        // - https://stackoverflow.com/questions/42603823/yuv-420-888-to-nv21-byte-array
        // - https://github.com/google/ExoPlayer/blob/release-v2/library/common/src/main/java/com/google/android/exoplayer2/util/YuvData.java (YuvData.toNv21)
        // - Android's CameraX library also has utility functions for this.

        int width = image.getWidth();
        int height = image.getHeight();
        Image.Plane[] planes = image.getPlanes();

        // Calculate NV21 buffer size: Y plane (W*H) + UV plane (W*H/2) = W*H*1.5
        byte[] nv21Bytes = new byte[width * height * 3 / 2];

        // Copy Y plane
        ByteBuffer yBuffer = planes[0].getBuffer();
        int ySize = yBuffer.remaining();
        yBuffer.get(nv21Bytes, 0, ySize);

        // Copy U and V planes, interleaving them into the NV21 format
        // NV21 is YYYY... VUVU... (V comes before U in the interleaved plane)
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();

        int uvIndex = ySize; // Start after Y plane
        int uvWidth = width / 2;
        int uvHeight = height / 2;

        // Iterate through the U and V planes and interleave them.
        // Pay close attention to rowStride and pixelStride.
        int uRowStride = planes[1].getRowStride();
        int uPixelStride = planes[1].getPixelStride();
        int vRowStride = planes[2].getRowStride();
        int vPixelStride = planes[2].getPixelStride();

        // This simplified loop assumes pixelStride is 1 for both U and V, and rowStride matches width.
        // Real implementation must account for varying strides.
        for (int row = 0; row < uvHeight; row++) {
            for (int col = 0; col < uvWidth; col++) {
                int uIdx = row * uRowStride + col * uPixelStride;
                int vIdx = row * vRowStride + col * vPixelStride;

                // NV21: V then U (Vu)
                if (uvIndex < nv21Bytes.length - 1) { // Prevent ArrayIndexOutOfBounds
                    if (vIdx < vBuffer.limit()) { // Ensure vBuffer has enough data
                        nv21Bytes[uvIndex++] = vBuffer.get(vIdx);
                    } else {
                        nv21Bytes[uvIndex++] = 0; // Pad with zero if out of bounds
                    }
                    if (uIdx < uBuffer.limit()) { // Ensure uBuffer has enough data
                        nv21Bytes[uvIndex++] = uBuffer.get(uIdx);
                    } else {
                        nv21Bytes[uvIndex++] = 0; // Pad with zero
                    }
                }
            }
        }
        XposedBridge.log(TAG + " getDataFromImage: Placeholder NV21 conversion executed. Verify implementation.");
        return nv21Bytes;
    }
}