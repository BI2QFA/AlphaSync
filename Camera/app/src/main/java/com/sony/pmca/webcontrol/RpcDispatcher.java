package com.sony.pmca.webcontrol;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Camera;
import android.os.BatteryManager;
import android.util.Pair;

import com.sony.scalar.hardware.CameraEx;
import com.sony.scalar.sysutil.ScalarProperties;
import com.sony.scalar.sysutil.didep.Settings;
import com.sony.scalar.sysutil.didep.Temperature;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * General reflection-based RPC dispatcher for Sony CameraEx, ParametersModifier,
 * standard android.hardware.Camera.Parameters, and whitelisted static Sony Scalar classes.
 */
public class RpcDispatcher {

    private static final Set<String> CAMERA_BLACKLIST = new HashSet<String>(Arrays.asList(
            "open", "reopen", "release", "getNormalCamera",
            "createEmptyParameters", "createParametersModifier",
            "createGammaTable", "createGammmaTable",
            "withSupportedParameters", "disableSettingChangedTypes", "enableSettingChangedTypes",
            "setAutoPictureReviewControl", "setRecordingMedia", "shiftFocusPosition"
    ));

    private static final Set<String> MODIFIER_BLACKLIST = new HashSet<String>(Arrays.asList(
            "tryIgnoreInhibit"
    ));

    private static final Set<String> PARAMS_WHITELIST = new HashSet<String>(Arrays.asList(
            "flatten", "get", "getInt", "set",
            "getExposureCompensation", "setExposureCompensation",
            "getMinExposureCompensation", "getMaxExposureCompensation", "getExposureCompensationStep",
            "getFocusMode", "setFocusMode", "getSupportedFocusModes",
            "getWhiteBalance", "setWhiteBalance", "getSupportedWhiteBalance",
            "getSceneMode", "setSceneMode", "getSupportedSceneModes"
    ));

    private final Map<String, List<Method>> m_cameraMethods = new HashMap<String, List<Method>>();
    private final Map<String, List<Method>> m_modifierMethods = new HashMap<String, List<Method>>();
    private final Map<String, List<Method>> m_paramsMethods = new HashMap<String, List<Method>>();
    private final Map<String, Map<String, List<Method>>> m_staticMethods = new HashMap<String, Map<String, List<Method>>>();

    public RpcDispatcher() {
        indexClassMethods(CameraEx.class, m_cameraMethods, true);
        indexClassMethods(CameraEx.ParametersModifier.class, m_modifierMethods, false);
        indexParamsMethods();

        registerStaticClass("static:ScalarProperties", ScalarProperties.class, new HashSet<String>(Arrays.asList(
                "getFirmwareVersion", "getString", "getInt", "getIntArray", "getByteArray", "getSupportedPictureSizes"
        )));
        registerStaticClass("static:Settings", Settings.class, null);
    }

