"""Encrypt an optimized APK's DEX files and install the Android 10+ loader.

Usage: python pack.py input.apk output.apk --sdk PATH --build-tools 36.0.0
The output is aligned and unsigned. Sign it with the existing production key.
Requires cryptography and the Android SDK/JDK. Per-build payload keys are temporary.
All application DEX files are AES-256-GCM encrypted; resources and native libraries
are preserved. A small public-API loader decrypts DEX into memory on Android 10+.
The runnable APK necessarily contains its decryption material: this increases the
cost of static inspection but cannot prevent runtime extraction on a controlled device.
"""
import argparse
import hashlib
import io
import os
from pathlib import Path
import re
import secrets
import shutil
import struct
import subprocess
import tempfile
from zipfile import ZipFile, ZIP_DEFLATED

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM


PACKAGE = "io.github.sparkcyf.voicesipagent.protection"
ORIGINAL_FACTORY = "androidx.core.app.CoreComponentFactory"


def replace_factory(manifest):
    kind, header, size = struct.unpack_from("<HHI", manifest)
    assert kind == 3 and size == len(manifest), "Invalid binary manifest"
    pool_kind, pool_header, pool_size, count, styles, flags, start, style_start = struct.unpack_from(
        "<HHIIIIII", manifest, header)
    assert pool_kind == 1 and pool_header == 28 and styles == style_start == 0
    pool = manifest[header:header + pool_size]
    offsets = struct.unpack_from("<" + "I" * count, pool, pool_header)
    utf8 = bool(flags & 256)

    def length(position, width):
        format_code, mask, shift = ("<B", 128, 8) if width == 1 else ("<H", 32768, 16)
        value = struct.unpack_from(format_code, pool, position)[0]
        position += width
        if value & mask:
            value = ((value & (mask - 1)) << shift) | struct.unpack_from(format_code, pool, position)[0]
            position += width
        return value, position

    def encode_length(value, width):
        if width == 1:
            return bytes([value]) if value < 128 else bytes([(value >> 8) | 128, value & 255])
        return struct.pack("<H", value) if value < 32768 else struct.pack("<HH", (value >> 16) | 32768, value & 65535)

    strings = []
    for offset in offsets:
        char_count, cursor = length(start + offset, 1 if utf8 else 2)
        if utf8:
            byte_count, cursor = length(cursor, 1)
            value = pool[cursor:cursor + byte_count].decode("utf8")
        else:
            value = pool[cursor:cursor + char_count * 2].decode("utf-16le")
        strings.append(value)
    assert strings.count(ORIGINAL_FACTORY) == 1, "Unexpected component factory"
    strings[strings.index(ORIGINAL_FACTORY)] = PACKAGE + ".EncryptedComponentFactory"
    encoded = bytearray()
    new_offsets = []
    for value in strings:
        new_offsets.append(len(encoded))
        units = len(value.encode("utf-16le")) // 2
        encoded.extend(encode_length(units, 1 if utf8 else 2))
        raw = value.encode("utf8" if utf8 else "utf-16le")
        if utf8:
            encoded.extend(encode_length(len(raw), 1))
        encoded.extend(raw)
        encoded.extend(b"\0" if utf8 else b"\0\0")
    encoded.extend(b"\0" * (-len(encoded) % 4))
    new_start = pool_header + count * 4
    new_pool = struct.pack("<HHIIIIII", 1, pool_header, new_start + len(encoded), count, 0,
                           flags & ~1, new_start, 0)
    new_pool += struct.pack("<" + "I" * count, *new_offsets) + encoded
    result = bytearray(manifest[:header] + new_pool + manifest[header + pool_size:])
    struct.pack_into("<I", result, 4, len(result))
    return bytes(result)


