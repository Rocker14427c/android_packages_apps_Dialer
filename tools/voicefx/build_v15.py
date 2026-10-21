#!/usr/bin/env python3
"""Build VoiceFX Dialer module v1.5 (TD-PSOLA DSP) from the proven v1.4 module.

Strategy — same discipline as v1.3 -> v1.4 ("exactly one thing differs"):

  1. Take YOUR VoiceFX-Dialer-Module-v1.4.zip (scripts, permissions XMLs and
     module layout are reused byte-for-byte).
  2. Inside its AxionVoiceDialer.apk, replace ONLY the
     com/android/incallui/voicefx/VoiceFxProcessor*.smali files with the new
     VoiceFxProcessor.java (v2/PSOLA) compiled via javac -> d8 -> baksmali.
  3. Re-assemble that one dex, zipalign, re-sign with YOUR axionvoice.jks
     (clean in-place upgrade over v1.4), verify cert + package name.
  4. Repack the module zip (module.prop bumped to v1.5) + md5 + build report.

Nothing else in the APK (hooks, resources, manifest, other dex files) is
touched, so every v1.4 guarantee (58/58 permissions, stock-diff, role
handling, uninstall rollback) carries over.

Requirements on your PC (any OS):
  * Python 3.9+
  * JDK 8+        (javac on PATH, e.g. Temurin 17: https://adoptium.net)
  * Android SDK build-tools (d8, zipalign, apksigner; aapt2 optional)
        -> set ANDROID_HOME (or ANDROID_SDK_ROOT), or pass --sdk
  * smali + baksmali jars
        -> passed via --smali-jar/--baksmali-jar, found via env
           SMALI_JAR/BAKSMALI_JAR, or auto-downloaded from GitHub
           (google/smali releases) into tools/voicefx/.tools/
  * Your files: VoiceFX-Dialer-Module-v1.4.zip and axionvoice.jks

Usage:
  python3 build_v15.py \\
      --v14-zip /path/VoiceFX-Dialer-Module-v1.4.zip \\
      --keystore /path/axionvoice.jks \\
      --out /path/outdir

Output: <out>/VoiceFX-Dialer-Module-v1.5.zip (+ md5.txt, BUILD_REPORT_V15.md)
Flash it over v1.4 in ReSukiSU/Magisk/KernelSU. Uninstall still reverts all.
"""
from __future__ import annotations

import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import zipfile
from pathlib import Path

V14_ZIP_MD5 = "937b24304ff29204073bb7ae813ed516"
V14_APK_MD5 = "9d4b390f770dfccf490804d70ea09bac"
EXPECTED_CERT_SHA256 = "2a6c986dab29cc0a49768d692aa354e5bb2b3d34624ecba63213a8a279ced06b"
EXPECTED_PACKAGE = "com.axionvoice.dialer"
TARGET_SMALI_PREFIX = "com/android/incallui/voicefx/VoiceFxProcessor"
MIN_API = "26"

SMALI_RELEASES = [
    # JesusFreke smali 2.5.2 — the exact version the v1.4 build used.
    ("https://bitbucket.org/JesusFreke/smali/downloads/smali-2.5.2.jar", "smali.jar"),
    ("https://bitbucket.org/JesusFreke/smali/downloads/baksmali-2.5.2.jar", "baksmali.jar"),
]


def log(msg: str) -> None:
    print(f"[v1.5] {msg}", flush=True)


def die(msg: str) -> None:
    print(f"[v1.5] ERROR: {msg}", file=sys.stderr, flush=True)
    sys.exit(1)


def run(cmd: list, desc: str) -> str:
    shown = " ".join(str(c) for c in cmd)
    shown = re.sub(r"pass:[^\s]+", "pass:********", shown)  # never log keystore passwords (CI logs are public)
    log(f"$ {shown}")
    p = subprocess.run([str(c) for c in cmd], capture_output=True, text=True)
    if p.returncode != 0:
        die(f"{desc} failed (exit {p.returncode})\n--- stdout ---\n{p.stdout}\n--- stderr ---\n{p.stderr}")
    return p.stdout


