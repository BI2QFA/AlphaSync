package com.sony.pmca.webcontrol;

import com.sony.scalar.hardware.CameraEx;
import io.github.bi2qfa.alphasync.ThumbnailExtractor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeoutException;

import fi.iki.elonen.NanoHTTPD;

public class MacroHttpServer extends NanoHTTPD {
    public static final int DEFAULT_PORT = 8080;

    private static final Response.IStatus STATUS_GATEWAY_TIMEOUT = new Response.IStatus() {
        @Override
        public int getRequestStatus() {
            return 504;
        }

        @Override
        public String getDescription() {
            return "504 Gateway Timeout";
        }
    };

    private static final Response.IStatus STATUS_SERVICE_UNAVAILABLE = new Response.IStatus() {
        @Override
        public int getRequestStatus() {
            return 503;
        }

        @Override
        public String getDescription() {
            return "503 Service Unavailable";
        }
    };

    private static final Response.IStatus STATUS_NOT_ACCEPTABLE = new Response.IStatus() {
        @Override
        public int getRequestStatus() {
            return 406;
        }

        @Override
        public String getDescription() {
            return "406 Not Acceptable";
        }
    };

    private static final int MAX_SSE_CLIENTS = 3;

    private static final class SseClient {
        final String cid;
        volatile boolean closed = false;

        SseClient(String cid) {
            this.cid = cid;
        }
    }

    private final MacroServerActivity m_activity;
    private final ArrayList<SseClient> m_sseClients = new ArrayList<SseClient>(MAX_SSE_CLIENTS);

    public MacroHttpServer(MacroServerActivity activity) {
        this(activity, DEFAULT_PORT);
    }

    public MacroHttpServer(MacroServerActivity activity, int port) {
        super(port);
        m_activity = activity;
    }

