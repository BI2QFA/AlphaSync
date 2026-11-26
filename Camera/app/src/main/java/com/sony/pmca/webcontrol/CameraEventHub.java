package com.sony.pmca.webcontrol;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedList;

/**
 * Thread-safe event ring buffer and latest state snapshot store.
 * Supports long-polling via waitForEvents(sinceSeq, timeoutSec).
 */
public class CameraEventHub {
    private static final int MAX_EVENTS = 256;

    private final Object m_lock = new Object();
    private final LinkedList<JSONObject> m_events = new LinkedList<JSONObject>();
    private final JSONObject m_state = new JSONObject();
    private long m_seq = 0;

    public CameraEventHub() {
        try {
            m_state.put("ok", true);
            m_state.put("seq", 0);
            m_state.put("aperture", new JSONObject());
            m_state.put("shutter", new JSONObject());
            m_state.put("focus", new JSONObject());
            m_state.put("zoom", new JSONObject());
            m_state.put("autoIso", 0);
            m_state.put("focalLength", 0);
            m_state.put("powerZoom", -1);
            m_state.put("previewMagnification", new JSONObject());
            m_state.put("lastStoreImage", new JSONObject());
            m_state.put("lastShutterStatus", -1);
            m_state.put("capturing", false);
            m_state.put("lens", new JSONObject());
            m_state.put("exposureCompensation", new JSONObject());
            setWifiInfo("0.0.0.0", MacroHttpServer.DEFAULT_PORT);
        } catch (Throwable ignored) {
        }
    }

    public void setState(String key, Object value) {
        synchronized (m_lock) {
            try {
                m_state.put(key, value != null ? value : new JSONObject());
            } catch (Throwable ignored) {
            }
        }
    }

    public void setWifiInfo(String ip, int port) {
        JSONObject wifi = new JSONObject();
        try {
            wifi.put("ip", ip != null ? ip : "0.0.0.0");
            wifi.put("port", port);
        } catch (Throwable ignored) {
        }
        setState("wifi", wifi);
    }

    public long emit(String type, JSONObject data) {
        synchronized (m_lock) {
            m_seq++;
            JSONObject ev = new JSONObject();
            try {
                m_state.put("seq", m_seq);
                ev.put("seq", m_seq);
                ev.put("t", System.currentTimeMillis());
                ev.put("type", type);
                ev.put("data", data != null ? data : new JSONObject());
            } catch (Throwable ignored) {
            }
            m_events.addLast(ev);
            while (m_events.size() > MAX_EVENTS) {
                m_events.removeFirst();
            }
            m_lock.notifyAll();
            return m_seq;
        }
    }

    public void wakeAll() {
        synchronized (m_lock) {
            m_lock.notifyAll();
        }
    }

    public JSONObject getStateSnapshot() {
        synchronized (m_lock) {
            try {
                return new JSONObject(m_state.toString());
            } catch (Throwable t) {
                return new JSONObject();
            }
        }
    }

    public JSONObject waitForEvents(long sinceSeq, int timeoutSec) {
        long clampedTimeoutMs = Math.max(0, Math.min(30, timeoutSec)) * 1000L;
        long deadline = System.currentTimeMillis() + clampedTimeoutMs;

        synchronized (m_lock) {
            while (m_seq <= sinceSeq && clampedTimeoutMs > 0) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    break;
                }
                try {
                    m_lock.wait(remaining);
                } catch (InterruptedException e) {
                    break;
                }
            }

            JSONArray arr = new JSONArray();
            for (JSONObject ev : m_events) {
                if (ev.optLong("seq", 0) > sinceSeq) {
                    arr.put(ev);
                }
            }

            JSONObject res = new JSONObject();
            try {
                res.put("ok", true);
                res.put("seq", m_seq);
                res.put("events", arr);
            } catch (Throwable ignored) {
            }
            return res;
        }
    }
}