def md5_of(path: Path) -> str:
    h = hashlib.md5()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def sha256_of_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def find_sdk(args) -> Path:
    sdk = args.sdk or os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        die("Android SDK not found. Set ANDROID_HOME or pass --sdk.")
    bt = Path(sdk) / "build-tools"
    if not bt.is_dir():
        die(f"No build-tools directory under {sdk}")
    versions = sorted((d for d in bt.iterdir() if d.is_dir()), key=lambda d: d.name)
    if not versions:
        die(f"No build-tools versions under {bt}")
    chosen = versions[-1]
    log(f"Android SDK build-tools: {chosen}")
    return chosen


def tool(build_tools: Path, name: str) -> Path:
    for cand in (name, name + ".exe", name + ".bat"):
        p = build_tools / cand
        if p.exists():
            return p
    die(f"Required tool '{name}' not found in {build_tools}")


def ensure_smali_jars(args, tools_dir: Path) -> tuple:
    smali = args.smali_jar or os.environ.get("SMALI_JAR")
    baksmali = args.baksmali_jar or os.environ.get("BAKSMALI_JAR")
    if smali and baksmali:
        return Path(smali), Path(baksmali)
    tools_dir.mkdir(parents=True, exist_ok=True)
    out = {}
    for url, fname in SMALI_RELEASES:
        dest = tools_dir / fname
        key = "baksmali" if fname.startswith("baksmali") else "smali"
        given = baksmali if key == "baksmali" else smali
        if given:
            out[key] = Path(given)
            continue
        if not dest.exists():
            log(f"Downloading {url}")
            try:
                urllib.request.urlretrieve(url, dest)
            except Exception as e:  # offline / renamed release
                die(f"Could not download {fname} ({e}).\n"
                    f"Download smali/baksmali 2.5.2 jars manually "
                    f"(https://bitbucket.org/JesusFreke/smali/downloads/) "
                    f"and pass --smali-jar/--baksmali-jar.")
        out[key] = dest
    return out["smali"], out["baksmali"]


def smali_cmd(java: str, smali_jar: Path) -> list:
    return [java, "-jar", str(smali_jar), "assemble"]


def baksmali_cmd(java: str, baksmali_jar: Path) -> list:
    return [java, "-jar", str(baksmali_jar), "disassemble"]


def tree_hashes(root: Path) -> dict:
    hashes = {}
    for p in sorted(root.rglob("*")):
        if p.is_file():
            h = hashlib.sha256()
            h.update(p.read_bytes())
            hashes[str(p.relative_to(root))] = h.hexdigest()
    return hashes


def gate_no_android_refs(new_smali_dir: Path) -> list:
    """Signature-trap gate (v1.4 discipline): the new processor must reference
    only java.* and its own classes — zero Android/external signatures."""
    bad = []
    invoke_re = re.compile(r"L([a-zA-Z0-9/$]+);->")
    owners = set()
    for p in new_smali_dir.rglob("VoiceFxProcessor*.smali"):
        text = p.read_text(errors="replace")
        for m in invoke_re.finditer(text):
            owners.add(m.group(1))
    for owner in sorted(owners):
        if owner.startswith("java/") or owner.startswith("com/android/incallui/voicefx/VoiceFxProcessor"):
            continue
        bad.append(owner)
    return bad


def rewrite_apk(src_apk: Path, dst_apk: Path, dex_replacements: dict) -> None:
    """Copy APK entries, swapping the patched dex and dropping old signatures
    (apksigner regenerates them). Per-entry compression type is preserved."""
    sig_suffixes = (".SF", ".RSA", ".DSA", ".EC")
    with zipfile.ZipFile(src_apk) as zin, zipfile.ZipFile(dst_apk, "w") as zout:
        for item in zin.infolist():
            name = item.filename
            if name.startswith("META-INF/") and (name.endswith(sig_suffixes) or name.endswith("MANIFEST.MF")):
                continue
            data = dex_replacements[name].read_bytes() if name in dex_replacements else zin.read(name)
            info = zipfile.ZipInfo(name, date_time=item.date_time)
            info.compress_type = item.compress_type
            info.external_attr = item.external_attr
            zout.writestr(info, data)


def check_package(aapt2: Path | None, apk: Path) -> str:
    if aapt2 and aapt2.exists():
        out = run([aapt2, "dump", "packagename", apk], "aapt2 dump packagename").strip()
        return out
    # Fallback: the package string appears UTF-16LE in the binary manifest.
    with zipfile.ZipFile(apk) as z:
        raw = z.read("AndroidManifest.xml")
    return EXPECTED_PACKAGE if EXPECTED_PACKAGE.encode("utf-16-le") in raw else "UNKNOWN"


