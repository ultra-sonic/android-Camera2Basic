/*
 * Copyright 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.camera2basic;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.DngCreator;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.Fragment;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static java.lang.Math.pow;

public class Camera2BasicFragment extends Fragment
        implements View.OnClickListener, ActivityCompat.OnRequestPermissionsResultCallback {

    /**
     * Conversion from screen rotation to JPEG orientation.
     */
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    private static final int REQUEST_CAMERA_PERMISSION = 1;
    private static final String FRAGMENT_DIALOG = "dialog";

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    /**
     * Tag for the {@link Log}.
     */
    private static final String TAG = "Camera2BasicFragment";

    /**
     * Camera state: Showing camera preview.
     */
    private static final int STATE_PREVIEW = 0;

    /**
     * Camera state: Waiting for the focus to be locked.
     */
    private static final int STATE_WAITING_LOCK = 1;

    /**
     * Camera state: Waiting for the exposure to be precapture state.
     */
    private static final int STATE_WAITING_PRECAPTURE = 2;

    /**
     * Camera state: Waiting for the exposure state to be something other than precapture.
     */
    private static final int STATE_WAITING_NON_PRECAPTURE = 3;

    /**
     * Camera state: Picture was taken.
     */
    private static final int STATE_PICTURE_TAKEN = 4;

    /**
     * Camera state: Picture sequence is currently being taken.
     */
    private static final int STATE_BURST_SEQUENCE = 5;


    /**
     * Max preview width that is guaranteed by Camera2 API
     */
    private static final int MAX_PREVIEW_WIDTH = 1920;

    /**
     * Max preview height that is guaranteed by Camera2 API
     */
    private static final int MAX_PREVIEW_HEIGHT = 1080;

    /**
     * {@link TextureView.SurfaceTextureListener} handles several lifecycle events on a
     * {@link TextureView}.
     */
    private final TextureView.SurfaceTextureListener mSurfaceTextureListener
            = new TextureView.SurfaceTextureListener() {

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
            openCamera(width, height);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
            configureTransform(width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture texture) {
        }

    };

    /**
     * ID of the current {@link CameraDevice}.
     */
    private String mCameraId;

    /**
     * An {@link AutoFitTextureView} for camera preview.
     */
    private AutoFitTextureView mTextureView;

    private EditText hostAdressView;

    private SeekBar focusSeekBarView,
                    exposureSeekBarView;
    private TextView focusDistanceView;

    private Spinner shotModeSpinnerView;
    private boolean mManualFocusEngaged;

    /**
     * A {@link CameraCaptureSession } for camera preview.
     */
    private CameraCaptureSession mCaptureSession;

    /**
     * A reference to the opened {@link CameraDevice}.
     */
    private CameraDevice mCameraDevice;

    /**
     * The {@link android.util.Size} of camera preview.
     */
    private Size mPreviewSize;


    /**
     * {@link CameraDevice.StateCallback} is called when {@link CameraDevice} changes its state.
     */
    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {

        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            // This method is called when the camera is opened.  We start camera preview here.
            mCameraOpenCloseLock.release();
            mCameraDevice = cameraDevice;
            createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int error) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
            Activity activity = getActivity();
            if (null != activity) {
                activity.finish();
            }
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

    /**
     * An {@link ImageReader} that handles still image capture.
     */
    private ImageReader mImageReader;
    private ImageReader mRawImageReader;
    // private Image[] mImageArray = new Image[40];
    static List<byte[]> byteList = new ArrayList<>();

    /**
     * This is the output file for our picture.
     */
    private File mFile;
    private File[] mFileArray = new File[99];
    private File[] mRawFileArray = new File[99];
    private ByteArrayOutputStream[] rawByteArrayOutputStreamArray = new ByteArrayOutputStream[99];

    private CameraCharacteristics mCameraCharacteristics;
    private CaptureResult mCaptureResult;

    private final ReentrantLock saveJpegLock = new ReentrantLock();
    private final ReentrantLock saveRawLock = new ReentrantLock();

    /**
     * This a callback object for the {@link ImageReader}. "onImageAvailable" will be called when a
     * still image is ready to be saved.
     */

    private final ImageReader.OnImageAvailableListener mOnRawImageAvailableListener
            = new ImageReader.OnImageAvailableListener() {

        @Override
        public void onImageAvailable(ImageReader reader) {
            saveRawLock.lock();
            try {
                Log.d(TAG, "OnRawImageAvailableListener: SAVING ");
                if (mCaptureResult == null) {
                    // Log.e(TAG, "captureResult is null");
                    throw new IOException("captureResult is null");
                }
                if (mCameraCharacteristics == null) {
                    // Log.e(TAG, "mCameraCharacteristics is null");
                    throw new IOException("mCameraCharacteristics is null");
                }
                DngCreator dngCreator = new DngCreator( mCameraCharacteristics, mCaptureResult );
                Image tmpImage = reader.acquireNextImage();
                ByteArrayOutputStream tmpOutputStream=new ByteArrayOutputStream();
                dngCreator.writeImage( tmpOutputStream, tmpImage);
                rawByteArrayOutputStreamArray[ pictureCounter ] = tmpOutputStream;
                tmpImage.close();

                pictureCounter++;
                perShotExposureMultiplier =1; // RESET perShotExposureMultiplier every frame

                if (lightstageOutStream != null)
                    try {
                        Log.d(TAG, "signaling lightstage to continue");
                        lightstageOutStream.writeByte( messageCodes.LIGHTSTAGE_CONTINUE_NEXT_LIGHT.getCode() );

                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                else
                    Log.e(TAG, "lightstage Outputstream not available");
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                saveRawLock.unlock();
            }

        }

    };

    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener
            = new ImageReader.OnImageAvailableListener() {

        @Override
        public void onImageAvailable(ImageReader reader) {
            saveJpegLock.lock();
            try {
                Log.d(TAG, "OnImageAvailableListener: SAVING ");
                Image tmpImage = reader.acquireNextImage();
                ByteBuffer buffer = tmpImage.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buffer.remaining()];
                byteList.add(bytes);
                buffer.get(bytes);
                tmpImage.close();
            }
            finally {
                saveJpegLock.unlock();
            }

        }

    };

    /**
     * {@link CaptureRequest.Builder} for the camera preview
     */
    private CaptureRequest.Builder mPreviewRequestBuilder;

    /**
     * {@link CaptureRequest} generated by {@link #mPreviewRequestBuilder}
     */
    private CaptureRequest mPreviewRequest;

    /**
     * The current state of camera state for taking pictures.
     *
     * @see #mCaptureCallback
     */
    private int mState = STATE_PREVIEW;

    /**
     * A {@link Semaphore} to prevent the app from exiting before closing the camera.
     */
    private Semaphore mCameraOpenCloseLock = new Semaphore(1);

    /**
     * Whether the current camera device supports Flash or not.
     */
    private boolean mFlashSupported;

    /**
     * Orientation of the camera sensor
     */
    private int mSensorOrientation;

    /**
     * A {@link CameraCaptureSession.CaptureCallback} that handles events related to JPEG capture.
     */
    private CameraCaptureSession.CaptureCallback mCaptureCallback
            = new CameraCaptureSession.CaptureCallback() {

        private void process(CaptureResult result) {

            switch (mState) {
                case STATE_BURST_SEQUENCE: {
                    captureStillPicture();
                }
                case STATE_PREVIEW: {
                    // We have nothing to do when the camera preview is working normally.
                    break;
                }
                case STATE_WAITING_LOCK: {
                    Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
                    if (afState == null) {
                        mState = STATE_PICTURE_TAKEN;
                        captureStillPicture();
                    } else if (CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == afState ||
                            CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == afState) {
                        // CONTROL_AE_STATE can be null on some devices
                        Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                        if (aeState == null ||
                                aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                            mState = STATE_PICTURE_TAKEN;
                            captureStillPicture();
                        } else {
                            runPrecaptureSequence();
                        }
                    }
                    break;
                }
                case STATE_WAITING_PRECAPTURE: {
                    // CONTROL_AE_STATE can be null on some devices
                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                    if (aeState == null ||
                            aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                            aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED) {
                        mState = STATE_WAITING_NON_PRECAPTURE;
                    }
                    break;
                }
                case STATE_WAITING_NON_PRECAPTURE: {
                    Log.d(TAG, "focus range: " + result.get(CaptureResult.LENS_FOCUS_RANGE) );
                    // CONTROL_AE_STATE can be null on some devices
                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                    if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                        mState = STATE_PICTURE_TAKEN;
                        captureStillPicture();
                    }
                    break;
                }
            }
        }

        @Override
        public void onCaptureProgressed(@NonNull CameraCaptureSession session,
                                        @NonNull CaptureRequest request,
                                        @NonNull CaptureResult partialResult) {
            process(partialResult);
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                       @NonNull CaptureRequest request,
                                       @NonNull TotalCaptureResult result) {
            process(result);
        }

    };

    /**
     * Shows a {@link Toast} on the UI thread.
     *
     * @param text The message to show
     */
    private void showToast(final String text) {
        final Activity activity = getActivity();
        if (activity != null) {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    /**
     * Given {@code choices} of {@code Size}s supported by a camera, choose the smallest one that
     * is at least as large as the respective texture view size, and that is at most as large as the
     * respective max size, and whose aspect ratio matches with the specified value. If such size
     * doesn't exist, choose the largest one that is at most as large as the respective max size,
     * and whose aspect ratio matches with the specified value.
     *
     * @param choices           The list of sizes that the camera supports for the intended output
     *                          class
     * @param textureViewWidth  The width of the texture view relative to sensor coordinate
     * @param textureViewHeight The height of the texture view relative to sensor coordinate
     * @param maxWidth          The maximum width that can be chosen
     * @param maxHeight         The maximum height that can be chosen
     * @param aspectRatio       The aspect ratio
     * @return The optimal {@code Size}, or an arbitrary one if none were big enough
     */
    private static Size chooseOptimalSize(Size[] choices, int textureViewWidth,
            int textureViewHeight, int maxWidth, int maxHeight, Size aspectRatio) {

        // Collect the supported resolutions that are at least as big as the preview Surface
        List<Size> bigEnough = new ArrayList<>();
        // Collect the supported resolutions that are smaller than the preview Surface
        List<Size> notBigEnough = new ArrayList<>();
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();
        for (Size option : choices) {
            if (option.getWidth() <= maxWidth && option.getHeight() <= maxHeight &&
                    option.getHeight() == option.getWidth() * h / w) {
                if (option.getWidth() >= textureViewWidth &&
                    option.getHeight() >= textureViewHeight) {
                    bigEnough.add(option);
                } else {
                    notBigEnough.add(option);
                }
            }
        }

        // Pick the smallest of those big enough. If there is no one big enough, pick the
        // largest of those not big enough.
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else if (notBigEnough.size() > 0) {
            return Collections.max(notBigEnough, new CompareSizesByArea());
        } else {
            Log.e(TAG, "Couldn't find any suitable preview size");
            if (choices.length>=12)
                return choices[12]; // dirty hack to return 1920x1080 on Zenfone AR
            else
                return choices[0];
        }
    }

    public static Camera2BasicFragment newInstance() {
        return new Camera2BasicFragment();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_camera2_basic, container, false);
    }


    private float mFocusDistance = 4.75f;
    private int globalExposure = 0;



    private Timer mTimer = null;

    private void setZoom(final boolean zoomEnabled) {
        if (mTimer != null)
            mTimer.cancel();

        if ( zoomEnabled ) {
            getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mTextureView.setTransform(mMatrixZoomed);
                }
            });
        }
        else {
            // wait 4 seconds when zoom got disabled
            mTimer = new Timer();
            mTimer.schedule (
                    new TimerTask()
                    {
                        public void run()
                        {
                            getActivity().runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    mTextureView.setTransform(mMatrix);
                                }
                            });
                        }
                    },
                    4000);  // run after two seconds



        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public void onViewCreated(final View view, Bundle savedInstanceState) {
        view.findViewById(R.id.picture).setOnClickListener(this);
        view.findViewById(R.id.autofocus).setOnClickListener(this);
        mTextureView = (AutoFitTextureView) view.findViewById(R.id.texture);
        hostAdressView = (EditText) view.findViewById(R.id.hostAddress);
        focusSeekBarView = (SeekBar) view.findViewById(R.id.focusSeekBar);
        focusDistanceView = (TextView) view.findViewById(R.id.focusDistance);

        exposureSeekBarView = (SeekBar) view.findViewById(R.id.exposureSeekBar );
        shotModeSpinnerView = (Spinner)  view.findViewById(R.id.shotModeSpinner );

        // next is from here: https://gist.github.com/royshil/8c760c2485257c85a11cafd958548482
        mTextureView.setOnTouchListener( new AutoFitTextureView.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                final int actionMasked = motionEvent.getActionMasked();

                if ( actionMasked != MotionEvent.ACTION_DOWN && actionMasked != MotionEvent.ACTION_MOVE ) {
                    return false;
                }

                setZoom(true);

                if ( actionMasked == MotionEvent.ACTION_MOVE ) {

                    float currentX = motionEvent.getX();
                    float previousX=currentX;
                    if ( motionEvent.getHistorySize() > 0 ) {
                        previousX=motionEvent.getHistoricalX( motionEvent.getHistorySize()-1 );
                    }

                    Log.d(TAG, "currentX MOVE: " + Float.toString(currentX));
                    float distance = currentX-previousX;
                    Log.d(TAG, "distance: " + Float.toString(distance));

                    mFocusDistance -= (distance / 1000) ; // pos 0=3.3f for faces - pos4=1.5f for sweethearts radkappe
                    focusDistanceView.setText( String.format("%.2f", mFocusDistance) );

                    setZoom(false);
                    Log.d(TAG, "setting LENS_FOCUS_DISTANCE for PREVIEW: " + Float.toString(mFocusDistance));
                    mPreviewRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, mFocusDistance);
                    mPreviewRequest = mPreviewRequestBuilder.build();
                    // do we need this?
                    try {
                        mCaptureSession.setRepeatingRequest(mPreviewRequest,
                                mCaptureCallback, mBackgroundHandler);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }

                }
                if ( false ) {
                }
                return true;
            }
        });

        focusSeekBarView.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                setZoom(true);
                // ZENFONE mFocusDistance = (3.3f - ((float)progress) * 0.45f / 1.5f ); // pos 0=3.3f for faces - pos4=1.5f for sweethearts radkappe

                // G4 IVY SCAN - 4.75 was good
                mFocusDistance = (4.75f - ((float)progress) * 0.45f / 1.5f ); // pos 0=3.3f for faces - pos4=1.5f for sweethearts radkappe
                focusDistanceView.setText( String.format("%.2f", mFocusDistance) );

                Log.d(TAG, "setting LENS_FOCUS_DISTANCE for PREVIEW: " + Float.toString(mFocusDistance));
                mPreviewRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, mFocusDistance);
                mPreviewRequest = mPreviewRequestBuilder.build();
                // do we need this?
                try {
                    mCaptureSession.setRepeatingRequest(mPreviewRequest,
                            mCaptureCallback, mBackgroundHandler);
                } catch (CameraAccessException e) {
                    e.printStackTrace();
                }
                finally {
                    setZoom(false);
                }
            }
        });

        exposureSeekBarView.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                globalExposure = progress;
                Log.d( TAG, "setting globalExposure: " + Integer.toString( globalExposure ) );
            }
        });
    }


    @Override
    public void onResume() {
        super.onResume();
        startBackgroundThread();

        // When the screen is turned off and turned back on, the SurfaceTexture is already
        // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
        // a camera and start preview from here (otherwise, we wait until the surface is ready in
        // the SurfaceTextureListener).
        if (mTextureView.isAvailable()) {
            openCamera(mTextureView.getWidth(), mTextureView.getHeight());
        } else {
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
    }

    @Override
    public void onPause() {
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    private void requestCameraPermission() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            new ConfirmationDialog().show(getChildFragmentManager(), FRAGMENT_DIALOG);
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length != 1 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                ErrorDialog.newInstance(getString(R.string.request_permission))
                        .show(getChildFragmentManager(), FRAGMENT_DIALOG);
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    /**
     * Sets up member variables related to camera.
     *
     * @param width  The width of available size for camera preview
     * @param height The height of available size for camera preview
     */
    @SuppressWarnings("SuspiciousNameCombination")
    private void setUpCameraOutputs(int width, int height) {
        Activity activity = getActivity();
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            for (String cameraId : manager.getCameraIdList()) {
                CameraCharacteristics characteristics
                        = manager.getCameraCharacteristics(cameraId);



                // We don't use a front facing camera in this sample.
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue;
                }

                if ( !contains(characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES),
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) ) {
                    Log.e(TAG,  "RAW Capture NOT supported");
                }
                else {
                    Log.d(TAG,  "RAW Capture SUPPORTED");
                }

                StreamConfigurationMap map = characteristics.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) {
                    continue;
                }

                // For still image captures, we use the largest available size.
                Size largestJpegImageSize = Collections.max(
                        Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)),
                        new CompareSizesByArea());
                Size largestRawImageSize = Collections.max(
                        Arrays.asList(map.getOutputSizes(ImageFormat.RAW_SENSOR)),
                        new CompareSizesByArea());
                mImageReader = ImageReader.newInstance(largestJpegImageSize.getWidth(), largestJpegImageSize.getHeight(),
                        ImageFormat.JPEG, /*maxImages*/1);
                mRawImageReader = ImageReader.newInstance(largestRawImageSize.getWidth(), largestRawImageSize.getHeight(),
                        ImageFormat.RAW_SENSOR, /*maxImages*/1);
                mImageReader.setOnImageAvailableListener(
                        mOnImageAvailableListener, mBackgroundHandler);
                mRawImageReader.setOnImageAvailableListener(
                        mOnRawImageAvailableListener, mBackgroundHandler);

                // Find out if we need to swap dimension to get the preview size relative to sensor
                // coordinate.
                int displayRotation = activity.getWindowManager().getDefaultDisplay().getRotation();
                //noinspection ConstantConditions
                mSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                boolean swappedDimensions = false;
                switch (displayRotation) {
                    case Surface.ROTATION_0:
                    case Surface.ROTATION_180:
                        if (mSensorOrientation == 90 || mSensorOrientation == 270) {
                            swappedDimensions = true;
                        }
                        break;
                    case Surface.ROTATION_90:
                    case Surface.ROTATION_270:
                        if (mSensorOrientation == 0 || mSensorOrientation == 180) {
                            swappedDimensions = true;
                        }
                        break;
                    default:
                        Log.e(TAG, "Display rotation is invalid: " + displayRotation);
                }

                Point displaySize = new Point();
                activity.getWindowManager().getDefaultDisplay().getSize(displaySize);
                int rotatedPreviewWidth = width;
                int rotatedPreviewHeight = height;
                int maxPreviewWidth = displaySize.x;
                int maxPreviewHeight = displaySize.y;

                if (swappedDimensions) {
                    rotatedPreviewWidth = height;
                    rotatedPreviewHeight = width;
                    maxPreviewWidth = displaySize.y;
                    maxPreviewHeight = displaySize.x;
                }

                if (maxPreviewWidth > MAX_PREVIEW_WIDTH) {
                    maxPreviewWidth = MAX_PREVIEW_WIDTH;
                }

                if (maxPreviewHeight > MAX_PREVIEW_HEIGHT) {
                    maxPreviewHeight = MAX_PREVIEW_HEIGHT;
                }

                // Danger, W.R.! Attempting to use too large a preview size could  exceed the camera
                // bus' bandwidth limitation, resulting in gorgeous previews but the storage of
                // garbage capture data.
                mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class),
                        rotatedPreviewWidth, rotatedPreviewHeight, maxPreviewWidth,
                        maxPreviewHeight, largestJpegImageSize);
                //mPreviewSize  = new Size(mPreviewSize.getWidth()*4, mPreviewSize.getHeight()*4);

                // We fit the aspect ratio of TextureView to the size of preview we picked.
                int orientation = getResources().getConfiguration().orientation;
                if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    mTextureView.setAspectRatio(
                            mPreviewSize.getWidth(), mPreviewSize.getHeight());
                } else {
                    mTextureView.setAspectRatio(
                            mPreviewSize.getHeight(), mPreviewSize.getWidth());
                }

                // Check if the flash is supported.
                Boolean available = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                mFlashSupported = available == null ? false : available;

                mCameraId = cameraId;

                mCameraCharacteristics = characteristics;
                return;
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (NullPointerException e) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            ErrorDialog.newInstance(getString(R.string.camera_error))
                    .show(getChildFragmentManager(), FRAGMENT_DIALOG);
        }
    }

    /**
     * Opens the camera specified by {@link Camera2BasicFragment#mCameraId}.
     */
    private void openCamera(int width, int height) {
        if (ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermission();
            return;
        }
        setUpCameraOutputs(width, height);
        configureTransform(width, height);
        Activity activity = getActivity();
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            manager.openCamera(mCameraId, mStateCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
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
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        } finally {
            mCameraOpenCloseLock.release();
        }
    }

    /**
     * Starts a background thread and its {@link Handler}.
     */
    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground");
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
            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            assert texture != null;

            // We configure the size of default buffer to be the size of camera preview we want.
            texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());

            // This is the output Surface we need to start preview.
            Surface surface = new Surface(texture);

            // We set up a CaptureRequest.Builder with the output Surface.
            mPreviewRequestBuilder
                    = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mPreviewRequestBuilder.addTarget(surface);

            // Here, we create a CameraCaptureSession for camera preview.
            mCameraDevice.createCaptureSession(Arrays.asList(surface,mImageReader.getSurface(),mRawImageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                            // The camera is already closed
                            if (null == mCameraDevice) {
                                return;
                            }

                            // When the session is ready, we start displaying the preview.
                            mCaptureSession = cameraCaptureSession;
                            try {
                                // Auto focus should be continuous for camera preview.
                                //mPreviewRequestBuilder.set(
                                //        CaptureRequest.CONTROL_AF_MODE,
                                //        CaptureRequest.CONTROL_AF_MODE_AUTO); // CONTROL_AF_MODE_CONTINUOUS_PICTURE

                                mPreviewRequestBuilder.set(
                                        CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_OFF);
                                mPreviewRequestBuilder.set(
                                        CaptureRequest.LENS_FOCUS_DISTANCE, mFocusDistance );
                                focusDistanceView.setText( String.format("%.2f", mFocusDistance) );

                                mPreviewRequestBuilder.set(
                                        CaptureRequest.CONTROL_AWB_MODE,
                                        CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT );


                                // Flash is automatically enabled when necessary.
                                // setAutoFlash(mPreviewRequestBuilder);
                                // setFlashOff(mPreviewRequestBuilder);

                                // Finally, we start displaying the camera preview.
                                mPreviewRequest = mPreviewRequestBuilder.build();
                                mCaptureSession.setRepeatingRequest(mPreviewRequest,
                                        mCaptureCallback, mBackgroundHandler);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(
                                @NonNull CameraCaptureSession cameraCaptureSession) {
                            showToast("Failed");
                        }
                    }, null
            );
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Configures the necessary {@link android.graphics.Matrix} transformation to `mTextureView`.
     * This method should be called after the camera preview size is determined in
     * setUpCameraOutputs and also the size of `mTextureView` is fixed.
     *
     * @param viewWidth  The width of `mTextureView`
     * @param viewHeight The height of `mTextureView`
     */
    private Matrix mMatrix = null;
    private Matrix mMatrixZoomed = null;

    private void configureTransform(int viewWidth, int viewHeight) {
        Activity activity = getActivity();
        if (null == mTextureView || null == mPreviewSize || null == activity) {
            return;
        }
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        mMatrix = new Matrix();
        mMatrixZoomed = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        Log.d(TAG, "Screen rotation: " + Integer.toString(rotation));
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            mMatrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            // matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.CENTER);
            float scale = Math.max(
                    (float) viewHeight / mPreviewSize.getHeight(),
                    (float) viewWidth / mPreviewSize.getWidth());
            mMatrix.postScale(scale, scale, centerX, centerY);
            mMatrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            mMatrix.postRotate(180, centerX, centerY);
        } else if (Surface.ROTATION_0 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            mMatrixZoomed.setRectToRect( bufferRect, viewRect, Matrix.ScaleToFit.CENTER);
            mMatrixZoomed.postScale( 4, 4, centerX, centerY);
        }
        mTextureView.setTransform(mMatrix);
    }


    private static boolean KEEP_FOCUS_LOCKED = false;
    /**
     * Initiate a still image capture.
     */
    private void takePicture() {
        // KEEP_FOCUS_LOCKED = true;
        try {

            mState = STATE_WAITING_NON_PRECAPTURE;

            mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback,
                    mBackgroundHandler);

        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (NullPointerException e) {

            e.printStackTrace();
        }
    }


    private void runPrecaptureSequence() {
        try {
            // This is how to tell the camera to trigger.
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            // Tell #mCaptureCallback to wait for the precapture sequence to be set.
            mState = STATE_WAITING_PRECAPTURE;
            mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback,
                    mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }



    private void captureStillPicture() {
        try {
            final Activity activity = getActivity();
            if (null == activity || null == mCameraDevice) {
                return;
            }
            // This is the CaptureRequest.Builder that we use to take a picture.
            final CaptureRequest.Builder captureRequestBuilder =
                    mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_MANUAL );
            captureRequestBuilder.addTarget(mImageReader.getSurface());
            captureRequestBuilder.addTarget(mRawImageReader.getSurface());

            captureRequestBuilder.set(
                    CaptureRequest.CONTROL_AWB_MODE,
                    CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT );

            // setManualMode(captureBuilder);
            captureRequestBuilder.set(
                    CaptureRequest.SENSOR_SENSITIVITY,
                    (int) (200)); // *isoBooster
            // setExposureTime(captureBuilder); // , exposureTime);

            Log.d(TAG, "Exposure muliplier: " + Integer.toString(perShotExposureMultiplier));
            captureRequestBuilder.set(
                    CaptureRequest.SENSOR_EXPOSURE_TIME,
                    (long)(200000000L * perShotExposureMultiplier * pow(2,globalExposure) ) ); // /isoBooster
            // 500000000L = 0.5 seconds

            //Set the JPEG quality here like so
            byte quality = 100;
            captureRequestBuilder.set(
                    CaptureRequest.JPEG_QUALITY,
                    quality );

            // Log.d(TAG, "setting LENS_FOCUS_DISTANCE for CAPTURE: " + Float.toString(mFocusDistance));
            captureRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, mFocusDistance);

            // Orientation
            int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
            captureRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION, getOrientation(rotation));

            CameraCaptureSession.CaptureCallback CaptureCallback
                    = new CameraCaptureSession.CaptureCallback() {

                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                               @NonNull CaptureRequest request,
                                               @NonNull TotalCaptureResult result) {


                    if (result==null)
                        Log.e(TAG, "onCaptureCompleted: capture result NULL");
                    else
                        Log.d(TAG, "onCaptureCompleted: capture result VALID");
                    mCaptureResult = result; //RAW shizzle
                    saveRawLock.unlock();
                }
            };

            mCaptureSession.stopRepeating();
            //mCaptureSession.abortCaptures();
            //mCaptureSession.close(); // instead of capture
            saveRawLock.lock();
            mCaptureSession.capture(captureRequestBuilder.build(), CaptureCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Retrieves the JPEG orientation from the specified screen rotation.
     *
     * @param rotation The screen rotation.
     * @return The JPEG orientation (one of 0, 90, 270, and 360)
     */
    private int getOrientation(int rotation) {
        // Sensor orientation is 90 for most devices, or 270 for some devices (eg. Nexus 5X)
        // We have to take that into account and rotate JPEG properly.
        // For devices with orientation of 90, we simply return our mapping from ORIENTATIONS.
        // For devices with orientation of 270, we need to rotate the JPEG 180 degrees.
        return (ORIENTATIONS.get(rotation) + mSensorOrientation + 270) % 360;
    }

    /**
     * Unlock the focus. This method should be called when still image capture sequence is
     * finished.
     */
    private void backToPreviewState() {
        try {
            // keep focus locked!!!
            // This is how to tell the camera to keep the focus locked.
//            mPreviewRequestBuilder.set(
//                    CaptureRequest.CONTROL_AF_TRIGGER,
//                    CaptureRequest.CONTROL_AF_TRIGGER_IDLE);

            mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback,
                    mBackgroundHandler);
            // After this, the camera will go back to the normal state of preview.
            mState = STATE_PREVIEW;
            mCaptureSession.setRepeatingRequest(mPreviewRequest, mCaptureCallback,
                    mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.picture: {
                Log.d(TAG, "SHOOT button pressed." );
                new initiateRemoteControlFromPi().execute("");
                break;
            }
            case R.id.autofocus: {
                Log.d(TAG, "AUTOFOCUS button pressed." );
                setZoom(true);

                // actionMasked == MotionEvent.ACTION_DOWN

                if (mManualFocusEngaged) {
                    Log.d(TAG, "Manual focus already engaged");
                    return;// true;
                }


                final Rect sensorArraySize = mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);

                Log.d(TAG, "Sensor Orientation: " + Integer.toString(mCameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)));


                //TODO: here I just flip x,y, but this needs to correspond with the sensor orientation (via SENSOR_ORIENTATION)
                final int x = sensorArraySize.width() / 2;
                final int y = sensorArraySize.width() / 2;
                //final int y = (int)((motionEvent.getX() / (float)view.getWidth())  * (float)sensorArraySize.height());
                //final int x = (int)((motionEvent.getY() / (float)view.getHeight()) * (float)sensorArraySize.width());
                //Log.d(TAG, "Touch coords: " + Float.toString(motionEvent.getX()) + " " + Float.toString(motionEvent.getY()));
                Log.d(TAG, "view Width/height: " + Integer.toString(view.getWidth()) + " " + Float.toString(view.getHeight()));
                Log.d(TAG, "sensor Width/height: " + Integer.toString(sensorArraySize.width()) + " " + Float.toString(sensorArraySize.height()));

                final int halfTouchWidth = 75; //(int)motionEvent.getTouchMajor(); //TODO: this doesn't represent actual touch size in pixel. Values range in [3, 10]...
                final int halfTouchHeight = halfTouchWidth; //(int)motionEvent.getTouchMinor();
                MeteringRectangle focusAreaTouch = new MeteringRectangle(Math.max(x - halfTouchWidth, 0),
                        Math.max(y - halfTouchHeight, 0),
                        halfTouchWidth * 2,
                        halfTouchHeight * 2,
                        MeteringRectangle.METERING_WEIGHT_MAX - 1);

                CameraCaptureSession.CaptureCallback captureCallbackHandler = new CameraCaptureSession.CaptureCallback() {
                    @Override
                    public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                        super.onCaptureCompleted(session, request, result);
                        mManualFocusEngaged = false;
                        setZoom(false);

                        if (request.getTag() == "FOCUS_TAG") {
                            //the focus trigger is complete -
                            //resume repeating (preview surface will get frames), clear AF trigger
                            Log.d(TAG, "Focus capture complete");
                            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, null);
                            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
                            try {
                                mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(), null, null);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }
                    }

                    @Override
                    public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request, CaptureFailure failure) {
                        super.onCaptureFailed(session, request, failure);
                        Log.e(TAG, "Manual AF failure: " + failure);
                        mManualFocusEngaged = false;
                        setZoom(false);
                    }
                };

                //first stop the existing repeating request
                try {
                    mCaptureSession.stopRepeating();

                    //cancel any existing AF trigger (repeated touches, etc.)
                    mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
                    mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
                    mCaptureSession.capture(mPreviewRequestBuilder.build(), captureCallbackHandler, mBackgroundHandler);

                    //Now add a new AF trigger with focus region
                    if (isMeteringAreaAFSupported()) {
                        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{focusAreaTouch});
                    } else {
                        Log.e(TAG, "Metering areas not suppurted!");
                    }
                    //mPreviewRequestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                    mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
                    mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
                    mPreviewRequestBuilder.setTag("FOCUS_TAG"); //we'll capture this later for resuming the preview

                    //then we ask for a single request (not repeating!)
                    mCaptureSession.capture(mPreviewRequestBuilder.build(), captureCallbackHandler, mBackgroundHandler);
                    mManualFocusEngaged = true;

                    return;// true;

                } catch (CameraAccessException e) {
                    e.printStackTrace();
                }
                break;
            }

        }
    }
    private boolean isMeteringAreaAFSupported() {
        return mCameraCharacteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) >= 1;
    }

    public static String getCurrentTimeStamp() {
        SimpleDateFormat sdfDate = new SimpleDateFormat("yyyyMMdd_HHmmss");//dd/MM/yyyy
        Date now = new Date();
        String strDate = sdfDate.format(now);
        return strDate;
    }

    /**
     * count from 0 for every lightstage capture session
     */

    private static DataOutputStream lightstageOutStream = null;
    private static DataOutputStream pmdOutStream = null;
    private static DataInputStream lightstageInputStream = null;
    private static DataInputStream pmdInputStream = null;
    private int pictureCounter=0;
    private int nextPictureToStartWith =0;
    private String pictureSession;

    private int perShotExposureMultiplier =1;


    private class initiateRemoteControlFromPi extends AsyncTask<String, Void, String> {


        private void callDelayedImageSaver() {
            saveJpegLock.lock();
            saveRawLock.lock();
            try {
                // delayed write all frames:
                for (int imgIdx = nextPictureToStartWith; imgIdx<pictureCounter; imgIdx++) {
                    mFile = new File(getActivity().getExternalFilesDir(null), pictureSession + "_" + String.format("%04d", imgIdx) + ".jpg");
                    mFileArray[imgIdx] = mFile;
                    mFile = new File(getActivity().getExternalFilesDir(null), pictureSession + "_" + String.format("%04d", imgIdx) + ".dng");
                    mRawFileArray[imgIdx] = mFile;
                }
                mBackgroundHandler.post(new DelayedImageSaver(mFileArray,mRawFileArray,nextPictureToStartWith, pictureCounter));
                nextPictureToStartWith = pictureCounter;
            }
            finally {
                saveJpegLock.unlock();
                saveRawLock.unlock();
            }
        }

        @Override
        protected String doInBackground(String... params) {

            String hostname = hostAdressView.getText().toString();
            Socket lightstageClientSocket = null;
            int lightStagePort=50007;
            Socket pmdClientSocket = null;
            int pmdPort=50008;

            pictureSession = getCurrentTimeStamp();
            Log.d(TAG, "timestamp: " + pictureSession );
            pictureCounter=0;
            byteList.clear();

            // init lightstage shotmode
            String shotMode = String.valueOf( shotModeSpinnerView.getSelectedItem() );
            /*
            if (shotMode.startsWith( "do nothing - just shoot" )) {
                takePicture();
                callDelayedImageSaver();
                backToPreviewState();
                Log.d( TAG,"clean exit");
                return null;
            }
            else {
                Log.d(TAG, "shotMode: " + shotMode);
            }
            */




            try {
                boolean pmd_present=false;
                try {
                    lightstageClientSocket = new Socket(hostname, lightStagePort);
                    }
                catch (UnknownHostException e) {
                    System.err.println("Don't know about host: " + hostname );
                    showToast("Don't know about host: " + hostname );
                    return null;
                }

                try {
                    pmdClientSocket = new Socket(hostname, pmdPort);
                    pmd_present=true;
                }
                catch (IOException e) {
                    System.err.println("PMD not present - continuing without it");

                }

                lightstageOutStream = new DataOutputStream(lightstageClientSocket.getOutputStream());
                lightstageInputStream = new DataInputStream(lightstageClientSocket.getInputStream());

                // INIT LIGHTSTAGE
                Integer welcomeMessage=-1;
                switch (shotMode) {
                    case "single shot": {
                        welcomeMessage=messageCodes.LIGHTSTAGE_SHOT_MODE_SINGLE_SHOT.getCode();
                        break;
                    }
                    case "single LED - substance style": {
                        welcomeMessage=messageCodes.LIGHTSTAGE_SHOT_MODE_SINGLE_LED_TURNAROUND.getCode();;
                        break;
                    }
                    case "no polarizer": {
                        welcomeMessage=messageCodes.LIGHTSTAGE_SHOT_MODE_NO_POLARIZER.getCode();;
                        break;
                    }
                    case "cross-polarized": {
                        welcomeMessage=messageCodes.LIGHTSTAGE_SHOT_MODE_CROSS_POLARIZED_ONLY.getCode();;
                        break;
                    }
                    case "full-blown shoot": {
                        welcomeMessage=messageCodes.LIGHTSTAGE_SHOT_MODE_FULL_BLOWN.getCode();;
                        break;
                    }
                    case "do nothing - just shoot": {
                        welcomeMessage=messageCodes.LIGHTSTAGE_SHOT_MODE_DO_NOTHING.getCode();;
                        break;
                    }
                }
                lightstageOutStream.writeByte(welcomeMessage);
                lightstageOutStream.flush(); // Send off the data

                if (pmd_present) {
                    pmdOutStream = new DataOutputStream(pmdClientSocket.getOutputStream());
                    pmdInputStream = new DataInputStream(pmdClientSocket.getInputStream());

                    pmdOutStream.writeByte(1); // INIT PMD
                    pmdOutStream.flush(); // Send off the data
                }

                Log.d(TAG, "waiting for lightstage");

                if (lightstageInputStream.readByte()==welcomeMessage) { // we are expecting the same message back that we just sent
                    Log.d(TAG, "lightstage ready!");
                }
                else {
                    Log.d(TAG, "lightstage not ready - got back wrong welcome message");
                    return null;
                }

                if (pmd_present) {
                    Log.d(TAG, "waiting for pmd");
                    // wait for pmd to answer
                    if (pmdInputStream.readByte()==1) {
                        Log.d(TAG, "pmd ready!");
                    }
                }


                boolean pmd_recording=false;
                while (true) {
                    if (pictureCounter-nextPictureToStartWith > 8) {
                        Log.d(TAG, "TOO many images in RAM - callDelayedImageSaver");
                        callDelayedImageSaver();
                    }

                    int messageFromLightstage = lightstageInputStream.readByte();

                    if (messageFromLightstage == messageCodes.ANDROID_START_CAPTURE.getCode() ) {
                        if (pmd_present && pmd_recording==false) {
                            // start recording on pmd
                            pmdOutStream.writeByte(2); // start recording
                            pmdOutStream.flush(); // Send off the data
                            pmd_recording=true;
                        }

                        try {
                            Log.d( TAG, "taking picture: " + String.format("%04d", pictureCounter));
                            takePicture();
                        }
                        catch (Exception e) {
                            backToPreviewState();
                            e.printStackTrace();
                            Log.e(TAG, messageCodes.ANDROID_ERROR_TAKING_PICTURE.getDescription());
                            try {
                                lightstageOutStream.writeByte( messageCodes.ANDROID_ERROR_TAKING_PICTURE.getCode() );
                                if (pmd_present)
                                    pmdOutStream.writeByte( messageCodes.ANDROID_ERROR_TAKING_PICTURE.getCode() );
                            }
                            catch (IOException e1) {
                                e1.printStackTrace();
                            }
                        }
                    } else if (messageFromLightstage == messageCodes.ANDROID_ADJUST_EXPOURE.getCode() ) {
                        perShotExposureMultiplier = lightstageInputStream.readByte();
                        //ACKNOLEDGE TO LIGHTSTAGE with same message
                        lightstageOutStream.writeByte(perShotExposureMultiplier);

                    } else if (messageFromLightstage == messageCodes.LIGHTSTAGE_POLARIZER_STARTS_ROTATING.getCode() ) {
                        // intermediate writing of frames because lightstage does some physical
                        // movemnet which gives us some time to do something useful to prevent
                        // running out of memory
                        Log.d( TAG, "LIGHTSTAGE_POLARIZER_STARTS_ROTATING - callDelayedImageSaver");
                        callDelayedImageSaver();
                        lightstageOutStream.writeByte(messageCodes.ANDROID_FINISHED_WRITING_IMAGES.getCode());

                    } else if (messageFromLightstage == messageCodes.CLEAN_EXIT.getCode() ) {
                        callDelayedImageSaver();

                        if (pmd_present) {
                            pmdOutStream.writeByte(-1);
                            int pmdReturn=0;
                            while (pmdReturn!=-1) {
                                pmdReturn = pmdInputStream.readByte();
                                if ( pmdReturn == -1) { // CLOSE PMD
                                    Log.d(TAG, "pmd shutdown successful");
                                    showToast("pmd shutdown successful");
                                } else if ( pmdReturn == 2) {
                                    Log.d(TAG, "pmd writing now");
                                    showToast("pmd writing now");
                                }
                                else {
                                    Log.d(TAG, "unknown message from pmd " + String.format("%04d", pictureCounter));
                                    showToast("unknown message from pmd " + String.format("%04d", pictureCounter));
                                }
                            }
//                        pmdInputStream.close();
                            pmdInputStream.close();
                            pmdOutStream.close();
                            pmdClientSocket.close();
                        }
                        lightstageInputStream.close();
                        lightstageOutStream.close();
                        lightstageClientSocket.close(); // close is the preferred way over shutdown

                        backToPreviewState();

                        Log.d( TAG,"clean exit");
                        return null;
                    }
                }

            } catch (UnknownHostException e) {
                System.err.println("Don't know about host: " + hostname);
            } catch (IOException e) {
                System.err.println("Couldn't get I/O for the connection to: " + hostname);
                System.err.println(e);

            }
            return null;
        }
    }


    /**
     * Saves a JPEG {@link Image} into the specified {@link File}.
     */
    /*private static class ImageSaver implements Runnable {

        *//**
         * The JPEG image
         *//*
        private final Image mImage;
        *//**
         * The file we save the image into.
         *//*
        private final File mFile;

        ImageSaver(Image image, File file) {
            mImage = image;
            mFile = file;
        }

        @Override
        public void run() {
            ByteBuffer buffer = mImage.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);

            FileOutputStream output = null;

            try {
                output = new FileOutputStream(mFile);
                output.write(bytes);
            } catch (IOException e) {
                e.printStackTrace();
                Log.d(TAG, "something went wrong during file save");
                try {
                    lightstageOutStream.writeByte(-2);
                } catch (IOException e1) {
                    e1.printStackTrace();
                }
            } finally {
                mImage.close();
                if (null != output) {
                    try {
                        output.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
            if (lightstageOutStream!=null)
                try {
                    Log.d(TAG, "signaling lightstage to continue");
                    lightstageOutStream.writeByte(3);

                } catch (IOException e) {
                    e.printStackTrace();
                }
            else
                Log.e(TAG, "lightstage Outputstream not available");
        }

    }*/

    public static ByteBuffer cloneByteBuffer(final ByteBuffer original) {
        // Create clone with same capacity as original.
        final ByteBuffer clone = (original.isDirect()) ?
                ByteBuffer.allocateDirect(original.capacity()) :
                ByteBuffer.allocate(original.capacity());
        clone.order( ByteOrder.LITTLE_ENDIAN );

        // Create a read-only copy of the original.
        // This allows reading from the original without modifying it.
        final ByteBuffer readOnlyCopy = original.asReadOnlyBuffer();

        // Flip and read from the original.
        readOnlyCopy.flip();
        clone.put(readOnlyCopy);

        return clone;
    }

    private class DelayedImageSaver implements Runnable {

        /**
         * The JPEG image array
         */
        //private final Image[] mImageArray;
        //private ByteBuffer[] bufferArray;

        private final int mNextPictureToStartWith,mImageCount;

        /**
         * The file we save the image into - array
         */
        private final File[] mFileArray, mRawFileArray;

        DelayedImageSaver( File[] fileArray, File[] rawFileArray, int nextPictureToStartWith, int imageCount) {
            mFileArray = fileArray;
            mRawFileArray = rawFileArray;
            mNextPictureToStartWith=nextPictureToStartWith;
            mImageCount = imageCount;
        }

        @Override
        public void run() {
            for (int imgIdx=mNextPictureToStartWith;imgIdx<mImageCount;imgIdx++) {
                File mFile=mFileArray[imgIdx];
                File mRawFile=mRawFileArray[imgIdx];
                FileOutputStream jpegOutput = null;
                FileOutputStream rawFileOutputStream = null;


                try {
                    // write jpeg
                    jpegOutput = new FileOutputStream(mFile);
                    jpegOutput.write( byteList.get(imgIdx) );
                    byteList.set( imgIdx, null ); // clear the element, but do not remove it
                    Log.d(TAG, "cleared JPG image: " + Integer.toString(imgIdx));

                    //write RAW
                    rawFileOutputStream = new FileOutputStream(mRawFile);
                    rawByteArrayOutputStreamArray[imgIdx].writeTo(rawFileOutputStream);
                    rawFileOutputStream.close();
                    rawByteArrayOutputStreamArray[imgIdx].close();
                    rawByteArrayOutputStreamArray[imgIdx]=null;
                    Log.d(TAG, "cleared RAW image: " + Integer.toString(imgIdx));
                } catch (IOException e) {
                    Log.d( TAG, messageCodes.ANDROID_ERROR_SAVING_PICTURE.getDescription() );
                    showToast( e.getMessage() );
                    e.printStackTrace();
                    try {
                        lightstageOutStream.writeByte(messageCodes.ANDROID_ERROR_SAVING_PICTURE.getCode());
                    } catch (IOException e1) {
                        e1.printStackTrace();
                    }
                } finally {
                    if (null != jpegOutput) {
                        try {
                            jpegOutput.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        }

    }

    /**
     * Compares two {@code Size}s based on their areas.
     */
    static class CompareSizesByArea implements Comparator<Size> {

        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }

    }

    /**
     * Shows an error message dialog.
     */
    public static class ErrorDialog extends DialogFragment {

        private static final String ARG_MESSAGE = "message";

        public static ErrorDialog newInstance(String message) {
            ErrorDialog dialog = new ErrorDialog();
            Bundle args = new Bundle();
            args.putString(ARG_MESSAGE, message);
            dialog.setArguments(args);
            return dialog;
        }

        @NonNull
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final Activity activity = getActivity();
            return new AlertDialog.Builder(activity)
                    .setMessage(getArguments().getString(ARG_MESSAGE))
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            activity.finish();
                        }
                    })
                    .create();
        }

    }

    /**
     * Shows OK/Cancel confirmation dialog about camera permission.
     */
    public static class ConfirmationDialog extends DialogFragment {

        @NonNull
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final Fragment parent = getParentFragment();
            return new AlertDialog.Builder(getActivity())
                    .setMessage(R.string.request_permission)
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            parent.requestPermissions(new String[]{Manifest.permission.CAMERA},
                                    REQUEST_CAMERA_PERMISSION);
                        }
                    })
                    .setNegativeButton(android.R.string.cancel,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    Activity activity = parent.getActivity();
                                    if (activity != null) {
                                        activity.finish();
                                    }
                                }
                            })
                    .create();
        }
    }

    private static Boolean contains( int [] modes, int mode ){
        if (modes == null) {
            return false;
        }
        for (int i : modes ) {
            if (i == mode)
                return true;
            // Log.d(TAG, "MODE: " + Integer.toString(i));
        }
        return false;
    }
}
