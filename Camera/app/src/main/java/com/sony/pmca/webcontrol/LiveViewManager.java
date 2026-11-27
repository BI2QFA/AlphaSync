package com.sony.pmca.webcontrol;

import com.github.ma1co.pmcademo.app.Logger;
import com.sony.scalar.hardware.CameraEx;
import com.sony.scalar.hardware.CameraSequence;
import com.sony.scalar.hardware.DeviceBuffer;
import com.sony.scalar.hardware.DeviceMemory;

import java.nio.ByteBuffer;
import java.util.ArrayList;

/**
 * Manages hardware LiveView streaming via Sony CameraSequence and
 * thread-safe frame delivery to MJPEG / snapshot HTTP clients.
 */
public class LiveViewManager {
    public static final int MAX_MJPEG_CLIENTS = 1;
    private static final int LIVEVIEW_BUF_CAPACITY = 128 * 1024;

    public static final class MjpegClient {
        final String cid;
        volatile boolean closed = false;

        MjpegClient(String cid) {
            this.cid = cid;
        }
    }

    public static final class LiveViewFrame {
        public final long seq;
        public final byte[] jpeg;

        public LiveViewFrame(long seq, byte[] jpeg) {
            this.seq = seq;
            this.jpeg = jpeg;
        }
    }

    private final Object m_frameLock = new Object();
    private final Object m_captureLock = new Object();

    private CameraSequence m_cameraSequence;
    private ByteBuffer m_liveviewByteBuf;
    private byte[] m_latestJpeg;
    private long m_lastLiveviewFetchMs = 0;
    private long m_lastFetchDurationMs = 0;
    private long m_frameSeq = 0;
    private final ArrayList<MjpegClient> m_mjpegClientList = new ArrayList<MjpegClient>(MAX_MJPEG_CLIENTS);
    private volatile boolean m_liveviewRunning = false;
    private Thread m_liveviewThread;

    public void start(CameraEx camera, String memoryMapLibPath) {
        if (camera == null) {
            return;
        }
        synchronized (m_captureLock) {
            if (m_cameraSequence != null) {
                return;
            }
            try {
                m_cameraSequence = CameraSequence.open(camera);
                if (m_cameraSequence != null) {
                    CameraSequence.Options opts = new CameraSequence.Options();
                    opts.setOption("MEMORY_MAP_FILE", memoryMapLibPath);
                    opts.setOption("EXPOSURE_COUNT", 1);
                    opts.setOption("RECORD_COUNT", 1);
                    opts.setOption("INTERIM_PRE_REVIEW_ENABLED", false);
                    opts.setOption("AUTO_RELEASE_LOCK_ENABLED", false);
                    opts.setOption("PREVIEW_FRAME_RATE", 30000);
                    opts.setOption("PREVIEW_FRAME_WIDTH", 640);
                    opts.setOption("PREVIEW_FRAME_HEIGHT", 0);
                    m_cameraSequence.setReleaseLock(false);
                    m_cameraSequence.startPreviewSequence(opts);
                    synchronized (m_frameLock) {
                        m_liveviewRunning = true;
                        m_liveviewThread = new Thread(new Runnable() {
                            @Override
                            public void run() {
                                runProducer();
                            }
                        }, "LiveviewProducer");
                        m_liveviewThread.start();
                    }
                    Logger.info("CameraSequence LiveView started (640px JPEG)");
                }
            } catch (Throwable t) {
                Logger.error("startLiveviewSequence error: " + t.toString());
                m_cameraSequence = null;
            }
        }
    }

    public void stop() {
        Thread threadToJoin;
        synchronized (m_frameLock) {
            m_liveviewRunning = false;
            m_latestJpeg = null;
            threadToJoin = m_liveviewThread;
            m_liveviewThread = null;
            m_frameLock.notifyAll();
        }
        if (threadToJoin != null && threadToJoin != Thread.currentThread()) {
            try {
                threadToJoin.join(500);
            } catch (InterruptedException ignored) {
            }
        }
        synchronized (m_captureLock) {
            if (m_cameraSequence != null) {
                try {
                    m_cameraSequence.stopPreviewSequence();
                } catch (Throwable ignored) {
                }
                try {
                    m_cameraSequence.release();
                } catch (Throwable ignored) {
                }
                m_cameraSequence = null;
            }
        }
    }

    public MjpegClient acquireMjpegClient(String cid) {
        String normalizedCid = normalizeCid(cid);
        synchronized (m_frameLock) {
            if (normalizedCid != null) {
                for (int i = m_mjpegClientList.size() - 1; i >= 0; i--) {
                    MjpegClient existing = m_mjpegClientList.get(i);
                    if (normalizedCid.equals(existing.cid)) {
                        existing.closed = true;
                        m_mjpegClientList.remove(i);
                    }
                }
            }
            while (m_mjpegClientList.size() >= MAX_MJPEG_CLIENTS) {
                MjpegClient oldest = m_mjpegClientList.remove(0);
                oldest.closed = true;
            }
            MjpegClient client = new MjpegClient(normalizedCid);
            m_mjpegClientList.add(client);
            m_frameLock.notifyAll();
            return client;
        }
    }