    @Override
    public void stop() {
        synchronized (m_sseClients) {
            for (int i = 0; i < m_sseClients.size(); i++) {
                m_sseClients.get(i).closed = true;
            }
            m_sseClients.clear();
        }
        m_activity.getEventHub().wakeAll();
        super.stop();
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();
        Map<String, String> params = session.getParms();

        if (Method.OPTIONS.equals(method)) {
            return addCors(new Response(Response.Status.OK, MIME_PLAINTEXT, "OK"));
        }

        try {
            if ("/".equals(uri) || "/index.html".equals(uri)) {
                String acceptEncoding = session.getHeaders().get("accept-encoding");
                boolean acceptsGzip = acceptEncoding != null && acceptEncoding.toLowerCase(Locale.US).contains("gzip");
                return serveAssetHtml("index.html.gzip", acceptsGzip);
            }

            if ("/api/describe".equals(uri)) {
                JSONObject desc = m_activity.getRpcDispatcher().describe(params.get("target"), params.get("q"));
                return jsonOk(desc.toString());
            }

            if ("/api/capture".equals(uri)) {
                if (!Method.POST.equals(method)) {
                    return jsonError(Response.Status.METHOD_NOT_ALLOWED, "请使用拍摄按钮");
                }
                JSONObject result = m_activity.runOnMainSync(new Callable<JSONObject>() {
                    @Override
                    public JSONObject call() throws Exception {
                        m_activity.capturePhoto();
                        return new JSONObject().put("ok", true);
                    }
                }, MacroServerActivity.MAIN_THREAD_TIMEOUT_MS);
                return jsonOk(result.toString());
            }

            if ("/api/call".equals(uri)) {
                if (!Method.POST.equals(method)) {
                    return jsonError(Response.Status.METHOD_NOT_ALLOWED, "Use POST /api/call with JSON body");
                }
                final CameraEx cam = m_activity.getCameraEx();
                if (cam == null) {
                    return jsonError(Response.Status.INTERNAL_ERROR, "Camera not initialized");
                }
                final JSONObject body = parseJsonBody(session);
                if (!body.has("calls") && "highlevel".equals(body.optString("target", ""))) {
                    String rpcMethod = body.optString("method", "");
                    JSONArray args = body.optJSONArray("args");
                    JSONObject result = m_activity.getGoalController().execute(rpcMethod, args);
                    JSONObject out = new JSONObject();
                    out.put("ok", true);
                    out.put("target", "highlevel");
                    out.put("method", rpcMethod);
                    out.put("result", result);
                    return jsonOk(out.toString());
                }
                JSONObject res = m_activity.runOnMainSync(new Callable<JSONObject>() {
                    @Override
                    public JSONObject call() throws Exception {
                        if ("rawSettings".equals(body.optString("target"))) {
                            return handleRawSettings(cam, body);
                        }
                        if ("focusSettings".equals(body.optString("target")) && "get".equals(body.optString("method"))) {
                            if (m_activity.getCameraEx() != cam || m_activity.isCaptureInProgress()) {
                                throw new IllegalStateException("相机正在处理拍摄，请稍后读取对焦设置");
                            }
                            return new JSONObject().put("ok", true).put("result", FocusSettings.read(cam));
                        }
                        if ("focusSettings".equals(body.optString("target")) && "set".equals(body.optString("method"))) {
                            JSONArray args = body.getJSONArray("args");
                            if (m_activity.getCameraEx() != cam) {
                                throw new IllegalStateException("相机尚未准备好");
                            }
                            if (m_activity.isCaptureInProgress()) {
                                throw new IllegalStateException("相机正在拍摄，请稍后调整对焦");
                            }
                            try {
                                FocusSettings.write(cam, args.getString(0), args.getString(1));
                                m_activity.getGoalController().onFocusSettingsChanged();
                            } catch (RuntimeException rejected) {
                                com.github.ma1co.pmcademo.app.Logger.error("Focus setting rejected: " + rejected);
                                String detail = rejected instanceof IllegalArgumentException || rejected instanceof IllegalStateException
                                        ? rejected.getMessage() : "相机拒绝了该对焦设置，请检查当前拍摄模式";
                                return new JSONObject().put("ok", false).put("error", detail);
                            } finally {
                                m_activity.scheduleControlRead();
                            }
                            return new JSONObject().put("ok", true);
                        }
                        JSONObject response = m_activity.getRpcDispatcher().executeBatchOrSingle(m_activity, cam, body);
                        if (body.optString("method").startsWith("set") || body.has("calls")) {
                            m_activity.scheduleControlRead();
                        }
                        return response;
                    }
                }, MacroServerActivity.MAIN_THREAD_TIMEOUT_MS);
                return jsonOk(res.toString());
            }

            if ("/api/state".equals(uri)) {
                return jsonOk(m_activity.getEventHub().getStateSnapshot().toString());
            }

            if ("/api/events".equals(uri)) {
                long since = parseLong(params.get("since"), 0L);
                int timeout = parseInt(params.get("timeout"), 10);
                JSONObject res = m_activity.getEventHub().waitForEvents(since, timeout);
                return jsonOk(res.toString());
            }

            if ("/api/events/stream".equals(uri)) {
                long since = parseLong(params.get("since"), -1L);
                long lastId = parseLong(session.getHeaders().get("last-event-id"), -1L);
                if (lastId > since) {
                    since = lastId;
                }
                if (since < 0L) {
                    since = 0L;
                }
                String cid = params.get("cid");
                SseClient client = acquireSseClient(cid);
                return new SseStreamResponse(client, since);
            }

            if ("/api/events/close".equals(uri)) {
                String cid = params.get("cid");
                boolean closed = closeSseClientByCid(cid);
                JSONObject res = new JSONObject();
                res.put("closed", closed);
                return jsonOk(res.toString());
            }

            if ("/api/liveview.mjpeg".equals(uri)) {
                String cid = params.get("cid");
                LiveViewManager.MjpegClient client = m_activity.getLiveViewManager().acquireMjpegClient(cid);
                return new MjpegStreamResponse(client);
            }

            if ("/api/liveview/close".equals(uri)) {
                String cid = params.get("cid");
                boolean closed = m_activity.getLiveViewManager().closeMjpegClient(cid);
                JSONObject res = new JSONObject();
                res.put("closed", closed);
                return jsonOk(res.toString());
            }

            if ("/api/liveview.jpg".equals(uri)) {
                byte[] jpeg = m_activity.getLiveViewManager().getLatestLiveViewJpeg();
                if (jpeg == null) {
                    return addCors(new Response(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "No preview frame yet"));
                }
                Response res = new Response(Response.Status.OK, "image/jpeg", new ByteArrayInputStream(jpeg));
                res.addHeader("Cache-Control", "no-store, no-cache, must-revalidate");
                return addCors(res);
            }

            if ("/api/photos/latest".equals(uri)) {
                boolean inline = "1".equals(params.get("inline"));
                String storedName = params.get("storedName");
                File latest = storedName == null
                        ? PhotoStorageHelper.getLatestPhotoFile()
                        : PhotoStorageHelper.findStoredPhoto(params.get("storedDir"), storedName);
                if (latest != null && latest.exists() && latest.isFile()) {
                    if ("1".equals(params.get("preview"))) return servePhotoPreview(latest);
                    return serveFileDownload(latest, inline);
                }
                return jsonError(Response.Status.NOT_FOUND, "No captured photo available yet");
            }

            if ("/api/photos".equals(uri)) {
                int limit = parseInt(params.get("limit"), 100);
                String dir = params.get("dir");
                JSONObject root = PhotoStorageHelper.listPhotoDirectory(dir, limit);
                return jsonOk(root.toString());
            }

            if ("/api/photos/download".equals(uri)) {
                boolean inline = "1".equals(params.get("inline"));
                String path = params.get("path");
                if (path == null || path.trim().isEmpty()) {
                    path = params.get("file");
                }
                File target = (path != null && !path.trim().isEmpty()) ? new File(path.trim()) : PhotoStorageHelper.getLatestPhotoFile();
                if (target == null || !target.exists() || !target.isFile()) {
                    return jsonError(Response.Status.NOT_FOUND, "Photo file not found");
                }
                if ("1".equals(params.get("preview"))) return servePhotoPreview(target);
                return serveFileDownload(target, inline);
            }

            if ("/api/log".equals(uri)) {
                return addCors(new Response(Response.Status.OK, MIME_PLAINTEXT, PhotoStorageHelper.getCrashLogText()));
            }

            if ("/api/debug".equals(uri)) {
                return jsonOk(buildDebugJson().toString());
            }

            if ("/api/bench".equals(uri)) {
                long bytes = parseLong(params.get("bytes"), 1048576L);
                if (bytes < 1L) bytes = 1L;
                if (bytes > 10L * 1024L * 1024L) bytes = 10L * 1024L * 1024L;
                Response res = new Response(Response.Status.OK, "application/octet-stream", new BenchInputStream(bytes));
                res.addHeader("Content-Length", String.valueOf(bytes));
                res.addHeader("Cache-Control", "no-store");
                return addCors(res);
            }

            return jsonError(Response.Status.NOT_FOUND, "Unknown endpoint: " + uri);
        } catch (TimeoutException te) {
            return jsonError(STATUS_GATEWAY_TIMEOUT, te.getMessage());
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            return jsonError(Response.Status.INTERNAL_ERROR,
                    cause.getMessage() == null ? cause.toString() : cause.getMessage());
        }
    }

