package org.apache.cordova.camera;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.hardware.camera2.CameraCharacteristics;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Rational;
import android.util.Size;
import android.view.OrientationEventListener;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;


import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraControl;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExposureState;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.MeteringPointFactory;
import androidx.camera.core.Preview;
import androidx.camera.core.ViewPort;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.core.ZoomState;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.view.PreviewView;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;
import androidx.transition.ChangeBounds;
import androidx.transition.Transition;
import androidx.transition.TransitionManager;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.OutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.cordova.camera.ExifHelper;

@ExperimentalCamera2Interop
public class CameraXActivity extends AppCompatActivity implements View.OnClickListener {
    private static final String TAG = "CameraXActivity";
    private static final int REQUEST_CODE_PERMISSIONS = 10;
    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.CAMERA,
    };
    private static final int JPEG = 0;
    private static final int PNG = 1;
    
    private boolean isInitialCameraStart = true;
    
    private boolean isInitialSetup = true;
    private int originalLeftPadding = 0;
    private int originalTopPadding = 0;
    private int originalRightPadding = 0; 
    private int originalBottomPadding = 0;
    private boolean originalPaddingSaved = false;

    // Preview mode
    private Bitmap currentPreviewBitmap;
    private boolean isInPreviewMode = false;
    private Uri capturedImageUri;
    private File tempImageFile;
    private LoadImageTask loadImageTask;

    private ConstraintLayout bottomControls;
    private ConstraintLayout previewControls;
    private ConstraintLayout actionBarBackground;
    private Button retakeButton;
    private Button usePhotoButton;

    private PreviewView previewView;
    private ImageView imagePreview;
    private ImageButton captureButton;
    private ImageButton cameraFlipButton;
    private ImageButton flashButton;
    private LinearLayout flashModesBar;
    private LinearLayout zoomButtonsContainer;
    private ImageButton flashAutoButton;
    private ImageButton flashOnButton;
    private ImageButton flashOffButton;
    private OrientationEventListener orientationListener;
    private SeekBar zoomSeekBar;
    private TextView zoomLevelText;
    private TextView wideAngleButton;
    private TextView normalZoomButton;
    
    private Handler handler = new Handler();
    private Runnable hideZoomControlsRunnable;
    private boolean hasReachedMinimum = false;
    private long timeAtMinZoom = 0;
    private static final long MIN_TIME_AT_MIN_ZOOM = 200;
    
    private boolean isUserControllingZoom = false;
    private boolean hasUltraWideCamera = false;
    private boolean usingUltraWideCamera = false;
    
    private ScaleGestureDetector scaleGestureDetector;
    private Camera camera;
    private float currentZoomRatio = 1.0f;
    private float maxZoomRatio = 8.0f;
    private float minZoomRatio = 0.5f;
    private int currentOrientation = 0;

    // Exposure control
    private LinearLayout exposureControlContainer;
    private SeekBar exposureSeekBar;
    private Handler exposureHideHandler = new Handler();
    private Runnable hideExposureControlsRunnable;
    private boolean isUserControllingExposure = false;
    private float currentExposureValue = 0f; 
    private float minExposure = -3.0f; 
    private float maxExposure = 3.0f;
    private long lastScaleEndTime = 0;
    private static final long SCALE_COOLDOWN_PERIOD = 300;
    
    private ImageCapture imageCapture;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    
    // Camera state
    private int cameraFacing = CameraSelector.LENS_FACING_BACK;
    private int flashMode = ImageCapture.FLASH_MODE_AUTO;
    private boolean flashModeBarVisible = false;
    
    // Options passed from Cordova
    private int quality = 50;
    private int targetWidth = 0;
    private int targetHeight = 0;
    private boolean saveToPhotoAlbum = false;
    private boolean correctOrientation = true;
    private int encodingType = 0;
    private boolean allowEdit = false;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(getResources().getIdentifier("camerax_activity", "layout", getPackageName()));
        
        initializeViews();
        
        // Extract parameters from intent
        Intent intent = getIntent();
        quality = intent.getIntExtra("quality", 50);
        targetWidth = intent.getIntExtra("targetWidth", 0);
        targetHeight = intent.getIntExtra("targetHeight", 0);
        saveToPhotoAlbum = intent.getBooleanExtra("saveToPhotoAlbum", false);
        correctOrientation = intent.getBooleanExtra("correctOrientation", true);
        allowEdit = intent.getBooleanExtra("allowEdit", false);
        encodingType = intent.getIntExtra("encodingType",0);
        flashMode = intent.getIntExtra("flashMode", ImageCapture.FLASH_MODE_AUTO);

        capturedImageUri = getIntent().getParcelableExtra(MediaStore.EXTRA_OUTPUT);

        // Restore state if needed
        if (savedInstanceState != null) {
            isInPreviewMode = savedInstanceState.getBoolean("isInPreviewMode", false);
            String tempPath = savedInstanceState.getString("tempImagePath");
            if (tempPath != null) {
                tempImageFile = new File(tempPath);
                if (!tempImageFile.exists()) {
                    tempImageFile = null;
                    isInPreviewMode = false;
                }
            }
            
            // Restore UI state after views are initialized
            if (isInPreviewMode && tempImageFile != null) {
                showPreviewMode();
            }
        }
        
        setFlashButtonIcon(flashMode);
        
        //set up orientation listener
        setupOrientationListener();
        
        // Check and request permissions
        if (allPermissionsGranted()) {
            if (!isInPreviewMode) {
            startCamera();
            }
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("isInPreviewMode", isInPreviewMode);
        if (tempImageFile != null) {
            outState.putString("tempImagePath", tempImageFile.getAbsolutePath());
        }
    }
    
    private static class LoadImageTask extends AsyncTask<String, Void, Bitmap> {
        private final WeakReference<CameraXActivity> activityRef;
        
        LoadImageTask(CameraXActivity activity) {
            this.activityRef = new WeakReference<>(activity);
        }
        
        @Override
        protected Bitmap doInBackground(String... paths) {
            if (isCancelled()) return null;
            
            String imagePath = paths[0];
            
            // Get the dimensions of the ImageView
            CameraXActivity activity = activityRef.get();
            if (activity == null || activity.imagePreview == null) return null;
            
            try {
            
                int targetWidth = 1080;
                int targetHeight = 1920;
                
                // First decode with inJustDecodeBounds=true to check dimensions
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(imagePath, options);
                
                if (isCancelled()) return null;
                
                // Calculate sample size to reduce memory usage
                int sampleSize = calculateInSampleSize(options, targetWidth, targetHeight);
                
                // Decode with inSampleSize set
                options.inJustDecodeBounds = false;
                options.inSampleSize = sampleSize;
                options.inPreferredConfig = Bitmap.Config.RGB_565;
                
                Bitmap bitmap = BitmapFactory.decodeFile(imagePath, options);
                
                if (bitmap == null || isCancelled()) return null;
                
                // Handle rotation based on EXIF data
                bitmap = rotateImageIfRequired(bitmap, imagePath);
                
                return bitmap;
            
        } catch (Exception e) {
            Log.e("LoadImageTask", "Error loading image: " + e.getMessage());
            return null;
        }
    }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            CameraXActivity activity = activityRef.get();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                // Activity is gone, clean up the bitmap
                if (bitmap != null && !bitmap.isRecycled()) {
                    bitmap.recycle();
                }
                return;
            }
            
            if (activity.imagePreview == null) {
                // View is not available, clean up
                if (bitmap != null && !bitmap.isRecycled()) {
                    bitmap.recycle();
                }
                return;
            }
            
            if (bitmap != null) {
                // Clean up previous bitmap properly
                activity.cleanupPreviewBitmap();
                
                // Set new bitmap
                activity.currentPreviewBitmap = bitmap;
                activity.imagePreview.setImageBitmap(bitmap);
            } else {
                Log.e(TAG, "Failed to load preview image");
                Toast.makeText(activity, "Error displaying preview", Toast.LENGTH_SHORT).show();
                activity.showCameraMode();
            }
        }
        
        private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
            final int height = options.outHeight;
            final int width = options.outWidth;
            int inSampleSize = 1;
            
            if (height > reqHeight || width > reqWidth) {
                final int halfHeight = height / 2;
                final int halfWidth = width / 2;
                
                while ((halfHeight / inSampleSize) >= reqHeight
                        && (halfWidth / inSampleSize) >= reqWidth) {
                    inSampleSize *= 2;
                }
            }
            return inSampleSize;
        }

    private Bitmap rotateImageIfRequired(Bitmap bitmap, String imagePath) {
        try {
            ExifInterface ei = new ExifInterface(imagePath);
            int orientation = ei.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    return rotateImage(bitmap, 90);
                case ExifInterface.ORIENTATION_ROTATE_180:
                    return rotateImage(bitmap, 180);
                case ExifInterface.ORIENTATION_ROTATE_270:
                    return rotateImage(bitmap, 270);
                default:
                    return bitmap;
            }
        } catch (IOException e) {
            Log.w("LoadImageTask", "Could not read EXIF data: " + e.getMessage());
            return bitmap;
        }
    }
    
    private Bitmap rotateImage(Bitmap source, float angle) {
        Matrix matrix = new Matrix();
        matrix.postRotate(angle);
        
        Bitmap rotatedBitmap = Bitmap.createBitmap(source, 0, 0, 
            source.getWidth(), source.getHeight(), matrix, true);
        
        // Recycle original if it's different from rotated
        if (rotatedBitmap != source) {
            source.recycle();
        }
        
        return rotatedBitmap;
    }
}
    
    // Cleanup methods
    private void cleanupPreviewBitmap() {
        if (imagePreview != null) {
                imagePreview.setImageDrawable(null);
            }
             
        if (currentPreviewBitmap != null && !currentPreviewBitmap.isRecycled()) {
            currentPreviewBitmap.recycle();
            currentPreviewBitmap = null;
        }
    }
    
    private void cleanupTempFile() {
        if (tempImageFile != null && tempImageFile.exists()) {
            try {
                tempImageFile.delete();
            } catch (Exception e) {
                Log.w(TAG, "Failed to delete temp file: " + e.getMessage());
            }
            tempImageFile = null;
        }
    }
    
    // Preview mode methods
    private void showPreviewMode() {
        isInPreviewMode = true;
        
        // Hide camera UI
        previewView.setVisibility(View.GONE);
        bottomControls.setVisibility(View.GONE);
        actionBarBackground.setVisibility(View.GONE);
        
        // Show preview UI
        imagePreview.setVisibility(View.VISIBLE);
        previewControls.setVisibility(View.VISIBLE);
        
        // Load and display the captured image

        updateNavigationBarPadding(getResources().getConfiguration().orientation);
        displayCapturedImage();
    }
    
    private void showCameraMode() {
        isInPreviewMode = false;

        cleanupPreviewBitmap();
        cleanupTempFile();
        
        // Show camera UI
        previewView.setVisibility(View.VISIBLE);
        bottomControls.setVisibility(View.VISIBLE);
        actionBarBackground.setVisibility(View.VISIBLE);
        
        // Hide preview UI
        imagePreview.setVisibility(View.GONE);
        previewControls.setVisibility(View.GONE);

        if (allPermissionsGranted()) {
            startCamera();
        }
    }
    
    private void displayCapturedImage() {
        if (tempImageFile != null && tempImageFile.exists()) {
            // Cancel any existing image loading task
            if (loadImageTask != null && !loadImageTask.isCancelled()) {
                loadImageTask.cancel(true);
            }
            
            // Start new background task to load and scale image
            loadImageTask = new LoadImageTask(this);
            loadImageTask.execute(tempImageFile.getAbsolutePath());
        }
    }
    
    private void handleRetake() {
        // Cancel any running image loading task first
        if (loadImageTask != null && !loadImageTask.isCancelled()) {
            loadImageTask.cancel(true);
            loadImageTask = null;
        }
        showCameraMode();
    }
    
    private void handleUsePhoto() {
        if (tempImageFile != null && tempImageFile.exists() && capturedImageUri != null) {
            try {
                // Copy the temp file to the expected output URI
                InputStream inputStream = new FileInputStream(tempImageFile);
                OutputStream outputStream = getContentResolver().openOutputStream(capturedImageUri);
                
                if (outputStream != null) {
                    byte[] buffer = new byte[8192]; // Larger buffer for better performance
                    int bytesRead;
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                    }
                    
                    inputStream.close();
                    outputStream.close();
                    
                    // Clean up resources
                    cleanupPreviewBitmap();
                    cleanupTempFile();
                    
                    // Return success to Cordova
                    setResult(Activity.RESULT_OK);
                    finish();
                } else {
                    Log.e(TAG, "Failed to open output stream");
                    setResult(Activity.RESULT_CANCELED);
                    finish();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error copying final image: " + e.getMessage());
                setResult(Activity.RESULT_CANCELED);
                finish();
            }
        } else {
            Log.e(TAG, "No temp image file or output URI");
            setResult(Activity.RESULT_CANCELED);
            finish();
        }
    }
    
    // Zoom methods
 
    private void showZoomControls() {
    if (zoomSeekBar != null) {
        zoomSeekBar.setVisibility(View.VISIBLE);
    }
    if (zoomLevelText != null) {
        zoomLevelText.setVisibility(View.VISIBLE);
    }
    
    // Cancel any pending hide operations
    handler.removeCallbacks(hideZoomControlsRunnable);
}

    private void updateZoomLevelDisplay(float zoomRatio) {
        String formattedZoom;

        if (usingUltraWideCamera && zoomRatio <= 1.1f) {
            formattedZoom = "0.5x";
        } else {formattedZoom = String.format(Locale.US, "%.1fx", zoomRatio);
               }
        
        zoomLevelText.setText(formattedZoom);
        zoomLevelText.setVisibility(View.VISIBLE);

        handler.removeCallbacks(hideZoomControlsRunnable);
        handler.postDelayed(hideZoomControlsRunnable, 2000);
    }

    @Override
    public void onClick(View view) {
        int id = view.getId();
        if (id == getResources().getIdentifier("capture_button", "id", getPackageName())) {
            takePhoto();
        } else if (id == getResources().getIdentifier("camera_flip_button", "id", getPackageName()))  {
            flipCamera();
        } else if (id == getResources().getIdentifier("flash_button", "id", getPackageName())) {
            toggleFlashModeBar();
        } else if (id == getResources().getIdentifier("flash_auto_button", "id", getPackageName()))  {
            setFlashMode(ImageCapture.FLASH_MODE_AUTO);
            toggleFlashModeBar();
        } else if (id == getResources().getIdentifier("flash_on_button", "id", getPackageName()))  {
            setFlashMode(ImageCapture.FLASH_MODE_ON);
            toggleFlashModeBar();
        } else if (id == getResources().getIdentifier("flash_off_button", "id", getPackageName()))  {
            setFlashMode(ImageCapture.FLASH_MODE_OFF);
            toggleFlashModeBar();
        } else if (id == getResources().getIdentifier("wide_angle_button", "id", getPackageName())) {
        switchToWideAngleCamera();
        } else if (id == getResources().getIdentifier("normal_zoom_button", "id", getPackageName())) {
            switchToNormalCamera();
        } else if (id == getResources().getIdentifier("retake_button", "id", getPackageName())) {
            handleRetake();
        } else if (id == getResources().getIdentifier("use_photo_button", "id", getPackageName())) {
            handleUsePhoto();
        }
    }
    
    // Flash Methods
    private void toggleFlashModeBar() {
        // Don't toggle flash mode bar in ultra-wide mode
        if (usingUltraWideCamera) {
            Toast.makeText(this, "Flash not available in wide-angle mode", Toast.LENGTH_SHORT).show();
            return;
        }
        
        flashModeBarVisible = !flashModeBarVisible;
        flashModesBar.setVisibility(flashModeBarVisible ? View.VISIBLE : View.GONE);
    }
    
   private void flipCamera() {
        cameraFacing = (cameraFacing == CameraSelector.LENS_FACING_BACK) ? 
                CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK;
        
        // Reset ultra-wide mode when switching to front camera
        if (cameraFacing == CameraSelector.LENS_FACING_FRONT) {
            usingUltraWideCamera = false;
        }
        
        startCamera(); // Restart camera with new facing
    }
    
    private void setFlashMode(int mode) {
        flashMode = mode;
        setFlashButtonIcon(flashMode);
        
        // Update the imageCapture configuration with the new flash mode
        if (imageCapture != null) {
            imageCapture.setFlashMode(flashMode);
        }
    }
    
    private void setFlashButtonIcon(int mode) {
        // Update the button icon
        switch (mode) {
            case ImageCapture.FLASH_MODE_AUTO:
                flashButton.setBackground(getDrawable(getResources().getIdentifier("ic_flash_auto", "drawable", getPackageName())));
                break;
            case ImageCapture.FLASH_MODE_ON:
                flashButton.setBackground(getDrawable(getResources().getIdentifier("ic_flash_on", "drawable", getPackageName())));
                break;
            case ImageCapture.FLASH_MODE_OFF:
                flashButton.setBackground(getDrawable(getResources().getIdentifier("ic_flash_off", "drawable", getPackageName())));
                break;
        }
    }

    // Orientation Methods
    private void setupOrientationListener() {
    try {
        orientationListener = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int orientation) {
                
            try {
                if (orientation == ORIENTATION_UNKNOWN) {
                    return;
                }
    
                // Convert orientation to nearest 90 degrees
                int rotation;
                if (orientation > 315 || orientation <= 45) {
                    rotation = Surface.ROTATION_0;
                } else if (orientation > 45 && orientation <= 135) {
                    rotation = Surface.ROTATION_90; 
                } else if (orientation > 135 && orientation <= 225) {
                    rotation = Surface.ROTATION_180; 
                } else {
                    rotation = Surface.ROTATION_270;
                }
    
                // Only update when rotation changes significantly
                if (Math.abs(rotation - currentOrientation) >= 90) {
                    currentOrientation = rotation;
                    
                    // Update camera rotation
                    if (imageCapture != null) {
                        imageCapture.setTargetRotation(getCameraRotation());
                    } else {
                        Log.w(TAG, "Cannot update rotation - imageCapture is null");
                }
                }
            } catch (Exception e) {
                    Log.e(TAG, "Error in orientation listener: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        };

    // Start the orientation listener if it can be enabled
    if (orientationListener.canDetectOrientation()) {
        orientationListener.enable();
    } else {
        Log.w(TAG, "Orientation detection not supported on this device");
    }
} catch (Exception e) {
        Log.e(TAG, "Failed to setup orientation listener: " + e.getMessage());
        e.printStackTrace();
    }
    }

    private int getCameraRotation() {
    try {
        // Convert device orientation to camera rotation value
        int displayRotation = getWindowManager().getDefaultDisplay().getRotation();
        
        switch (displayRotation) {
            case Surface.ROTATION_0: // Portrait
                return Surface.ROTATION_0;
            case Surface.ROTATION_90: // Landscape right
                return Surface.ROTATION_90;
            case Surface.ROTATION_180: // Upside down portrait
                return Surface.ROTATION_180;
            case Surface.ROTATION_270: // Landscape left
                return Surface.ROTATION_270;
            default:
                Log.w(TAG, "Unknown display rotation: " + displayRotation + ", defaulting to 0");
                return Surface.ROTATION_0;
        }
} catch (Exception e) {
        Log.e(TAG, "Error getting camera rotation: " + e.getMessage());
        e.printStackTrace();
        return Surface.ROTATION_0;
    }
}

@Override
public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    if (hasFocus) {
        updateNavigationBarPadding(getResources().getConfiguration().orientation);
    }
}

@Override
public void onConfigurationChanged(Configuration newConfig) {
    super.onConfigurationChanged(newConfig);

    try {
        // Save important state before changing layouts
        boolean isCameraRunning = camera != null;
        int currentCameraFacing = cameraFacing;
        boolean currentUsingUltraWide = usingUltraWideCamera;
        boolean currentPreviewMode = isInPreviewMode;

        if (loadImageTask != null && !loadImageTask.isCancelled()) {
            loadImageTask.cancel(true);
            loadImageTask = null;
        }

        if (imagePreview != null) {
            imagePreview.setImageDrawable(null);
        }
        
        // Manually apply the appropriate layout
        setContentView(getResources().getIdentifier("camerax_activity", "layout", getPackageName()));
        
        // Reinitialize all view references
        initializeViews();
        
        // Restore camera state
        if (currentPreviewMode && currentPreviewBitmap != null && !currentPreviewBitmap.isRecycled()) {
            // If we were in preview mode and have a valid bitmap, restore it
            showPreviewMode();
            // Directly set the existing bitmap instead of reloading
            imagePreview.setImageBitmap(currentPreviewBitmap);
        } else if (currentPreviewMode && tempImageFile != null && tempImageFile.exists()) {
            // If bitmap was lost but file exists, reload it
            showPreviewMode();
        } else if (isCameraRunning) {
            // If camera was running, restart it
            cameraFacing = currentCameraFacing;
            usingUltraWideCamera = currentUsingUltraWide;
            startCamera();
        }
        
        // Update padding for system UI
        updateNavigationBarPadding(newConfig.orientation);
        
        // Update rotation for image capture
        if (camera != null && imageCapture != null) {
            imageCapture.setTargetRotation(getCameraRotation());
        }
    } catch (Exception e) {
        Log.e(TAG, "Error in onConfigurationChanged: " + e.getMessage());
        e.printStackTrace();
    }
}

// Helper method to update padding for navigation bars
private void updateNavigationBarPadding(int orientation) {
    // Get navigation bar height
    int navBarHeightId = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
    int navBarHeight = 0;
    if (navBarHeightId > 0) {
        navBarHeight = getResources().getDimensionPixelSize(navBarHeightId);
    }
    
    if (bottomControls!= null){
        if (!originalPaddingSaved && isInitialSetup) {
            originalLeftPadding = bottomControls.getPaddingLeft();
            originalTopPadding = bottomControls.getPaddingTop();
            originalRightPadding = bottomControls.getPaddingRight();
            originalBottomPadding = bottomControls.getPaddingBottom();
            originalPaddingSaved = true;
            isInitialSetup = false;
        }
        
        // Apply appropriate padding based on orientation
        if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            bottomControls.setPadding(
                originalLeftPadding,
                originalTopPadding,
                originalRightPadding,
                navBarHeight + 16);
        } else {
            bottomControls.setPadding(
                originalLeftPadding,
                originalTopPadding,
                navBarHeight + 16,
                originalBottomPadding + 5);
        }
    }
    
    //update the preview screen as well
    if (previewControls != null) {
        if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            previewControls.setPadding(
                previewControls.getPaddingLeft(),
                previewControls.getPaddingTop(),
                previewControls.getPaddingRight(),
                navBarHeight + 16);
        } else {
            previewControls.setPadding(
                previewControls.getPaddingLeft(),
                previewControls.getPaddingTop(),
                navBarHeight + 16,
                previewControls.getPaddingBottom());
        }
    }
}

