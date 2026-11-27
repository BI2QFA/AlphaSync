package com.sony.pmca.webcontrol;

import android.hardware.Camera;

import com.sony.scalar.hardware.CameraEx;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.Callable;

/**
 * Event-driven, device-side closed-loop controller for camera parameters that
 * only expose relative/stepping APIs (focus, aperture, shutter speed, power zoom).
 *
 * Concurrency model:
 * - Each parameter has an independent GoalSlot, so different parameters run in parallel.
 * - Within the same parameter, a new command increments generationId (latest-wins preemption),
 *   immediately waking and aborting any in-flight older request for that parameter.
 * - Waiting happens on the calling HTTP worker thread; only individual short drive calls
 *   are dispatched to the Android main thread via runOnMainSync, so the camera UI never blocks.
 */
public class CameraGoalController {

    private static final int FOCUS_NEAR = 0;
    private static final int FOCUS_FAR = 1;
    private static final int ZOOM_TELE = 0;
    private static final int ZOOM_WIDE = 1;

    private static final class GoalSlot {
        long generation = 0;
        long updateSeq = 0;
        boolean hasValue = false;

        int v1 = 0;
        int v2 = 0;
        int min1 = 0;
        int max1 = 0;
        int minN = 0;
        int minD = 0;
        int maxN = 0;
        int maxD = 0;

        synchronized long nextGeneration() {
            generation++;
            notifyAll();
            return generation;
        }

        synchronized long getUpdateSeq() {
            return updateSeq;
        }

        synchronized boolean isCurrent(long myGen) {
            return generation == myGen;
        }

        synchronized void waitForChange(long myGen, long prevSeq, long timeoutMs) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (generation == myGen && updateSeq == prevSeq) {
                long rem = deadline - System.currentTimeMillis();
                if (rem <= 0) break;
                try {
                    wait(rem);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
        }

        synchronized int waitForFocusToSettle(long myGen, long seq, int originalPosition) throws InterruptedException {
            long deadline = android.os.SystemClock.uptimeMillis() + 220;
            long quietUntil = deadline;
            int position = originalPosition;
            while (generation == myGen) {
                long now = android.os.SystemClock.uptimeMillis();
                if (updateSeq != seq && v1 != position) {
                    position = v1;
                    quietUntil = now + 45;
                }
                seq = updateSeq;
                long remaining = Math.min(deadline, quietUntil) - now;
                if (remaining <= 0) break;
                wait(remaining);
            }
            return v1;
        }
    }

    private final MacroServerActivity m_activity;
    private final GoalSlot m_focus = new GoalSlot();
    private final Object m_focusOpLock = new Object();
    private int m_focusSettingsRevision;
    private final GoalSlot m_aperture = new GoalSlot();
    private final GoalSlot m_shutter = new GoalSlot();
    private final GoalSlot m_zoom = new GoalSlot();

    public CameraGoalController(MacroServerActivity activity) {
        m_activity = activity;
    }

    /** Wake pending parameter goals before the activity releases CameraEx. */
    public void cancelAll() {
        m_focus.nextGeneration();
        m_aperture.nextGeneration();
        m_shutter.nextGeneration();
        m_zoom.nextGeneration();
    }

    // Both explicit settings and tap-to-focus parameter writes run on the camera main thread.
    void onFocusSettingsChanged() {
        m_focusSettingsRevision++;
        m_focus.nextGeneration();
    }

    // --- Hardware Listener Hooks ---

    public void onFocusChanged(int currentPos, int maxPos) {
        synchronized (m_focus) {
            m_focus.hasValue = true;
            m_focus.v1 = currentPos;
            m_focus.max1 = maxPos;
            m_focus.updateSeq++;
            m_focus.notifyAll();
        }
    }