    private JSONObject handleRawSettings(CameraEx camera, JSONObject body) throws Exception {
        if (m_activity.getCameraEx() != camera || m_activity.isCaptureInProgress()) {
            return new JSONObject().put("ok", false).put("error", "相机正在处理拍摄或尚未准备好，请稍后再试");
        }
        String method = body.optString("method");
        if ("get".equals(method)) {
            return new JSONObject().put("ok", true).put("result", RawSettings.read(camera));
        }
        if (!"set".equals(method)) {
            return new JSONObject().put("ok", false).put("error", "未知的 RAW 设置操作");
        }
        try {
            RawSettings.write(camera, body.getJSONArray("args").getString(0));
            return new JSONObject().put("ok", true);
        } catch (IllegalArgumentException rejected) {
            return new JSONObject().put("ok", false).put("error", rejected.getMessage());
        } catch (RuntimeException rejected) {
            return new JSONObject().put("ok", false).put("error", "相机拒绝切换 RAW 类型");
        } catch (LinkageError unavailable) {
            return new JSONObject().put("ok", false).put("error", "相机未提供 RAW 类型切换接口");
        } finally {
            m_activity.scheduleControlRead();
        }
    }

    private JSONObject buildDebugJson() throws Exception {
        JSONObject root = new JSONObject();
        root.put("ok", true);
        root.put("uptimeMs", android.os.SystemClock.uptimeMillis());
        root.put("threads", Thread.activeCount());

        Runtime rt = Runtime.getRuntime();
        JSONObject mem = new JSONObject();
        long total = rt.totalMemory();
        long free = rt.freeMemory();
        mem.put("usedBytes", total - free);
        mem.put("freeBytes", free);
        mem.put("totalBytes", total);
        mem.put("maxBytes", rt.maxMemory());
        root.put("memory", mem);

        String loadavg = readSingleLine("/proc/loadavg");
        if (loadavg != null) {
            root.put("loadavg", loadavg);
        }

        long pingStart = System.currentTimeMillis();
        try {
            m_activity.runOnMainSync(new Callable<Void>() {
                @Override
                public Void call() {
                    return null;
                }
            }, 2000L);
            root.put("mainThreadPingMs", System.currentTimeMillis() - pingStart);
            root.put("mainThreadBlocked", false);
        } catch (TimeoutException te) {
            root.put("mainThreadPingMs", System.currentTimeMillis() - pingStart);
            root.put("mainThreadBlocked", true);
        } catch (Throwable t) {
            root.put("mainThreadPingError", t.toString());
        }

        root.put("liveview", m_activity.getLiveViewManager().getDebugStats());
        root.put("eventSeq", m_activity.getEventHub().getStateSnapshot().optLong("seq", 0L));
        return root;
    }