// exposure methods
private void showExposureControls(float x, float y) {
    if (exposureControlContainer == null) return;
    
    // Make sure the container is visible
    exposureControlContainer.setVisibility(View.VISIBLE);
    
    // Wait for the view to be measured so we can calculate its position correctly
    exposureControlContainer.post(() -> {
        // Get container dimensions
        int containerWidth = exposureControlContainer.getWidth();
        int containerHeight = exposureControlContainer.getHeight();
        
        // Get screen dimensions
        int screenWidth = previewView.getWidth();
        int screenHeight = previewView.getHeight();
        
        // Calculate position, keeping the control fully on screen
        int newX = (int) Math.max(0, Math.min(x - containerWidth / 2, screenWidth - containerWidth));
        // Position slightly above the tap point so it's not covered by the finger
        int newY = (int) Math.max(0, Math.min(y - containerHeight - 40, screenHeight - containerHeight));
        
        // Create layout parameters
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        
        // Set position
        params.leftMargin = newX;
        params.topMargin = newY;
        
        // Apply parameters
        exposureControlContainer.setLayoutParams(params);
    });
    
    // Update the seekbar based on camera's current exposure
    updateExposureSeekbarValue();
    
    // Cancel any pending hide operations
    exposureHideHandler.removeCallbacks(hideExposureControlsRunnable);
    
    // Auto-hide after a delay
    exposureHideHandler.postDelayed(hideExposureControlsRunnable, 3000);
}

