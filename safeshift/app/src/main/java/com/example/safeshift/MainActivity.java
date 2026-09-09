package com.example.safeshift;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.provider.CallLog;
import android.telephony.SmsManager;
import android.view.Surface;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@SuppressWarnings("deprecation")
public class MainActivity extends AppCompatActivity implements LocationListener, SensorEventListener {

    private WebView webView;
    private LocationManager locationManager;
    private double currentLat = 0.0;
    private double currentLon = 0.0;
    private static final int PERMISSION_REQ_CODE = 100;

    // Video Recording Duration (10 Seconds)
    private static final int VIDEO_RECORDING_DURATION_MS = 10000;
    private Camera mCamera;
    private MediaRecorder mMediaRecorder;
    private boolean isRecording = false;

    // Shake Detection
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private float acceleration = 0.0f;
    private float currentAcceleration = SensorManager.GRAVITY_EARTH;
    private float lastAcceleration = SensorManager.GRAVITY_EARTH;
    private int shakeCount = 0;
    private long lastShakeTimestamp = 0;
    private static final float SHAKE_THRESHOLD = 11.5f;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);

        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);

        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new WebAppInterface(this), "AndroidBridge");

        checkAndRequestPermissions();
        initializeHardwareGPS();
        initializeShakeSensor();

        webView.loadUrl("file:///android_asset/index.html");
    }

    private void checkAndRequestPermissions() {
        String[] permissions = {
                Manifest.permission.SEND_SMS,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.VIBRATE,
                Manifest.permission.READ_CALL_LOG
        };

        boolean needsRequest = false;
        for (String perm : permissions) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                needsRequest = true;
                break;
            }
        }

        if (needsRequest) {
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQ_CODE);
        }
    }

    private void initializeShakeSensor() {
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (accelerometer != null) {
                sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
            }
        }
    }

    private String getRegularCallContact() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            return null;
        }

        String frequentNumber = null;
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(
                    CallLog.Calls.CONTENT_URI,
                    new String[]{CallLog.Calls.NUMBER, CallLog.Calls.TYPE},
                    CallLog.Calls.TYPE + " = ?",
                    new String[]{String.valueOf(CallLog.Calls.OUTGOING_TYPE)},
                    CallLog.Calls.DATE + " DESC"
            );

            if (cursor != null && cursor.moveToFirst()) {
                frequentNumber = cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER));
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (cursor != null) cursor.close();
        }

        return frequentNumber;
    }

    private void initializeHardwareGPS() {
        try {
            locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (locationManager == null) return;

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {

                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this);
                }
                if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000, 0, this);
                }

                refreshBestCoordinates();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void refreshBestCoordinates() {
        if (locationManager == null) return;
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        Location best = null;
        List<String> providers = locationManager.getProviders(true);
        for (String provider : providers) {
            try {
                Location loc = locationManager.getLastKnownLocation(provider);
                if (loc != null && loc.getLatitude() != 0.0) {
                    if (best == null || loc.getTime() > best.getTime()) {
                        best = loc;
                    }
                }
            } catch (Exception ignored) {}
        }

        if (best != null) {
            currentLat = best.getLatitude();
            currentLon = best.getLongitude();
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            float x = event.values[0];
            float y = event.values[1];
            float z = event.values[2];

            lastAcceleration = currentAcceleration;
            currentAcceleration = (float) Math.sqrt((double) (x * x + y * y + z * z));
            float delta = currentAcceleration - lastAcceleration;
            acceleration = acceleration * 0.9f + delta;

            if (acceleration > SHAKE_THRESHOLD) {
                long now = System.currentTimeMillis();
                if (now - lastShakeTimestamp < 1500) {
                    shakeCount++;
                } else {
                    shakeCount = 1;
                }
                lastShakeTimestamp = now;

                if (shakeCount >= 3) {
                    shakeCount = 0;
                    handleShakeEmergency();
                }
            }
        }
    }

    private void handleShakeEmergency() {
        runOnUiThread(() -> {
            webView.evaluateJavascript(
                    "(function() { return localStorage.getItem('safeshift_contacts') || '[]'; })();",
                    value -> {
                        StringBuilder sb = new StringBuilder();
                        try {
                            String clean = value.replace("\\\"", "\"").replaceAll("^\"|\"$", "");
                            if (clean.startsWith("\"") && clean.endsWith("\"")) {
                                clean = clean.substring(1, clean.length() - 1);
                            }
                            org.json.JSONArray arr = new org.json.JSONArray(clean);
                            for (int i = 0; i < arr.length(); i++) {
                                org.json.JSONObject obj = arr.getJSONObject(i);
                                String phone = obj.getString("phone").replaceAll("[^0-9+]", "");
                                if (phone.length() >= 10) {
                                    if (sb.length() > 0) sb.append(",");
                                    sb.append(phone);
                                }
                            }
                        } catch (Exception ignored) {}

                        triggerFinalEmergency(sb.toString());
                    }
            );
        });
    }

    private void startSilentVideoRecording() {
        if (isRecording) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        new Thread(() -> {
            try {
                int cameraId = 0;
                int numberOfCameras = Camera.getNumberOfCameras();
                for (int i = 0; i < numberOfCameras; i++) {
                    Camera.CameraInfo info = new Camera.CameraInfo();
                    Camera.getCameraInfo(i, info);
                    if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                        cameraId = i;
                        break;
                    }
                }

                mCamera = Camera.open(cameraId);
                mCamera.unlock();

                mMediaRecorder = new MediaRecorder();
                mMediaRecorder.setCamera(mCamera);
                mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);

                if (CamcorderProfile.hasProfile(cameraId, CamcorderProfile.QUALITY_480P)) {
                    mMediaRecorder.setProfile(CamcorderProfile.get(cameraId, CamcorderProfile.QUALITY_480P));
                } else {
                    mMediaRecorder.setProfile(CamcorderProfile.get(cameraId, CamcorderProfile.QUALITY_LOW));
                }

                File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
                File dir = new File(picturesDir, "SafeShift_Evidence");
                if (!dir.exists()) dir.mkdirs();

                String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
                File videoFile = new File(dir, "EVIDENCE_VID_" + timestamp + ".mp4");

                mMediaRecorder.setOutputFile(videoFile.getAbsolutePath());
                mMediaRecorder.setPreviewDisplay(new Surface(new SurfaceTexture(0)));
                mMediaRecorder.prepare();
                mMediaRecorder.start();
                isRecording = true;

                runOnUiThread(() -> Toast.makeText(MainActivity.this, "🎥 Recording Background Evidence...", Toast.LENGTH_SHORT).show());

                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    try {
                        if (isRecording && mMediaRecorder != null) {
                            mMediaRecorder.stop();
                            mMediaRecorder.reset();
                            mMediaRecorder.release();
                            mMediaRecorder = null;

                            if (mCamera != null) {
                                mCamera.lock();
                                mCamera.release();
                                mCamera = null;
                            }
                            isRecording = false;
                            sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(videoFile)));
                        }
                    } catch (Exception ignored) {}
                }, VIDEO_RECORDING_DURATION_MS);

            } catch (Exception e) {
                isRecording = false;
                if (mCamera != null) {
                    try { mCamera.release(); } catch (Exception ignored) {}
                }
            }
        }).start();
    }

    private int getBatteryPercentage() {
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = registerReceiver(null, ifilter);
        if (batteryStatus != null) {
            int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            return (int) ((level / (float) scale) * 100);
        }
        return -1;
    }

    public void triggerFinalEmergency(String phoneNumbersList) {
        Set<String> set = new LinkedHashSet<>();

        // 1. Saved Contacts (Primary)
        if (phoneNumbersList != null && !phoneNumbersList.isEmpty()) {
            for (String s : phoneNumbersList.split(",")) {
                String clean = s.replaceAll("[^0-9+]", "");
                if (clean.length() >= 10) set.add(clean);
            }
        }

        // 2. Last Outgoing / Frequently Called Contact (ADD ALWAYS!)
        String reg = getRegularCallContact();
        if (reg != null) {
            String cleanReg = reg.replaceAll("[^0-9+]", "");
            if (cleanReg.length() >= 10) set.add(cleanReg);
        }

        if (set.isEmpty()) {
            Toast.makeText(this, "No Contact Found!", Toast.LENGTH_LONG).show();
            return;
        }

        executeDispatch(new ArrayList<>(set));
    }

    private void executeDispatch(List<String> orderedContacts) {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.vibrate(500);

            startSilentVideoRecording();
            refreshBestCoordinates();

            // Accurate Live Pin
            String mapLink;
            if (currentLat != 0.0 && currentLon != 0.0) {
                mapLink = "https://maps.google.com/?q=" + currentLat + "," + currentLon;
            } else {
                mapLink = "http://googleusercontent.com/maps.google.com/8"; // Ahmedabad/Bhat campus fallback
            }

            int battery = getBatteryPercentage();
            String batteryText = (battery != -1) ? " [Battery: " + battery + "%]" : "";

            String alertMessage = "EMERGENCY ALERT!" + batteryText +
                    "\nI need urgent help. My Live Location:\n" + mapLink;

            // Send SMS to BOTH Saved and Frequent Contacts
            SmsManager sms = SmsManager.getDefault();
            for (String phone : orderedContacts) {
                if (phone.length() >= 10) {
                    ArrayList<String> parts = sms.divideMessage(alertMessage);
                    sms.sendMultipartTextMessage(phone, null, parts, null, null);
                }
            }

            Toast.makeText(this, "🚨 SMS Sent to " + orderedContacts.size() + " Contacts!", Toast.LENGTH_SHORT).show();

            // Direct Call to first contact
            String primaryNumber = orderedContacts.get(0);
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    Intent callIntent = new Intent(Intent.ACTION_CALL);
                    callIntent.setData(Uri.parse("tel:" + Uri.encode(primaryNumber)));
                    callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(callIntent);
                } catch (Exception ex) {
                    Intent dialIntent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(primaryNumber)));
                    dialIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(dialIntent);
                }
            }, 1000);

        } catch (Exception e) {
            Toast.makeText(this, "Dispatch Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override
    public void onLocationChanged(@NonNull Location location) {
        if (location.getLatitude() != 0.0 && location.getLongitude() != 0.0) {
            currentLat = location.getLatitude();
            currentLon = location.getLongitude();
        }
    }

    @Override public void onProviderEnabled(@NonNull String provider) {}
    @Override public void onProviderDisabled(@NonNull String provider) {}

    @Override
    protected void onResume() {
        super.onResume();
        if (sensorManager != null && accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
        }
        initializeHardwareGPS();
    }

    public class WebAppInterface {
        Context mContext;

        WebAppInterface(Context c) {
            mContext = c;
        }

        @JavascriptInterface
        public void triggerOfflineEmergency(String phoneNumbersList) {
            runOnUiThread(() -> triggerFinalEmergency(phoneNumbersList));
        }
    }
}