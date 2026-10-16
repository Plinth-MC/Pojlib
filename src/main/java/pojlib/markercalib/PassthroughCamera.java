package pojlib.markercalib;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;

import androidx.annotation.Keep;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Meta Passthrough Camera API access for the HoloCraft marker (AprilTag) table calibration.
 * <p>
 *     The Passthrough Camera API (Quest 3 / 3S, Horizon OS v74+) is plain Android Camera2:
 *     the passthrough cameras are listed by {@link CameraManager} and tagged with the Meta vendor
 *     characteristics {@code com.meta.extra_metadata.camera_source} (0 = passthrough) and
 *     {@code com.meta.extra_metadata.position} (0 = left, 1 = right). Needs the runtime permissions
 *     {@code android.permission.CAMERA} and {@code horizonos.permission.HEADSET_CAMERA}.
 * </p>
 * <p>
 *     Only the Y plane of low-res YUV_420_888 frames is kept (tightly packed, latest frame only).
 *     Called from the Minecraft JVM through the {@code holocraft_markercalib} JNI bridge
 *     (src/main/jni/markercalib), which attaches the calling thread to this (ART) VM. All entry points
 *     are static and thread-safe; camera callbacks run on a private handler thread.
 *     Permission results come back through {@link #onRequestPermissionsResult}, forwarded by
 *     {@link pojlib.UnityPlayerActivity}.
 * </p>
 */
@Keep
public final class PassthroughCamera {
    private static final String TAG = "HoloCraftMarkerCalib";

    public static final String PERMISSION_HEADSET_CAMERA = "horizonos.permission.HEADSET_CAMERA";
    public static final int PERMISSION_REQUEST_CODE = 0x4843; // "HC"

    private static final String KEY_CAMERA_SOURCE = "com.meta.extra_metadata.camera_source";
    private static final String KEY_CAMERA_POSITION = "com.meta.extra_metadata.position";
    private static final int CAMERA_SOURCE_PASSTHROUGH = 0;

    // ---- status (mirrored in the add-on's QuestCameraBridge) ----
    public static final int STATUS_IDLE = 0;
    public static final int STATUS_REQUESTING_PERMISSION = 1;
    public static final int STATUS_OPENING = 2;
    public static final int STATUS_STREAMING = 3;
    public static final int STATUS_PERMISSION_DENIED = -1;
    public static final int STATUS_UNAVAILABLE = -2;
    public static final int STATUS_ERROR = -3;

    // ---- calibration array layout (mirrored in the add-on's QuestCameraBridge) ----
    public static final int CALIB_WIDTH = 0, CALIB_HEIGHT = 1;
    /** LENS_INTRINSIC_CALIBRATION: fx, fy, cx, cy, s (pixels of the pre-correction active array) */
    public static final int CALIB_FX = 2, CALIB_FY = 3, CALIB_CX = 4, CALIB_CY = 5, CALIB_SKEW = 6;
    /** SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE (left, top, width, height) */
    public static final int CALIB_ARRAY_LEFT = 7, CALIB_ARRAY_TOP = 8, CALIB_ARRAY_WIDTH = 9, CALIB_ARRAY_HEIGHT = 10;
    /** LENS_POSE_TRANSLATION x, y, z (meters) */
    public static final int CALIB_POSE_T = 11;
    /** LENS_POSE_ROTATION x, y, z, w */
    public static final int CALIB_POSE_Q = 14;
    /** LENS_DISTORTION k1, k2, k3, p1, p2 (0 if not reported) */
    public static final int CALIB_DISTORTION = 18;
    /** SENSOR_INFO_TIMESTAMP_SOURCE: 0 UNKNOWN, 1 REALTIME */
    public static final int CALIB_TIMESTAMP_SOURCE = 23;
    /** LENS_POSE_REFERENCE, -1 if not reported */
    public static final int CALIB_POSE_REFERENCE = 24;
    /** com.meta.extra_metadata.position of the opened camera (0 left, 1 right, -1 unknown) */
    public static final int CALIB_POSITION = 25;
    /** bit 0: intrinsics, bit 1: lens pose, bit 2: distortion */
    public static final int CALIB_FLAGS = 26;
    public static final int CALIB_LENGTH = 27;

    // ---- frame metadata layout (mirrored in the add-on's QuestCameraBridge) ----
    /** sequence number, timestamp converted to CLOCK_MONOTONIC ns, raw SENSOR_TIMESTAMP ns, width, height */
    public static final int FRAME_META_LENGTH = 5;

    private static final Object LOCK = new Object();

    private static volatile int status = STATUS_IDLE;
    private static volatile String message = "";

    // all below guarded by LOCK
    private static Activity activity;
    private static int requestedPosition;
    private static int requestedWidth, requestedHeight;
    private static HandlerThread thread;
    private static Handler handler;
    private static CameraDevice device;
    private static CameraCaptureSession session;
    private static ImageReader reader;
    private static float[] calibration;
    private static volatile boolean timestampRealtime;
    /** Bumped on every start / stop, so stale camera callbacks are ignored */
    private static volatile int generation;

    private static final Object FRAME_LOCK = new Object();
    // guarded by FRAME_LOCK
    private static ByteBuffer latestY;
    private static long latestSeq;
    private static long latestMonotonicNs;
    private static long latestSensorNs;
    private static int latestWidth, latestHeight;

    private PassthroughCamera() {
    }

    /**
     * Start streaming (requests the camera permissions first if needed).
     * Progress is reported by {@link #getStatus()} / {@link #getMessage()}
     *
     * @param ctx      the activity (the JNI bridge passes the launcher activity)
     * @param position preferred camera: 0 left, 1 right
     * @param width    requested frame width (closest supported YUV size is used)
     * @param height   requested frame height
     * @return the status after this call
     */
    public static int start(Activity ctx, int position, int width, int height) {
        synchronized (LOCK) {
            stopLocked();
            activity = ctx;
            requestedPosition = position;
            requestedWidth = width;
            requestedHeight = height;
            List<String> missing = missingPermissions(ctx);
            if (!missing.isEmpty()) {
                setStatus(STATUS_REQUESTING_PERMISSION, "Requesting camera permission " + missing);
                String[] perms = missing.toArray(new String[0]);
                ctx.runOnUiThread(() -> ctx.requestPermissions(perms, PERMISSION_REQUEST_CODE));
                return status;
            }
            openLocked();
            return status;
        }
    }

    /**
     * Stop streaming and release the camera
     */
    public static void stop() {
        synchronized (LOCK) {
            stopLocked();
            setStatus(STATUS_IDLE, "");
        }
    }

    public static int getStatus() {
        return status;
    }

    public static String getMessage() {
        return message;
    }

    /**
     * @return the calibration of the opened camera (see CALIB_* indices), or null while not streaming
     */
    public static float[] getCalibration() {
        synchronized (LOCK) {
            return calibration == null ? null : calibration.clone();
        }
    }

    /**
     * Copy the latest Y plane (tightly packed, width * height bytes) into dst
     *
     * @param dst      destination (a direct buffer over memory of the calling VM)
     * @param afterSeq only copy a frame newer than this sequence number
     * @return frame metadata (see FRAME_META_LENGTH), or null if there is no newer frame / dst is too small
     */
    public static long[] copyLatestFrame(ByteBuffer dst, long afterSeq) {
        synchronized (FRAME_LOCK) {
            if (latestY == null || latestSeq <= afterSeq) {
                return null;
            }
            int size = latestWidth * latestHeight;
            if (dst.capacity() < size) {
                return null;
            }
            ByteBuffer src = latestY.duplicate();
            src.position(0).limit(size);
            dst.clear();
            dst.put(src);
            return new long[]{latestSeq, latestMonotonicNs, latestSensorNs, latestWidth, latestHeight};
        }
    }

    /**
     * Activity hook: called from {@link pojlib.UnityPlayerActivity#onRequestPermissionsResult}
     *
     * @return true if the result was for the marker calibration request (consumed)
     */
    public static boolean onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != PERMISSION_REQUEST_CODE) {
            return false;
        }
        synchronized (LOCK) {
            if (status != STATUS_REQUESTING_PERMISSION || activity == null) {
                return true;
            }
            List<String> missing = missingPermissions(activity);
            if (!missing.isEmpty()) {
                setStatus(STATUS_PERMISSION_DENIED, "Camera permission denied " + missing);
                return true;
            }
            openLocked();
        }
        return true;
    }

    private static List<String> missingPermissions(Context ctx) {
        List<String> missing = new ArrayList<>();
        for (String p : new String[]{Manifest.permission.CAMERA, PERMISSION_HEADSET_CAMERA}) {
            if (ctx.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                missing.add(p);
            }
        }
        return missing;
    }

    private static void setStatus(int newStatus, String newMessage) {
        status = newStatus;
        message = newMessage == null ? "" : newMessage;
        if (newStatus < 0) {
            Log.w(TAG, "status " + newStatus + ": " + message);
        } else {
            Log.i(TAG, "status " + newStatus + ": " + message);
        }
    }

    // ---- camera (LOCK held) ----

    @SuppressLint("MissingPermission")
    private static void openLocked() {
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) {
            setStatus(STATUS_UNAVAILABLE, "No camera service");
            return;
        }
        String cameraId;
        CameraCharacteristics chars;
        int position;
        try {
            String best = null, fallback = null;
            int bestPosition = -1, fallbackPosition = -1;
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics c = manager.getCameraCharacteristics(id);
                int source = vendorInt(c, KEY_CAMERA_SOURCE);
                int pos = vendorInt(c, KEY_CAMERA_POSITION);
                Log.i(TAG, "camera " + id + ": source=" + source + " position=" + pos);
                if (source != CAMERA_SOURCE_PASSTHROUGH) {
                    continue;
                }
                if (pos == requestedPosition && best == null) {
                    best = id;
                    bestPosition = pos;
                } else if (fallback == null) {
                    fallback = id;
                    fallbackPosition = pos;
                }
            }
            cameraId = best != null ? best : fallback;
            position = best != null ? bestPosition : fallbackPosition;
            if (cameraId == null) {
                setStatus(STATUS_UNAVAILABLE, "No passthrough camera (needs Quest 3 / 3S, Horizon OS v74+)");
                return;
            }
            chars = manager.getCameraCharacteristics(cameraId);
        } catch (CameraAccessException | RuntimeException e) {
            setStatus(STATUS_UNAVAILABLE, "Camera list failed: " + e);
            return;
        }

        Size size = chooseSize(chars, requestedWidth, requestedHeight);
        if (size == null) {
            setStatus(STATUS_UNAVAILABLE, "No YUV_420_888 output on camera " + cameraId);
            return;
        }
        calibration = readCalibration(chars, size, position);
        Integer tsSource = chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
        timestampRealtime = tsSource != null && tsSource == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME;

        thread = new HandlerThread("HoloCraftMarkerCamera");
        thread.start();
        handler = new Handler(thread.getLooper());
        reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 3);
        final int gen = ++generation;
        reader.setOnImageAvailableListener(r -> onImage(r, gen), handler);

        setStatus(STATUS_OPENING, "Opening camera " + cameraId + " (" + size + ")");
        try {
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    synchronized (LOCK) {
                        if (gen != generation) {
                            camera.close();
                            return;
                        }
                        device = camera;
                        createSessionLocked(gen);
                    }
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    synchronized (LOCK) {
                        if (gen == generation) {
                            device = null;
                            setStatus(STATUS_ERROR, "Camera disconnected");
                        }
                    }
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    camera.close();
                    synchronized (LOCK) {
                        if (gen == generation) {
                            device = null;
                            setStatus(STATUS_ERROR, "Camera error " + error);
                        }
                    }
                }
            }, handler);
        } catch (CameraAccessException | RuntimeException e) {
            setStatus(STATUS_ERROR, "Camera open failed: " + e);
        }
    }

    @SuppressWarnings("deprecation") // createCaptureSession(List, ...) is fine on API 29+
    private static void createSessionLocked(int gen) {
        try {
            final CaptureRequest.Builder request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            request.addTarget(reader.getSurface());
            device.createCaptureSession(Collections.singletonList(reader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession s) {
                            synchronized (LOCK) {
                                if (gen != generation) {
                                    s.close();
                                    return;
                                }
                                session = s;
                                try {
                                    s.setRepeatingRequest(request.build(), null, handler);
                                    setStatus(STATUS_STREAMING, "Streaming");
                                } catch (CameraAccessException | RuntimeException e) {
                                    setStatus(STATUS_ERROR, "Repeating request failed: " + e);
                                }
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession s) {
                            synchronized (LOCK) {
                                if (gen == generation) {
                                    setStatus(STATUS_ERROR, "Capture session configuration failed");
                                }
                            }
                        }
                    }, handler);
        } catch (CameraAccessException | RuntimeException e) {
            setStatus(STATUS_ERROR, "Capture session failed: " + e);
        }
    }

    private static void stopLocked() {
        generation++;
        if (session != null) {
            try {
                session.close();
            } catch (RuntimeException ignored) {
            }
            session = null;
        }
        if (device != null) {
            device.close();
            device = null;
        }
        if (reader != null) {
            reader.close();
            reader = null;
        }
        if (thread != null) {
            thread.quitSafely();
            thread = null;
            handler = null;
        }
        calibration = null;
        synchronized (FRAME_LOCK) {
            latestY = null;
            latestSeq = 0;
        }
    }

    private static void onImage(ImageReader r, int gen) {
        Image image;
        try {
            image = r.acquireLatestImage();
        } catch (RuntimeException e) {
            return;
        }
        if (image == null) {
            return;
        }
        try {
            if (gen != generation) {
                return;
            }
            int w = image.getWidth(), h = image.getHeight();
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer src = plane.getBuffer();
            int rowStride = plane.getRowStride();
            long sensorNs = image.getTimestamp();
            // UNCERTAIN: SENSOR_TIMESTAMP base. Android: REALTIME source = elapsedRealtimeNanos
            // (CLOCK_BOOTTIME), UNKNOWN = "monotonic, not comparable to other clocks" (in practice usually
            // CLOCK_MONOTONIC). Convert to CLOCK_MONOTONIC (System.nanoTime), which is what the Meta OpenXR
            // runtime appears to use for XrTime (not guaranteed by the spec).
            long monotonicNs = timestampRealtime
                    ? sensorNs - (SystemClock.elapsedRealtimeNanos() - System.nanoTime())
                    : sensorNs;
            synchronized (FRAME_LOCK) {
                if (latestY == null || latestY.capacity() < w * h) {
                    latestY = ByteBuffer.allocateDirect(w * h);
                }
                latestY.clear();
                if (rowStride == w) {
                    src.position(0).limit(w * h);
                    latestY.put(src);
                } else {
                    for (int y = 0; y < h; y++) {
                        src.limit(y * rowStride + w).position(y * rowStride);
                        latestY.put(src);
                    }
                }
                latestSeq++;
                latestMonotonicNs = monotonicNs;
                latestSensorNs = sensorNs;
                latestWidth = w;
                latestHeight = h;
            }
        } finally {
            image.close();
        }
    }

    private static Size chooseSize(CameraCharacteristics chars, int width, int height) {
        StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] sizes = map == null ? null : map.getOutputSizes(ImageFormat.YUV_420_888);
        if (sizes == null || sizes.length == 0) {
            return null;
        }
        Size best = null;
        long bestScore = Long.MAX_VALUE;
        for (Size s : sizes) {
            if (s.getWidth() == width && s.getHeight() == height) {
                return s;
            }
            // prefer the same aspect, then the closest pixel count
            boolean sameAspect = (long) s.getWidth() * height == (long) s.getHeight() * width;
            long score = Math.abs((long) s.getWidth() * s.getHeight() - (long) width * height)
                    + (sameAspect ? 0 : 1L << 40);
            if (score < bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best;
    }

    private static float[] readCalibration(CameraCharacteristics chars, Size size, int position) {
        float[] c = new float[CALIB_LENGTH];
        c[CALIB_WIDTH] = size.getWidth();
        c[CALIB_HEIGHT] = size.getHeight();
        int flags = 0;
        float[] intrinsics = chars.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION);
        if (intrinsics != null && intrinsics.length >= 5) {
            System.arraycopy(intrinsics, 0, c, CALIB_FX, 5);
            flags |= 1;
        }
        Rect array = chars.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE);
        if (array == null) {
            array = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        }
        if (array != null) {
            c[CALIB_ARRAY_LEFT] = array.left;
            c[CALIB_ARRAY_TOP] = array.top;
            c[CALIB_ARRAY_WIDTH] = array.width();
            c[CALIB_ARRAY_HEIGHT] = array.height();
        } else {
            c[CALIB_ARRAY_WIDTH] = size.getWidth();
            c[CALIB_ARRAY_HEIGHT] = size.getHeight();
        }
        float[] t = chars.get(CameraCharacteristics.LENS_POSE_TRANSLATION);
        float[] q = chars.get(CameraCharacteristics.LENS_POSE_ROTATION);
        if (t != null && t.length >= 3 && q != null && q.length >= 4) {
            System.arraycopy(t, 0, c, CALIB_POSE_T, 3);
            System.arraycopy(q, 0, c, CALIB_POSE_Q, 4);
            flags |= 2;
        }
        float[] distortion = chars.get(CameraCharacteristics.LENS_DISTORTION);
        if (distortion != null && distortion.length >= 5) {
            System.arraycopy(distortion, 0, c, CALIB_DISTORTION, 5);
            flags |= 4;
        }
        Integer tsSource = chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
        c[CALIB_TIMESTAMP_SOURCE] = tsSource == null ? 0 : tsSource;
        Integer poseReference = chars.get(CameraCharacteristics.LENS_POSE_REFERENCE);
        c[CALIB_POSE_REFERENCE] = poseReference == null ? -1 : poseReference;
        c[CALIB_POSITION] = position;
        c[CALIB_FLAGS] = flags;
        Log.i(TAG, "calibration: size=" + size + " intrinsics=" + arrayString(intrinsics)
                + " array=" + array + " t=" + arrayString(t) + " q=" + arrayString(q)
                + " distortion=" + arrayString(distortion) + " tsSource=" + tsSource
                + " poseReference=" + poseReference);
        return c;
    }

    /**
     * Meta vendor keys are not public CameraCharacteristics constants: look them up by name.
     * Meta's samples read them as 1-element arrays
     *
     * @return the value, or -1 if absent
     */
    private static int vendorInt(CameraCharacteristics chars, String name) {
        for (CameraCharacteristics.Key<?> key : chars.getKeys()) {
            if (!name.equalsIgnoreCase(key.getName())) {
                continue;
            }
            Object value;
            try {
                value = chars.get(key);
            } catch (RuntimeException e) {
                return -1;
            }
            if (value instanceof byte[] && ((byte[]) value).length > 0) {
                return ((byte[]) value)[0];
            } else if (value instanceof int[] && ((int[]) value).length > 0) {
                return ((int[]) value)[0];
            } else if (value instanceof Number) {
                return ((Number) value).intValue();
            }
            return -1;
        }
        return -1;
    }

    private static String arrayString(float[] a) {
        return a == null ? "null" : java.util.Arrays.toString(a);
    }
}
