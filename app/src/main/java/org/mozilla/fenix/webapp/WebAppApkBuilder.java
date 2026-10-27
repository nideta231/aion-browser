/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.webapp;

import com.android.apksig.ApkSigner;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Turns the prebuilt template APK (assets/webapp-template.apk) into a per-site app: rewrites the
 * package name, label and start URL in the compiled manifest, swaps the launcher icon, adds the Gecko
 * engine files from Aion's own APK, then signs it.
 * Pure Java with no Android dependencies, so it can be tested on a desktop JVM.
 */
public final class WebAppApkBuilder {
    public static final String TEMPLATE_PACKAGE = "com.nath.aion.webapp.template0000";
    public static final String LABEL_PLACEHOLDER = "@@AION_LABEL_PLACEHOLDER_0000000000000000@@";
    public static final String START_URL_PLACEHOLDER = "@@AION_START_URL_PLACEHOLDER_000000000000@@";
    public static final String ICON_ENTRY = "res/mipmap-xxxhdpi-v4/ic_launcher.png";

    /** Gecko engine files the template leaves out; they are copied from Aion's own APK. */
    public static final String[] ENGINE_ENTRIES = {
        "assets/omni.ja",
        "lib/arm64-v8a/libxul.so",
        "lib/arm64-v8a/libmozglue.so",
        "lib/arm64-v8a/libnss3.so",
        "lib/arm64-v8a/libfreebl3.so",
        "lib/arm64-v8a/libsoftokn3.so",
        "lib/arm64-v8a/liblgpllibs.so",
        "lib/arm64-v8a/libgkcodecs.so",
        "lib/arm64-v8a/libmozavcodec.so",
        "lib/arm64-v8a/libmozavutil.so",
        "lib/arm64-v8a/libplugin-container.so",
        "lib/arm64-v8a/libclearkey.so",
        "lib/arm64-v8a/libcrashhelper.so",
        "lib/arm64-v8a/libcrashtools.so",
    };
    private static final int MIN_SDK = 26;

    private WebAppApkBuilder() {}

    /** Builds and signs the per-site APK into [output]. [iconPng] may be null to keep the template icon. */
    public static void build(
            InputStream template,
            File engineApk,
            File output,
            File workDir,
            String packageName,
            String label,
            String startUrl,
            byte[] iconPng,
            PrivateKey key,
            X509Certificate certificate) throws Exception {
        Map<String, String> replacements = new HashMap<>();
        replacements.put(TEMPLATE_PACKAGE, packageName);
        replacements.put(LABEL_PLACEHOLDER, label);
        replacements.put(START_URL_PLACEHOLDER, startUrl);

        File unsigned = new File(workDir, "webapp-unsigned.apk");
        try (ZipInputStream in = new ZipInputStream(template);
             ZipOutputStream out = new ZipOutputStream(new FileOutputStream(unsigned))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                byte[] data = readAll(in);
                if (entry.getName().equals("AndroidManifest.xml")) {
                    data = patchManifest(data, replacements);
                } else if (entry.getName().equals(ICON_ENTRY) && iconPng != null && iconPng.length > 0) {
                    data = iconPng;
                }
                writeEntry(out, entry.getName(), data, entry.getMethod() == ZipEntry.STORED);
            }
            copyEngine(engineApk, out);
        }

