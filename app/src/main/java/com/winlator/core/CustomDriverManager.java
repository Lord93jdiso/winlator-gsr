package com.winlator.core;

import android.content.Context;

import com.winlator.xenvironment.RootFS;
import com.winlator.MainActivity;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Imports plain custom graphics driver archives (from the Adreno driver community)
 * into the Winlator component system.
 *
 * Accepted containers: .zip, .tzst (tar+zstd), .tar.zst, .xz (tar+xz), .tar.xz
 * Accepted contents: libvulkan_*.so (+ optional ICD .json manifests), optionally
 * with an existing usr/lib + usr/share/vulkan/icd.d layout.
 *
 * The result is stored as a regular Turnip component (installed_components/turnip/turnip-<name>.tzst)
 * and becomes selectable in every Turnip driver settings dialog and in Settings -> Snapdragon driver.
 */
public abstract class CustomDriverManager {
    private static final byte[] ZIP_MAGIC = {0x50, 0x4B};
    private static final byte[] ZSTD_MAGIC = {(byte)0x28, (byte)0xB5, (byte)0x2F, (byte)0xFD};
    private static final byte[] XZ_MAGIC = {(byte)0xFD, '7', 'z', 'X', 'Z', 0x00};

    public static File getTurnipComponentDir(Context context) {
        File dir = new File(context.getFilesDir(), "/installed_components/turnip");
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    public static boolean importPlainDriverPack(Context context, File source, String requestedName) {
        if (source == null || !source.isFile()) return false;

        File tempDir = new File(context.getCacheDir(), "custom_driver_import");
        FileUtils.delete(tempDir);
        tempDir.mkdirs();

        try {
            byte[] magic = readMagic(source, 6);
            boolean extracted = false;

            if (startsWith(magic, ZIP_MAGIC)) {
                extracted = ZipUtils.extract(source, tempDir);
            }
            else if (startsWith(magic, ZSTD_MAGIC)) {
                extracted = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, source, tempDir);
            }
            else if (startsWith(magic, XZ_MAGIC)) {
                extracted = TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, source, tempDir);
            }
            else {
                // try all formats as a fallback
                extracted = ZipUtils.extract(source, tempDir);
                if (!extracted) extracted = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, source, tempDir);
                if (!extracted) extracted = TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, source, tempDir);
            }

            if (!extracted) return false;

            File stagingDir = new File(context.getCacheDir(), "custom_driver_staging");
            FileUtils.delete(stagingDir);
            stagingDir.mkdirs();

            File driverFile = findFirst(tempDir, (file, name) -> name.startsWith("libvulkan") && name.endsWith(".so"));
            if (driverFile == null) driverFile = findFirst(tempDir, (file, name) -> name.endsWith(".so"));
            if (driverFile == null) return false;

            String libraryName = driverFile.getName();
            FileUtils.copy(driverFile, new File(stagingDir, "libvulkan_freedreno.so"));
            if (!libraryName.equals("libvulkan_freedreno.so")) {
                FileUtils.copy(driverFile, new File(stagingDir, libraryName));
            }

            // ICD manifest
            File icdFile = null;
            File[] jsonCandidates = findFiles(tempDir, (file, name) -> name.endsWith(".json"));
            for (File candidate : jsonCandidates) {
                String content = FileUtils.readString(candidate);
                if (content != null && content.contains("ICD")) {
                    icdFile = candidate;
                    break;
                }
            }

            File icdDir = new File(stagingDir, "usr/share/vulkan/icd.d");
            icdDir.mkdirs();
            if (icdFile != null) {
                FileUtils.copy(icdFile, new File(icdDir, "freedreno_icd.aarch64.json"));
            }
            else {
                String rootfsLibPath = new File(RootFS.find(context).getRootDir(), "lib").getPath();
                JSONObject icd = new JSONObject();
                JSONObject icdData = new JSONObject();
                try {
                    icdData.put("api_version", "1.3.289");
                    icdData.put("library_arch", "64");
                    icdData.put("library_path", rootfsLibPath + "/libvulkan_freedreno.so");
                    icd.put("ICD", icdData);
                    icd.put("file_format_version", "1.0.1");
                    FileUtils.writeString(new File(icdDir, "freedreno_icd.aarch64.json"), icd.toString());
                }
                catch (Exception e) {}
            }

            // the library must live in usr/lib inside the rootfs
            File libDir = new File(stagingDir, "usr/lib");
            libDir.mkdirs();
            FileUtils.moveFile(new File(stagingDir, "libvulkan_freedreno.so"), new File(libDir, "libvulkan_freedreno.so"));
            File extraLib = new File(stagingDir, libraryName);
            if (extraLib.isFile() && !libraryName.equals("libvulkan_freedreno.so")) {
                FileUtils.moveFile(extraLib, new File(libDir, libraryName));
            }

            String name = StringUtils.parseIdentifier(requestedName != null && !requestedName.isEmpty() ? requestedName : "custom");
            name = name.replaceAll("[^a-z0-9._-]", "-");
            File destination = new File(getTurnipComponentDir(context), "turnip-"+name+".tzst");
            if (destination.isFile()) destination.delete();

            boolean compressed = TarCompressorUtils.compress(TarCompressorUtils.Type.ZSTD, stagingDir, destination, MainActivity.CONTAINER_PATTERN_COMPRESSION_LEVEL);
            FileUtils.delete(tempDir);
            FileUtils.delete(stagingDir);
            return compressed && destination.isFile();
        }
        catch (Exception e) {
            FileUtils.delete(tempDir);
            return false;
        }
    }

    private interface FileMatcher {
        boolean matches(File file, String name);
    }

    private static File findFirst(File dir, FileMatcher matcher) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File file : files) {
            if (file.isDirectory()) {
                File result = findFirst(file, matcher);
                if (result != null) return result;
            }
            else if (matcher.matches(file, file.getName())) return file;
        }
        return null;
    }

    private static File[] findFiles(File dir, FileMatcher matcher) {
        java.util.ArrayList<File> result = new java.util.ArrayList<>();
        collectFiles(dir, matcher, result);
        return result.toArray(new File[0]);
    }

    private static void collectFiles(File dir, FileMatcher matcher, java.util.ArrayList<File> result) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) collectFiles(file, matcher, result);
            else if (matcher.matches(file, file.getName())) result.add(file);
        }
    }

    private static boolean moveFile(File srcFile, File dstFile) {
        if (srcFile == null || !srcFile.isFile()) return false;
        if (FileUtils.copy(srcFile, dstFile)) return FileUtils.delete(srcFile);
        return false;
    }

    private static byte[] readMagic(File file, int length) throws IOException {
        try (InputStream is = new FileInputStream(file)) {
            byte[] buffer = new byte[length];
            int read = is.read(buffer);
            return read == length ? buffer : java.util.Arrays.copyOf(buffer, Math.max(read, 0));
        }
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data == null || data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) return false;
        }
        return true;
    }
}