    public void onApertureChanged(int currentAperture, int minAperture, int maxAperture) {
        synchronized (m_aperture) {
            m_aperture.hasValue = true;
            m_aperture.v1 = currentAperture;
            m_aperture.min1 = minAperture;
            m_aperture.max1 = maxAperture;
            m_aperture.updateSeq++;
            m_aperture.notifyAll();
        }
    }

    public void onShutterChanged(int n, int d, int minN, int minD, int maxN, int maxD) {
        synchronized (m_shutter) {
            m_shutter.hasValue = true;
            m_shutter.v1 = n;
            m_shutter.v2 = d;
            m_shutter.minN = minN;
            m_shutter.minD = minD;
            m_shutter.maxN = maxN;
            m_shutter.maxD = maxD;
            m_shutter.updateSeq++;
            m_shutter.notifyAll();
        }
    }

    public void onZoomChanged(int opticalPosition, boolean stopped) {
        synchronized (m_zoom) {
            m_zoom.hasValue = true;
            m_zoom.v1 = opticalPosition;
            m_zoom.v2 = stopped ? 1 : 0;
            m_zoom.updateSeq++;
            m_zoom.notifyAll();
        }
    }

    // --- RPC Entrypoint ---

    public JSONObject execute(String method, JSONArray args) throws Exception {
        if ("setFocusPosition".equals(method)) {
            if (args == null || args.length() < 1) {
                throw new IllegalArgumentException("setFocusPosition requires (targetPos[, tolerance])");
            }
            int target = args.getInt(0);
            int tol = args.length() >= 2 ? args.getInt(1) : 0;
            return setFocusPosition(target, Math.max(0, tol));
        }
        if ("autoFocusAt".equals(method)) {
            if (args == null || args.length() < 2) {
                throw new IllegalArgumentException("autoFocusAt requires (normX, normY)");
            }
            return autoFocusAt(args.getDouble(0), args.getDouble(1));
        }
        if ("setAperture".equals(method)) {
            if (args == null || args.length() < 1) {
                throw new IllegalArgumentException("setAperture requires (fValue100)");
            }
            return setAperture(args.getInt(0));
        }
        if ("setShutterSpeed".equals(method)) {
            if (args == null || args.length() < 2) {
                throw new IllegalArgumentException("setShutterSpeed requires (numerator, denominator)");
            }
            return setShutterSpeed(args.getInt(0), args.getInt(1));
        }
        if ("setZoomPosition".equals(method)) {
            if (args == null || args.length() < 1) {
                throw new IllegalArgumentException("setZoomPosition requires (opticalPosition[, tolerance])");
            }
            int target = args.getInt(0);
            int tol = args.length() >= 2 ? args.getInt(1) : 1;
            return setZoomPosition(target, Math.max(0, tol));
        }
        throw new NoSuchMethodException("Unknown highlevel method: " + method);
    }

    // --- Closed-Loop Implementations ---

    private static double predictFocusStep(int zoomPos, int speed) {
        // Fallback to mid-tele (z=74) when optical zoom state is unavailable (e.g. prime lens)
        int z = (zoomPos >= 0) ? zoomPos : 74;
        return (0.0491 + 0.2667 * Math.pow(0.9634, z)) * Math.pow(2.615, speed);
    }

    private static int chooseFocusSpeed(int absDiff, int zoomPos, int zeroStreak, int reversals) {
        int chosen = 1;
        for (int s = 7; s >= 1; s--) {
            if (predictFocusStep(zoomPos, s) <= absDiff * 0.92) {
                chosen = s;
                break;
            }
        }
        // Keep predicted step >= 0.75 ticks to avoid sub-tick deadzones unless damping a tight ±1..2 bounce
        boolean allowSubtick = (reversals >= 2 && absDiff <= 2);
        if (!allowSubtick) {
            while (chosen < 7 && predictFocusStep(zoomPos, chosen) < 0.75) {
                chosen++;
            }
        }
        // Temporarily escalate speed after repeated ignored/zero-displacement pulses
        if (zeroStreak >= 3) {
            int boost = zeroStreak / 3;
            int cap = (absDiff <= 3) ? Math.min(7, chosen + 1) : 7;
            chosen = Math.min(cap, chosen + boost);
        }
        return chosen;
    }

