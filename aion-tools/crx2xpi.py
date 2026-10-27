#!/usr/bin/env python3
"""Convert a Chrome Web Store extension (CRX) into a Gecko-compatible XPI.

Usage:
    crx2xpi.py <chrome-extension-id> <output.xpi>
    crx2xpi.py --file <input.crx> <output.xpi>

Steps:
 1. Download the CRX from Google's update endpoint (same URL Chrome uses).
 2. Strip the CRX2/CRX3 header to get the inner ZIP.
 3. Patch manifest.json for Gecko:
    - add browser_specific_settings.gecko.id (required by Gecko)
    - MV3 background.service_worker -> background.scripts (Gecko Android
      does not run extension service workers)
    - drop Chrome-only keys Gecko rejects
 4. Re-zip as .xpi (unsigned; the browser must allow unsigned add-ons).
"""
import io
import json
import struct
import sys
import urllib.request
import zipfile

UPDATE_URL = (
    "https://clients2.google.com/service/update2/crx?response=redirect"
    "&prodversion=130.0&acceptformat=crx2,crx3&x=id%3D{id}%26uc"
)
CHROME_ONLY_KEYS = ("update_url", "key", "minimum_chrome_version", "differential_fingerprint")


def fetch_crx(ext_id: str) -> bytes:
    with urllib.request.urlopen(UPDATE_URL.format(id=ext_id), timeout=60) as r:
        return r.read()


def crx_to_zip(data: bytes) -> bytes:
    if data[:4] != b"Cr24":
        raise ValueError("not a CRX file")
    version = struct.unpack("<I", data[4:8])[0]
    if version == 3:
        header_len = struct.unpack("<I", data[8:12])[0]
        return data[12 + header_len:]
    if version == 2:
        key_len, sig_len = struct.unpack("<II", data[8:16])
        return data[16 + key_len + sig_len:]
    raise ValueError(f"unsupported CRX version {version}")


def patch_manifest(manifest: dict, ext_id: str) -> dict:
    for key in CHROME_ONLY_KEYS:
        manifest.pop(key, None)
    bss = manifest.setdefault("browser_specific_settings", {})
    bss.setdefault("gecko", {}).setdefault("id", f"{ext_id}@chrome.aion")
    bg = manifest.get("background")
    if isinstance(bg, dict) and "service_worker" in bg:
        sw = bg.pop("service_worker")
        bg["scripts"] = [sw]
        bg.pop("type", None) if bg.get("type") != "module" else None
    return manifest


def convert(crx: bytes, ext_id: str) -> bytes:
    src = zipfile.ZipFile(io.BytesIO(crx_to_zip(crx)))
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as dst:
        for info in src.infolist():
            if info.filename.startswith("_metadata/"):
                continue  # Chrome Web Store signature data
            data = src.read(info.filename)
            if info.filename == "manifest.json":
                text = data.decode("utf-8-sig")
                data = json.dumps(patch_manifest(json.loads(text), ext_id), indent=2).encode()
            dst.writestr(info.filename, data)
    return out.getvalue()


def main(argv):
    if len(argv) == 4 and argv[1] == "--file":
        crx, ext_id, out = open(argv[2], "rb").read(), "local", argv[3]
    elif len(argv) == 3:
        ext_id, out = argv[1], argv[2]
        crx = fetch_crx(ext_id)
    else:
        print(__doc__)
        return 2
    xpi = convert(crx, ext_id)
    open(out, "wb").write(xpi)
    print(f"wrote {out} ({len(xpi)} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