        ApkSigner.SignerConfig signer = new ApkSigner.SignerConfig.Builder(
                "AION", key, Collections.singletonList(certificate)).build();
        new ApkSigner.Builder(Collections.singletonList(signer))
                .setInputApk(unsigned)
                .setOutputApk(output)
                .setMinSdkVersion(MIN_SDK)
                .setV1SigningEnabled(false)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setLibraryPageAlignmentBytes(16384)
                .build()
                .sign();
        //noinspection ResultOfMethodCallIgnored
        unsigned.delete();
    }

    /**
     * Copies the engine files uncompressed, so Android maps the libraries straight from the APK
     * (extractNativeLibs=false) instead of keeping a second extracted copy on disk.
     */
    private static void copyEngine(File engineApk, ZipOutputStream out) throws IOException {
        try (ZipFile engine = new ZipFile(engineApk)) {
            byte[] buffer = new byte[1 << 16];
            for (String name : ENGINE_ENTRIES) {
                ZipEntry source = engine.getEntry(name);
                if (source == null) {
                    if (name.endsWith("libxul.so") || name.equals("assets/omni.ja")) {
                        throw new IOException("Engine file missing from Aion: " + name);
                    }
                    continue;
                }
                ZipEntry entry = new ZipEntry(name);
                entry.setTime(0L);
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(source.getSize());
                entry.setCompressedSize(source.getSize());
                entry.setCrc(source.getCrc());
                out.putNextEntry(entry);
                try (InputStream in = engine.getInputStream(source)) {
                    int read;
                    while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                }
                out.closeEntry();
            }
        }
    }

    private static void writeEntry(ZipOutputStream out, String name, byte[] data, boolean stored) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0L);
        if (stored) {
            CRC32 crc = new CRC32();
            crc.update(data);
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(data.length);
            entry.setCompressedSize(data.length);
            entry.setCrc(crc.getValue());
        } else {
            entry.setMethod(ZipEntry.DEFLATED);
        }
        out.putNextEntry(entry);
        out.write(data);
        out.closeEntry();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) != -1) buffer.write(chunk, 0, read);
        return buffer.toByteArray();
    }

    /**
     * Rewrites the string pool of a compiled (AXML) manifest, replacing whole strings found in
     * [replacements]. The pool is re-encoded as UTF-16 so any length and any script works.
     */
    public static byte[] patchManifest(byte[] axml, Map<String, String> replacements) {
        ByteBuffer in = ByteBuffer.wrap(axml).order(ByteOrder.LITTLE_ENDIAN);
        if (in.getShort(0) != 0x0003) throw new IllegalArgumentException("Not a binary XML file");
        int poolStart = in.getShort(2) & 0xFFFF;
        if (in.getShort(poolStart) != 0x0001) throw new IllegalArgumentException("String pool not found");
        int poolHeaderSize = in.getShort(poolStart + 2) & 0xFFFF;
        int poolSize = in.getInt(poolStart + 4);
        int count = in.getInt(poolStart + 8);
        int styleCount = in.getInt(poolStart + 12);
        int flags = in.getInt(poolStart + 16);
        int stringsStart = in.getInt(poolStart + 20);
        if (styleCount != 0) throw new IllegalArgumentException("Styled strings are not supported");
        boolean utf8 = (flags & 0x100) != 0;

        List<String> strings = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int p = poolStart + stringsStart + in.getInt(poolStart + poolHeaderSize + 4 * i);
            String value;
            if (utf8) {
                int b = axml[p] & 0xFF;
                p += (b & 0x80) != 0 ? 2 : 1; // character count, unused
                int length = axml[p] & 0xFF;
                p++;
                if ((length & 0x80) != 0) {
                    length = ((length & 0x7F) << 8) | (axml[p] & 0xFF);
                    p++;
                }
                value = new String(axml, p, length, StandardCharsets.UTF_8);
            } else {
                int length = in.getShort(p) & 0xFFFF;
                p += 2;
                if ((length & 0x8000) != 0) {
                    length = ((length & 0x7FFF) << 16) | (in.getShort(p) & 0xFFFF);
                    p += 2;
                }
                value = new String(axml, p, length * 2, StandardCharsets.UTF_16LE);
            }
            String replacement = replacements.get(value);
            if (replacement == null && value.startsWith(TEMPLATE_PACKAGE + ".")) {
                replacement = replacements.get(TEMPLATE_PACKAGE) + value.substring(TEMPLATE_PACKAGE.length());
            }
            strings.add(replacement != null ? replacement : value);
        }

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int[] offsets = new int[count];
        for (int i = 0; i < count; i++) {
            offsets[i] = body.size();
            byte[] encoded = strings.get(i).getBytes(StandardCharsets.UTF_16LE);
            int length = encoded.length / 2;
            if (length >= 0x8000) {
                writeShort(body, 0x8000 | (length >> 16));
                writeShort(body, length & 0xFFFF);
            } else {
                writeShort(body, length);
            }
            body.write(encoded, 0, encoded.length);
            writeShort(body, 0);
        }
        while (body.size() % 4 != 0) body.write(0);

        int newStringsStart = poolHeaderSize + 4 * count;
        int newPoolSize = newStringsStart + body.size();
        int tailStart = poolStart + poolSize;
        int newTotal = poolStart + newPoolSize + (axml.length - tailStart);

        ByteBuffer out = ByteBuffer.allocate(newTotal).order(ByteOrder.LITTLE_ENDIAN);
        out.put(axml, 0, poolStart);
        out.putInt(4, newTotal);
        out.putShort((short) 0x0001);
        out.putShort((short) poolHeaderSize);
        out.putInt(newPoolSize);
        out.putInt(count);
        out.putInt(0);
        out.putInt(flags & ~0x100);
        out.putInt(newStringsStart);
        out.putInt(0);
        out.put(axml, poolStart + 28, poolHeaderSize - 28);
        for (int offset : offsets) out.putInt(offset);
        out.put(body.toByteArray());
        out.put(axml, tailStart, axml.length - tailStart);
        return out.array();
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }
}