def pack(source, output, sdk, build_tools):
    tools = sdk / "build-tools" / build_tools
    android = sdk / "platforms" / "android-37.0" / "android.jar"
    assert android.is_file(), "Android 37 SDK required"
    key = secrets.token_bytes(32)
    cipher = AESGCM(key)
    with tempfile.TemporaryDirectory(prefix="yx-protect-") as temporary:
        work = Path(temporary)
        classes = work / "classes"
        dex_output = work / "dex"
        classes.mkdir()
        dex_output.mkdir()
        generated_key = work / "PayloadKey.java"
        key_values = ",".join(f"(byte)0x{value:02x}" for value in key)
        generated_key.write_text(f"package {PACKAGE}; final class PayloadKey {{ static byte[] bytes() {{ return new byte[]{{{key_values}}}; }} }}", encoding="utf8")
        sources = list((Path(__file__).parent / "src").rglob("*.java"))
        subprocess.run([shutil.which("javac"), "-Xlint:-options", "-source", "8", "-target", "8",
                        "-classpath", str(android), "-d", str(classes), *map(str, sources), str(generated_key)], check=True)
        subprocess.run([shutil.which("java"), "-cp", str(tools / "lib" / "d8.jar"), "com.android.tools.r8.D8",
                        "--release", "--min-api", "30", "--lib", str(android), "--output", str(dex_output),
                        *map(str, classes.rglob("*.class"))], check=True)
        shell = (dex_output / "classes.dex").read_bytes()
        with ZipFile(source) as original:
            dex_names = sorted((name for name in original.namelist() if re.fullmatch(r"classes(?:[2-9]|[1-9][0-9]+)?\.dex", name)),
                               key=lambda name: 1 if name == "classes.dex" else int(name[7:-4]))
            assert dex_names and len(dex_names) <= 16
            payload = io.BytesIO()
            payload.write(struct.pack(">II", 0x59585031, len(dex_names)))
            for index, name in enumerate(dex_names):
                assert name == ("classes.dex" if index == 0 else f"classes{index + 1}.dex")
                plain = original.read(name)
                nonce = secrets.token_bytes(12)
                aad = name.encode()
                encrypted = cipher.encrypt(nonce, plain, aad)
                assert cipher.decrypt(nonce, encrypted, aad) == plain
                damaged = bytearray(encrypted)
                damaged[-1] ^= 1
                try:
                    cipher.decrypt(nonce, bytes(damaged), aad)
                    raise AssertionError("Modified ciphertext was accepted")
                except InvalidTag:
                    pass
                payload.write(struct.pack(">H", len(aad)) + aad + nonce + struct.pack(">I", len(encrypted)) + encrypted)
            unaligned = work / "packed.apk"
            with ZipFile(unaligned, "w") as packed:
                for item in original.infolist():
                    if item.filename in dex_names or re.fullmatch(r"META-INF/[^/]+\.(?:SF|RSA|DSA|EC|MF)", item.filename):
                        continue
                    data = original.read(item)
                    if item.filename == "AndroidManifest.xml":
                        data = replace_factory(data)
                    packed.writestr(item, data)
                packed.writestr("classes.dex", shell, compress_type=ZIP_DEFLATED)
                packed.writestr("assets/protected.dex", payload.getvalue())
        executable = tools / ("zipalign.exe" if os.name == "nt" else "zipalign")
        subprocess.run([str(executable), "-P", "16", "4", str(unaligned), str(output)], check=True)
        with ZipFile(source) as original, ZipFile(output) as packed:
            for name in original.namelist():
                if name.endswith(".so") or name == "resources.arsc":
                    assert original.read(name) == packed.read(name)
            assert [name for name in packed.namelist() if re.fullmatch(r"classes\d*\.dex", name)] == ["classes.dex"]
        print(f"Encrypted {len(dex_names)} DEX file(s); authentication and native/resource preservation checks passed.")
        print("SHA256 " + hashlib.sha256(Path(output).read_bytes()).hexdigest())


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--sdk", required=True, type=Path)
    parser.add_argument("--build-tools", default="36.0.0")
    args = parser.parse_args()
    pack(args.source, args.output, args.sdk, args.build_tools)
