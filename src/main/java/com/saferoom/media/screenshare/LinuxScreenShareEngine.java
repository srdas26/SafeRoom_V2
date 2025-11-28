package com.saferoom.media.screenshare;

import dev.onvoid.webrtc.media.video.CustomVideoSource;
import dev.onvoid.webrtc.media.video.NativeI420Buffer;
import dev.onvoid.webrtc.media.video.VideoFrame;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Linux-specific screen capture engine that uses JavaCV/FFmpeg to grab desktop frames (PipeWire first,
 * X11 as a fallback) and pushes them into a WebRTC {@link CustomVideoSource}.
 *
 * Pipeline:
 *   FFmpeg (pipewire:/x11grab → YUV420P) → JavaCV Frame/AVFrame → NativeI420Buffer → WebRTC CustomVideoSource
 */
public final class LinuxScreenShareEngine implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(LinuxScreenShareEngine.class.getName());
    private static final int TARGET_FPS = 30;
    private static final long FRAME_INTERVAL_MS = 1000L / TARGET_FPS;

    private final CustomVideoSource videoSource;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private Thread captureThread;
    private FFmpegFrameGrabber grabber;

    public LinuxScreenShareEngine(CustomVideoSource videoSource) {
        this.videoSource = Objects.requireNonNull(videoSource, "videoSource");
    }

    /**
     * Starts the capture loop if it is not already running.
     */
    public synchronized void start() {
        if (running.get()) {
            LOGGER.fine("Linux screen share already running");
            return;
        }

        try {
            grabber = initGrabber();
        } catch (FFmpegFrameGrabber.Exception e) {
            throw new IllegalStateException("Unable to initialize Linux screen capture", e);
        }

        running.set(true);
        captureThread = Thread.ofVirtual()
            .name("linux-screen-share")
            .start(this::captureLoop);
    }

    /**
     * Stops the capture loop and releases native resources.
     */
    public synchronized void stop() {
        if (!running.getAndSet(false)) {
            return;
        }

        Thread thread = captureThread;
        if (thread != null) {
            thread.interrupt();
        }

        closeGrabber();

        if (thread != null) {
            try {
                thread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        captureThread = null;
    }

    @Override
    public void close() {
        stop();
    }

    private FFmpegFrameGrabber initGrabber() throws FFmpegFrameGrabber.Exception {
        try {
            LOGGER.info("[LinuxScreenShare] Attempting PipeWire capture");
            return buildAndStartGrabber("pipewire:", "pipewire");
        } catch (FFmpegFrameGrabber.Exception pipeWireError) {
            LOGGER.log(Level.WARNING,
                "PipeWire capture failed, attempting X11 fallback", pipeWireError);
            return startX11Grabber();
        }
    }

    private FFmpegFrameGrabber startX11Grabber() throws FFmpegFrameGrabber.Exception {
        String display = System.getenv("DISPLAY");
        if (display == null || display.isBlank()) {
            throw new FFmpegFrameGrabber.Exception(
                "DISPLAY environment variable is not set. Unable to use x11grab fallback.");
        }
        LOGGER.info("[LinuxScreenShare] Attempting X11 capture on display " + display);
        return buildAndStartGrabber(display, "x11grab");
    }

    private FFmpegFrameGrabber buildAndStartGrabber(String device, String format)
            throws FFmpegFrameGrabber.Exception {

        FFmpegFrameGrabber g = new FFmpegFrameGrabber(device);
        g.setFormat(format);
        g.setOption("framerate", Integer.toString(TARGET_FPS));
        g.setFrameRate(TARGET_FPS);
        g.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);
        g.setVideoOption("pixel_format", "yuv420p");
        g.start();
        return g;
    }

    private void captureLoop() {
        try {
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                Frame frame;
                try {
                    frame = (grabber != null) ? grabber.grab() : null;
                } catch (FrameGrabber.Exception e) {
                    LOGGER.log(Level.WARNING, "Failed to grab frame, stopping capture loop", e);
                    break;
                }

                if (frame == null) {
                    continue;
                }

                try {
                    pushFrameI420(frame);
                } catch (Throwable t) {
                    LOGGER.log(Level.WARNING, "Failed to push frame to WebRTC source", t);
                }

                pace();
            }
        } finally {
            running.set(false);
            closeGrabber();
        }
    }

    private void pushFrameI420(Frame frame) {
        int width = frame.imageWidth;
        int height = frame.imageHeight;

        if (width <= 0 || height <= 0) {
            return;
        }

        AVFrame avFrame = (frame.opaque instanceof AVFrame) ? (AVFrame) frame.opaque : null;

        PlaneBuffer yPlane = planeBuffer(frame, avFrame, 0, width, height);
        PlaneBuffer uPlane = planeBuffer(frame, avFrame, 1, (width + 1) / 2, (height + 1) / 2);
        PlaneBuffer vPlane = planeBuffer(frame, avFrame, 2, (width + 1) / 2, (height + 1) / 2);

        if (yPlane == null || uPlane == null || vPlane == null) {
            LOGGER.fine("Frame pointers missing, dropping frame");
            return;
        }

        NativeI420Buffer buffer = NativeI420Buffer.allocate(width, height);
        boolean success = false;
        try {
            copyPlanes(width, height, yPlane, uPlane, vPlane, buffer);
            long timestampNs = System.nanoTime();
            VideoFrame videoFrame = new VideoFrame(buffer, 0, timestampNs);
            try {
                videoSource.pushFrame(videoFrame);
            } finally {
                videoFrame.release();
            }
            success = true;
        } finally {
            if (!success) {
                buffer.release();
            }
        }
    }

    private void copyPlanes(int width,
                            int height,
                            PlaneBuffer yPlane,
                            PlaneBuffer uPlane,
                            PlaneBuffer vPlane,
                            NativeI420Buffer dst) {

        int chromaWidth = (width + 1) / 2;
        int chromaHeight = (height + 1) / 2;

        ByteBuffer dstY = dst.getDataY();
        ByteBuffer dstU = dst.getDataU();
        ByteBuffer dstV = dst.getDataV();

        int dstStrideY = dst.getStrideY();
        int dstStrideU = dst.getStrideU();
        int dstStrideV = dst.getStrideV();

        validateStride("Y", width, yPlane.stride, dstStrideY);
        validateStride("U", chromaWidth, uPlane.stride, dstStrideU);
        validateStride("V", chromaWidth, vPlane.stride, dstStrideV);

        byte[] scratch = new byte[Math.max(width, chromaWidth)];

        copyPlane(yPlane, dstY, dstStrideY, width, height, scratch);
        copyPlane(uPlane, dstU, dstStrideU, chromaWidth, chromaHeight, scratch);
        copyPlane(vPlane, dstV, dstStrideV, chromaWidth, chromaHeight, scratch);
    }

    private static void copyPlane(PlaneBuffer src,
                                  ByteBuffer dst,
                                  int dstStride,
                                  int width,
                                  int height,
                                  byte[] scratch) {

        ByteBuffer srcView = src.buffer.duplicate();
        srcView.position(0);

        ByteBuffer dstView = dst.duplicate();
        dstView.position(0);

        // Fast path: tightly packed on both sides
        if (src.stride == width && dstStride == width) {
            int length = width * height;
            ByteBuffer srcSlice = srcView.duplicate();
            srcSlice.limit(srcSlice.position() + length);
            ByteBuffer dstSlice = dstView.slice();
            dstSlice.limit(length);
            dstSlice.put(srcSlice);
            return;
        }

        // Row-by-row copy with stride
        for (int row = 0; row < height; row++) {
            srcView.position(row * src.stride);
            srcView.get(scratch, 0, width);

            dstView.position(row * dstStride);
            dstView.put(scratch, 0, width);
        }
    }

    private static void validateStride(String plane,
                                       int expectedWidth,
                                       int srcStride,
                                       int dstStride) {
        if (srcStride < expectedWidth || dstStride < expectedWidth) {
            throw new IllegalStateException(
                "Invalid " + plane + " stride (src=" + srcStride
                    + ", dst=" + dstStride + ", expected>=" + expectedWidth + ")"
            );
        }
    }

    private void pace() {
        try {
            Thread.sleep(FRAME_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void closeGrabber() {
        FFmpegFrameGrabber current = grabber;
        grabber = null;
        if (current == null) {
            return;
        }
        try {
            current.stop();
        } catch (FFmpegFrameGrabber.Exception e) {
            LOGGER.log(Level.FINE, "Error stopping grabber", e);
        }
        try {
            current.release();
        } catch (FFmpegFrameGrabber.Exception e) {
            LOGGER.log(Level.FINE, "Error releasing grabber", e);
        }
    }

    private PlaneBuffer planeBuffer(Frame frame,
                                    AVFrame avFrame,
                                    int planeIndex,
                                    int width,
                                    int height) {

        // Prefer AVFrame (native FFmpeg planes)
        if (avFrame != null) {
            int stride = avFrame.linesize(planeIndex);
            BytePointer pointer = avFrame.data(planeIndex);
            if (pointer != null && stride > 0 && height > 0) {
                long byteCount = (long) stride * height;
                if (byteCount > Integer.MAX_VALUE) {
                    byteCount = Integer.MAX_VALUE;
                }
                ByteBuffer buffer = pointer.limit(byteCount).position(0).asByteBuffer();
                return new PlaneBuffer(buffer, stride);
            }
        }

        // Fallback: JavaCV Frame.image[] + imageStride
        if (frame.image != null
            && frame.image.length > planeIndex
            && frame.image[planeIndex] instanceof ByteBuffer raw) {

            ByteBuffer buffer = ((ByteBuffer) raw).duplicate();
            buffer.position(0);

            int stride = frame.imageStride > 0 ? frame.imageStride : width;

            return new PlaneBuffer(buffer, stride);
        }

        return null;
    }

    private static final class PlaneBuffer {
        private final ByteBuffer buffer;
        private final int stride;

        private PlaneBuffer(ByteBuffer buffer, int stride) {
            this.buffer = buffer;
            this.stride = stride;
        }
    }
}