    private JSONObject setFocusPosition(int targetPos, int tolerance) throws Exception {
        final long myGen = m_focus.nextGeneration();
        // A few firmwares report the saved file before CaptureEnd. Wait only while capture is active.
        long idleDeadline = android.os.SystemClock.uptimeMillis() + 1500;
        while (m_activity.isCaptureInProgress() && m_focus.isCurrent(myGen)) {
            if (android.os.SystemClock.uptimeMillis() >= idleDeadline) {
                throw new IllegalStateException("相机仍在处理上一张照片，请等拍摄完成后重试");
            }
            Thread.sleep(35);
        }
        m_activity.runOnMainSync(new Callable<Void>() {
            @Override public Void call() {
                if (!m_focus.isCurrent(myGen)) return null;
                CameraEx camera = m_activity.getCameraEx();
                if (camera == null) throw new IllegalStateException("相机尚未准备好");
                String mode = camera.getNormalCamera().getParameters().getFocusMode();
                if (!CameraEx.ParametersModifier.FOCUS_MODE_MANUAL.equals(mode)) {
                    throw new IllegalStateException("请先切换到手动对焦（MF），再移动焦点或开始包围拍摄");
                }
                return null;
            }
        }, MacroServerActivity.MAIN_THREAD_TIMEOUT_MS);
        int zeroStreak = 0;
        int steps = 0;
        int speedPenalty = 0;
        int directionMismatches = 0;
        boolean reversedMapping = false;

        for (int i = 0; i < 100; i++) {
            int current;
            boolean has;
            int maxPos;
            synchronized (m_focus) {
                if (!m_focus.isCurrent(myGen)) {
                    return buildResult(false, true, false, m_focus.v1, targetPos, steps);
                }
                has = m_focus.hasValue;
                current = m_focus.v1;
                maxPos = m_focus.max1;
            }
            if (!has) {
                throw new IllegalStateException("相机未报告焦点位置。请确认镜头处于 MF 并支持电子对焦驱动");
            }

            int zoomPos;
            synchronized (m_zoom) {
                zoomPos = m_zoom.hasValue ? m_zoom.v1 : -1;
            }

            int clampedTarget = (maxPos > 0) ? Math.max(0, Math.min(maxPos, targetPos)) : targetPos;
            int diffToTarget = clampedTarget - current;
            int absDist = Math.abs(diffToTarget);
            if (absDist <= tolerance) {
                return buildResult(true, false, false, current, clampedTarget, steps);
            }

            final int requestedSign = diffToTarget > 0 ? 1 : -1;
            final int dir = (requestedSign > 0) != reversedMapping ? FOCUS_FAR : FOCUS_NEAR;
            final int speed = Math.max(1, chooseFocusSpeed(absDist, zoomPos, zeroStreak, speedPenalty) - speedPenalty);

            long seqBefore = m_focus.getUpdateSeq();
            driveOnMain(new Runnable() {
                @Override
                public void run() {
                    CameraEx cam = m_activity.getCameraEx();
                    if (cam != null && m_focus.isCurrent(myGen)) cam.startOneShotFocusDrive(dir, speed);
                }
            });
            steps++;

            int posAfter = m_focus.waitForFocusToSettle(myGen, seqBefore, current);
            if (!m_focus.isCurrent(myGen)) {
                return buildResult(false, true, false, posAfter, clampedTarget, steps);
            }
            if (Integer.signum(posAfter - current) == -requestedSign) {
                // A single opposite update can come from the previous pulse or the lens settling.
                // Confirm the direction using small pulses before adapting to the lens.
                directionMismatches++;
                speedPenalty = Math.max(speedPenalty, 2);
                if (directionMismatches >= 2 && !reversedMapping) {
                    reversedMapping = true;
                    directionMismatches = 0;
                } else if (directionMismatches >= 3) {
                    JSONObject result = buildResult(false, false, false, posAfter, clampedTarget, steps);
                    return result.put("unstableDirection", true);
                }
            } else if (posAfter != current) {
                directionMismatches = 0;
                if (Integer.signum(clampedTarget - posAfter) == -requestedSign) {
                    speedPenalty = Math.min(6, speedPenalty + 1);
                } else if (absDist > 8) {
                    speedPenalty = Math.max(0, speedPenalty - 1);
                }
            }
            if (posAfter == current) {
                zeroStreak++;
                if (zeroStreak >= 14) {
                    return buildResult(false, false, true, posAfter, clampedTarget, steps);
                }
            } else {
                zeroStreak = 0;
            }
        }

        synchronized (m_focus) {
            int clampedTarget = (m_focus.max1 > 0) ? Math.max(0, Math.min(m_focus.max1, targetPos)) : targetPos;
            boolean converged = Math.abs(clampedTarget - m_focus.v1) <= tolerance;
            return buildResult(converged, false, false, m_focus.v1, clampedTarget, steps);
        }
    }

