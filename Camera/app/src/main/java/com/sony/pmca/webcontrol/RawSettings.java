package com.sony.pmca.webcontrol;

import android.hardware.Camera;
import com.sony.scalar.hardware.CameraEx;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** RAW recording settings for the current camera session. */
final class RawSettings {
    private static final String COMPRESSED = CameraEx.ParametersModifier.RAW_REC_MODE_COMPRESS;
    private static final String UNCOMPRESSED = CameraEx.ParametersModifier.RAW_REC_MODE_UNCOMPRESS;

    private RawSettings() {}

    static JSONObject read(CameraEx camera) throws Exception {
        CameraEx.ParametersModifier modifier = currentModifier(camera);
        JSONArray options = new JSONArray();
        List<?> supported = supportedModes(modifier);
        if (supported != null) {
            if (supported.contains(COMPRESSED)) options.put(COMPRESSED);
            if (supported.contains(UNCOMPRESSED)) options.put(UNCOMPRESSED);
        }
        String mode = null;
        try {
            mode = modifier.getRawRecMode();
        } catch (RuntimeException ignored) {
        } catch (LinkageError ignored) {
        }
        return new JSONObject().put("mode", mode == null ? JSONObject.NULL : mode).put("options", options);
    }

    static void write(CameraEx camera, String mode) {
        if (!COMPRESSED.equals(mode) && !UNCOMPRESSED.equals(mode)) {
            throw new IllegalArgumentException("无效的 RAW 类型");
        }
        List<?> supported = supportedModes(currentModifier(camera));
        if (supported == null || !supported.contains(mode)) {
            throw new IllegalArgumentException("相机当前模式不支持所选 RAW 类型");
        }
        Camera.Parameters params = camera.createEmptyParameters();
        camera.createParametersModifier(params).setRawRecMode(mode);
        camera.getNormalCamera().setParameters(params);
    }

    private static CameraEx.ParametersModifier currentModifier(CameraEx camera) {
        return camera.createParametersModifier(camera.getNormalCamera().getParameters());
    }

    private static List<?> supportedModes(CameraEx.ParametersModifier modifier) {
        try {
            return modifier.getSupportedRawRecModes();
        } catch (RuntimeException ignored) {
            return null;
        } catch (LinkageError ignored) {
            return null;
        }
    }
}
