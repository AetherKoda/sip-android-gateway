package io.github.sparkcyf.voicesipagent.protection;

import dalvik.system.InMemoryDexClassLoader;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Authenticated DEX payloads are decrypted into memory, never into application storage. */
public final class EncryptedCode {
    private EncryptedCode() {}

    public static String nativePath(String apk, String extracted) {
        StringBuilder path = new StringBuilder(extracted == null ? "" : extracted);
        for (String abi : android.os.Build.SUPPORTED_ABIS) {
            if (path.length() > 0) path.append(java.io.File.pathSeparator);
            path.append(apk).append("!/lib/").append(abi);
        }
        return path.toString();
    }

    public static ClassLoader load(String apk, String nativePath, ClassLoader parent) {
        byte[] key = PayloadKey.bytes();
        try (ZipFile archive = new ZipFile(apk)) {
            ZipEntry entry = archive.getEntry("assets/protected.dex");
            if (entry == null || entry.getSize() > 128 * 1024 * 1024) {
                throw new IOException("Missing protected code");
            }
            try (DataInputStream input = new DataInputStream(archive.getInputStream(entry))) {
                if (input.readInt() != 0x59585031) throw new IOException("Invalid code format");
                int count = input.readInt();
                if (count < 1 || count > 16) throw new IOException("Invalid DEX count");
                ByteBuffer[] buffers = new ByteBuffer[count];
                for (int index = 0; index < count; index++) {
                    int nameLength = input.readUnsignedShort();
                    if (nameLength < 1 || nameLength > 32) throw new IOException("Invalid DEX name");
                    byte[] name = new byte[nameLength];
                    input.readFully(name);
                    String expected = index == 0 ? "classes.dex" : "classes" + (index + 1) + ".dex";
                    if (!expected.equals(new String(name, StandardCharsets.UTF_8))) {
                        throw new IOException("Invalid DEX order");
                    }
                    byte[] nonce = new byte[12];
                    input.readFully(nonce);
                    int length = input.readInt();
                    if (length < 16 || length > 64 * 1024 * 1024) throw new IOException("Invalid DEX length");
                    byte[] encrypted = new byte[length];
                    input.readFully(encrypted);
                    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                    cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
                    cipher.updateAAD(name);
                    byte[] dex = cipher.doFinal(encrypted);
                    if (dex.length < 112 || dex[0] != 'd' || dex[1] != 'e' || dex[2] != 'x' || dex[3] != '\n') {
                        throw new IOException("Invalid decrypted DEX");
                    }
                    ByteBuffer buffer = ByteBuffer.allocateDirect(dex.length);
                    buffer.put(dex).flip();
                    buffers[index] = buffer;
                    Arrays.fill(dex, (byte) 0);
                }
                if (input.read() != -1) throw new IOException("Unexpected code trailer");
                return new InMemoryDexClassLoader(buffers, nativePath, parent);
            }
        } catch (Exception error) {
            throw new IllegalStateException("Protected code could not be loaded", error);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }
}
