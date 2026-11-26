package com.sony.pmca.webcontrol;

import android.hardware.Camera;
import com.sony.scalar.hardware.CameraEx;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read and change only focus settings offered by the camera in its current mode. */
final class FocusSettings {
    private FocusSettings() {}

    static JSONObject read(CameraEx camera) throws Exception {
        Camera.Parameters params = camera.getNormalCamera().getParameters();
        return read(params, camera.createParametersModifier(params));
    }

    static JSONObject read(Camera.Parameters params, CameraEx.ParametersModifier modifier) throws Exception {
        Set<String> modes = new LinkedHashSet<String>();
        List<String> baseModes = params.getSupportedFocusModes();
        String mode = params.getFocusMode();
        if (baseModes != null) {
            for (String value : baseModes) {
                if (!Camera.Parameters.FOCUS_MODE_AUTO.equals(value)) modes.add(value);
            }
        }
        if (Camera.Parameters.FOCUS_MODE_AUTO.equals(mode) ||
                (baseModes != null && baseModes.contains(Camera.Parameters.FOCUS_MODE_AUTO))) {
            boolean hasSonyAutoMode = false;
            try {
                List<?> afModes = modifier.getSupportedAutoFocusModes();
                if (afModes != null) for (Object af : afModes) {
                    if (af instanceof String && ((String) af).length() > 0) {
                        modes.add("auto:" + af);
                        hasSonyAutoMode = true;
                    }
                }
                if (Camera.Parameters.FOCUS_MODE_AUTO.equals(mode)) {
                    String af = modifier.getAutoFocusMode();
                    if (af != null && af.length() > 0) mode = "auto:" + af;
                }
            } catch (Throwable ignored) {
                // Some firmware only implements the base Android focus-mode interface.
            }
            if (!hasSonyAutoMode || Camera.Parameters.FOCUS_MODE_AUTO.equals(mode)) {
                modes.add(Camera.Parameters.FOCUS_MODE_AUTO);
            }
        }
        JSONObject result = new JSONObject().put("mode", mode).put("modes", new JSONArray(modes));
        try {
            result.put("area", modifier.getFocusAreaMode());
        } catch (Throwable ignored) {
        }
        try {
            List<?> areas = modifier.getSupportedFocusAreaModes();
            result.put("areas", areas == null ? new JSONArray() : new JSONArray(areas));
        } catch (Throwable ignored) {
            // Focus area APIs can be absent or restricted by the selected mode.
        }
        if (isFlexibleSpot(result)) {
            try {
                result.put("spotSize", modifier.getFocusAreaFlexibleSpotSize());
                List<?> sizes = modifier.getSupportedFocusAreaFlexibleSpotSizes();
                if (sizes != null) result.put("spotSizes", new JSONArray(sizes));
            } catch (Throwable ignored) {
                // Some firmware reports the area without offering a writable size.
            }
        }
        return result;
    }

    private static boolean isFlexibleSpot(JSONObject settings) {
        return !CameraEx.ParametersModifier.FOCUS_MODE_MANUAL.equals(settings.optString("mode"))
                && CameraEx.ParametersModifier.FOCUS_AREA_MODE_FLEX_SPOT.equals(settings.optString("area"));
    }

    static void write(CameraEx camera, String key, String value) throws Exception {
        boolean mode = "mode".equals(key);
        boolean size = "spotSize".equals(key);
        if (!mode && !size && !"area".equals(key)) throw new IllegalArgumentException("未知对焦设置");
        JSONObject current = read(camera);
        if (size && !isFlexibleSpot(current)) {
            throw new IllegalStateException("请先切换到自动对焦并选择自由点");
        }
        JSONArray choices = current.optJSONArray(mode ? "modes" : size ? "spotSizes" : "areas");
        boolean supported = false;
        if (choices != null) for (int i = 0; i < choices.length(); i++) {
            if (value.equals(choices.optString(i))) {
                supported = true;
                break;
            }
        }
        if (!supported) throw new IllegalArgumentException("当前机身、镜头或拍摄模式不支持此设置");
        Camera.Parameters update = camera.createEmptyParameters();
        CameraEx.ParametersModifier modifier = camera.createParametersModifier(update);
        if (size) {
            modifier.setFocusAreaFlexibleSpotSize(value);
        } else if (!mode) {
            String currentMode = current.optString("mode");
            if (CameraEx.ParametersModifier.FOCUS_MODE_MANUAL.equals(currentMode)) {
                throw new IllegalStateException("请先切换到自动对焦，再调整对焦区域");
            }
            if (currentMode.startsWith("auto:")) {
                update.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                modifier.setAutoFocusMode(currentMode.substring("auto:".length()));
            }
            modifier.setFocusAreaMode(value);
        } else {
            applyMode(update, modifier, value);
        }
        camera.getNormalCamera().setParameters(update);
    }

    static void applyMode(Camera.Parameters params, CameraEx.ParametersModifier modifier, String value) {
        if (value.startsWith("auto:")) {
            params.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            modifier.setAutoFocusMode(value.substring("auto:".length()));
        } else {
            params.setFocusMode(value);
        }
    }
}