// Helper method to update the seekbar value
private void updateExposureSeekbarValue() {
    if (camera != null && exposureSeekBar != null) {
        CameraInfo cameraInfo = camera.getCameraInfo();
        ExposureState exposureState = cameraInfo.getExposureState();
        
        if (exposureState.isExposureCompensationSupported()) {
            // Update min/max exposure ranges
            minExposure = exposureState.getExposureCompensationRange().getLower();
            maxExposure = exposureState.getExposureCompensationRange().getUpper();
            
            // Get current exposure value
            int currentExposureCompensation = exposureState.getExposureCompensationIndex();
            
            // Calculate progress value (0-100)
            float exposureRange = maxExposure - minExposure;
            float progressValue = ((currentExposureCompensation - minExposure) / exposureRange) * 100;
            
            // Set seekbar position
            exposureSeekBar.setProgress((int)progressValue);
        }
    }
}

// Method to hide exposure controls
private void hideExposureControls() {
    if (exposureControlContainer != null) {
        exposureControlContainer.setVisibility(View.GONE);
    }
}

// New helper method to initialize all view references
private void initializeViews() {
    // Find all UI elements by resource ID
    previewView = findViewById(getResources().getIdentifier("preview_view", "id", getPackageName()));
    imagePreview = findViewById(getResources().getIdentifier("image_preview", "id", getPackageName()));
    captureButton = findViewById(getResources().getIdentifier("capture_button", "id", getPackageName()));
    cameraFlipButton = findViewById(getResources().getIdentifier("camera_flip_button", "id", getPackageName()));
    flashButton = findViewById(getResources().getIdentifier("flash_button", "id", getPackageName()));
    flashModesBar = findViewById(getResources().getIdentifier("flash_modes_bar", "id", getPackageName()));
    flashAutoButton = findViewById(getResources().getIdentifier("flash_auto_button", "id", getPackageName()));
    flashOnButton = findViewById(getResources().getIdentifier("flash_on_button", "id", getPackageName()));
    flashOffButton = findViewById(getResources().getIdentifier("flash_off_button", "id", getPackageName()));
    zoomLevelText = findViewById(getResources().getIdentifier("zoom_level_text", "id", getPackageName()));
    zoomSeekBar = findViewById(getResources().getIdentifier("zoom_seekbar", "id", getPackageName()));
    wideAngleButton = findViewById(getResources().getIdentifier("wide_angle_button", "id", getPackageName()));
    normalZoomButton = findViewById(getResources().getIdentifier("normal_zoom_button", "id", getPackageName()));
    zoomButtonsContainer = findViewById(getResources().getIdentifier("zoom_buttons_container", "id", getPackageName()));
    exposureControlContainer = findViewById(getResources().getIdentifier("exposure_control_container", "id", getPackageName()));
    exposureSeekBar = findViewById(getResources().getIdentifier("exposure_seekbar", "id", getPackageName()));

    // Preview mode UI elements
    
    bottomControls = findViewById(getResources().getIdentifier("bottom_controls", "id", getPackageName()));
    previewControls = findViewById(getResources().getIdentifier("preview_controls", "id", getPackageName()));
    actionBarBackground = findViewById(getResources().getIdentifier("action_bar_background", "id", getPackageName()));
    retakeButton = findViewById(getResources().getIdentifier("retake_button", "id", getPackageName()));
    usePhotoButton = findViewById(getResources().getIdentifier("use_photo_button", "id", getPackageName()));

    // exposure logic

    if (exposureSeekBar != null) {
        exposureSeekBar.setMax(100);
        exposureSeekBar.setProgress(50);

        exposureSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            if (fromUser && camera != null) {
                isUserControllingExposure = true;
                
                // Convert progress (0-100) to exposure compensation value (minExposure to maxExposure)
                float exposureRange = maxExposure - minExposure;
                float exposureValue = minExposure + (progress / 100f) * exposureRange;
                currentExposureValue = exposureValue;
                
                // Apply exposure to camera
                camera.getCameraControl().setExposureCompensationIndex((int)exposureValue);
            }
        }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                isUserControllingExposure = true;
                // Cancel auto-hide when user starts interacting
                exposureHideHandler.removeCallbacks(hideExposureControlsRunnable);
            }
    
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // Schedule auto-hide after user stops interacting
                exposureHideHandler.postDelayed(hideExposureControlsRunnable, 2000);
            }
        });
        }

