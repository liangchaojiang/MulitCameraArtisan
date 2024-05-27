package com.liangchao.mulitcameraartisan;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraDevice;
import android.media.Image;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.view.View;
import android.widget.Button;

import com.liangchao.mulitcameraartisan.camera.Camera2Helper;
import com.liangchao.mulitcameraartisan.camera.Camera2Listener;

public class MainActivity extends AppCompatActivity {
    Button btStart,btStop;
    private static final String TAG = "MainActivity";
    private Camera2Listener listener2,listener21,listener22,listener23;
    private Camera2Helper camera2,camera21,camera22,camera23;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        int checkSelfPermission = ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if(checkSelfPermission  == PackageManager.PERMISSION_DENIED){
            ActivityCompat.requestPermissions(this,new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.CAMERA},1);
        }
        btStart = (Button) findViewById(R.id.btStart);
        btStop = (Button) findViewById(R.id.btStop);
        listener2 = new Camera2Listener() {
            @Override
            public void onCameraOpened(CameraDevice camera, String cameraId, int width, int height) {
                Log.i(TAG,"onCameraOpened");
            }
            private int pCBCount = 0;
            private long startTimeNs, endTimeNs = 0;
            @Override
            public void onPreview(Image rawData, int width, int height, boolean isRgb32, CameraDevice camera) {
                Log.i(TAG,"onPreview");
                endTimeNs = System.nanoTime();
                pCBCount++;
                if ((endTimeNs - startTimeNs) > 1000000000) {
                    startTimeNs = endTimeNs;
                    Log.d(TAG, "cameraID =  " + camera.getId() + " onImageAvailable fps = " + pCBCount);
                    pCBCount = 0;
                }

            }

            @Override
            public void onCameraClosed() {
                Log.i(TAG,"onCameraClosed");
            }

            @Override
            public void onCameraError(Exception e) {
                Log.i(TAG,"onCameraError");
            }
        };
        btStart.setOnClickListener(btStartListener);
        btStop.setOnClickListener(btStopListener);
    }

    View.OnClickListener btStartListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            camera2 = new Camera2Helper.Builder()
                    .rgb32Format(false)
                    .specificCameraId(String.valueOf(0))
                    .context(MainActivity.this.getBaseContext())
                    .maxPreviewSize(new Size(1920, 1080))
                    .previewSize(new Size(1920, 1080))
                    .cameraListener(listener2)
                    .build();
            camera2.start();
        }
    };

    View.OnClickListener btStopListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            camera2.stop();
        }
    };

}