    public boolean closeMjpegClient(String cid) {
        String normalizedCid = normalizeCid(cid);
        if (normalizedCid == null) {
            return false;
        }
        boolean closedAny = false;
        synchronized (m_frameLock) {
            for (int i = m_mjpegClientList.size() - 1; i >= 0; i--) {
                MjpegClient existing = m_mjpegClientList.get(i);
                if (normalizedCid.equals(existing.cid)) {
                    existing.closed = true;
                    m_mjpegClientList.remove(i);
                    closedAny = true;
                }
            }
            if (closedAny) {
                m_frameLock.notifyAll();
            }
        }
        return closedAny;
    }

    private static String normalizeCid(String cid) {
        if (cid == null) return null;
        String trimmed = cid.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public void releaseMjpegClient(MjpegClient client) {
        synchronized (m_frameLock) {
            if (client != null) {
                client.closed = true;
                m_mjpegClientList.remove(client);
            }
            m_frameLock.notifyAll();
        }
    }

    public LiveViewFrame waitForNextFrame(MjpegClient client, long lastSeq, long timeoutMs) {
        synchronized (m_frameLock) {
            if (m_latestJpeg == null && m_liveviewRunning) {
                // Trigger an immediate fetch outside frameLock if no frame is cached yet
                m_frameLock.notifyAll();
            }
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (m_liveviewRunning && (client == null || !client.closed) && m_frameSeq == lastSeq) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    break;
                }
                try {
                    m_frameLock.wait(remaining);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
            if (!m_liveviewRunning || (client != null && client.closed)) {
                return null;
            }
            // If timed out while still running (e.g. long exposure), return current seq so caller keeps waiting
            return new LiveViewFrame(m_frameSeq, m_latestJpeg);
        }
    }

    public byte[] getLatestLiveViewJpeg() {
        fetchPreviewFrame(33);
        synchronized (m_frameLock) {
            return m_latestJpeg;
        }
    }

    private void runProducer() {
        while (m_liveviewRunning) {
            long intervalMs;
            synchronized (m_frameLock) {
                while (m_liveviewRunning && m_mjpegClientList.isEmpty()) {
                    try {
                        m_frameLock.wait(500);
                    } catch (InterruptedException ignored) {
                    }
                }
                if (!m_liveviewRunning) {
                    break;
                }
                int clientCount = m_mjpegClientList.size();
                if (clientCount <= 1) {
                    intervalMs = 66; // Bound producer rate to about 15 fps on small Sony CPUs.
                } else if (clientCount == 2) {
                    intervalMs = 50;
                } else {
                    intervalMs = 85;
                }
            }

            fetchPreviewFrame(intervalMs);

            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException ignored) {
            }
        }
    }

    private void fetchPreviewFrame(long minIntervalMs) {
        long now = System.currentTimeMillis();
        synchronized (m_frameLock) {
            if (!m_liveviewRunning || (now - m_lastLiveviewFetchMs) < minIntervalMs) {
                return;
            }
        }

        byte[] jpeg = null;
        long fetchStart = System.currentTimeMillis();
        synchronized (m_captureLock) {
            if (m_cameraSequence == null) {
                return;
            }
            DeviceMemory[] frames = null;
            try {
                frames = m_cameraSequence.getPreviewSequenceFrames(1);
                if (frames != null && frames.length > 0 && frames[0] != null) {
                    DeviceBuffer buf = (DeviceBuffer) frames[0];
                    if (m_liveviewByteBuf == null) {
                        m_liveviewByteBuf = ByteBuffer.allocateDirect(LIVEVIEW_BUF_CAPACITY);
                    }
                    m_liveviewByteBuf.clear();
                    int size = buf.read(m_liveviewByteBuf);
                    if (size > 0 && size <= LIVEVIEW_BUF_CAPACITY) {
                        jpeg = new byte[size];
                        m_liveviewByteBuf.rewind();
                        m_liveviewByteBuf.get(jpeg, 0, size);
                    }
                }
            } catch (Throwable t) {
                Logger.error("getPreviewSequenceFrames error: " + t.toString());
            } finally {
                if (frames != null) {
                    for (DeviceMemory dm : frames) {
                        if (dm != null) {
                            try {
                                dm.release();
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
            }
        }

        if (jpeg != null) {
            long finishedAt = System.currentTimeMillis();
            synchronized (m_frameLock) {
                m_latestJpeg = jpeg;
                m_lastLiveviewFetchMs = finishedAt;
                m_lastFetchDurationMs = finishedAt - fetchStart;
                m_frameSeq++;
                m_frameLock.notifyAll();
            }
        }
    }

    public org.json.JSONObject getDebugStats() {
        org.json.JSONObject obj = new org.json.JSONObject();
        try {
            synchronized (m_frameLock) {
                obj.put("running", m_liveviewRunning);
                obj.put("clientCount", m_mjpegClientList.size());
                org.json.JSONArray clients = new org.json.JSONArray();
                for (int i = 0; i < m_mjpegClientList.size(); i++) {
                    clients.put(m_mjpegClientList.get(i).cid);
                }
                obj.put("clients", clients);
                obj.put("frameSeq", m_frameSeq);
                obj.put("lastFrameBytes", m_latestJpeg != null ? m_latestJpeg.length : 0);
                obj.put("lastFetchDurationMs", m_lastFetchDurationMs);
                obj.put("lastFetchAgeMs", m_lastLiveviewFetchMs > 0 ? (System.currentTimeMillis() - m_lastLiveviewFetchMs) : -1);
            }
        } catch (Throwable ignored) {
        }
        return obj;
    }
}