// Initialize the auto-hide runnable
if (hideExposureControlsRunnable == null) {
    hideExposureControlsRunnable = () -> {
        if (exposureControlContainer != null) {
            exposureControlContainer.setVisibility(View.GONE);
        }
        isUserControllingExposure = false;
    };
}
    
    if (zoomLevelText != null) {
        zoomLevelText.setVisibility(View.GONE);
    }

    // Configure zoom seekbar
    if (zoomSeekBar != null) {
        zoomSeekBar.setMax(100);
        zoomSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && camera != null) {
                    isUserControllingZoom = true;
                    
                    CameraInfo cameraInfo = camera.getCameraInfo();
                    ZoomState zoomState = cameraInfo.getZoomState().getValue();
                    
                    if (zoomState == null) return;
                    
                    float minZoom = Math.max(0.5f, zoomState.getMinZoomRatio());
                    float maxZoom = zoomState.getMaxZoomRatio();
                    float zoomRange = maxZoom - minZoom;
                    float zoomRatio = minZoom + (progress / 100f) * zoomRange;

                    if (!usingUltraWideCamera && progress == 0) {
                        // User has reached the minimum and is likely trying to go further
                        hasReachedMinimum = true;
            
                        // Only switch after a short delay of being at minimum, to avoid accidental switches
                    handler.postDelayed(() -> {
                        // If still at minimum after the delay, switch cameras
                        if (hasReachedMinimum && !usingUltraWideCamera && hasUltraWideCamera) {
                            usingUltraWideCamera = true;
                            updateZoomButtonsState();
                            startCamera();
                        }
                    }, 200); // Short delay to confirm intent
                    } else {
                        // No longer at minimum
                        hasReachedMinimum = false;
                        handler.removeCallbacksAndMessages(null); // Cancel any pending switch
                    }
                    
                    // Apply zoom to camera
                    camera.getCameraControl().setZoomRatio(zoomRatio);
                    updateZoomLevelDisplay(zoomRatio);
                    // switch cameras?

                    if (usingUltraWideCamera && zoomRatio > 1.1f) {
                        usingUltraWideCamera = false;
                        updateZoomButtonsState();
                        startCamera();
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                isUserControllingZoom = true;
                // Cancel auto-hide when user starts interacting
                handler.removeCallbacks(hideZoomControlsRunnable);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // Schedule auto-hide after user stops interacting
                handler.postDelayed(hideZoomControlsRunnable, 2000);
            }
        });
    }
    
    // Initialize or reinitialize the runnables if needed
    
    if (hideZoomControlsRunnable == null) {
        hideZoomControlsRunnable = () -> {
            if (zoomLevelText != null) {
                zoomLevelText.setVisibility(View.GONE);
            }
            if (zoomSeekBar != null) {
                zoomSeekBar.setVisibility(View.GONE);
            }
            isUserControllingZoom = false;
        };
    }
    
    // Set up click listeners for all buttons
    if (captureButton != null) captureButton.setOnClickListener(this);
    if (cameraFlipButton != null) cameraFlipButton.setOnClickListener(this);
    if (flashButton != null) flashButton.setOnClickListener(this);
    if (flashAutoButton != null) flashAutoButton.setOnClickListener(this);
    if (flashOnButton != null) flashOnButton.setOnClickListener(this);
    if (flashOffButton != null) flashOffButton.setOnClickListener(this);
    if (wideAngleButton != null) wideAngleButton.setOnClickListener(this);
    if (normalZoomButton != null) normalZoomButton.setOnClickListener(this);
    if (retakeButton != null) retakeButton.setOnClickListener(this);
    if (usePhotoButton != null) usePhotoButton.setOnClickListener(this);
    
    // Set up pinch gesture detector if it's not already initialized
    if (scaleGestureDetector == null) {
        scaleGestureDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            private float lastZoomRatio = 1.0f;
            
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (isInPreviewMode) {
                    return false;
                }
                if (camera == null) {
                    return false;
                }
                
                showZoomControls();
                
                CameraControl cameraControl = camera.getCameraControl();
                CameraInfo cameraInfo = camera.getCameraInfo();
                ZoomState zoomState = cameraInfo.getZoomState().getValue();
                if (zoomState == null) return false;
                
                // Get current actual zoom ratio and limits from camera
                float currentZoomRatio = zoomState.getZoomRatio();
                float minZoom = Math.max(0.5f, zoomState.getMinZoomRatio());
                float maxZoom = zoomState.getMaxZoomRatio();
                
                // Calculate new zoom based on pinch scale factor
                float scaleFactor = detector.getScaleFactor();
                boolean isZoomingOut = scaleFactor < 1.0f;

                if (!usingUltraWideCamera && hasUltraWideCamera && isZoomingOut) {
                    if (Math.abs(currentZoomRatio - minZoom) < 0.05f) { // Very close to minimum
                        // We're at minimum zoom and still trying to zoom out
                        long currentTime = System.currentTimeMillis();
                        
                        if (timeAtMinZoom == 0) {
                            // Just reached minimum
                            timeAtMinZoom = currentTime;
                        } else if (currentTime - timeAtMinZoom > MIN_TIME_AT_MIN_ZOOM) {
                            // User has been trying to zoom out at minimum for the threshold time
                            usingUltraWideCamera = true;
                            timeAtMinZoom = 0;
                            updateZoomButtonsState();
                            startCamera();
                            return true;
                        }
                    } else {
                        // Not at minimum zoom
                        timeAtMinZoom = 0;
                    }
                }
                
                float newZoomRatio = lastZoomRatio * scaleFactor;
                newZoomRatio = Math.max(minZoom, Math.min(newZoomRatio, maxZoom));
                
                // Save for next frame
                lastZoomRatio = newZoomRatio;
                
                updateZoomLevelDisplay(newZoomRatio);
                
                if (zoomSeekBar != null) {
                    zoomSeekBar.setVisibility(View.VISIBLE);
                    
                    // Calculate and set slider position based on the zoom ratio
                    float zoomProgress = ((newZoomRatio - minZoom) / (maxZoom - minZoom)) * 100;
                    zoomSeekBar.setProgress((int)zoomProgress);
                }
                
                cameraControl.setZoomRatio(newZoomRatio);
                 if (usingUltraWideCamera && newZoomRatio > 1.1f) {
                    usingUltraWideCamera = false;
                    updateZoomButtonsState();
                    startCamera();
                }
                return true;
            }
            
            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
                if (isInPreviewMode) {
                    return false;
                }
                if (camera != null) {
                    ZoomState zoomState = camera.getCameraInfo().getZoomState().getValue();
                    if (zoomState != null) {
                        // Initialize with current zoom
                        lastZoomRatio = zoomState.getZoomRatio();
                    }
                }
                
                // Show zoom controls
                if (zoomLevelText != null) {
                    zoomLevelText.setVisibility(View.VISIBLE);
                }
                if (zoomSeekBar != null) {
                    zoomSeekBar.setVisibility(View.VISIBLE);
                }
                
                // Remove any pending hide callbacks
                handler.removeCallbacks(hideZoomControlsRunnable);
                return true;
            }
            
            @Override
            public void onScaleEnd(ScaleGestureDetector detector) {
                // Hide zoom controls after a delay
                lastScaleEndTime = System.currentTimeMillis();
                handler.postDelayed(hideZoomControlsRunnable, 2000);
                if (exposureControlContainer != null && exposureControlContainer.getVisibility() == View.VISIBLE) {
                        hideExposureControls();
                }
            }
        });
    }
    
   // set up touch listener for zoom and exposure