    private JSONObject autoFocusAt(double nx, double ny) throws Exception {
        final long myGen = m_focus.nextGeneration();
        final double clampedX = Math.max(0.0, Math.min(1.0, nx));
        final double clampedY = Math.max(0.0, Math.min(1.0, ny));

        synchronized (m_focusOpLock) {
            if (!m_focus.isCurrent(myGen)) {
                JSONObject res = new JSONObject();
                res.put("focused", false);
                res.put("superseded", true);
                res.put("timedOut", false);
                return res;
            }

            final Object afLock = new Object();
            final boolean[] afDone = new boolean[]{false};
            final boolean[] afSuccess = new boolean[]{false};
            final int[] targetPoint = new int[]{0, 0};
            final CameraEx[] focusCamera = new CameraEx[1];
            final JSONObject[] originalSettings = new JSONObject[1];
            final int[] settingsRevision = new int[1];

            try {
                driveOnMain(new Runnable() {
                    @Override
                    public void run() {
                        CameraEx cam = m_activity.getCameraEx();
                        if (cam == null || !m_focus.isCurrent(myGen)) return;
                        focusCamera[0] = cam;
                        settingsRevision[0] = m_focusSettingsRevision;
                        try {
                            originalSettings[0] = FocusSettings.read(cam);
                        } catch (Exception error) {
                            throw new IllegalStateException("无法读取当前对焦设置", error);
                        }
                        if (originalSettings[0].optString("mode").length() == 0) {
                            throw new IllegalStateException("相机未提供当前对焦模式");
                        }

                        try {
                            cam.stopPreviewMagnification();
                        } catch (Throwable ignored) {
                        }

                        Camera normal = cam.getNormalCamera();
                        try {
                            normal.cancelAutoFocus();
                        } catch (Throwable ignored) {
                        }

                        int left = -784, right = 785, top = -819, bottom = 820;
                        try {
                            CameraEx.FocusAreaInfos[] infos = cam.getFocusAreaInfos(0);
                            if (infos != null) {
                                for (CameraEx.FocusAreaInfos info : infos) {
                                    if (info != null && CameraEx.ParametersModifier.FOCUS_AREA_MODE_FLEX_SPOT.equals(info.focusAreaMode)
                                            && info.rectInfos != null && info.rectInfos.length > 0 && info.rectInfos[0] != null && info.rectInfos[0].rect != null) {
                                        left = info.rectInfos[0].rect.left;
                                        right = info.rectInfos[0].rect.right;
                                        top = info.rectInfos[0].rect.top;
                                        bottom = info.rectInfos[0].rect.bottom;
                                        break;
                                    }
                                }
                            }
                        } catch (Throwable ignored) {
                        }

                        int fx = (int) Math.round(left + clampedX * (right - left));
                        int fy = (int) Math.round(top + clampedY * (bottom - top));
                        targetPoint[0] = fx;
                        targetPoint[1] = fy;

                        Camera.Parameters params = cam.createEmptyParameters();
                        CameraEx.ParametersModifier modifier = cam.createParametersModifier(params);
                        params.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                        modifier.setFocusAreaMode(CameraEx.ParametersModifier.FOCUS_AREA_MODE_FLEX_SPOT);
                        modifier.setAutoFocusMode(CameraEx.ParametersModifier.AUTO_FOCUS_MODE_AF_S);
                        modifier.setFocusPoint(fx, fy);
                        normal.setParameters(params);

                        normal.autoFocus(new Camera.AutoFocusCallback() {
                            @Override
                            public void onAutoFocus(boolean success, Camera camera) {
                                synchronized (afLock) {
                                    afSuccess[0] = success;
                                    afDone[0] = true;
                                    afLock.notifyAll();
                                }
                            }
                        });
                    }
                });

                long deadline = System.currentTimeMillis() + 5000L;
                synchronized (afLock) {
                    while (!afDone[0] && m_focus.isCurrent(myGen)) {
                        long rem = deadline - System.currentTimeMillis();
                        if (rem <= 0) break;
                        try {
                            afLock.wait(Math.min(rem, 100L));
                        } catch (InterruptedException ignored) {
                            break;
                        }
                    }
                }
            } finally {
                try {
                    driveOnMain(new Runnable() {
                        @Override
                        public void run() {
                            CameraEx cam = m_activity.getCameraEx();
                            if (cam == null || cam != focusCamera[0] || originalSettings[0] == null
                                    || settingsRevision[0] != m_focusSettingsRevision) return;
                            Camera normal = cam.getNormalCamera();
                            if (!afDone[0] || !m_focus.isCurrent(myGen)) {
                                try {
                                    normal.cancelAutoFocus();
                                } catch (Throwable ignored) {
                                }
                            }
                            try {
                                Camera.Parameters restore = cam.createEmptyParameters();
                                CameraEx.ParametersModifier modifier = cam.createParametersModifier(restore);
                                String mode = originalSettings[0].optString("mode");
                                if (mode.length() == 0) return;
                                FocusSettings.applyMode(restore, modifier, mode);
                                String area = originalSettings[0].optString("area");
                                if (!CameraEx.ParametersModifier.FOCUS_MODE_MANUAL.equals(mode)
                                        && area.length() > 0) modifier.setFocusAreaMode(area);
                                String size = originalSettings[0].optString("spotSize");
                                if (size.length() > 0) modifier.setFocusAreaFlexibleSpotSize(size);
                                normal.setParameters(restore);
                            } catch (Throwable error) {
                                com.github.ma1co.pmcademo.app.Logger.error("Restore focus settings: " + error);
                            } finally {
                                m_activity.scheduleControlRead();
                            }
                        }
                    });
                } catch (Throwable ignored) {
                }
            }

            int pos;
            synchronized (m_focus) {
                pos = m_focus.v1;
            }
            JSONObject res = new JSONObject();
            res.put("focused", afSuccess[0]);
            res.put("superseded", !m_focus.isCurrent(myGen));
            res.put("timedOut", !afDone[0]);
            res.put("x", targetPoint[0]);
            res.put("y", targetPoint[1]);
            res.put("position", pos);
            return res;
        }
    }

