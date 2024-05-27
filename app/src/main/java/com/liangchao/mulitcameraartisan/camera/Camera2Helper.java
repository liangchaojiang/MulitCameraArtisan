package com.liangchao.mulitcameraartisan.camera;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.core.app.ActivityCompat;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public class Camera2Helper {
    private static final String TAG = "Camera2Helper";

    private Size maxPreviewSize;
    private Size minPreviewSize;

    public static final String CAMERA_ID_FRONT = "1";
    public static final String CAMERA_ID_BACK = "0";

    public static String debugMsg = "";
    public static String outputData="";
    public static String resetMsg = "";

    private String mCameraId;
    private String specificCameraId;
    private Camera2Listener camera2Listener;
    private Size previewViewSize;
    private Size specificPreviewSize;
    private Context context;
    private CountDownTimer mTimer = new MyCountDownTimer(20 * 1000, 1000);
    private long mFrameCount = 0;
    private boolean mRgb32Format = false;
    /**
     * A {@link CameraCaptureSession } for camera preview.
     */
    private CameraCaptureSession mCaptureSession;

    /**
     * A reference to the opened {@link CameraDevice}.
     */
    private CameraDevice mCameraDevice;

    private Size mPreviewSize;

    public static boolean checkPermission(Activity activity) {
        if (ActivityCompat.checkSelfPermission(activity.getBaseContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        if (!ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)) {
            ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.CAMERA}, 0);
            return false;
        }
        return true;
    }

    public static String[] cameraList(Context ctx) {
        CameraManager mgr = (CameraManager) ctx.getSystemService(ctx.CAMERA_SERVICE);
        String[] ids = new String[0];
        try {
            ids = mgr.getCameraIdList();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return ids;
    }

    class MyCountDownTimer extends CountDownTimer{
        private int resetCount = 0;
        private long frameLast = 0;
        private int tryCount = 0;
        private long framePerSecond = 0;

        public MyCountDownTimer(long millisInFuture, long countDownInterval) {
            super(millisInFuture, countDownInterval);
        }

        private void resetCamera() {
            resetCount++;
            Log.e(TAG, "Camera2 MyCountDownTimer reset begin:" + resetCount);
            Camera2Helper.this.stopInternal();
            Camera2Helper.this.startInternal();
            resetMsg = String.format("reset:%d", resetCount);
            Log.e(TAG, String.format("Camera2.ResetCount: %d - frameCount:%d - frameLast:%d", resetCount, mFrameCount, frameLast));
            debugMsg = "";
            outputData = "";
            Log.e(TAG, "Camera2 MyCountDownTimer reset end:" + resetCount);
        }

        @Override
        public void onTick(long l) {
            Log.e(TAG, String.format("Camera2 MyCountDownTimer::onTick: %d - resetCount:%d- QPS:%d - frameCount:%d - frameLast:%d - tryCount:%d", l, resetCount, framePerSecond, mFrameCount, frameLast, tryCount));
            if(mFrameCount == frameLast) {
                tryCount++;
                if(tryCount > 20) {
                    resetCamera();
                    tryCount = 0;
                }
            }else{
                tryCount = 0;
                framePerSecond = mFrameCount - frameLast;
                frameLast = mFrameCount;
            }
        }

        @Override
        public void onFinish() {
            Log.e(TAG, String.format("Camera2 MyCountDownTimer::onFinish: frameCount: %d - frameLast:%d - tryCount:%d", mFrameCount, frameLast, tryCount));
            start();
        }
    } ;

    public static String debugMessage() {
        return resetMsg + debugMsg + outputData;
    }

    private Camera2Helper(Builder builder) {
        specificCameraId = builder.specificCameraId;
        camera2Listener = builder.camera2Listener;
        previewViewSize = builder.previewViewSize;
        specificPreviewSize = builder.previewSize;
        maxPreviewSize = builder.maxPreviewSize;
        minPreviewSize = builder.minPreviewSize;
        context = builder.context;
        mRgb32Format = builder.rgb32Format;
        debugMsg = "";
        outputData = "";
        resetMsg = "";
    }

    public void switchCamera() {
        if (CAMERA_ID_BACK.equals(mCameraId)) {
            specificCameraId = CAMERA_ID_FRONT;
        } else if (CAMERA_ID_FRONT.equals(mCameraId)) {
            specificCameraId = CAMERA_ID_BACK;
        }
        stop();
        start();
    }

    private CameraDevice.StateCallback mDeviceStateCallback = new CameraDevice.StateCallback() {

        @Override
        public void onOpened(CameraDevice cameraDevice) {
            Log.i(TAG, "onOpened: ");
            // This method is called when the camera is opened.  We start camera preview here.
            mCameraOpenCloseLock.release();
            mCameraDevice = cameraDevice;
            createCameraPreviewSession();
            if (camera2Listener != null) {
                Log.e(TAG, String.format("Camera2 Open width:%d - height:%d", mPreviewSize.getWidth(), mPreviewSize.getHeight()));
                camera2Listener.onCameraOpened(cameraDevice, mCameraId, mPreviewSize.getWidth(), mPreviewSize.getHeight());
            }
        }

        @Override
        public void onDisconnected(CameraDevice cameraDevice) {
            Log.i(TAG, "onDisconnected: ");
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
            if (camera2Listener != null) {
                camera2Listener.onCameraClosed();
            }
        }

        @Override
        public void onError(CameraDevice cameraDevice, int error) {
            Log.e(TAG, "onError: " + error);
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;

            if (camera2Listener != null) {
                camera2Listener.onCameraError(new Exception("error occurred, code is " + error));
            }
        }

    };
    private CameraCaptureSession.StateCallback mCaptureStateCallback = new CameraCaptureSession.StateCallback() {

        @Override
        public void onConfigured(CameraCaptureSession cameraCaptureSession) {
            Log.i(TAG, "onConfigured: ");
            // The camera is already closed
            if (null == mCameraDevice) {
                return;
            }

            // When the session is ready, we start displaying the preview.
            mCaptureSession = cameraCaptureSession;
            try {
                mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(), mCaptureCallBack, mBackgroundHandler);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onConfigureFailed(
                CameraCaptureSession cameraCaptureSession) {
            Log.i(TAG, "onConfigureFailed: ");
            if (camera2Listener != null) {
                camera2Listener.onCameraError(new Exception("configureFailed"));
            }
        }
    };

    private CameraCaptureSession.CaptureCallback mCaptureCallBack = new CameraCaptureSession.CaptureCallback(){
        @Override
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);
        }

        @Override
        public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request, CaptureFailure failure) {
            super.onCaptureFailed(session, request, failure);
        }
    };


    /**
     * An additional thread for running tasks that shouldn't block the UI.
     */
    private HandlerThread mBackgroundThread;

    /**
     * A {@link Handler} for running tasks in the background.
     */
    private Handler mBackgroundHandler;

    private ImageReader mImageReader;


    /**
     * {@link CaptureRequest.Builder} for the camera preview
     */
    private CaptureRequest.Builder mPreviewRequestBuilder;


    /**
     * A {@link Semaphore} to prevent the app from exiting before closing the camera.
     */
    private Semaphore mCameraOpenCloseLock = new Semaphore(1);


    /**
     * Orientation of the camera sensor
     */
    private int mSensorOrientation;

    private Size getBestSupportedSize(List<Size> sizes) {
        Size defaultSize = sizes.get(0);
        Size[] tempSizes = sizes.toArray(new Size[0]);
        Arrays.sort(tempSizes, new Comparator<Size>() {
            @Override
            public int compare(Size o1, Size o2) {
                if (o1.getWidth() > o2.getWidth()) {
                    return -1;
                } else if (o1.getWidth() == o2.getWidth()) {
                    return o1.getHeight() > o2.getHeight() ? -1 : 1;
                } else {
                    return 1;
                }
            }
        });
        sizes = new ArrayList<>(Arrays.asList(tempSizes));
        for (int i = sizes.size() - 1; i >= 0; i--) {
            if (maxPreviewSize != null) {
                if (sizes.get(i).getWidth() > maxPreviewSize.getWidth() || sizes.get(i).getHeight() > maxPreviewSize.getHeight()) {
                    sizes.remove(i);
                    continue;
                }
            }
            if (minPreviewSize != null) {
                if (sizes.get(i).getWidth() < minPreviewSize.getWidth() || sizes.get(i).getHeight() < minPreviewSize.getHeight()) {
                    sizes.remove(i);
                }
            }
        }
        if (sizes.size() == 0) {
            String msg = "can not find suitable previewSize, now using default";
            if (camera2Listener != null) {
                Log.e(TAG, msg);
                camera2Listener.onCameraError(new Exception(msg));
            }
            return defaultSize;
        }
        Size bestSize = sizes.get(0);
        float previewViewRatio;
        if (previewViewSize != null) {
            previewViewRatio = (float) previewViewSize.getWidth() / (float) previewViewSize.getHeight();
        } else {
            previewViewRatio = (float) bestSize.getWidth() / (float) bestSize.getHeight();
        }

        if (previewViewRatio > 1) {
            previewViewRatio = 1 / previewViewRatio;
        }

        for (Size s : sizes) {
            if (specificPreviewSize != null && specificPreviewSize.getWidth() == s.getWidth() && specificPreviewSize.getHeight() == s.getHeight()) {
                return s;
            }
            if (Math.abs((s.getHeight() / (float) s.getWidth()) - previewViewRatio) < Math.abs(bestSize.getHeight() / (float) bestSize.getWidth() - previewViewRatio)) {
                bestSize = s;
            }
        }
        return bestSize;
    }

    public synchronized void start() {
        startInternal();
        mTimer.start();
    }

    public boolean startInternal() {
        if (mBackgroundThread != null) {
            return false;
        }
        startBackgroundThread();
        openCamera();
        return true;
    }

    public synchronized void stop() {
        stopInternal();
        mTimer.cancel();
    }

    public void stopInternal() {
        if (mBackgroundThread == null) {
            return;
        }
        closeCamera();
        stopBackgroundThread();
    }

    public void release() {
        stop();
        camera2Listener = null;
        context = null;
    }

    private void setUpCameraOutputs(CameraManager cameraManager) {
        try {
            if (configCameraParams(cameraManager, specificCameraId)) {
                return;
            }
            for (String cameraId : cameraManager.getCameraIdList()) {
                if (configCameraParams(cameraManager, cameraId)) {
                    return;
                }
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (NullPointerException e) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.

            if (camera2Listener != null) {
                camera2Listener.onCameraError(e);
            }
        }
    }

    @SuppressLint("WrongConstant")
    private boolean configCameraParams(CameraManager manager, String cameraId) throws CameraAccessException {
        CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) {
            return false;
        }
        ArrayList<Size> stSize = new ArrayList<Size>(Arrays.asList(map.getOutputSizes(SurfaceTexture.class)));
        mPreviewSize = getBestSupportedSize(stSize);
        int[] fmts = map.getOutputFormats();
        String szTxt = "Size:";
        for(int i = 0; i < stSize.size(); i++) {
            Size tmp = stSize.get(i);
            szTxt += String.format("(%d,%d)", tmp.getWidth(), tmp.getHeight());
        }
        String msg="formatList:(";
        for(int i = 0; i < fmts.length; i++) {
            msg += String.format("0x%x-", fmts[i]);
        }
        msg += ")";
        debugMsg += szTxt;
        debugMsg += msg;
        debugMsg += String.format(",PreviewSize:(%d,%d)", mPreviewSize.getWidth(), mPreviewSize.getHeight());

        Log.e(TAG, "Camera2.CameraParams:" + debugMsg);
        mImageReader = ImageReader.newInstance(mPreviewSize.getWidth(), mPreviewSize.getHeight(), mRgb32Format ? PixelFormat.RGBA_8888 : ImageFormat.YUV_420_888, 2);
        mImageReader.setOnImageAvailableListener(new OnImageAvailableListenerImpl(), mBackgroundHandler);
        mCameraId = cameraId;
        return true;
    }

    @SuppressLint("MissingPermission")
    private void openCamera() {
        CameraManager cameraManager = (CameraManager) context.getSystemService(context.CAMERA_SERVICE);
        setUpCameraOutputs(cameraManager);
        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            cameraManager.openCamera(mCameraId, mDeviceStateCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            if (camera2Listener != null) {
                camera2Listener.onCameraError(e);
            }
        } catch (InterruptedException e) {
            if (camera2Listener != null) {
                camera2Listener.onCameraError(e);
            }
        }
    }

    /**
     * Closes the current {@link CameraDevice}.
     */
    private void closeCamera() {
        try {
            mCameraOpenCloseLock.acquire();
            if (null != mCaptureSession) {
                mCaptureSession.close();
                mCaptureSession = null;
            }
            if (null != mCameraDevice) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
            if (null != mImageReader) {
                mImageReader.close();
                mImageReader = null;
            }
            if (camera2Listener != null) {
                camera2Listener.onCameraClosed();
            }
        } catch (InterruptedException e) {
            if (camera2Listener != null) {
                camera2Listener.onCameraError(e);
            }
        } finally {
            mCameraOpenCloseLock.release();
        }
    }

    /**
     * Starts a background thread and its {@link Handler}.
     */
    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground"+specificCameraId);
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());

    }

    /**
     * Stops the background thread and its {@link Handler}.
     */
    private void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Creates a new {@link CameraCaptureSession} for camera preview.
     */
    private void createCameraPreviewSession() {
        try {
              // We set up a CaptureRequest.Builder with the output Surface.
            // Here, we create a CameraCaptureSession for camera preview.
            Surface surface = mImageReader.getSurface();
            mPreviewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mPreviewRequestBuilder.addTarget(surface);
            mCameraDevice.createCaptureSession(Arrays.asList(surface),  mCaptureStateCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    public static final class Builder {
        /**
         * 指定的相机ID
         */
        private String specificCameraId;
        /**
         * 事件回调
         */
        private Camera2Listener camera2Listener;
        /**
         * 屏幕的长宽，在选择最佳相机比例时用到
         */
        private Size previewViewSize;
        /**
         * 指定的预览宽高，若系统支持则会以这个预览宽高进行预览
         */
        private Size previewSize;
        /**
         * 最大分辨率
         */
        private Size maxPreviewSize;
        /**
         * 最小分辨率
         */
        private Size minPreviewSize;
        /**
         * 上下文，用于获取CameraManager
         */
        private Context context;

        private boolean rgb32Format;

        public Builder() {
        }

        public Builder rgb32Format(boolean on) {
            rgb32Format = on;
            return this;
        }

        public Builder previewSize(Size val) {
            previewSize = val;
            return this;
        }

        public Builder maxPreviewSize(Size val) {
            maxPreviewSize = val;
            return this;
        }

        public Builder minPreviewSize(Size val) {
            minPreviewSize = val;
            return this;
        }

        public Builder previewViewSize(Size val) {
            previewViewSize = val;
            return this;
        }

        public Builder specificCameraId(String val) {
            specificCameraId = val;
            return this;
        }

        public Builder cameraListener(Camera2Listener val) {
            camera2Listener = val;
            return this;
        }

        public Builder context(Context val) {
            context = val;
            return this;
        }


        public Camera2Helper build() {
            if (previewViewSize == null) {
                Log.e(TAG, "previewViewSize is null, now use default previewSize");
            }
            if (camera2Listener == null) {
                Log.e(TAG, "camera2Listener is null, callback will not be called");
            }
            if (maxPreviewSize != null && minPreviewSize != null) {
                if (maxPreviewSize.getWidth() < minPreviewSize.getWidth() || maxPreviewSize.getHeight() < minPreviewSize.getHeight()) {
                    throw new IllegalArgumentException("maxPreviewSize must greater than minPreviewSize");
                }
            }
            return new Camera2Helper(this);
        }
    }

    private class OnImageAvailableListenerImpl implements ImageReader.OnImageAvailableListener {
        private ReentrantLock lock = new ReentrantLock();
        @SuppressLint("SuspiciousIndentation")
        @Override
        public void onImageAvailable(ImageReader reader) {
            lock.lock();
            Image image = reader.acquireNextImage();
            try {
                if(image == null) {
                    return;
                }
                mFrameCount++;
                int fmt = image.getFormat();
                if(fmt == ImageFormat.YUV_420_888) {
                    camera2Listener.onPreview(image, image.getWidth(), image.getHeight(), false, mCameraDevice);
                }else if(fmt == PixelFormat.RGBA_8888){
                    Log.d(TAG, "here..."+mFrameCount);
                    camera2Listener.onPreview(image, image.getWidth(), image.getHeight(), true, mCameraDevice);
                }
            }catch (Exception e){
                e.printStackTrace();
            }finally {
                if(image != null)
                image.close();
                lock.unlock();
            }
        }
    }
}