#!/usr/bin/env python3
"""Check the CrystalGram ARM JNI library against the NDK API 16 stubs."""

import argparse
import os
from pathlib import Path
import re
import subprocess


def readelf(*args):
    return subprocess.check_output(["readelf", *args], text=True, env={**os.environ, "LC_ALL": "C"})


def symbols(path):
    for line in readelf("--dyn-syms", "--wide", str(path)).splitlines():
        fields = line.split()
        if fields and fields[0].endswith(":") and len(fields) >= 8 and fields[0][:-1].isdigit():
            yield fields[4], fields[6], fields[7].replace("@@", "@")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("library", type=Path)
    parser.add_argument("api16_stubs", type=Path)
    args = parser.parse_args()

    name = str(args.library)
    header = readelf("-h", name)
    dynamic = readelf("-dW", name)
    attributes = readelf("-A", name)
    dependencies = re.findall(r"\(NEEDED\).*?\[(.*?)\]", dynamic)
    problems = []
    if not re.search(r"Class:\s+ELF32", header) or not re.search(r"Machine:\s+ARM\b", header):
        problems.append("Expected a 32-bit ARM library")
    if "Tag_CPU_arch: v7" not in attributes or "Tag_Advanced_SIMD_arch:" in attributes:
        problems.append("Expected ARMv7 without a binary-wide NEON requirement")
    if not re.search(r"\(HASH\)", dynamic):
        problems.append("Missing the SysV hash table required by Jelly Bean's linker")
    if re.search(r"\((?:TEXTREL|RELR\w*|ANDROID_\w*)\)", dynamic):
        problems.append("Found text relocations or unsupported packed dynamic relocations")
    if not re.search(r"\(SONAME\).*?\[libtdjni\.so\]", dynamic):
        problems.append("Unexpected JNI library SONAME")

    exports = set()
    for dependency in dependencies:
        stub = args.api16_stubs / dependency
        if dependency not in {"libc.so", "libdl.so", "liblog.so", "libm.so", "libz.so"} or not stub.is_file():
            problems.append(f"Unexpected API 16 dependency {dependency}")
            continue
        for _, section, symbol in symbols(stub):
            if section != "UND":
                exports.update((symbol, symbol.split("@")[0]))

    imported = set()
    optional = set()
    # A constant-initialized emulated TLS variable needs no initializer.
    # Clang checks its weak initializer symbol before calling it. OpenSSL
    # 1.1.1 also checks for getentropy before using its /dev/urandom fallback.
    guarded_weak = {"_ZTHN2td3jni11parse_errorE", "getentropy"}
    jni_entry = False
    for binding, section, symbol in symbols(args.library):
        if section != "UND" and symbol == "JNI_OnLoad":
            jni_entry = True
        if section == "UND" and binding in {"GLOBAL", "WEAK"}:
            if binding == "WEAK" and symbol in guarded_weak and symbol not in exports:
                optional.add(symbol)
            else:
                imported.add(symbol)
    if not jni_entry:
        problems.append("Missing JNI_OnLoad export")
    problems.extend(f"API 16 stubs lack {symbol}" for symbol in sorted(imported - exports))

    if problems:
        parser.exit(1, "\n".join(problems) + "\n")
    if optional:
        print(f"Guarded weak imports absent at API 16: {', '.join(sorted(optional))}.")
    print(f"{len(imported)} imports resolved by API 16 stubs ({', '.join(dependencies)}).")


if __name__ == "__main__":
    main()