    private static String readSingleLine(String path) {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(path));
            return br.readLine();
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (br != null) {
                try { br.close(); } catch (IOException ignored) {}
            }
        }
    }

    private static final class BenchInputStream extends InputStream {
        private static final byte[] CHUNK = new byte[8192];
        static {
            for (int i = 0; i < CHUNK.length; i++) {
                CHUNK[i] = (byte) (i & 0xFF);
            }
        }

        private long m_remaining;

        BenchInputStream(long totalBytes) {
            m_remaining = totalBytes;
        }

        @Override
        public int available() {
            return (int) Math.min(m_remaining, Integer.MAX_VALUE);
        }

        @Override
        public int read() {
            if (m_remaining <= 0) return -1;
            m_remaining--;
            return 0x55;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (m_remaining <= 0) return -1;
            int toCopy = (int) Math.min(m_remaining, Math.min(len, CHUNK.length));
            System.arraycopy(CHUNK, 0, b, off, toCopy);
            m_remaining -= toCopy;
            return toCopy;
        }
    }

    private JSONObject parseJsonBody(IHTTPSession session) throws Exception {
        Map<String, String> headers = session.getHeaders();
        String lenStr = headers.get("content-length");
        if (lenStr == null) {
            lenStr = headers.get("Content-Length");
        }
        int contentLength = parseInt(lenStr, 0);
        if (contentLength <= 0) {
            return new JSONObject();
        }
        InputStream is = session.getInputStream();
        byte[] buf = new byte[contentLength];
        int offset = 0;
        while (offset < contentLength) {
            int n = is.read(buf, offset, contentLength - offset);
            if (n < 0) break;
            offset += n;
        }
        String postData = new String(buf, 0, offset, "UTF-8").trim();
        if (!postData.isEmpty()) {
            return new JSONObject(postData);
        }
        return new JSONObject();
    }

    private Response serveAssetHtml(String assetName, boolean acceptsGzip) {
        if (!acceptsGzip) {
            Response res = new Response(STATUS_NOT_ACCEPTABLE, MIME_PLAINTEXT, "Client must support gzip (Accept-Encoding: gzip)");
            res.addHeader("Vary", "Accept-Encoding");
            return addCors(res);
        }
        try {
            InputStream is = m_activity.getAssets().open(assetName);
            Response res = new Response(Response.Status.OK, "text/html; charset=utf-8", is);
            res.addHeader("Content-Encoding", "gzip");
            res.addHeader("Vary", "Accept-Encoding");
            res.addHeader("Cache-Control", "no-store, no-cache");
            return addCors(res);
        } catch (Throwable t) {
            return addCors(new Response(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Failed to load asset: " + t.toString()));
        }
    }

    private Response servePhotoPreview(File file) throws IOException, InterruptedException {
        File photo = file.getCanonicalFile();
        String root = PhotoStorageHelper.resolveSdDir(null).getCanonicalPath();
        if (!photo.getPath().startsWith(root + File.separator) || !ThumbnailExtractor.supports(photo)) {
            return jsonError(Response.Status.NOT_FOUND, "Photo file not found");
        }
        if (!photo.getName().toLowerCase(Locale.US).endsWith(".arw")) {
            Response image = serveFileDownload(photo, true);
            image.addHeader("Cache-Control", "no-store");
            return image;
        }

        ThumbnailExtractor.LAST_REQUEST_EXTRACT_AT = System.currentTimeMillis();
        ThumbnailExtractor.SD_LOCK.lockInterruptibly();
        byte[] jpeg;
        try {
            ThumbnailExtractor.PreviewBundle large = ThumbnailExtractor.extractPreviewWithExif(photo);
            jpeg = large == null ? ThumbnailExtractor.extractSmall(photo) : large.jpeg;
        } finally {
            ThumbnailExtractor.SD_LOCK.unlock();
        }
        if (jpeg == null) return jsonError(Response.Status.NOT_FOUND, "No embedded JPEG preview");
        Response image = new Response(Response.Status.OK, "image/jpeg", new ByteArrayInputStream(jpeg));
        image.addHeader("Content-Length", String.valueOf(jpeg.length));
        image.addHeader("Cache-Control", "no-store");
        return addCors(image);
    }

    private Response serveFileDownload(File file, boolean inline) {
        try {
            String lower = file.getName().toLowerCase();
            String mime = lower.endsWith(".arw") ? "application/octet-stream" : "image/jpeg";
            FileInputStream fis = new FileInputStream(file);
            Response res = new Response(Response.Status.OK, mime, fis);
            res.addHeader("Content-Disposition", (inline ? "inline" : "attachment") + "; filename=\"" + file.getName() + "\"");
            res.addHeader("Content-Length", String.valueOf(file.length()));
            return addCors(res);
        } catch (FileNotFoundException e) {
            return jsonError(Response.Status.NOT_FOUND, "File not found");
        }
    }

    private Response jsonOk(String json) {
        return addCors(new Response(Response.Status.OK, "application/json", json));
    }

    private Response jsonError(Response.IStatus status, String msg) {
        JSONObject err = new JSONObject();
        try {
            err.put("ok", false);
            err.put("error", msg != null ? msg : "unknown error");
        } catch (Throwable ignored) {
        }
        return addCors(new Response(status, "application/json", err.toString()));
    }

    private class MjpegStreamResponse extends Response {
        private final LiveViewManager.MjpegClient m_client;

        MjpegStreamResponse(LiveViewManager.MjpegClient client) {
            super(Status.OK, "multipart/x-mixed-replace; boundary=frame", "");
            m_client = client;
        }

        @Override
        protected void send(OutputStream outputStream) {
            try {
                String headers = "HTTP/1.1 200 OK\r\n"
                        + "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n"
                        + "Cache-Control: no-store, no-cache, must-revalidate\r\n"
                        + "Connection: close\r\n"
                        + "Access-Control-Allow-Origin: *\r\n"
                        + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
                        + "Access-Control-Allow-Headers: Content-Type\r\n\r\n";
                outputStream.write(headers.getBytes("UTF-8"));
                outputStream.flush();

                long lastSeq = -1;
                LiveViewManager liveView = m_activity.getLiveViewManager();
                while (true) {
                    LiveViewManager.LiveViewFrame frame = liveView.waitForNextFrame(m_client, lastSeq, 2000);
                    if (frame == null) {
                        break;
                    }
                    if (frame.seq == lastSeq) {
                        continue;
                    }
                    lastSeq = frame.seq;
                    if (frame.jpeg == null) {
                        continue;
                    }
                    String partHeader = "--frame\r\n"
                            + "Content-Type: image/jpeg\r\n"
                            + "Content-Length: " + frame.jpeg.length + "\r\n\r\n";
                    outputStream.write(partHeader.getBytes("UTF-8"));
                    outputStream.write(frame.jpeg);
                    outputStream.write("\r\n".getBytes("UTF-8"));
                    outputStream.flush();
                }
            } catch (IOException ignored) {
                // Client disconnected
            } finally {
                m_activity.getLiveViewManager().releaseMjpegClient(m_client);
            }
        }
    }

    private SseClient acquireSseClient(String cid) {
        String normalizedCid = (cid != null && !cid.trim().isEmpty()) ? cid.trim() : null;
        SseClient client = new SseClient(normalizedCid);
        boolean woke = false;
        synchronized (m_sseClients) {
            if (normalizedCid != null) {
                for (int i = m_sseClients.size() - 1; i >= 0; i--) {
                    SseClient existing = m_sseClients.get(i);
                    if (normalizedCid.equals(existing.cid)) {
                        existing.closed = true;
                        m_sseClients.remove(i);
                        woke = true;
                    }
                }
            }
            while (m_sseClients.size() >= MAX_SSE_CLIENTS) {
                SseClient oldest = m_sseClients.remove(0);
                oldest.closed = true;
                woke = true;
            }
            m_sseClients.add(client);
        }
        if (woke) {
            m_activity.getEventHub().wakeAll();
        }
        return client;
    }

    private boolean closeSseClientByCid(String cid) {
        if (cid == null || cid.trim().isEmpty()) return false;
        String target = cid.trim();
        boolean closedAny = false;
        synchronized (m_sseClients) {
            for (int i = m_sseClients.size() - 1; i >= 0; i--) {
                SseClient c = m_sseClients.get(i);
                if (target.equals(c.cid)) {
                    c.closed = true;
                    m_sseClients.remove(i);
                    closedAny = true;
                }
            }
        }
        if (closedAny) {
            m_activity.getEventHub().wakeAll();
        }
        return closedAny;
    }

    private void releaseSseClient(SseClient client) {
        synchronized (m_sseClients) {
            client.closed = true;
            m_sseClients.remove(client);
        }
    }

    private class SseStreamResponse extends Response {
        private final SseClient m_client;
        private long m_since;

        SseStreamResponse(SseClient client, long since) {
            super(Status.OK, "text/event-stream; charset=utf-8", "");
            m_client = client;
            m_since = since;
        }

        @Override
        protected void send(OutputStream outputStream) {
            try {
                String headers = "HTTP/1.1 200 OK\r\n"
                        + "Content-Type: text/event-stream; charset=utf-8\r\n"
                        + "Cache-Control: no-cache, no-store\r\n"
                        + "Connection: close\r\n"
                        + "Access-Control-Allow-Origin: *\r\n"
                        + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
                        + "Access-Control-Allow-Headers: Content-Type\r\n\r\n"
                        + "retry: 2000\n\n";
                outputStream.write(headers.getBytes("UTF-8"));
                outputStream.flush();

                CameraEventHub hub = m_activity.getEventHub();
                while (!m_client.closed) {
                    JSONObject batch = hub.waitForEvents(m_since, 8);
                    if (m_client.closed) {
                        break;
                    }
                    JSONArray events = batch.optJSONArray("events");
                    if (events != null && events.length() > 0) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < events.length(); i++) {
                            JSONObject ev = events.optJSONObject(i);
                            if (ev == null) continue;
                            long seq = ev.optLong("seq", m_since);
                            if (seq > m_since) {
                                m_since = seq;
                            }
                            sb.append("id: ").append(seq).append('\n');
                            sb.append("data: ").append(ev.toString()).append("\n\n");
                        }
                        if (sb.length() > 0) {
                            outputStream.write(sb.toString().getBytes("UTF-8"));
                            outputStream.flush();
                        }
                    } else {
                        outputStream.write(": ping\n\n".getBytes("UTF-8"));
                        outputStream.flush();
                    }
                }
            } catch (IOException ignored) {
                // Client disconnected
            } finally {
                releaseSseClient(m_client);
            }
        }
    }

    private Response addCors(Response res) {
        res.addHeader("Access-Control-Allow-Origin", "*");
        res.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        res.addHeader("Access-Control-Allow-Headers", "Content-Type");
        return res;
    }

    private static int parseInt(String s, int def) {
        if (s == null) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static long parseLong(String s, long def) {
        if (s == null) return def;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
