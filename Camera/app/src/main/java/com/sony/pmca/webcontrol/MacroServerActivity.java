package com.sony.pmca.webcontrol;

import io.github.bi2qfa.alphasync.R;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.hardware.Camera;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.util.Pair;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.TextView;

import com.github.ma1co.pmcademo.app.BaseActivity;
import com.github.ma1co.pmcademo.app.Logger;
import com.sony.scalar.hardware.CameraEx;
import com.sony.scalar.sysutil.ScalarInput;

import org.json.JSONObject;

import java.util.Locale;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

public class MacroServerActivity extends BaseActivity implements SurfaceHolder.Callback {

    public static final long MAIN_THREAD_TIMEOUT_MS = 3000L;

    private SurfaceHolder m_surfaceHolder;
    private CameraEx m_camera;
    private CameraEx.AutoPictureReviewControl m_autoReviewControl;
    private int m_pictureReviewTime;

    private final Handler m_handler = new Handler(Looper.getMainLooper());
    private final CameraEventHub m_eventHub = new CameraEventHub();
    private final RpcDispatcher m_rpc = new RpcDispatcher();
    private final LiveViewManager m_liveViewManager = new LiveViewManager();

    private View m_topBar;
    private FocusScaleView m_focusScaleView;
    private BatteryView m_batteryView;
    private TextView m_tvCameraStatus;
    private TextView m_tvBattery;
    private TextView m_tvWifiStatus;
    private boolean m_statusBarsVisible = true;

    private WifiManager m_wifiManager;
    private BroadcastReceiver m_networkStateReceiver;
    private BroadcastReceiver m_batteryReceiver;
    private MacroHttpServer m_httpServer;
    private int m_httpPort = MacroHttpServer.DEFAULT_PORT;
    private String m_wifiIp = "0.0.0.0";

    // Cached display values for on-camera LCD
    private volatile int m_curFocus = 0;
    private volatile int m_maxFocus = 0;
    private volatile int m_aperture = 0;
    private volatile String m_shutterSpeed = "--";
    private volatile int m_iso = 0;
    private volatile double m_exposureEv;
    private volatile int m_digitalZoom = 100;
    private volatile int m_focalLength = 0;
    private boolean m_isoDialMode;
    private String m_dialMessage = "";
    private final Runnable m_clearDialStatus = new Runnable() {
        @Override public void run() {
            m_dialMessage = "";
            m_topBar.setVisibility(m_statusBarsVisible || m_isoDialMode ? View.VISIBLE : View.GONE);
            updateStatusText();
        }
    };
    private volatile boolean m_captureInProgress;
    private static final long CAPTURE_SETTLE_MS = 650L;
    private volatile long m_controlReadsAfter;
    private final Runnable m_readExposure = new Runnable() {
        @Override public void run() {
            if (m_captureInProgress) return;
            long delay = m_controlReadsAfter - android.os.SystemClock.uptimeMillis();
            if (delay > 0) {
                m_handler.postDelayed(this, delay);
            } else {
                publishControlSettings();
            }
        }
    };

    public CameraEventHub getEventHub() {
        return m_eventHub;
    }

    public RpcDispatcher getRpcDispatcher() {
        return m_rpc;
    }

    public CameraEx getCameraEx() {
        return m_camera;
    }

    public LiveViewManager getLiveViewManager() {
        return m_liveViewManager;
    }

    private final CameraGoalController m_goalController = new CameraGoalController(this);

