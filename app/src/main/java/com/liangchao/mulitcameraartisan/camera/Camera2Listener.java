package com.liangchao.mulitcameraartisan.camera;

import android.hardware.camera2.CameraDevice;
import android.media.Image;


public interface Camera2Listener {
    /**
     * 当打开时执行
     * @param camera 相机实例
     * @param cameraId 相机ID
     */
    void onCameraOpened(CameraDevice camera, String cameraId, int width, int height);

    /**
     * 预览数据回调
     * @param rawData 预览数据
     * @param camera 相机实例
     */
    void onPreview(Image rawData, int width, int height, boolean isRgb32, CameraDevice camera);

    /**
     * 当相机关闭时执行
     */
    void onCameraClosed();

    /**
     * 当出现异常时执行
     * @param e 相机相关异常
     */
    void onCameraError(Exception e);
}