    private JSONObject setAperture(int targetF100) throws Exception {
        final long myGen = m_aperture.nextGeneration();
        int stalled = 0;
        int steps = 0;

        for (int i = 0; i < 45; i++) {
            int current;
            int minAp;
            int maxAp;
            boolean has;
            synchronized (m_aperture) {
                if (!m_aperture.isCurrent(myGen)) {
                    return buildResult(false, true, false, m_aperture.v1, targetF100, steps);
                }
                has = m_aperture.hasValue;
                current = m_aperture.v1;
                minAp = m_aperture.min1;
                maxAp = m_aperture.max1;
            }
            if (!has) {
                throw new IllegalStateException("Aperture state unavailable");
            }

            int clamped = targetF100;
            if (minAp > 0 && clamped < minAp) clamped = minAp;
            if (maxAp > 0 && clamped > maxAp) clamped = maxAp;

            int diff = clamped - current;
            if (Math.abs(diff) <= 15) {
                return buildResult(true, false, false, current, clamped, steps);
            }

            final boolean inc = diff > 0;
            long seqBefore = m_aperture.getUpdateSeq();
            driveOnMain(new Runnable() {
                @Override
                public void run() {
                    CameraEx cam = m_activity.getCameraEx();
                    if (cam != null) {
                        if (inc) cam.incrementAperture();
                        else cam.decrementAperture();
                    }
                }
            });
            steps++;

            m_aperture.waitForChange(myGen, seqBefore, 250);
            if (!m_aperture.isCurrent(myGen)) {
                synchronized (m_aperture) {
                    return buildResult(false, true, false, m_aperture.v1, clamped, steps);
                }
            }
            if (m_aperture.getUpdateSeq() == seqBefore) {
                stalled++;
                if (stalled >= 2) {
                    synchronized (m_aperture) {
                        return buildResult(false, false, true, m_aperture.v1, clamped, steps);
                    }
                }
            } else {
                stalled = 0;
            }
        }

        synchronized (m_aperture) {
            return buildResult(false, false, false, m_aperture.v1, targetF100, steps);
        }
    }

