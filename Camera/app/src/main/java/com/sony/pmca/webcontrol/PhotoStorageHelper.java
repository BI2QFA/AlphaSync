package com.sony.pmca.webcontrol;

import android.os.Environment;

import com.github.ma1co.pmcademo.app.Logger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Helper for browsing SD card photo directories and reading the camera crash/debug log.
 */
public final class PhotoStorageHelper {

    private static final Comparator<File> NAME_DESC = new Comparator<File>() {
        @Override
        public int compare(File a, File b) {
            return b.getName().compareToIgnoreCase(a.getName());
        }
    };

    private PhotoStorageHelper() {
    }

    private static boolean isPhotoFile(File f) {
        if (!f.isFile()) return false;
        String name = f.getName().toLowerCase();
        return name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".arw");
    }

    public static File resolveSdDir(String relDir) {
        File external = Environment.getExternalStorageDirectory();
        File dcim = new File(external, "DCIM");
        File baseRoot = (dcim.exists() && dcim.isDirectory()) ? dcim : external;
        if (relDir == null || relDir.trim().isEmpty() || "/".equals(relDir.trim())) {
            return baseRoot;
        }
        String clean = relDir.trim().replace("\\", "/");
        while (clean.startsWith("/")) clean = clean.substring(1);
        if (clean.contains("..")) {
            return baseRoot;
        }
        if (clean.startsWith("DCIM/")) {
            clean = clean.substring("DCIM/".length());
        } else if ("DCIM".equals(clean)) {
            return baseRoot;
        }
        return clean.isEmpty() ? baseRoot : new File(baseRoot, clean);
    }

    public static JSONObject listPhotoDirectory(String relDir, int limit) {
        JSONObject out = new JSONObject();
        JSONArray dirsArr = new JSONArray();
        JSONArray photosArr = new JSONArray();
        try {
            File external = Environment.getExternalStorageDirectory();
            File dcim = new File(external, "DCIM");
            File baseRoot = (dcim.exists() && dcim.isDirectory()) ? dcim : external;
            File target = resolveSdDir(relDir);

            List<File> subDirs = new ArrayList<File>();
            List<File> subPhotos = new ArrayList<File>();
            File[] entries = target.listFiles();
            if (entries != null) {
                for (File f : entries) {
                    if (f.isDirectory()) {
                        subDirs.add(f);
                    } else if (isPhotoFile(f)) {
                        subPhotos.add(f);
                    }
                }
            }

            Collections.sort(subDirs, NAME_DESC);
            Collections.sort(subPhotos, NAME_DESC);

            for (File d : subDirs) {
                JSONObject item = new JSONObject();
                item.put("name", d.getName());
                item.put("path", toRelativeDcimPath(baseRoot, d));
                dirsArr.put(item);
            }

            int maxPhotos = (limit > 0 && subPhotos.size() > limit) ? limit : subPhotos.size();
            for (int i = 0; i < maxPhotos; i++) {
                File f = subPhotos.get(i);
                JSONObject item = new JSONObject();
                item.put("name", f.getName());
                item.put("path", f.getAbsolutePath().replace("\\", "/"));
                item.put("size", f.length());
                photosArr.put(item);
            }

            out.put("ok", true);
            out.put("dir", toRelativeDcimPath(baseRoot, target));
            out.put("totalPhotosInDir", subPhotos.size());
            out.put("dirs", dirsArr);
            out.put("photos", photosArr);
        } catch (Throwable t) {
            Logger.error("listPhotoDirectory error: " + t.toString());
            try {
                out.put("ok", false);
                out.put("error", t.toString());
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static String toRelativeDcimPath(File baseRoot, File target) {
        String rootPath = baseRoot.getAbsolutePath();
        String targetPath = target.getAbsolutePath();
        if (targetPath.equals(rootPath)) {
            return "";
        }
        if (targetPath.startsWith(rootPath + "/")) {
            return targetPath.substring(rootPath.length() + 1);
        }
        return target.getName();
    }

    public static File getLatestPhotoFile() {
        try {
            File root = resolveSdDir(null);
            File[] entries = root.listFiles();
            if (entries == null) return null;

            List<File> subDirs = new ArrayList<File>();
            List<File> directPhotos = new ArrayList<File>();
            for (File f : entries) {
                if (f.isDirectory()) {
                    subDirs.add(f);
                } else if (isPhotoFile(f)) {
                    directPhotos.add(f);
                }
            }
            Collections.sort(subDirs, NAME_DESC);
            for (File d : subDirs) {
                File[] files = d.listFiles();
                if (files == null) continue;
                File best = null;
                for (File f : files) {
                    if (isPhotoFile(f) && (best == null || compareCaptureFiles(f, best) > 0)) {
                        best = f;
                    }
                }
                if (best != null) return best;
            }
            if (!directPhotos.isEmpty()) {
                File best = directPhotos.get(0);
                for (File f : directPhotos) {
                    if (compareCaptureFiles(f, best) > 0) best = f;
                }
                return best;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static int compareCaptureFiles(File first, File second) {
        String a = first.getName();
        String b = second.getName();
        int aDot = a.lastIndexOf('.');
        int bDot = b.lastIndexOf('.');
        int order = a.substring(0, aDot).compareToIgnoreCase(b.substring(0, bDot));
        if (order != 0) return order;
        boolean aRaw = a.substring(aDot).equalsIgnoreCase(".arw");
        boolean bRaw = b.substring(bDot).equalsIgnoreCase(".arw");
        return aRaw == bRaw ? 0 : aRaw ? -1 : 1;
    }

    public static File findStoredPhoto(String directory, String name) {
        if (directory == null || name == null || !name.matches("(?i)[A-Z0-9_-]+\\.(jpg|jpeg|arw)")) return null;
        String normalized = directory.replace('\\', '/').replaceAll("/+$", "");
        String folder = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (!folder.matches("[0-9]{3}[A-Za-z0-9_-]+")) return null;
        File file = new File(new File(resolveSdDir(null), folder), name);
        return isPhotoFile(file) ? file : null;
    }

    public static String getCrashLogText() {
        Logger.flush();
        File f = Logger.getFile();
        if (!f.exists()) {
            return "Log file does not exist yet: " + f.getAbsolutePath();
        }
        StringBuilder sb = new StringBuilder();
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(f));
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
        } catch (Throwable t) {
            sb.append("Error reading log: ").append(t.toString());
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return sb.toString();
    }
}