    private void indexClassMethods(Class<?> cls, Map<String, List<Method>> out, boolean isCameraEx) {
        for (Method m : cls.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers()) || Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            String name = m.getName();
            if (isCameraEx) {
                if (CAMERA_BLACKLIST.contains(name)) continue;
                if (name.endsWith("Listener") || name.endsWith("Callback")) continue;
                if (hasInterfaceOrCallbackParam(m)) continue;
            } else {
                if (MODIFIER_BLACKLIST.contains(name)) continue;
                if (!(name.startsWith("is") || name.startsWith("get") || name.startsWith("set"))) continue;
            }
            addMethod(out, m);
        }
    }

    private void indexParamsMethods() {
        for (Method m : Camera.Parameters.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers()) || Modifier.isStatic(m.getModifiers())) continue;
            if (PARAMS_WHITELIST.contains(m.getName())) {
                addMethod(m_paramsMethods, m);
            }
        }
    }

    private void registerStaticClass(String targetKey, Class<?> cls, Set<String> allowList) {
        Map<String, List<Method>> map = new HashMap<String, List<Method>>();
        for (Method m : cls.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers()) || !Modifier.isStatic(m.getModifiers())) continue;
            if (allowList != null && !allowList.contains(m.getName())) continue;
            if (hasInterfaceOrCallbackParam(m)) continue;
            addMethod(map, m);
        }
        m_staticMethods.put(targetKey, map);
    }

    private static void addMethod(Map<String, List<Method>> map, Method m) {
        List<Method> list = map.get(m.getName());
        if (list == null) {
            list = new ArrayList<Method>();
            map.put(m.getName(), list);
        }
        list.add(m);
    }

    private static boolean hasInterfaceOrCallbackParam(Method m) {
        for (Class<?> pt : m.getParameterTypes()) {
            if (pt.isInterface() && pt != List.class) return true;
        }
        return false;
    }

    // --- Introspection (/api/describe) ---

    public JSONObject describe(String targetFilter, String queryFilter) {
        JSONObject root = new JSONObject();
        try {
            root.put("ok", true);
            JSONObject targets = new JSONObject();

            if (matchesTarget(targetFilter, "camera")) {
                JSONArray arr = describeMethodMap(m_cameraMethods, queryFilter);
                // Add synthetic camera helper
                if (matchesQuery(queryFilter, "capabilities")) {
                    JSONObject cap = new JSONObject();
                    cap.put("name", "capabilities");
                    cap.put("params", new JSONArray());
                    cap.put("returnType", "JSONObject");
                    arr.put(cap);
                }
                targets.put("camera", arr);
            }
            if (matchesTarget(targetFilter, "modifier")) {
                targets.put("modifier", describeMethodMap(m_modifierMethods, queryFilter));
            }
            if (matchesTarget(targetFilter, "params")) {
                targets.put("params", describeMethodMap(m_paramsMethods, queryFilter));
            }
            for (String staticKey : m_staticMethods.keySet()) {
                if (matchesTarget(targetFilter, staticKey)) {
                    targets.put(staticKey, describeMethodMap(m_staticMethods.get(staticKey), queryFilter));
                }
            }
            if (matchesTarget(targetFilter, "static:Temperature")) {
                JSONArray arr = new JSONArray();
                if (matchesQuery(queryFilter, "getStatus")) {
                    JSONObject item = new JSONObject();
                    item.put("name", "getStatus");
                    item.put("params", new JSONArray());
                    item.put("returnType", "Temperature.Status");
                    arr.put(item);
                }
                targets.put("static:Temperature", arr);
            }
            if (matchesTarget(targetFilter, "static:System")) {
                JSONArray arr = new JSONArray();
                if (matchesQuery(queryFilter, "getBattery")) {
                    JSONObject item = new JSONObject();
                    item.put("name", "getBattery");
                    item.put("params", new JSONArray());
                    item.put("returnType", "JSONObject");
                    arr.put(item);
                }
                targets.put("static:System", arr);
            }
            if (matchesTarget(targetFilter, "highlevel")) {
                JSONArray arr = new JSONArray();
                addSyntheticMethod(arr, queryFilter, "setFocusPosition", new String[]{"int", "int"}, "JSONObject");
                addSyntheticMethod(arr, queryFilter, "autoFocusAt", new String[]{"double", "double"}, "JSONObject");
                addSyntheticMethod(arr, queryFilter, "setAperture", new String[]{"int"}, "JSONObject");
                addSyntheticMethod(arr, queryFilter, "setShutterSpeed", new String[]{"int", "int"}, "JSONObject");
                addSyntheticMethod(arr, queryFilter, "setZoomPosition", new String[]{"int", "int"}, "JSONObject");
                targets.put("highlevel", arr);
            }

            root.put("targets", targets);
        } catch (Throwable ignored) {
        }
        return root;
    }

    private static boolean matchesTarget(String filter, String target) {
        return filter == null || filter.trim().isEmpty() || filter.trim().equalsIgnoreCase(target);
    }

    private static boolean matchesQuery(String query, String name) {
        return query == null || query.trim().isEmpty() || name.toLowerCase().contains(query.trim().toLowerCase());
    }

    private static void addSyntheticMethod(JSONArray arr, String queryFilter, String name, String[] paramTypes, String returnType) throws Exception {
        if (!matchesQuery(queryFilter, name)) return;
        JSONObject item = new JSONObject();
        item.put("name", name);
        JSONArray params = new JSONArray();
        for (String p : paramTypes) {
            params.put(p);
        }
        item.put("params", params);
        item.put("returnType", returnType);
        arr.put(item);
    }

    private JSONArray describeMethodMap(Map<String, List<Method>> map, String queryFilter) {
        List<Method> all = new ArrayList<Method>();
        for (List<Method> list : map.values()) {
            all.addAll(list);
        }
        Collections.sort(all, new Comparator<Method>() {
            @Override
            public int compare(Method a, Method b) {
                return a.getName().compareTo(b.getName());
            }
        });
        JSONArray arr = new JSONArray();
        for (Method m : all) {
            if (!matchesQuery(queryFilter, m.getName())) continue;
            try {
                JSONObject item = new JSONObject();
                item.put("name", m.getName());
                item.put("returnType", m.getReturnType().getSimpleName());
                JSONArray pArr = new JSONArray();
                for (Class<?> pt : m.getParameterTypes()) {
                    pArr.put(pt.getSimpleName());
                }
                item.put("params", pArr);
                arr.put(item);
            } catch (Throwable ignored) {
            }
        }
        return arr;
    }

    // --- Execution (/api/call) ---

    public JSONObject executeBatchOrSingle(Context ctx, CameraEx cam, JSONObject payload) throws Exception {
        if (payload.has("calls")) {
            JSONArray calls = payload.getJSONArray("calls");
            JSONArray results = new JSONArray();
            Camera.Parameters pendingModifierWrite = null;
            CameraEx.ParametersModifier pendingModifier = null;

            for (int i = 0; i < calls.length(); i++) {
                JSONObject call = calls.getJSONObject(i);
                String target = call.optString("target", "");
                String method = call.optString("method", "");
                JSONArray args = call.optJSONArray("args");

                boolean isModifierSet = "modifier".equals(target) && method.startsWith("set");
                if (!isModifierSet && pendingModifierWrite != null) {
                    cam.getNormalCamera().setParameters(pendingModifierWrite);
                    pendingModifierWrite = null;
                    pendingModifier = null;
                }

                if (isModifierSet) {
                    if (pendingModifierWrite == null) {
                        pendingModifierWrite = cam.createEmptyParameters();
                        pendingModifier = cam.createParametersModifier(pendingModifierWrite);
                    }
                    Method m = resolveMethod(m_modifierMethods, method, args);
                    Object[] javaArgs = convertJsonArgs(m.getParameterTypes(), args);
                    m.invoke(pendingModifier, javaArgs);
                    JSONObject item = new JSONObject();
                    item.put("ok", true);
                    item.put("target", target);
                    item.put("method", method);
                    item.put("result", JSONObject.NULL);
                    results.put(item);
                } else {
                    JSONObject singleRes = executeSingle(ctx, cam, target, method, args);
                    results.put(singleRes);
                }
            }

            if (pendingModifierWrite != null) {
                cam.getNormalCamera().setParameters(pendingModifierWrite);
            }

            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("results", results);
            return out;
        } else {
            String target = payload.optString("target", "");
            String method = payload.optString("method", "");
            JSONArray args = payload.optJSONArray("args");
            return executeSingle(ctx, cam, target, method, args);
        }
    }

    private JSONObject executeSingle(Context ctx, CameraEx cam, String target, String method, JSONArray args) throws Exception {
        Object resultObj;

        if ("camera".equals(target)) {
            if ("capabilities".equals(method)) {
                resultObj = buildCapabilities(cam);
            } else if (!m_cameraMethods.containsKey(method) && m_modifierMethods.containsKey(method)) {
                // Graceful fallback: method belongs to modifier (e.g. setISOSensitivity)
                Method m = resolveMethod(m_modifierMethods, method, args);
                Object[] javaArgs = convertJsonArgs(m.getParameterTypes(), args);
                if (method.startsWith("set")) {
                    Camera.Parameters p = cam.createEmptyParameters();
                    CameraEx.ParametersModifier mod = cam.createParametersModifier(p);
                    resultObj = m.invoke(mod, javaArgs);
                    cam.getNormalCamera().setParameters(p);
                } else {
                    Camera.Parameters p = cam.getNormalCamera().getParameters();
                    CameraEx.ParametersModifier mod = cam.createParametersModifier(p);
                    resultObj = m.invoke(mod, javaArgs);
                }
            } else {
                Method m = resolveMethod(m_cameraMethods, method, args);
                Object[] javaArgs = convertJsonArgs(m.getParameterTypes(), args);
                resultObj = m.invoke(cam, javaArgs);
            }
        } else if ("modifier".equals(target)) {
            Method m = resolveMethod(m_modifierMethods, method, args);
            Object[] javaArgs = convertJsonArgs(m.getParameterTypes(), args);
            if (method.startsWith("set")) {
                Camera.Parameters p = cam.createEmptyParameters();
                CameraEx.ParametersModifier mod = cam.createParametersModifier(p);
                resultObj = m.invoke(mod, javaArgs);
                cam.getNormalCamera().setParameters(p);
            } else {
                Camera.Parameters p = cam.getNormalCamera().getParameters();
                CameraEx.ParametersModifier mod = cam.createParametersModifier(p);
                resultObj = m.invoke(mod, javaArgs);
            }
        } else if ("params".equals(target)) {
            Method m = resolveMethod(m_paramsMethods, method, args);
            Object[] javaArgs = convertJsonArgs(m.getParameterTypes(), args);
            if (method.startsWith("set")) {
                Camera.Parameters p = cam.createEmptyParameters();
                resultObj = m.invoke(p, javaArgs);
                cam.getNormalCamera().setParameters(p);

                if ("setExposureCompensation".equals(method) && ctx instanceof MacroServerActivity) {
                    try {
                        MacroServerActivity act = (MacroServerActivity) ctx;
                        Camera.Parameters cur = cam.getNormalCamera().getParameters();
                        JSONObject ecObj = new JSONObject();
                        ecObj.put("index", cur.getExposureCompensation());
                        ecObj.put("step", (double) cur.getExposureCompensationStep());
                        ecObj.put("min", cur.getMinExposureCompensation());
                        ecObj.put("max", cur.getMaxExposureCompensation());
                        act.getEventHub().setState("exposureCompensation", ecObj);
                        act.getEventHub().emit("SettingChanged", new JSONObject());
                    } catch (Throwable ignored) {
                    }
                }
            } else {
                Camera.Parameters p = cam.getNormalCamera().getParameters();
                resultObj = m.invoke(p, javaArgs);
            }
        } else if ("static:Temperature".equals(target)) {
            if (!"getStatus".equals(method)) {
                throw new NoSuchMethodException("Unknown method on static:Temperature: " + method);
            }
            Temperature temp = new Temperature();
            try {
                resultObj = temp.getStatus();
            } finally {
                try {
                    temp.release();
                } catch (Throwable ignored) {
                }
            }
        } else if ("static:System".equals(target)) {
            if (!"getBattery".equals(method)) {
                throw new NoSuchMethodException("Unknown method on static:System: " + method);
            }
            resultObj = getBatteryInfo(ctx);
        } else if (m_staticMethods.containsKey(target)) {
            Map<String, List<Method>> map = m_staticMethods.get(target);
            Method m = resolveMethod(map, method, args);
            Object[] javaArgs = convertJsonArgs(m.getParameterTypes(), args);
            resultObj = m.invoke(null, javaArgs);
        } else {
            throw new IllegalArgumentException("Unknown target: " + target);
        }

        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("target", target);
        out.put("method", method);
        out.put("result", toJsonValue(resultObj, 0));
        return out;
    }

    private JSONObject buildCapabilities(CameraEx cam) throws Exception {
        JSONObject flags = new JSONObject();
        Camera.Parameters p = cam.getNormalCamera().getParameters();
        CameraEx.ParametersModifier mod = cam.createParametersModifier(p);
        for (List<Method> list : m_modifierMethods.values()) {
            for (Method m : list) {
                String name = m.getName();
                if (name.startsWith("is") && m.getParameterTypes().length == 0 && m.getReturnType() == boolean.class) {
                    try {
                        flags.put(name, m.invoke(mod));
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        JSONObject res = new JSONObject();
        res.put("lens", toJsonValue(cam.getLensInfo(), 0));
        res.put("flags", flags);
        return res;
    }

    private JSONObject getBatteryInfo(Context ctx) throws Exception {
        JSONObject obj = new JSONObject();
        Intent battery = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery != null) {
            int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            obj.put("level", level);
            obj.put("scale", scale);
            obj.put("percent", scale > 0 ? (level * 100 / scale) : level);
            obj.put("status", status);
            obj.put("plugged", plugged);
        }
        return obj;
    }

    // --- Overload Resolution & Type Conversion ---

    private Method resolveMethod(Map<String, List<Method>> map, String name, JSONArray args) throws NoSuchMethodException {
        List<Method> candidates = map.get(name);
        if (candidates == null || candidates.isEmpty()) {
            throw new NoSuchMethodException("Method not found: " + name);
        }
        int argCount = (args == null) ? 0 : args.length();
        Method best = null;
        int bestScore = -1;

        for (Method m : candidates) {
            Class<?>[] pts = m.getParameterTypes();
            if (pts.length != argCount) continue;
            int score = scoreCompatibility(pts, args);
            if (score > bestScore) {
                bestScore = score;
                best = m;
            }
        }
        if (best != null) {
            return best;
        }
        StringBuilder sigs = new StringBuilder();
        for (Method m : candidates) {
            if (sigs.length() > 0) sigs.append("; ");
            sigs.append(m.getName()).append("(");
            Class<?>[] pts = m.getParameterTypes();
            for (int i = 0; i < pts.length; i++) {
                if (i > 0) sigs.append(", ");
                sigs.append(pts[i].getSimpleName());
            }
            sigs.append(")");
        }
        throw new IllegalArgumentException("No matching overload for " + name + " with " + argCount + " args. Candidates: " + sigs);
    }

    private static int scoreCompatibility(Class<?>[] pts, JSONArray args) {
        if (pts.length == 0) return 100;
        int score = 0;
        for (int i = 0; i < pts.length; i++) {
            Object val = args.opt(i);
            Class<?> pt = pts[i];
            if (val == null || val == JSONObject.NULL) {
                if (!pt.isPrimitive()) score += 5;
            } else if ((pt == int.class || pt == Integer.class) && val instanceof Number) {
                score += 10;
            } else if ((pt == boolean.class || pt == Boolean.class) && val instanceof Boolean) {
                score += 10;
            } else if (pt == String.class && val instanceof String) {
                score += 10;
            } else if (pt == Pair.class && val instanceof JSONArray) {
                score += 10;
            } else if (pt.isArray() && val instanceof JSONArray) {
                score += 10;
            } else if (val instanceof JSONObject && !pt.isPrimitive() && pt != String.class) {
                score += 8;
            } else {
                score += 1;
            }
        }
        return score;
    }

    private static Object[] convertJsonArgs(Class<?>[] pts, JSONArray args) throws Exception {
        if (pts.length == 0) return new Object[0];
        Object[] out = new Object[pts.length];
        for (int i = 0; i < pts.length; i++) {
            Object raw = (args != null && i < args.length()) ? args.get(i) : JSONObject.NULL;
            out[i] = convertJsonVal(pts[i], raw);
        }
        return out;
    }

    private static Object convertJsonVal(Class<?> type, Object val) throws Exception {
        if (val == null || val == JSONObject.NULL) {
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            if (type == boolean.class) return false;
            if (type == double.class) return 0.0;
            if (type == float.class) return 0.0f;
            return null;
        }
        if (type == int.class || type == Integer.class) {
            if (val instanceof Number) return ((Number) val).intValue();
            return Integer.parseInt(String.valueOf(val).trim());
        }
        if (type == long.class || type == Long.class) {
            if (val instanceof Number) return ((Number) val).longValue();
            return Long.parseLong(String.valueOf(val).trim());
        }
        if (type == float.class || type == Float.class) {
            if (val instanceof Number) return ((Number) val).floatValue();
            return Float.parseFloat(String.valueOf(val).trim());
        }
        if (type == double.class || type == Double.class) {
            if (val instanceof Number) return ((Number) val).doubleValue();
            return Double.parseDouble(String.valueOf(val).trim());
        }
        if (type == boolean.class || type == Boolean.class) {
            if (val instanceof Boolean) return val;
            String s = String.valueOf(val).trim().toLowerCase();
            return "true".equals(s) || "1".equals(s) || "yes".equals(s);
        }
        if (type == String.class) {
            return String.valueOf(val);
        }
        if (type == Pair.class && val instanceof JSONArray) {
            JSONArray ja = (JSONArray) val;
            int a = ja.length() > 0 ? ja.getInt(0) : 0;
            int b = ja.length() > 1 ? ja.getInt(1) : 0;
            return new Pair<Integer, Integer>(a, b);
        }
        if (type == int[].class && val instanceof JSONArray) {
            JSONArray ja = (JSONArray) val;
            int[] arr = new int[ja.length()];
            for (int i = 0; i < ja.length(); i++) {
                arr[i] = ja.getInt(i);
            }
            return arr;
        }
        if (type == byte[].class && val instanceof JSONArray) {
            JSONArray ja = (JSONArray) val;
            byte[] arr = new byte[ja.length()];
            for (int i = 0; i < ja.length(); i++) {
                arr[i] = (byte) ja.getInt(i);
            }
            return arr;
        }
        if (type == String[].class && val instanceof JSONArray) {
            JSONArray ja = (JSONArray) val;
            String[] arr = new String[ja.length()];
            for (int i = 0; i < ja.length(); i++) {
                arr[i] = ja.getString(i);
            }
            return arr;
        }
        if (val instanceof JSONObject) {
            Constructor<?> ctor = type.getConstructor();
            Object pojo = ctor.newInstance();
            JSONObject jo = (JSONObject) val;
            Iterator<String> keys = jo.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                try {
                    Field f = type.getField(k);
                    f.set(pojo, convertJsonVal(f.getType(), jo.get(k)));
                } catch (NoSuchFieldException ignored) {
                }
            }
            return pojo;
        }
        return val;
    }

    // --- Reflective JSON Serialization ---

    public static Object toJsonValue(Object val) {
        return toJsonValue(val, 0);
    }

    private static Object toJsonValue(Object val, int depth) {
        if (val == null) {
            return JSONObject.NULL;
        }
        if (val instanceof JSONObject || val instanceof JSONArray) {
            return val;
        }
        if (val instanceof Number || val instanceof Boolean || val instanceof String) {
            return val;
        }
        if (depth > 4) {
            return String.valueOf(val);
        }
        if (val instanceof Pair) {
            Pair<?, ?> p = (Pair<?, ?>) val;
            JSONArray arr = new JSONArray();
            arr.put(toJsonValue(p.first, depth + 1));
            arr.put(toJsonValue(p.second, depth + 1));
            return arr;
        }
        if (val instanceof List) {
            JSONArray arr = new JSONArray();
            for (Object item : (List<?>) val) {
                arr.put(toJsonValue(item, depth + 1));
            }
            return arr;
        }
        if (val.getClass().isArray()) {
            int len = Array.getLength(val);
            JSONArray arr = new JSONArray();
            for (int i = 0; i < len; i++) {
                arr.put(toJsonValue(Array.get(val, i), depth + 1));
            }
            return arr;
        }
        // Serialize public non-static fields of Sony structs (LensInfo, Temperature.Status, etc.)
        Field[] fields = val.getClass().getFields();
        if (fields.length > 0) {
            JSONObject obj = new JSONObject();
            for (Field f : fields) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                try {
                    obj.put(f.getName(), toJsonValue(f.get(val), depth + 1));
                } catch (Throwable ignored) {
                }
            }
            if (obj.length() > 0) {
                return obj;
            }
        }
        return String.valueOf(val);
    }
}