    private JSONObject setShutterSpeed(int targetN, int targetD) throws Exception {
        if (targetN <= 0 || targetD <= 0) {
            throw new IllegalArgumentException("Shutter numerator and denominator must be > 0");
        }
        final double targetSec = (double) targetN / (double) targetD;
        final long myGen = m_shutter.nextGeneration();
        int stalled = 0;
        int steps = 0;

        for (int i = 0; i < 65; i++) {
            int curN;
            int curD;
            boolean has;
            synchronized (m_shutter) {
                if (!m_shutter.isCurrent(myGen)) {
                    return buildShutterResult(false, true, false, m_shutter.v1, m_shutter.v2, targetN, targetD, steps);
                }
                has = m_shutter.hasValue;
                curN = m_shutter.v1;
                curD = m_shutter.v2;
            }
            if (!has || curD <= 0) {
                throw new IllegalStateException("Shutter speed state unavailable");
            }

            double curSec = (double) curN / (double) curD;
            double ratio = curSec / targetSec;
            // Within ~10% (less than 1/6 EV) is considered converged
            if (ratio >= 0.90 && ratio <= 1.11) {
                return buildShutterResult(true, false, false, curN, curD, targetN, targetD, steps);
            }

            // incrementShutterSpeed -> faster (smaller seconds), decrementShutterSpeed -> slower (larger seconds)
            final boolean faster = curSec > targetSec;
            long seqBefore = m_shutter.getUpdateSeq();
            driveOnMain(new Runnable() {
                @Override
                public void run() {
                    CameraEx cam = m_activity.getCameraEx();
                    if (cam != null) {
                        if (faster) cam.incrementShutterSpeed();
                        else cam.decrementShutterSpeed();
                    }
                }
            });
            steps++;

            m_shutter.waitForChange(myGen, seqBefore, 250);
            if (!m_shutter.isCurrent(myGen)) {
                synchronized (m_shutter) {
                    return buildShutterResult(false, true, false, m_shutter.v1, m_shutter.v2, targetN, targetD, steps);
                }
            }
            if (m_shutter.getUpdateSeq() == seqBefore) {
                stalled++;
                if (stalled >= 2) {
                    synchronized (m_shutter) {
                        return buildShutterResult(false, false, true, m_shutter.v1, m_shutter.v2, targetN, targetD, steps);
                    }
                }
            } else {
                stalled = 0;
            }
        }

        synchronized (m_shutter) {
            return buildShutterResult(false, false, false, m_shutter.v1, m_shutter.v2, targetN, targetD, steps);
        }
    }