if (previewView != null) {
    previewView.setOnTouchListener((view, event) -> {

        if (isInPreviewMode) {
            return false;
        }
        // Handle scale gestures for zoom
        boolean handled = scaleGestureDetector.onTouchEvent(event);
        
        // Handle tap for focus and exposure (only if not zooming)
        if (event.getAction() == MotionEvent.ACTION_UP && 
            camera != null && !scaleGestureDetector.isInProgress()) {
            
            // Only handle simple taps
            if (event.getPointerCount() <= 1 && System.currentTimeMillis() - lastScaleEndTime > SCALE_COOLDOWN_PERIOD) {
                // Get the tap coordinates
                float x = event.getX();
                float y = event.getY();
                
                // Create a metering point where the user tapped
                MeteringPointFactory factory = previewView.getMeteringPointFactory();
                MeteringPoint point = factory.createPoint(x, y);
                
                // Build focus and metering actions
                FocusMeteringAction focusMeteringAction = new FocusMeteringAction.Builder(point)
                    .addPoint(point, FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(3, TimeUnit.SECONDS)
                    .build();
                
                // Start focus and metering
                camera.getCameraControl().startFocusAndMetering(focusMeteringAction)
                    .addListener(() -> {
                        // Show exposure control after focusing
                        runOnUiThread(() -> {
                            showExposureControls(x, y);
                        });
                    }, ContextCompat.getMainExecutor(CameraXActivity.this));
                
                return true;
            }
        }
        return handled;
    });
}
    
    // Update flash mode button icon
    if (flashButton != null) {
        setFlashButtonIcon(flashMode);
    }
    
    // Update zoom button states if needed
    updateZoomButtonsState();
}

    // Wide Lens Camera Methods
    @ExperimentalCamera2Interop
    private void switchToWideAngleCamera() {
        if (!hasUltraWideCamera || cameraFacing != CameraSelector.LENS_FACING_BACK) {
            // Wide angle not available or front camera is active
            Toast.makeText(this, "Wide angle camera not available", Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (!usingUltraWideCamera) {
            usingUltraWideCamera = true;
            
            // Disable flash for ultra-wide camera
            setFlashMode(ImageCapture.FLASH_MODE_OFF);
            
            updateZoomButtonsState();
            startCamera();
        }
    }
    
    private void switchToNormalCamera() {
        if (usingUltraWideCamera) {
            usingUltraWideCamera = false;
            updateZoomButtonsState();
            startCamera(); 
        }
    }
    
    private void updateZoomButtonsState() {
        if (usingUltraWideCamera) {
            wideAngleButton.setBackground(getDrawable(getResources().getIdentifier("circular_button_selected", "drawable", getPackageName())));
            wideAngleButton.setTextColor(getResources().getColor(android.R.color.black));
            normalZoomButton.setBackground(getDrawable(getResources().getIdentifier("circular_button", "drawable", getPackageName())));
            normalZoomButton.setTextColor(getResources().getColor(android.R.color.white));
            
            // Disable flash controls for ultra-wide camera
            flashButton.setAlpha(0.5f);
            flashButton.setEnabled(false);
        } else {
            normalZoomButton.setBackground(getDrawable(getResources().getIdentifier("circular_button_selected", "drawable", getPackageName())));
            normalZoomButton.setTextColor(getResources().getColor(android.R.color.black));
            wideAngleButton.setBackground(getDrawable(getResources().getIdentifier("circular_button", "drawable", getPackageName())));
            wideAngleButton.setTextColor(getResources().getColor(android.R.color.white));
            
            // Re-enable flash controls for normal camera
            flashButton.setAlpha(1.0f);
            flashButton.setEnabled(true);
        }
    }
    @ExperimentalCamera2Interop
    private void detectUltraWideCamera(ProcessCameraProvider cameraProvider) {
        try {
            hasUltraWideCamera = false;
            for (CameraInfo cameraInfo : cameraProvider.getAvailableCameraInfos()) {
                Camera2CameraInfo camera2CameraInfo = Camera2CameraInfo.from(cameraInfo);
                int lensFacing = camera2CameraInfo.getCameraCharacteristic(
                        CameraCharacteristics.LENS_FACING);
                
                if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                    float[] focalLengths = camera2CameraInfo.getCameraCharacteristic(
                            CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                    
                    if (focalLengths != null && focalLengths.length > 0) {
                        // Ultra-wide lenses typically have shorter focal lengths
                        if (focalLengths[0] < 2.0f) { // Approximate threshold
                            hasUltraWideCamera = true;
                            break;
                        }
                    }
                }
            }
            
            // Update wide angle button visibility
            wideAngleButton.setVisibility(hasUltraWideCamera ? View.VISIBLE : View.GONE);
            
        } catch (Exception e) {
            Log.e(TAG, "Error detecting camera types: " + e.getMessage());
        }
    }
    
    @ExperimentalCamera2Interop
    private CameraSelector createUltraWideCameraSelector() {
        return new CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .addCameraFilter(cameraInfos -> {
                // Filter to find cameras with shortest focal length (ultra-wide)
                List<CameraInfo> backCameras = new ArrayList<>();
                CameraInfo selectedCamera = null;
                float shortestFocalLength = Float.MAX_VALUE;
                
                for (CameraInfo info : cameraInfos) {
                    try {
                        Camera2CameraInfo camera2Info = Camera2CameraInfo.from(info);
                        int lensFacing = camera2Info.getCameraCharacteristic(
                                CameraCharacteristics.LENS_FACING);
                        
                        if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                            backCameras.add(info);
                            
                            float[] focalLengths = camera2Info.getCameraCharacteristic(
                                    CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                            
                            if (focalLengths != null && focalLengths.length > 0 && 
                                    focalLengths[0] < shortestFocalLength) {
                                shortestFocalLength = focalLengths[0];
                                selectedCamera = info;
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error examining camera: " + e.getMessage());
                    }
                }
                
                if (selectedCamera != null) {
                    return Collections.singletonList(selectedCamera);
                }
                
                return backCameras;
            })
            .build();
    }

    @ExperimentalCamera2Interop
private void startCamera() {
    ListenableFuture<ProcessCameraProvider> cameraProviderFuture = 
            ProcessCameraProvider.getInstance(this);
    
    cameraProviderFuture.addListener(() -> {
        try {
            // Camera provider is now guaranteed to be available
            ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

            // Create resolution selector
            ResolutionSelector resolutionSelector = new ResolutionSelector.Builder().build();

            // Check for ultra-wide camera on back camera only
            if (cameraFacing == CameraSelector.LENS_FACING_BACK) {
                detectUltraWideCamera(cameraProvider);
            } else {
                // No wide angle for front camera
                hasUltraWideCamera = false;
                usingUltraWideCamera = false;
            }
            
            // Update UI button states
            updateZoomButtonsState();

            // Set up the preview use case
            Preview preview = new Preview.Builder()
                .setResolutionSelector(resolutionSelector)
                .build();

            previewView.setScaleType(PreviewView.ScaleType.FIT_CENTER);
            preview.setSurfaceProvider(previewView.getSurfaceProvider());
            
            // Set up the capture use case
            imageCapture = new ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setResolutionSelector(resolutionSelector)
                    .setTargetRotation(getCameraRotation())
                    .setFlashMode(flashMode)
                    .build();
            
            // Select appropriate camera - ultra-wide or standard
            CameraSelector cameraSelector;
            if (usingUltraWideCamera && hasUltraWideCamera && cameraFacing == CameraSelector.LENS_FACING_BACK) {
                cameraSelector = createUltraWideCameraSelector();
            } else {
                cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(cameraFacing)
                        .build();
            }
            
            // Unbind any bound use cases before rebinding
            cameraProvider.unbindAll();
            
            // Bind use cases to camera
            camera = cameraProvider.bindToLifecycle(
                    this, // LifecycleOwner
                    cameraSelector,
                    preview,
                    imageCapture);
            
            // Update camera zoom state when switching cameras
            if (camera != null) {

                //reset and hide exposure controls
                camera.getCameraControl().setExposureCompensationIndex(0);
                currentExposureValue = 0f;
                hideExposureControls();
                
                // Reset zoom to appropriate initial value based on camera
                currentZoomRatio = usingUltraWideCamera ? 0.5f : 1.0f;
                
                // Reset UI to match
                zoomLevelText.setText(usingUltraWideCamera ? "0.5x" : "1.0x");
                zoomSeekBar.setProgress(0);
                
                // Force the camera to reset zoom
                camera.getCameraControl().setZoomRatio(currentZoomRatio);

                // Show zoom UI briefly if not initial startup
                if (!isInitialCameraStart) {
                    // Show briefly then hide
                    zoomLevelText.setVisibility(View.VISIBLE);
                    zoomSeekBar.setVisibility(View.VISIBLE);
                    
                    // Hide after delay unless user is interacting
                    handler.postDelayed(() -> {
                        if (!isUserControllingZoom) {
                            zoomLevelText.setVisibility(View.GONE);
                            zoomSeekBar.setVisibility(View.GONE);
                        }
                    }, 2000);
                } else {
                    // For initial camera start, keep UI hidden
                    zoomLevelText.setVisibility(View.GONE);
                    zoomSeekBar.setVisibility(View.GONE);
                    isInitialCameraStart = false;
                }
                
                // Set up observer for camera zoom changes
                camera.getCameraInfo().getZoomState().observe(this, zoomState -> {
                    if (zoomState != null && !isUserControllingZoom && zoomState.getZoomRatio() != currentZoomRatio) {
                        // Get zoom limits
                        float minZoom = Math.max(0.5f, zoomState.getMinZoomRatio());
                        float maxZoom = zoomState.getMaxZoomRatio();
                        float zoomRatio = zoomState.getZoomRatio();
                        
                        // Update current zoom tracking variable
                        currentZoomRatio = zoomRatio;
                        
                        // Calculate and set slider position
                        float zoomProgress = ((zoomRatio - minZoom) / (maxZoom - minZoom)) * 100;
                        zoomSeekBar.setProgress((int)zoomProgress);
                        
                        // Update text display
                        updateZoomLevelDisplay(zoomRatio);
                    }
                });
            }
        } catch (ExecutionException | InterruptedException e) {
            Log.e(TAG, "Error starting camera: " + e.getMessage());
        }
    }, ContextCompat.getMainExecutor(this));
}
    
     private void takePhoto() {
        if (imageCapture == null) {
            Log.e(TAG, "imageCapture is null");
            return;
        }
        
        if (capturedImageUri == null) {
            Log.e(TAG, "No output URI provided");
            setResult(Activity.RESULT_CANCELED);
            finish();
            return;
        }
        
        try {
            // Create a temporary file to save the image first
            tempImageFile = File.createTempFile("temp_capture", ".jpg", getCacheDir());
            
            // Create output options with the temporary file
            ImageCapture.OutputFileOptions outputOptions = new ImageCapture.OutputFileOptions.Builder(tempImageFile).build();
        
                // Take the picture
                imageCapture.takePicture(
                    outputOptions,
                    executor,
                    new ImageCapture.OnImageSavedCallback() {
                        @Override
                        public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                            // Show preview mode instead of immediately returning
                            runOnUiThread(() -> {
                                showPreviewMode();
                            });
                        }
                    
                        @Override
                        public void onError(@NonNull ImageCaptureException exception) {
                            Log.e(TAG, "Photo capture failed: " + exception.getMessage());
                            Intent resultIntent = new Intent();
                            resultIntent.putExtra("error", exception.getMessage());
                            setResult(Activity.RESULT_CANCELED, resultIntent);
                            finish();
                        }
                    }
            );
        } catch (Exception e) {
            Log.e(TAG, "Error setting up image capture: " + e.getMessage());
            setResult(Activity.RESULT_CANCELED);
            finish();
        }
    }  
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == 1 && resultCode == Activity.RESULT_OK && data != null) {
            // Get edited image result and return it
            String editedImageUri = data.getStringExtra("editedImageUri");
            Intent resultIntent = new Intent();
            resultIntent.putExtra("imageUri", editedImageUri);
            setResult(Activity.RESULT_OK, resultIntent);
            finish();
        } else if (resultCode == Activity.RESULT_CANCELED) {
            setResult(Activity.RESULT_CANCELED);
            finish();
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, 
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera();
            } else {
                Toast.makeText(this, "Permissions not granted.", Toast.LENGTH_SHORT).show();
                setResult(Activity.RESULT_CANCELED);
                finish();
            }
        }
    }
    
    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            boolean granted = ContextCompat.checkSelfPermission(this, permission) 
                == PackageManager.PERMISSION_GRANTED;
            Log.d(TAG, "Permission " + permission + " granted: " + granted);
            if (!granted) {
                return false;
            }
    }
    return true;
}
    @Override
    protected void onPause() {
        super.onPause();
        if (loadImageTask != null && !loadImageTask.isCancelled()) {
            loadImageTask.cancel(true);
            loadImageTask = null;
        }
         if (imagePreview != null && !isChangingConfigurations()) {
        imagePreview.setImageDrawable(null);
    }
    }
    
    
    @Override
    public void onBackPressed() {
        if (isInPreviewMode) {
            // If in preview mode, go back to camera
            handleRetake();
        } else {
            // If in camera mode, exit
            setResult(Activity.RESULT_CANCELED);
            super.onBackPressed();
        }
    }
    
@Override
protected void onDestroy() {
    // Disable listeners first
    if (orientationListener != null) {
        orientationListener.disable();
        orientationListener = null;
    }
    
    // Cancel any running AsyncTask
    if (loadImageTask != null) {
        loadImageTask.cancel(true);
        loadImageTask = null;
    }
    
    // Clear all handler callbacks
    if (handler != null) {
        handler.removeCallbacksAndMessages(null);
    }
    if (exposureHideHandler != null) {
        exposureHideHandler.removeCallbacksAndMessages(null);
    }
     
    // Clean up camera
    if (camera != null) {
        camera = null;
    }
    
    // Clean up resources only if not changing configuration
    if (!isChangingConfigurations()) {
        cleanupPreviewBitmap();
        cleanupTempFile();
    }
    
    // Properly shutdown executor
    if (executor != null && !executor.isShutdown()) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(800, TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
    }
    
    // Clear view references
    previewView = null;
    imagePreview = null;
    scaleGestureDetector = null;
    
    super.onDestroy();
    
    System.gc();
}
}