def bump_module_prop(text: str) -> str:
    lines = []
    for line in text.splitlines():
        if line.startswith("version="):
            line = "version=v1.5"
        elif line.startswith("versionCode="):
            try:
                line = f"versionCode={int(line.split('=', 1)[1].strip()) + 1}"
            except ValueError:
                line = "versionCode=5"
        lines.append(line)
    if not any(l.startswith("version=") for l in lines):
        lines.append("version=v1.5")
    if not any(l.startswith("versionCode=") for l in lines):
        lines.append("versionCode=5")
    return "\n".join(lines) + "\n"


def main() -> int:
    here = Path(__file__).resolve().parent
    repo = here.parents[1]
    ap = argparse.ArgumentParser(description="Build VoiceFX Dialer module v1.5 from v1.4")
    ap.add_argument("--v14-zip", required=True, help="Your VoiceFX-Dialer-Module-v1.4.zip")
    ap.add_argument("--keystore", required=True, help="Your axionvoice.jks")
    ap.add_argument("--ks-pass", default="axionvoice2026")
    ap.add_argument("--ks-alias", default="axionvoice")
    ap.add_argument("--key-pass", default=None, help="defaults to --ks-pass")
    ap.add_argument("--processor-java",
                    default=str(repo / "java/com/android/incallui/voicefx/VoiceFxProcessor.java"))
    ap.add_argument("--sdk", default=None)
    ap.add_argument("--smali-jar", default=None)
    ap.add_argument("--baksmali-jar", default=None)
    ap.add_argument("--out", default=str(here / "out"))
    ap.add_argument("--force", action="store_true", help="continue despite md5/cert/package mismatches")
    ap.add_argument("--allow-different-cert", action="store_true",
                    help="signing key intentionally differs from v1.4 (fresh install required: "
                         "uninstall the old module first). Package + md5 checks stay strict.")
    args = ap.parse_args()
    args.key_pass = args.key_pass or args.ks_pass

    java = shutil.which("java") or die("java not on PATH (install a JDK)")
    javac = shutil.which("javac") or die("javac not on PATH (install a JDK, not just a JRE)")
    bt = find_sdk(args)
    d8 = tool(bt, "d8")
    zipalign = tool(bt, "zipalign")
    apksigner = tool(bt, "apksigner")
    aapt2 = bt / ("aapt2.exe" if sys.platform.startswith("win") else "aapt2")
    smali_jar, baksmali_jar = ensure_smali_jars(args, here / ".tools")

    v14_zip = Path(args.v14_zip)
    if not v14_zip.exists():
        die(f"v1.4 module zip not found: {v14_zip}")
    got = md5_of(v14_zip)
    if got != V14_ZIP_MD5:
        msg = f"v1.4 zip md5 {got} != recorded {V14_ZIP_MD5} (different/corrupt module build)"
        if not args.force:
            die(msg + ". Re-download the official v1.4 release zip, or pass --force if you know why.")
        log(f"WARNING: {msg} — continuing due to --force")
    else:
        log("v1.4 zip md5 verified.")

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix="voicefx15-"))
    log(f"work dir: {work}")
    report = []

    # ---- unpack v1.4 module, locate the APK ----
    mod_root = work / "module"
    with zipfile.ZipFile(v14_zip) as z:
        z.extractall(mod_root)
        zip_infos = {i.filename: i for i in z.infolist()}
    apks = list(mod_root.rglob("*.apk"))
    if len(apks) != 1:
        die(f"Expected exactly one APK in the module, found {apks}")
    src_apk = apks[0]
    apk_rel = str(src_apk.relative_to(mod_root))
    if md5_of(src_apk) != V14_APK_MD5 and not args.force:
        die(f"Module APK md5 mismatch (expected {V14_APK_MD5}). Pass --force only if intentional.")
    log(f"module APK: {apk_rel} (md5 verified)")

    # ---- compile the new processor: javac -> d8 -> baksmali ----
    src_java = Path(args.processor_java)
    if not src_java.exists():
        die(f"VoiceFxProcessor.java not found: {src_java}")
    classes_dir = work / "classes"
    classes_dir.mkdir()
    run([javac, "-source", "8", "-target", "8", "-d", str(classes_dir), str(src_java)],
        "javac VoiceFxProcessor.java")
    class_files = sorted(str(p) for p in classes_dir.rglob("*.class"))
    if not class_files:
        die("javac produced no class files")
    new_dex_dir = work / "newdex"
    new_dex_dir.mkdir()
    run([d8, "--min-api", MIN_API, "--output", str(new_dex_dir)] + class_files, "d8")
    new_smali = work / "new_smali"
    run(baksmali_cmd(java, baksmali_jar) + [str(new_dex_dir / "classes.dex"), "-o", str(new_smali)],
        "baksmali new processor")
    new_files = {p.relative_to(new_smali).as_posix(): p for p in new_smali.rglob("*.smali")}
    for rel in new_files:
        if not rel.startswith(TARGET_SMALI_PREFIX):
            die(f"Unexpected class compiled from VoiceFxProcessor.java: {rel}")
    bad = gate_no_android_refs(new_smali)
    if bad:
        die(f"Signature-trap gate FAILED — external refs beyond java.*: {bad}")
    log(f"new processor: {len(new_files)} smali files; signature gate PASS (java.* + own classes only)")
    report.append(f"- New VoiceFxProcessor smali files: {len(new_files)} ({', '.join(sorted(new_files))})")
    report.append("- Signature-trap gate: PASS (zero Android/external method references)")

    # ---- find the dex holding VoiceFxProcessor in the v1.4 APK ----
    apk_extract = work / "apk"
    apk_extract.mkdir()
    with zipfile.ZipFile(src_apk) as z:
        dex_names = [n for n in z.namelist() if re.fullmatch(r"classes(\d*)\.dex", n)]
        for n in dex_names:
            z.extract(n, apk_extract)
    target_dex = None
    target_tree = None
    for dex_name in sorted(dex_names):
        tree = work / f"smali_{dex_name}"
        run(baksmali_cmd(java, baksmali_jar) + [str(apk_extract / dex_name), "-o", str(tree)],
            f"baksmali {dex_name}")
        if (tree / (TARGET_SMALI_PREFIX + ".smali")).exists():
            target_dex, target_tree = dex_name, tree
            break
    if not target_dex:
        die("VoiceFxProcessor.smali not found in any dex of the v1.4 APK")
    log(f"VoiceFxProcessor lives in {target_dex}")

    before = tree_hashes(target_tree)
    old_files = sorted(p for p in target_tree.rglob("*")
                         if p.is_file() and p.relative_to(target_tree).as_posix().startswith(TARGET_SMALI_PREFIX))
    for p in old_files:
        p.unlink()
    for rel, src in new_files.items():
        dest = target_tree / rel
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(src, dest)
    after = tree_hashes(target_tree)
    changed = sorted(k for k in set(before) | set(after) if before.get(k) != after.get(k))
    unexpected = [c for c in changed if not c.startswith(TARGET_SMALI_PREFIX)]
    if unexpected:
        die(f"Smali diff touches files outside VoiceFxProcessor*: {unexpected}")
    log(f"smali diff confined to VoiceFxProcessor*: {len(changed)} files changed/added/removed")
    report.append(f"- Patched dex: {target_dex}; smali diff confined to VoiceFxProcessor* ({len(changed)} files)")

    patched_dex = work / target_dex
    run(smali_cmd(java, smali_jar) + [str(target_tree), "-o", str(patched_dex)], "smali assemble")

    # ---- rebuild + align + sign the APK ----
    unsigned = work / "unsigned.apk"
    rewrite_apk(src_apk, unsigned, {target_dex: patched_dex})
    aligned = work / "aligned.apk"
    run([zipalign, "-f", "-p", "4", str(unsigned), str(aligned)], "zipalign")
    signed = work / "AxionVoiceDialer.apk"
    run([apksigner, "sign", "--ks", args.keystore, "--ks-pass", f"pass:{args.ks_pass}",
         "--ks-key-alias", args.ks_alias, "--key-pass", f"pass:{args.key_pass}",
         "--v1-signing-enabled", "true", "--v2-signing-enabled", "true",
         "--out", str(signed), str(aligned)], "apksigner sign")
    run([apksigner, "verify", "--verbose", str(signed)], "apksigner verify")
    certs = run([apksigner, "verify", "--print-certs", str(signed)], "print certs")
    m = re.search(r"SHA-256 digest:\s*([0-9a-fA-F]+)", certs)
    cert = m.group(1).lower() if m else "UNKNOWN"
    if cert != EXPECTED_CERT_SHA256:
        msg = f"Signing cert {cert} != v1.4 cert {EXPECTED_CERT_SHA256} — this will NOT clean-upgrade over v1.4"
        if not (args.force or args.allow_different_cert):
            die(msg + ". Are you using the original axionvoice.jks? "
                "(or pass --allow-different-cert for an intentional fresh key)")
        log(f"WARNING: {msg}")
        log("Install order for a fresh key: UNINSTALL the old module -> reboot -> install v1.5 -> reboot.")
        report.append("- Signing: NEW custom key (differs from v1.4) — uninstall the old module "
                      "and reboot BEFORE installing v1.5; keep this keystore for all future updates.")
    else:
        log("signing cert matches v1.4 — clean upgrade.")
        report.append("- Signing: same cert as v1.4 — clean in-place upgrade.")
    report.append(f"- Signing cert SHA-256: {cert} (v1.4 match: {cert == EXPECTED_CERT_SHA256})")

    pkg = check_package(aapt2 if aapt2.exists() else None, signed)
    if pkg != EXPECTED_PACKAGE and not args.force:
        die(f"APK package is '{pkg}', expected '{EXPECTED_PACKAGE}'")
    log(f"package verified: {pkg}")
    report.append(f"- Package: {pkg}")

    # ---- repack the module zip (v1.4 scripts/layout preserved) ----
    shutil.copyfile(signed, src_apk)
    prop = mod_root / "module.prop"
    if prop.exists():
        prop.write_text(bump_module_prop(prop.read_text()))
        report.append("- module.prop: version=v1.5, versionCode bumped by 1")
    else:
        report.append("- module.prop: NOT FOUND in v1.4 zip (left as-is)")
        log("WARNING: module.prop not found at zip root; check the module layout.")

    out_zip = out_dir / "VoiceFX-Dialer-Module-v1.5.zip"
    with zipfile.ZipFile(out_zip, "w", zipfile.ZIP_DEFLATED) as zout:
        for p in sorted(mod_root.rglob("*")):
            if not p.is_file():
                continue
            rel = p.relative_to(mod_root).as_posix()
            info = zipfile.ZipInfo(rel)
            old = zip_infos.get(rel) or zip_infos.get(rel.replace(os.sep, "/"))
            if old is not None:
                info.external_attr = old.external_attr
                info.compress_type = zipfile.ZIP_DEFLATED
            else:
                mode = 0o755 if p.suffix == ".sh" else 0o644
                info.external_attr = (mode & 0xFFFF) << 16
            zout.writestr(info, p.read_bytes())
    zip_md5 = md5_of(out_zip)
    apk_md5 = md5_of(signed)
    shutil.copyfile(signed, out_dir / "AxionVoiceDialer.apk")
    (out_dir / "md5.txt").write_text(
        f"{zip_md5}  VoiceFX-Dialer-Module-v1.5.zip\n{apk_md5}  AxionVoiceDialer.apk\n")
    report_text = (
        "# VoiceFX Dialer v1.5 — build report\n\n"
        "TD-PSOLA voice engine (Girl/Agent rebuilt); packaging identical to v1.4.\n\n"
        + "\n".join(report)
        + f"\n- Module zip md5: {zip_md5}\n- APK md5: {apk_md5}\n"
        + f"- APK sha256: {sha256_of_file(signed)}\n\n"
        + ("Install per the Signing note above (fresh key = uninstall old module + "
         "reboot first; same key = flash over). Uninstall (manager) + reboot fully "
         "restores stock, exactly as in v1.4 (scripts reused unchanged).\n"))
    (out_dir / "BUILD_REPORT_V15.md").write_text(report_text)

    log(f"DONE: {out_zip}")
    log(f"  zip md5 {zip_md5}")
    log(f"  also: {out_dir / 'AxionVoiceDialer.apk'}, md5.txt, BUILD_REPORT_V15.md")
    log("Flash over v1.4 in ReSukiSU, reboot, and test Girl + Agent in a call.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