    private JSONObject setZoomPosition(int targetPos, int tolerance) throws Exception {
        final long myGen = m_zoom.nextGeneration();
        int stalled = 0;
        int steps = 0;

        for (int i = 0; i < 80; i++) {
            int current;
            boolean has;
            synchronized (m_zoom) {
                if (!m_zoom.isCurrent(myGen)) {
                    return buildResult(false, true, false, m_zoom.v1, targetPos, steps);
                }
                has = m_zoom.hasValue;
                current = m_zoom.v1;
            }
            if (!has) {
                throw new IllegalStateException("Zoom state unavailable");
            }

            int diff = targetPos - current;
            if (Math.abs(diff) <= tolerance) {
                return buildResult(true, false, false, current, targetPos, steps);
            }

            final int dir = diff > 0 ? CameraEx.ZOOM_DIRECTION_TELE : CameraEx.ZOOM_DIRECTION_WIDE;
            final int speed = Math.abs(diff) > 12 ? 2 : 1;
            long seqBefore = m_zoom.getUpdateSeq();
            driveOnMain(new Runnable() {
                @Override
                public void run() {
                    CameraEx cam = m_activity.getCameraEx();
                    if (cam != null) cam.startZoom(dir, speed);
                }
            });
            steps++;

            m_zoom.waitForChange(myGen, seqBefore, 120);
            driveOnMain(new Runnable() {
                @Override
                public void run() {
                    CameraEx cam = m_activity.getCameraEx();
                    if (cam != null) cam.stopZoom();
                }
            });
            if (!m_zoom.isCurrent(myGen)) {
                synchronized (m_zoom) {
                    return buildResult(false, true, false, m_zoom.v1, targetPos, steps);
                }
            }
            if (m_zoom.getUpdateSeq() == seqBefore) {
                stalled++;
                if (stalled >= 2) {
                    synchronized (m_zoom) {
                        return buildResult(false, false, true, m_zoom.v1, targetPos, steps);
                    }
                }
            } else {
                stalled = 0;
            }
        }

        synchronized (m_zoom) {
            return buildResult(Math.abs(targetPos - m_zoom.v1) <= tolerance, false, false, m_zoom.v1, targetPos, steps);
        }
    }

    private void driveOnMain(final Runnable r) throws Exception {
        m_activity.runOnMainSync(new Callable<Void>() {
            @Override
            public Void call() {
                r.run();
                return null;
            }
        }, MacroServerActivity.MAIN_THREAD_TIMEOUT_MS);
    }

    private static JSONObject buildResult(boolean converged, boolean superseded, boolean stalled, int actual, int target, int steps) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("converged", converged);
            obj.put("superseded", superseded);
            obj.put("stalled", stalled);
            obj.put("position", actual);
            obj.put("target", target);
            obj.put("steps", steps);
        } catch (Throwable ignored) {
        }
        return obj;
    }

    private static JSONObject buildShutterResult(boolean converged, boolean superseded, boolean stalled, int n, int d, int targetN, int targetD, int steps) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("converged", converged);
            obj.put("superseded", superseded);
            obj.put("stalled", stalled);
            obj.put("currentShutterSpeed_n", n);
            obj.put("currentShutterSpeed_d", d);
            obj.put("target_n", targetN);
            obj.put("target_d", targetD);
            obj.put("steps", steps);
        } catch (Throwable ignored) {
        }
        return obj;
    }
}