    public CameraGoalController getGoalController() {
        return m_goalController;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // AlphaSync retains its own process-wide error handling.
        setContentView(R.layout.activity_macro_server);

        SurfaceView surfaceView = (SurfaceView) findViewById(R.id.surfaceView);
        m_surfaceHolder = surfaceView.getHolder();
        m_surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

        m_topBar = findViewById(R.id.topBar);
        m_focusScaleView = (FocusScaleView) findViewById(R.id.vFocusScale);
        m_batteryView = (BatteryView) findViewById(R.id.vBattery);
        m_tvCameraStatus = (TextView) findViewById(R.id.tvCameraStatus);
        m_tvBattery = (TextView) findViewById(R.id.tvBattery);
        m_tvWifiStatus = (TextView) findViewById(R.id.tvWifiStatus);

        m_wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        m_networkStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                updateWifiStatus();
            }
        };
        m_batteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                updateBatteryView(intent);
            }
        };
    }

    @Override
    protected void onResume() {
        super.onResume();
        setAutoPowerOffMode(false);

        registerReceiver(m_networkStateReceiver, new IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION));
        Intent stickyBattery = registerReceiver(m_batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (stickyBattery != null) {
            updateBatteryView(stickyBattery);
        }
        // AlphaSync has already established client/hotspot mode; do not reset its radio.

        startHttpServerWithFallback();

        try {
            m_camera = CameraEx.open(0, null);
            m_surfaceHolder.addCallback(this);
            m_autoReviewControl = new CameraEx.AutoPictureReviewControl();
            m_camera.setAutoPictureReviewControl(m_autoReviewControl);

            registerCameraListeners();
            initCameraDefaults();
            refreshInitialState();
            publishControlSettings();
            updateStatusText();
        } catch (Throwable t) {
            Logger.error("onResume camera init error: " + t.toString());
            m_tvCameraStatus.setText("Init error: " + t.getMessage());
        }
    }

    private void registerCameraListeners() {
        m_camera.setShutterListener(new CameraEx.ShutterListener() {
            @Override
            public void onShutter(int status, CameraEx cameraEx) {
                try {
                    if (cameraEx == m_camera) cameraEx.cancelTakePicture();
                } catch (Throwable ignored) {
                }
                m_eventHub.setState("lastShutterStatus", status);
                JSONObject data = new JSONObject();
                try {
                    data.put("status", status);
                } catch (Throwable ignored) {
                }
                m_eventHub.emit("Shutter", data);
                updateStatusText();
            }
        });

        try {
            m_camera.setCaptureStatusListener(new CameraEx.OnCaptureStatusListener() {
                @Override
                public void onStart(int reason, CameraEx cameraEx) {
                    if (cameraEx != m_camera) return;
                    m_captureInProgress = true;
                    m_eventHub.setState("capturing", true);
                    JSONObject data = new JSONObject();
                    try {
                        data.put("capturing", true);
                        data.put("reason", reason);
                    } catch (Throwable ignored) {
                    }
                    m_eventHub.emit("CaptureStart", data);
                }

                @Override
                public void onEnd(int reason, int arg1, CameraEx cameraEx) {
                    if (cameraEx != m_camera) return;
                    deferControlReadAfterCapture();
                    m_captureInProgress = false;
                    m_eventHub.setState("capturing", false);
                    JSONObject data = new JSONObject();
                    try {
                        data.put("capturing", false);
                        data.put("reason", reason);
                    } catch (Throwable ignored) {
                    }
                    m_eventHub.emit("CaptureEnd", data);
                }
            });
        } catch (Throwable ignored) {
        }

        // Store completion supplies metadata without asking the firmware to deliver a full JPEG.
        try {
            m_camera.setStoreImageCompleteListener(new CameraEx.StoreImageCompleteListener() {
                @Override
                public void onDone(int status, CameraEx.StoreImageInfo info, CameraEx cameraEx) {
                    if (cameraEx != m_camera) return;
                    deferControlReadAfterCapture();
                    JSONObject data = new JSONObject();
                    try {
                        data.put("status", status);
                        if (info != null) {
                            data.put("DirectoryName", info.DirectoryName);
                            data.put("FileName", info.FileName);
                            data.put("DirectoryNo", info.DirectoryNo);
                            data.put("FileNo", info.FileNo);
                            data.put("MediaId", info.MediaId);
                        }
                    } catch (Throwable ignored) {
                    }
                    m_eventHub.setState("lastStoreImage", data);
                    m_eventHub.emit("StoreImageComplete", data);
                }
            });
        } catch (Throwable ignored) {
        }

        m_camera.setFocusDriveListener(new CameraEx.FocusDriveListener() {
            @Override
            public void onChanged(CameraEx.FocusPosition focusPosition, CameraEx cameraEx) {
                if (focusPosition == null) return;
                m_maxFocus = focusPosition.maxPosition;
                m_curFocus = focusPosition.currentPosition;
                m_focusScaleView.setMaxPosition(m_maxFocus);
                m_focusScaleView.setCurPosition(m_curFocus);

                JSONObject data = new JSONObject();
                try {
                    data.put("currentPosition", m_curFocus);
                    data.put("maxPosition", m_maxFocus);
                } catch (Throwable ignored) {
                }
                m_eventHub.setState("focus", data);
                m_eventHub.emit("FocusDrive", data);
                m_goalController.onFocusChanged(m_curFocus, m_maxFocus);
                updateStatusText();
            }
        });

        try {
            m_camera.setAutoFocusStartListener(new CameraEx.AutoFocusStartListener() {
                @Override
                public void onStart(CameraEx cameraEx) {
                    m_eventHub.emit("AutoFocusStart", new JSONObject());
                }
            });
            m_camera.setAutoFocusDoneListener(new CameraEx.AutoFocusDoneListener() {
                @Override
                public void onDone(int status, int[] focusAreaIds, CameraEx cameraEx) {
                    JSONObject data = new JSONObject();
                    try {
                        data.put("status", status);
                        data.put("focusAreaIds", RpcDispatcher.toJsonValue(focusAreaIds));
                    } catch (Throwable ignored) {
                    }
                    m_eventHub.emit("AutoFocusDone", data);
                }
            });
        } catch (Throwable ignored) {
        }

        m_camera.setApertureChangeListener(new CameraEx.ApertureChangeListener() {
            @Override
            public void onApertureChange(CameraEx.ApertureInfo info, CameraEx cameraEx) {
                if (info == null) return;
                m_aperture = info.currentAperture;
                JSONObject data = new JSONObject();
                try {
                    data.put("currentAperture", info.currentAperture);
                    data.put("currentAvailableMin", info.currentAvailableMin);
                    data.put("currentAvailableMax", info.currentAvailableMax);
                } catch (Throwable ignored) {
                }
                m_eventHub.setState("aperture", data);
                m_eventHub.emit("ApertureChange", data);
                m_goalController.onApertureChanged(info.currentAperture, info.currentAvailableMin, info.currentAvailableMax);
                updateStatusText();
            }
        });

        m_camera.setShutterSpeedChangeListener(new CameraEx.ShutterSpeedChangeListener() {
            @Override
            public void onShutterSpeedChange(CameraEx.ShutterSpeedInfo info, CameraEx cameraEx) {
                if (info == null) return;
                m_shutterSpeed = formatShutter(info.currentShutterSpeed_n, info.currentShutterSpeed_d);
                JSONObject data = new JSONObject();
                try {
                    data.put("currentShutterSpeed_n", info.currentShutterSpeed_n);
                    data.put("currentShutterSpeed_d", info.currentShutterSpeed_d);
                    data.put("currentAvailableMin_n", info.currentAvailableMin_n);
                    data.put("currentAvailableMin_d", info.currentAvailableMin_d);
                    data.put("currentAvailableMax_n", info.currentAvailableMax_n);
                    data.put("currentAvailableMax_d", info.currentAvailableMax_d);
                } catch (Throwable ignored) {
                }
                m_eventHub.setState("shutter", data);
                m_eventHub.emit("ShutterSpeedChange", data);
                m_goalController.onShutterChanged(
                        info.currentShutterSpeed_n, info.currentShutterSpeed_d,
                        info.currentAvailableMin_n, info.currentAvailableMin_d,
                        info.currentAvailableMax_n, info.currentAvailableMax_d);
                updateStatusText();
            }
        });

        try {
            m_camera.setAutoISOSensitivityListener(new CameraEx.AutoISOSensitivityListener() {
                @Override
                public void onChanged(int iso, CameraEx cameraEx) {
                    m_eventHub.setState("autoIso", iso);
                    JSONObject data = new JSONObject();
                    try {
                        data.put("iso", iso);
                    } catch (Throwable ignored) {
                    }
                    m_eventHub.emit("AutoISOSensitivity", data);
                }
            });
        } catch (Throwable ignored) {
        }

        try {
            m_camera.setZoomChangeListener(new CameraEx.ZoomChangeListener() {
                @Override
                public void onChanged(CameraEx.ZoomInfo info, CameraEx cameraEx) {
                    if (info == null) return;
                    if (info.digitalMagnification > 0) m_digitalZoom = info.digitalMagnification;
                    JSONObject data = new JSONObject();
                    try {
                        data.put("opticalPosition", info.opticalPosition);
                        data.put("opticalMagnification", info.opticalMagnification);
                        data.put("digitalPosition", info.digitalPosition);
                        data.put("digitalMagnification", info.digitalMagnification);
                        data.put("digitalZoomType", info.digitalZoomType);
                        data.put("stopped", info.stopped);
                    } catch (Throwable ignored) {
                    }
                    m_eventHub.setState("zoom", data);
                    m_eventHub.emit("ZoomChange", data);
                    m_goalController.onZoomChanged(info.opticalPosition, info.stopped);
                    updateStatusText();
                }
            });
        } catch (Throwable ignored) {
        }

        try {
            m_camera.setFocalLengthChangeListener(new CameraEx.FocalLengthChangeListener() {
                @Override
                public void onFocalLengthChanged(int fl, CameraEx cameraEx) {
                    m_focalLength = fl;
                    m_eventHub.setState("focalLength", fl);
                    JSONObject data = new JSONObject();
                    try {
                        data.put("focalLength", fl);
                    } catch (Throwable ignored) {
                    }
                    m_eventHub.emit("FocalLengthChange", data);
                    updateStatusText();
                }
            });
        } catch (Throwable ignored) {
        }

        try {
            m_camera.setPowerZoomListener(new CameraEx.PowerZoomListener() {
                @Override
                public void onChanged(int status, CameraEx cameraEx) {
                    m_eventHub.setState("powerZoom", status);
                    JSONObject data = new JSONObject();
                    try {
                        data.put("status", status);
                    } catch (Throwable ignored) {
                    }
                    m_eventHub.emit("PowerZoom", data);
                }
            });
        } catch (Throwable ignored) {
        }

        try {
            m_camera.setPreviewMagnificationListener(new CameraEx.PreviewMagnificationListener() {
                @Override
                public void onChanged(boolean enabled, int ratio, int arg2, Pair pos, CameraEx cameraEx) {
                    JSONObject data = new JSONObject();
                    try {
                        data.put("enabled", enabled);
                        data.put("ratio", ratio);
                        data.put("pos", RpcDispatcher.toJsonValue(pos));
                    } catch (Throwable ignored) {
                    }
                    m_eventHub.setState("previewMagnification", data);
                    m_eventHub.emit("PreviewMagnification", data);
                }

                @Override
                public void onInfoUpdated(boolean enabled, Pair pos, CameraEx cameraEx) {
                    JSONObject data = new JSONObject();
                    try {
                        data.put("enabled", enabled);
                        data.put("pos", RpcDispatcher.toJsonValue(pos));
                    } catch (Throwable ignored) {
                    }
                    m_eventHub.emit("PreviewMagnificationInfo", data);
                }
            });
        } catch (Throwable ignored) {
        }

        try {
            m_camera.setSettingChangedListener(new CameraEx.SettingChangedListener() {
                @Override
                public void onChanged(int[] types, Camera.Parameters params, CameraEx cameraEx) {
                    try {
                        if (params != null) {
                            m_iso = m_camera.createParametersModifier(params).getISOSensitivity();
                            int ec = params.getExposureCompensation();
                            m_exposureEv = ec * (double) params.getExposureCompensationStep();
                            JSONObject ecObj = new JSONObject();
                            ecObj.put("index", ec);
                            ecObj.put("step", (double) params.getExposureCompensationStep());
                            ecObj.put("min", params.getMinExposureCompensation());
                            ecObj.put("max", params.getMaxExposureCompensation());
                            m_eventHub.setState("exposureCompensation", ecObj);
                        }
                    } catch (Throwable ignored) {
                    }
                    JSONObject data = new JSONObject();
                    try {
                        data.put("types", RpcDispatcher.toJsonValue(types));
                    } catch (Throwable ignored) {
                    }
                    m_eventHub.emit("SettingChanged", data);
                    scheduleControlRead();
                    updateStatusText();
                }
            });
        } catch (Throwable ignored) {
        }

        try {
            m_camera.setEquipmentCallback(new CameraEx.EquipmentCallback() {
                @Override
                public void onEquipmentChange(int deviceType, int status, CameraEx cameraEx) {
                    if (deviceType == CameraEx.EquipmentCallback.DEVICE_TYPE_LENS) {
                        m_eventHub.setState("lens", buildLensInfoJson());
                    }
                    JSONObject data = new JSONObject();
                    try {
                        data.put("deviceType", deviceType);
                        data.put("status", status);
                    } catch (Throwable ignored) {
                    }
                    m_eventHub.emit("EquipmentChange", data);
                }
            });
        } catch (Throwable ignored) {
        }

        try {
            m_camera.setErrorCallback(new CameraEx.ErrorCallback() {
                @Override
                public void onError(int error, CameraEx cameraEx) {
                    JSONObject data = new JSONObject();
                    try {
                        data.put("error", error);
                    } catch (Throwable ignored) {
                    }
                    m_eventHub.emit("Error", data);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private void startHttpServerWithFallback() {
        int[] candidatePorts = new int[]{80, MacroHttpServer.DEFAULT_PORT};
        for (int port : candidatePorts) {
            try {
                MacroHttpServer srv = new MacroHttpServer(this, port);
                srv.start();
                m_httpServer = srv;
                m_httpPort = port;
                m_eventHub.setWifiInfo(m_wifiIp, m_httpPort);
                Logger.info("HTTP server listening on port " + port);
                return;
            } catch (Throwable t) {
                Logger.info("Port " + port + " unavailable (" + t.getMessage() + ")");
            }
        }
        Logger.error("Failed to start HTTP server on any port");
    }

    @Override
    protected void onPause() {
        m_handler.removeCallbacks(m_readExposure);
        m_handler.removeCallbacks(m_clearDialStatus);
        m_captureInProgress = false;
        m_controlReadsAfter = 0L;
        m_isoDialMode = false;
        m_dialMessage = "";
        m_goalController.cancelAll();
        super.onPause();
        com.github.ma1co.pmcademo.app.Logger.flush();

        if (m_httpServer != null) {
            try {
                m_httpServer.stop();
            } catch (Throwable ignored) {
            }
            m_httpServer = null;
        }

        try {
            unregisterReceiver(m_networkStateReceiver);
        } catch (Throwable ignored) {
        }
        try {
            unregisterReceiver(m_batteryReceiver);
        } catch (Throwable ignored) {
        }
        // Parent AlphaSync owns Wi-Fi and automatic-power-off policy across this mode.


        m_liveViewManager.stop();

        if (m_camera != null) {
            m_surfaceHolder.removeCallback(this);
            try {
                m_camera.stopZoom();
            } catch (Throwable ignored) {
            }
            try {
                m_camera.stopPreviewMagnification();
            } catch (Throwable ignored) {
            }
            try {
                m_camera.getNormalCamera().stopPreview();
            } catch (Throwable ignored) {
            }
            if (m_autoReviewControl != null) {
                try {
                    m_autoReviewControl.setPictureReviewTime(m_pictureReviewTime);
                    m_camera.setAutoPictureReviewControl(null);
                } catch (Throwable ignored) {
                }
                m_autoReviewControl = null;
            }
            try {
                m_camera.release();
            } catch (Throwable ignored) {
            }
            m_camera = null;
        }
    }

    private void initCameraDefaults() {
        try {
            m_pictureReviewTime = m_autoReviewControl.getPictureReviewTime();
            m_autoReviewControl.setPictureReviewTime(0);

            // Do not change the user's drive mode, focus mode or digital zoom on entry.
        } catch (Throwable t) {
            Logger.error("initCameraDefaults error: " + t.toString());
        }
    }

    private void refreshInitialState() {
        m_eventHub.setState("lens", buildLensInfoJson());
        try {
            Camera.Parameters params = m_camera.getNormalCamera().getParameters();
            CameraEx.ParametersModifier mod = m_camera.createParametersModifier(params);
            try {
                m_aperture = mod.getAperture();
                if (m_aperture > 0) {
                    JSONObject ap = new JSONObject();
                    ap.put("currentAperture", m_aperture);
                    m_eventHub.setState("aperture", ap);
                }
            } catch (Throwable ignored) {
            }
            try {
                Pair ss = mod.getShutterSpeed();
                if (ss != null && ss.first != null && ss.second != null) {
                    int n = (Integer) ss.first;
                    int d = (Integer) ss.second;
                    m_shutterSpeed = formatShutter(n, d);
                    JSONObject ssObj = new JSONObject();
                    ssObj.put("currentShutterSpeed_n", n);
                    ssObj.put("currentShutterSpeed_d", d);
                    m_eventHub.setState("shutter", ssObj);
                }
            } catch (Throwable ignored) {
            }
            try {
                m_iso = mod.getISOSensitivity();
            } catch (Throwable ignored) {
            }
            try {
                int ec = params.getExposureCompensation();
                JSONObject ecObj = new JSONObject();
                ecObj.put("index", ec);
                ecObj.put("step", (double) params.getExposureCompensationStep());
                ecObj.put("min", params.getMinExposureCompensation());
                ecObj.put("max", params.getMaxExposureCompensation());
                m_eventHub.setState("exposureCompensation", ecObj);
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }

    private String formatShutter(int n, int d) {
        if (d <= 0) return "--";
        if (d == 1) return n + "s";
        if (n == 1) return "1/" + d;
        return n + "/" + d;
    }

    private void updateWifiStatus() {
        try {
            WifiInfo info = m_wifiManager.getConnectionInfo();
            int ipInt = info.getIpAddress();
            m_wifiIp = (ipInt != 0) ? Formatter.formatIpAddress(ipInt) : "0.0.0.0";
            m_eventHub.setWifiInfo(m_wifiIp, m_httpPort);

            String ssid = info.getSSID() != null ? info.getSSID() : "";
            String portSuffix = (m_httpPort == 80) ? "" : (":" + m_httpPort);
            NetworkInfo.DetailedState state = WifiInfo.getDetailedStateOf(info.getSupplicantState());
            if (ipInt != 0) {
                m_tvWifiStatus.setText("Wi-Fi: " + ssid + " -> http://" + m_wifiIp + portSuffix);
            } else {
                m_tvWifiStatus.setText("Wi-Fi: " + state.name() + " (" + ssid + ")");
            }
        } catch (Throwable t) {
            m_tvWifiStatus.setText("Wi-Fi: error");
        }
    }

    private void updateBatteryView(Intent intent) {
        if (intent == null || m_tvBattery == null) return;
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (level < 0 || scale <= 0) return;
        final int pct = (level * 100) / scale;
        final int color;
        if (pct < 15) {
            color = Color.parseColor("#FF5555");
        } else if (pct <= 30) {
            color = Color.parseColor("#FFD700");
        } else {
            color = Color.parseColor("#00FF88");
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (m_batteryView != null) {
                    m_batteryView.setPercent(pct);
                }
                m_tvBattery.setText(pct + "%");
                m_tvBattery.setTextColor(color);
            }
        });
    }

    private void updateStatusText() {
        final String apStr = (m_aperture > 0) ? String.format(Locale.US, "F%.1f", m_aperture / 100.0f) : "F--";
        final String isoStr = (m_iso == 0) ? "AUTO" : String.valueOf(m_iso);
        final String flStr = (m_focalLength > 0) ? ("  " + Math.round(m_focalLength / 10.0f) + "mm") : "";
        final String zoomStr = (m_digitalZoom > 100) ? String.format(Locale.US, "  DZ:%.1fx", m_digitalZoom / 100.0f) : "";
        final String text = apStr + "  " + m_shutterSpeed + "  ISO " + isoStr
                + (m_dialMessage.length() > 0 ? "\n" + ("曝光补偿".equals(m_dialMessage)
                        ? String.format(Locale.US, "曝光补偿 %+.1f EV", m_exposureEv) : m_dialMessage)
                : m_isoDialMode ? "\nISO 拨轮模式" : flStr + zoomStr + "  MF " + m_curFocus + "/" + m_maxFocus);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                m_tvCameraStatus.setText(text);
            }
        });
    }

    // --- Synchronous Main-Thread Execution Helper ---

    public <T> T runOnMainSync(final Callable<T> task, long timeoutMs) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return task.call();
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<T> resultRef = new AtomicReference<T>();
        final AtomicReference<Throwable> errRef = new AtomicReference<Throwable>();

        m_handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    resultRef.set(task.call());
                } catch (Throwable t) {
                    errRef.set(t);
                } finally {
                    latch.countDown();
                }
            }
        });

        boolean ok = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        if (!ok) {
            throw new TimeoutException("Main thread camera call timed out after " + timeoutMs + "ms");
        }
        Throwable err = errRef.get();
        if (err != null) {
            if (err instanceof Exception) {
                throw (Exception) err;
            }
            throw new RuntimeException(err);
        }
        return resultRef.get();
    }

    // --- Lens Info & Raw CameraEx Queries ---

    public JSONObject buildLensInfoJson() {
        if (m_camera == null) return new JSONObject();
        try {
            Object val = RpcDispatcher.toJsonValue(m_camera.getLensInfo());
            if (val instanceof JSONObject) {
                return (JSONObject) val;
            }
        } catch (Throwable ignored) {
        }
        return new JSONObject();
    }

    // --- SurfaceHolder.Callback ---

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        try {
            Camera cam = m_camera.getNormalCamera();
            cam.setPreviewDisplay(holder);
            cam.startPreview();
            m_liveViewManager.start(m_camera, "/data/data/" + getPackageName() + "/lib/lib_memorymap.so");
        } catch (Throwable t) {
            Logger.error("surfaceCreated error: " + t.toString());
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        m_liveViewManager.stop();
    }

    // --- Physical Camera Keys ---

    void scheduleControlRead() {
        m_handler.removeCallbacks(m_readExposure);
        m_handler.postDelayed(m_readExposure, 150L);
    }

    private void deferControlReadAfterCapture() {
        m_controlReadsAfter = android.os.SystemClock.uptimeMillis() + CAPTURE_SETTLE_MS;
        scheduleControlRead();
    }

    void publishControlSettings() {
        CameraEx camera = m_camera;
        if (camera == null) return;
        JSONObject values = new JSONObject();
        try {
            Camera.Parameters params = camera.getNormalCamera().getParameters();
            CameraEx.ParametersModifier modifier = camera.createParametersModifier(params);
            putControlValue(values, "sceneMode", params.getSceneMode());
            try {
                m_iso = modifier.getISOSensitivity();
                putControlValue(values, "isoSetting", m_iso);
            } catch (Throwable ignored) {
            }
            JSONObject previous = m_eventHub.getStateSnapshot();
            try {
                int aperture = modifier.getAperture();
                if (aperture > 0) {
                    m_aperture = aperture;
                    JSONObject ap = previous.optJSONObject("aperture");
                    if (ap == null) ap = new JSONObject();
                    putControlValue(values, "aperture", ap.put("currentAperture", aperture));
                }
            } catch (Throwable ignored) {
            }
            try {
                Pair shutter = modifier.getShutterSpeed();
                if (shutter != null && shutter.first instanceof Number && shutter.second instanceof Number) {
                    int n = ((Number) shutter.first).intValue();
                    int d = ((Number) shutter.second).intValue();
                    m_shutterSpeed = formatShutter(n, d);
                    JSONObject ss = previous.optJSONObject("shutter");
                    if (ss == null) ss = new JSONObject();
                    putControlValue(values, "shutter", ss.put("currentShutterSpeed_n", n).put("currentShutterSpeed_d", d));
                }
            } catch (Throwable ignored) {
            }
            try {
                putControlValue(values, "focusSettings", FocusSettings.read(params, modifier));
            } catch (Throwable ignored) {
            }
            try {
                putControlValue(values, "rawRecMode", modifier.getRawRecMode());
            } catch (Throwable ignored) {
            }
            try {
                m_exposureEv = params.getExposureCompensation() * (double) params.getExposureCompensationStep();
                putControlValue(values, "exposureCompensation", new JSONObject()
                        .put("index", params.getExposureCompensation())
                        .put("step", (double) params.getExposureCompensationStep())
                        .put("min", params.getMinExposureCompensation())
                        .put("max", params.getMaxExposureCompensation()));
            } catch (Throwable ignored) {
            }
            if (values.length() > 0) m_eventHub.emit("ControlSettings", values);
            updateStatusText();
        } catch (Throwable error) {
            Logger.error("Read control settings: " + error);
        }
    }

    private void putControlValue(JSONObject values, String key, Object value) throws Exception {
        if (value == null) return;
        values.put(key, value);
        m_eventHub.setState(key, value);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (event.getScanCode()) {
            case ScalarInput.ISV_DIAL_1_CLOCKWISE: adjustDial(1, true); return true;
            case ScalarInput.ISV_DIAL_1_COUNTERCW: adjustDial(1, false); return true;
            case ScalarInput.ISV_DIAL_2_CLOCKWISE: adjustDial(2, true); return true;
            case ScalarInput.ISV_DIAL_2_COUNTERCW: adjustDial(2, false); return true;
            case ScalarInput.ISV_DIAL_KURU_CLOCKWISE: adjustDial(3, true); return true;
            case ScalarInput.ISV_DIAL_KURU_COUNTERCW: adjustDial(3, false); return true;
            case ScalarInput.ISV_DIAL_3_CLOCKWISE: adjustDial(4, true); return true;
            case ScalarInput.ISV_DIAL_3_COUNTERCW: adjustDial(4, false); return true;
            default: return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    protected boolean onFnKeyDown() { return true; }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (event.getScanCode() == ScalarInput.ISV_KEY_EV_COMPENSATION) {
            showDialStatus("曝光补偿");
            scheduleControlRead();
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    protected boolean onFnKeyUp() {
        m_isoDialMode = !m_isoDialMode;
        showDialStatus(m_isoDialMode ? "ISO 拨轮模式" : "曝光拨轮模式");
        return true;
    }

    private void showDialStatus(String message) {
        m_dialMessage = message;
        m_topBar.setVisibility(View.VISIBLE);
        updateStatusText();
        m_handler.removeCallbacks(m_clearDialStatus);
        m_handler.postDelayed(m_clearDialStatus, 2400L);
    }

    private void adjustDial(int dial, boolean clockwise) {
        CameraEx camera = m_camera;
        if (camera == null) return;
        if (m_captureInProgress || android.os.SystemClock.uptimeMillis() < m_controlReadsAfter) {
            showDialStatus("拍摄处理中");
            return;
        }
        try {
            Camera.Parameters params = camera.getNormalCamera().getParameters();
            String label;
            if (m_isoDialMode || dial == 3) {
                label = "ISO";
                CameraEx.ParametersModifier modifier = camera.createParametersModifier(params);
                int current = modifier.getISOSensitivity();
                List<?> supported = modifier.getSupportedISOSensitivities();
                Integer next = null;
                if (supported != null) for (Object entry : supported) {
                    if (!(entry instanceof Number)) continue;
                    int choice = ((Number) entry).intValue();
                    if (choice < 0) continue;
                    if (clockwise && choice > current && (next == null || choice < next)) next = choice;
                    if (!clockwise && choice < current && (next == null || choice > next)) next = choice;
                }
                if (next != null) {
                    Camera.Parameters update = camera.createEmptyParameters();
                    camera.createParametersModifier(update).setISOSensitivity(next);
                    camera.getNormalCamera().setParameters(update);
                }
            } else {
                String mode = params.getSceneMode();
                boolean manual = "manual-exposure".equals(mode);
                boolean aperturePriority = "aperture-priority".equals(mode);
                boolean shutterPriority = "shutter-speed".equals(mode);
                if (dial == 1 && (manual || aperturePriority)) {
                    label = "光圈";
                    if (clockwise) camera.incrementAperture();
                    else camera.decrementAperture();
                } else if (dial == 2 && (manual || shutterPriority)) {
                    label = "快门";
                    if (clockwise) camera.incrementShutterSpeed();
                    else camera.decrementShutterSpeed();
                } else if (dial == 4 || (dial == 2 && aperturePriority)
                        || (dial == 1 && shutterPriority) || (dial == 2 && "program-auto".equals(mode))) {
                    int index = params.getExposureCompensation();
                    int min = params.getMinExposureCompensation();
                    int max = params.getMaxExposureCompensation();
                    if (max <= min) {
                        showDialStatus("当前模式无法调整曝光补偿");
                        return;
                    }
                    int next = Math.max(min, Math.min(max, index + (clockwise ? 1 : -1)));
                    if (next != index) {
                        Camera.Parameters update = camera.createEmptyParameters();
                        update.setExposureCompensation(next);
                        camera.getNormalCamera().setParameters(update);
                    }
                    label = "曝光补偿";
                } else {
                    showDialStatus("当前模式由相机自动控制");
                    return;
                }
            }
            showDialStatus(label);
            scheduleControlRead();
        } catch (Throwable error) {
            Logger.error("Body dial adjustment: " + error);
            showDialStatus("相机未接受拨轮设置");
        }
    }

    void capturePhoto() {
        CameraEx camera = m_camera;
        if (camera == null) {
            throw new IllegalStateException("相机尚未准备好");
        }
        deferControlReadAfterCapture();
        camera.burstableTakePicture();
    }

    boolean isCaptureInProgress() {
        return m_captureInProgress;
    }

    @Override
    protected boolean onShutterKeyDown() {
        try {
            capturePhoto();
        } catch (Throwable error) {
            Logger.error("Capture failed: " + error);
        }
        return true;
    }

    @Override
    protected boolean onShutterKeyUp() {
        return true;
    }

    @Override
    protected boolean onUpKeyDown() {
        return true;
    }

    @Override
    protected boolean onUpKeyUp() {
        toggleStatusBars();
        return true;
    }

    @Override
    protected boolean onEnterKeyDown() {
        return true;
    }

    @Override
    protected boolean onEnterKeyUp() {
        toggleStatusBars();
        return true;
    }

    private void toggleStatusBars() {
        m_statusBarsVisible = !m_statusBarsVisible;
        int visibility = m_statusBarsVisible ? View.VISIBLE : View.GONE;
        if (m_topBar != null) {
            m_topBar.setVisibility(visibility);
        }
        if (m_tvWifiStatus != null) {
            m_tvWifiStatus.setVisibility(visibility);
        }
    }

    @Override
    protected boolean onMenuKeyUp() {
        onBackPressed();
        return true;
    }

    @Override
    protected void setColorDepth(boolean highQuality) {
        super.setColorDepth(false);
    }
}
