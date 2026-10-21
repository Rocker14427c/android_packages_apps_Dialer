#!/usr/bin/env python3
"""Build the VoiceFX Dialer module (v1.6) from the released v1.4 module.

Why this exists
---------------
The module ships a full dialer APK and there is no source-built binary for this
ROM, so a release is produced by patching the *proven* v1.4 APK:

  * the voice-engine classes (VoiceFxProcessor, VoiceFxPreset, dsp.*) and
    VoiceFxController are compiled here with javac -> d8 and spliced into the
    dex that already holds them,
  * every dialer member those classes reference (method *signature*, field
    *type*) is checked against the APK's own smali, so a renamed or retyped
    method fails the build here instead of throwing NoSuchMethodError on the
    phone,
  * the APK is re-signed, packaged with the module scripts, and the resulting
    zip is re-opened and validated the way the module manager will see it.

Everything else in the APK is untouched, which carries the v1.4 guarantees
(permissions, stock-app diff, role handling) over unchanged.

Requirements: python3, a JDK (javac+java), Android SDK build-tools (d8,
zipalign, apksigner, aapt2), the v1.4 module zip, a keystore, and
smali/baksmali jars (downloaded automatically when missing).

  python3 tools/voicefx/build_v16.py \
      --v14-zip VoiceFX-Dialer-Module-v1.4.zip \
      --keystore voicefx-release.jks --ks-pass '...' --ks-alias voicefx \
      --out out
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
import time
import urllib.request
import fnmatch
import zipfile
from pathlib import Path

V14_ZIP_MD5 = "937b24304ff29204073bb7ae813ed516"
V14_APK_MD5 = "9d4b390f770dfccf490804d70ea09bac"
EXPECTED_CERT_SHA256 = "2a6c986dab29cc0a49768d692aa354e5bb2b3d34624ecba63213a8a279ced06b"
EXPECTED_PACKAGE = "com.axionvoice.dialer"
APK_REL = "system/product/priv-app/AxionVoiceDialer/AxionVoiceDialer.apk"

# Classes replaced/added inside the APK. Anything else that comes out of javac
# is a build error (a stray class would leak into the shipping APK).
REPLACE_PREFIXES = ("com/android/incallui/voicefx/VoiceFxProcessor",
                    "com/android/incallui/voicefx/VoiceFxController")
ADD_PREFIXES = ("com/android/incallui/voicefx/VoiceFxPreset",
                "com/android/incallui/voicefx/VoiceFxController$",
                "com/android/incallui/voicefx/dsp/")
ALLOWED_PREFIXES = REPLACE_PREFIXES + ADD_PREFIXES
# Classes that must already exist in the APK and are dropped from it before the
# new ones are written in.
MUST_EXIST = ("com/android/incallui/voicefx/VoiceFxProcessor",
              "com/android/incallui/voicefx/VoiceFxController")

# Exactly the sources that are compiled and shipped into the APK. VoiceFxUi is
# deliberately absent: it is UI code that touches androidx classes and is not
# part of this release.
SHIP_SOURCES = (
    "java/com/android/incallui/voicefx/VoiceFxProcessor.java",
    "java/com/android/incallui/voicefx/VoiceFxPreset.java",
    "java/com/android/incallui/voicefx/VoiceFxController.java",
)

# Scripts taken from tools/voicefx/module-template and written over whatever the
# base module shipped: these are the install/uninstall/watchdog logic of this
# release. The permission XMLs are NOT touched — the ones inside the released
# module are the authoritative grant lists.
MODULE_SCRIPTS = ("customize.sh", "service.sh", "uninstall.sh", "gate.sh", "post-fs-data.sh")
MODULE_FILES = ("module.prop",) + MODULE_SCRIPTS
# Globs that must match something inside the packaged module. The permission
# XMLs are taken from the base module as-is, so their exact names are whatever
# that module used; only their presence matters here.
MODULE_PATTERNS = (
    "system/etc/permissions/*.xml",
    "system/etc/default-permissions/*.xml",
)

SMALI_MIRRORS = [
    # Bitbucket is where JesusFreke publishes the runnable fat jars; this is the
    # exact toolchain the v1.4/v1.5 modules were built with.
    ("smali/baksmali 2.5.2 (Bitbucket)",
     "https://bitbucket.org/JesusFreke/smali/downloads/smali-2.5.2.jar",
     "https://bitbucket.org/JesusFreke/smali/downloads/baksmali-2.5.2.jar"),
    ("smali/baksmali 2.5.2 (Maven Central)",
     "https://repo1.maven.org/maven2/org/smali/smali/2.5.2/smali-2.5.2.jar",
     "https://repo1.maven.org/maven2/org/smali/baksmali/2.5.2/baksmali-2.5.2.jar"),
    ("smali/baksmali 3.0.9 (Maven Central)",
     "https://repo1.maven.org/maven2/com/android/tools/smali/smali/3.0.9/smali-3.0.9.jar",
     "https://repo1.maven.org/maven2/com/android/tools/smali/baksmali/3.0.9/baksmali-3.0.9.jar"),
]

# Lowner;->name(params)ret  or  Lowner;->name:type
REF_RE = re.compile(r"L([\w/$]+);->([\w<>$]+)(\([^)]*\)(?:[\w/$;\[]+)|:[\w/$;\[]+)")
TYPE_RE = re.compile(r"L([\w/$]+);")


def log(msg: str) -> None:
    print(f"[v1.6] {msg}", flush=True)


def die(msg: str) -> None:
    print(f"[v1.6] ERROR: {msg}", file=sys.stderr, flush=True)
    sys.exit(1)


def try_run(cmd, timeout=7200):
    shown = " ".join(str(c) for c in cmd)
    shown = re.sub(r"pass:\S+", "pass:********", shown)
    log(f"$ {shown}")
    p = subprocess.run([str(c) for c in cmd], capture_output=True, text=True, timeout=timeout)
    return p.returncode, p.stdout, p.stderr


def run(cmd, desc, timeout=7200):
    rc, out, err = try_run(cmd, timeout)
    if rc != 0:
        die(f"{desc} failed (exit {rc})\n--- stdout ---\n{out[-6000:]}"
            f"\n--- stderr ---\n{err[-6000:]}")
    return out


def md5_of(path: Path) -> str:
    h = hashlib.md5()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


# ---------------------------------------------------------------------------
# Toolchain.


def find_sdk(arg_sdk) -> Path:
    sdk = arg_sdk or os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    candidates = []
    if sdk:
        candidates.append(Path(sdk))
    candidates += [Path("/usr/local/lib/android/sdk"), Path("/opt/android-sdk")]
    for cand in candidates:
        if (cand / "build-tools").is_dir():
            versions = sorted((d for d in (cand / "build-tools").iterdir() if d.is_dir()),
                              key=lambda d: [int(x) for x in re.findall(r"\d+", d.name)])
            if versions:
                log(f"build-tools: {versions[-1]}")
                return versions[-1]
    die("Android SDK build-tools not found. Set ANDROID_HOME or pass --sdk.")
    raise SystemExit(1)


def tool(build_tools: Path, name: str) -> Path:
    for cand in (name, name + ".exe", name + ".bat"):
        p = build_tools / cand
        if p.exists():
            return p
    die(f"'{name}' not found in {build_tools}")


def download(url: str, dest: Path) -> bool:
    log(f"download {url}")
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "voicefx-build"})
        with urllib.request.urlopen(req, timeout=180) as r, open(dest, "wb") as f:
            shutil.copyfileobj(r, f)
        if dest.stat().st_size < 100_000:
            raise RuntimeError("file too small")
        return True
    except Exception as e:  # noqa: BLE001 — try the next mirror
        log(f"  failed: {e}")
        dest.unlink(missing_ok=True)
        return False


def jar_works(jar: Path, kind: str) -> bool:
    """True only for a runnable fat jar.

    Maven Central also serves org.smali:* — but those are *libraries* without a
    Main-Class, so `java -jar` cannot run them. Checking the manifest is the
    only reliable way to tell the two apart before trying to use them.
    """
    try:
        with zipfile.ZipFile(jar) as z:
            manifest = z.read("META-INF/MANIFEST.MF").decode("utf-8", "replace")
    except Exception:  # noqa: BLE001
        return False
    m = re.search(r"^Main-Class:\s*(\S+)\s*$", manifest, re.MULTILINE)
    if not m:
        return False
    main = m.group(1)
    if kind == "baksmali":
        return "baksmali" in main
    return "smali" in main


def pick_tools(java: str, args, cache: Path, probe_dex: Path):
    """Return working (smali, baksmali) jars.

    A jar is only accepted if it can actually disassemble this APK's dex: an
    older smali silently lacking support for a newer dex version would
    otherwise blow up halfway through the build.
    """
    if args.smali_jar and args.baksmali_jar:
        sm, bk = Path(args.smali_jar), Path(args.baksmali_jar)
        for jar, kind in ((sm, "smali"), (bk, "baksmali")):
            if not jar.exists():
                die(f"{jar} does not exist")
            if not jar_works(jar, kind):
                die(f"{jar} is not a runnable {kind} jar (no Main-Class in its manifest). "
                    "Use the Bitbucket fat jar, not the Maven Central library artifact.")
        return sm, bk
    cache.mkdir(parents=True, exist_ok=True)
    problems = []
    for label, smali_url, baksmali_url in SMALI_MIRRORS:
        # one cache slot per mirror: a jar that failed the probe must never be
        # silently reused for the next mirror
        slot = cache / re.sub(r"[^a-z0-9]+", "-", label.lower())
        slot.mkdir(parents=True, exist_ok=True)
        s, b = slot / "smali.jar", slot / "baksmali.jar"
        ok = True
        for jar, url, kind in ((s, smali_url, "smali"), (b, baksmali_url, "baksmali")):
            if jar.exists() and jar_works(jar, kind):
                continue
            got = False
            for attempt in range(1, 4):
                if download(url, jar) and jar_works(jar, kind):
                    got = True
                    break
                log(f"  attempt {attempt} for {jar.name} failed")
                time.sleep(3 * attempt)
            if not got:
                problems.append(f"{label}: {kind} jar")
                ok = False
                break
        if not ok:
            continue
        probe = slot / "probe_smali"
        if probe.exists():
            shutil.rmtree(probe)
        probe.mkdir()
        rc, out, err = try_run([java, "-Xmx2g", "-jar", str(b), "disassemble",
                                str(probe_dex), "-o", str(probe)], timeout=1800)
        shutil.rmtree(probe, ignore_errors=True)
        if rc != 0:
            problems.append(f"{label}: cannot read this APK's dex ({err.strip().splitlines()[-1:]}"
                            if err.strip() else f"{label}: cannot read this APK's dex")
            continue
        log(f"smali toolchain: {label}")
        return s, b
    die("could not obtain smali/baksmali jars that can read this APK (tried: %s). "
        "Pass --smali-jar/--baksmali-jar." % "; ".join(problems))


def disassemble(java: str, baksmali: Path, dex: Path, out: Path, *extra) -> None:
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)
    run([java, "-Xmx4g", "-jar", str(baksmali), "disassemble", *extra, str(dex), "-o", str(out)],
        f"baksmali {dex.name}")


# ---------------------------------------------------------------------------
# Smali indexing: exact (owner, member+signature) pairs.


def index_declarations(root: Path) -> set:
    """Every member *declared* in a smali tree, as (owner, name+signature)."""
    decls = set()
    for p in root.rglob("*.smali"):
        owner = None
        try:
            text = p.read_text(errors="replace")
        except OSError:
            continue
        for line in text.splitlines():
            ls = line.strip()
            if ls.startswith(".class "):
                m = TYPE_RE.search(ls)
                if m:
                    owner = m.group(1)
            elif owner and ls.startswith(".method "):
                m = re.search(r"([\w<>$]+\([^)]*\)[\w/$;\[]+)$", ls)
                if m:
                    decls.add((owner, m.group(1)))
            elif owner and ls.startswith(".field "):
                m = re.search(r"\s([\w<>$]+:[\w/$;\[]+)(?:\s*=|$)", ls)
                if m:
                    decls.add((owner, m.group(1)))
    return decls


def index_classes(root: Path) -> set:
    return {p.relative_to(root).as_posix()[:-len(".smali")] for p in root.rglob("*.smali")}


def references_of(root: Path):
    """(owner, member) references and (type) references used by a smali tree."""
    refs, types = set(), set()
    for p in root.rglob("*.smali"):
        text = p.read_text(errors="replace")
        for m in REF_RE.finditer(text):
            refs.add((m.group(1), m.group(2) + m.group(3)))
        for m in TYPE_RE.finditer(text):
            types.add(m.group(1))
    return refs, types


def abi_gate(refs, types, decls: set, classes: set, own_prefix: str) -> list:
    """Dialer-owned members/types the new code uses must exist in the base APK.

    Only com.android.* owners are checked: android.*/java.*/androidx.* come from
    the platform or from libraries that are already linked into the APK and are
    therefore not ours to verify.
    """
    problems = []
    for owner, member in sorted(refs):
        base_owner = owner.split("$")[0]
        if not owner.startswith("com/android/") or owner.startswith(own_prefix):
            continue
        if (owner, member) in decls or (base_owner, member) in decls:
            continue
        problems.append(f"missing member  L{owner};->{member}")
    for t in sorted(types):
        if not t.startswith("com/android/") or t.startswith(own_prefix):
            continue
        if t in classes or t.split("$")[0] in classes:
            continue
        problems.append(f"missing class   L{t};")
    return problems


# ---------------------------------------------------------------------------


def build_stub_src(work: Path, stubs_dir: Path) -> Path:
    src = work / "stubsrc"
    if src.exists():
        shutil.rmtree(src)
    shutil.copytree(stubs_dir, src)
    return src


def javac_cmd(javac: str, android_jar: Path, out: Path, sources) -> list:
    """javac invocation that works on JDK 8 through 25.

    JDK 9+ wants --release, the Java-8 tools.jar compiler (and older JDKs) only
    understand -source/-target/-bootclasspath. Probe once, use what works.
    """
    probe = subprocess.run([javac, "--release", "8", "-version"],
                           capture_output=True, text=True)
    if "invalid flag" not in (probe.stdout + probe.stderr):
        return [javac, "--release", "8", "-nowarn", "-Xlint:-options",
                "-classpath", str(android_jar), "-d", str(out)] + sources
    return [javac, "-source", "8", "-target", "8", "-nowarn", "-Xlint:-options",
            "-bootclasspath", str(android_jar), "-classpath", str(android_jar),
            "-d", str(out)] + sources


def compile_voice(work: Path, javac: str, android_jar: Path, repo: Path, stub_src: Path) -> Path:
    out = work / "classes"
    if out.exists():
        shutil.rmtree(out)
    out.mkdir()
    sources = []
    for rel in SHIP_SOURCES:
        p = repo / rel
        if not p.exists():
            die(f"missing source {rel}")
        sources.append(str(p))
    sources += sorted(str(p) for p in (repo / "java/com/android/incallui/voicefx/dsp").glob("*.java"))
    sources += sorted(str(p) for p in stub_src.rglob("*.java"))
    run(javac_cmd(javac, android_jar, out, sources), "javac voicefx sources")
    # The stubs only exist so javac can type-check the dialer-internal calls;
    # they must never reach the APK. Everything outside the voicefx package goes.
    dropped = []
    for stray in sorted(out.rglob("*.class")):
        rel = stray.relative_to(out).as_posix()
        if not rel.startswith("com/android/incallui/voicefx/"):
            dropped.append(rel)
            stray.unlink()
    if dropped:
        log(f"dropped stub classes: {dropped}")
    left = sorted(p.relative_to(out).as_posix()[:-len(".class")] for p in out.rglob("*.class"))
    if not left:
        die("javac produced no classes")
    log(f"compiled {len(left)} classes: {left}")
    return out


def main() -> int:
    here = Path(__file__).resolve().parent
    repo = here.parents[1]
    ap = argparse.ArgumentParser(description="Build the VoiceFX Dialer module")
    ap.add_argument("--v14-zip", required=True)
    ap.add_argument("--keystore", required=True)
    ap.add_argument("--ks-pass", default="")
    ap.add_argument("--ks-alias", default="voicefx")
    ap.add_argument("--key-pass", default=None)
    ap.add_argument("--sdk", default=None)
    ap.add_argument("--android-jar", default=None)
    ap.add_argument("--smali-jar", default=None)
    ap.add_argument("--baksmali-jar", default=None)
    ap.add_argument("--out", default=str(here / "out"))
    ap.add_argument("--version", default="v1.6")
    ap.add_argument("--allow-different-cert", action="store_true")
    ap.add_argument("--force", action="store_true", help="ignore base md5 mismatches")
    args = ap.parse_args()
    args.key_pass = args.key_pass if args.key_pass is not None else args.ks_pass
    version = args.version

    java = shutil.which("java") or die("java not on PATH")
    javac = shutil.which("javac") or die("javac not on PATH (need a JDK)")
    bt = find_sdk(args.sdk)
    d8, zipalign, apksigner = tool(bt, "d8"), tool(bt, "zipalign"), tool(bt, "apksigner")
    aapt2 = bt / ("aapt2.exe" if os.name == "nt" else "aapt2")
    android_jar = Path(args.android_jar) if args.android_jar else None
    if android_jar is None:
        for root in (bt.parent.parent, Path(args.sdk) if args.sdk else None):
            if root and (root / "platforms").is_dir():
                plats = sorted((root / "platforms").glob("android-*"),
                               key=lambda d: [int(x) for x in re.findall(r"\d+", d.name)])
                if plats:
                    android_jar = plats[-1] / "android.jar"
                    break
    if not android_jar or not android_jar.exists():
        die("android.jar not found; pass --android-jar")
    log(f"android.jar: {android_jar}")

    # ---- base module -----------------------------------------------------
    v14 = Path(args.v14_zip)
    if not v14.exists():
        die(f"v1.4 module zip not found: {v14}")
    base_zip_md5 = md5_of(v14)
    if base_zip_md5 != V14_ZIP_MD5:
        if not args.force:
            die(f"v1.4 zip md5 {base_zip_md5} != {V14_ZIP_MD5}; re-download the official release")
        log(f"WARNING: base zip md5 {base_zip_md5} (not the released value); --force given")
    else:
        log("v1.4 module zip md5 verified")

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix="voicefx-build-"))
    log(f"work dir: {work}")

    mod = work / "module"
    with zipfile.ZipFile(v14) as z:
        z.extractall(mod)
    apk_path = mod / APK_REL
    if not apk_path.exists():
        found = list(mod.rglob("*.apk"))
        if len(found) != 1:
            die(f"expected one APK in the module, found {found}")
        apk_path = found[0]
    base_apk_md5 = md5_of(apk_path)
    if base_apk_md5 != V14_APK_MD5 and not args.force:
        die(f"base APK md5 {base_apk_md5} != {V14_APK_MD5}")
    log(f"base APK: {apk_path.relative_to(mod)} ({base_apk_md5})")

    # ---- disassemble the base APK ---------------------------------------
    dex_dir = work / "dex"
    dex_dir.mkdir()
    with zipfile.ZipFile(apk_path) as z:
        dex_names = sorted(n for n in z.namelist() if re.fullmatch(r"classes(\d*)\.dex", n))
        for n in dex_names:
            z.extract(n, dex_dir)
    if not dex_names:
        die("the base APK has no classes*.dex")
    smali_jar, baksmali_jar = pick_tools(java, args, here / ".tools", dex_dir / dex_names[0])
    trees, target_dex = {}, None
    for name in dex_names:
        tree = work / f"smali_{name}"
        disassemble(java, baksmali_jar, dex_dir / name, tree)
        trees[name] = tree
        if (tree / "com/android/incallui/voicefx/VoiceFxProcessor.smali").exists():
            target_dex = name
    if target_dex is None:
        die("VoiceFxProcessor.smali is not in the base APK")
    log(f"voice classes live in {target_dex}; dex files: {dex_names}")

    target_tree = trees[target_dex]
    base_classes = set()
    base_decls = set()
    for t in trees.values():
        base_classes |= index_classes(t)
        base_decls |= index_declarations(t)
    for must in MUST_EXIST:
        if must not in base_classes:
            die(f"expected class {must} is missing from the base APK")

    # ---- compile + dex ---------------------------------------------------
    stub_src = build_stub_src(work, here / "stubs")
    classes = compile_voice(work, javac, android_jar, repo, stub_src)
    new_dex_dir = work / "newdex"
    new_dex_dir.mkdir()
    run([d8, "--min-api", "26", "--lib", str(android_jar), "--output", str(new_dex_dir)]
        + sorted(str(p) for p in classes.rglob("*.class")), "d8")
    new_smali = work / "new_smali"
    disassemble(java, baksmali_jar, new_dex_dir / "classes.dex", new_smali, "--skip-debug")

    new_classes = sorted(index_classes(new_smali))
    stray = [c for c in new_classes if not c.startswith(ALLOWED_PREFIXES)]
    if stray:
        die(f"classes outside the patch whitelist: {stray}")
    for prefix in REPLACE_PREFIXES:
        if not any(c.startswith(prefix) for c in new_classes):
            die(f"no compiled class for {prefix}")
    log(f"{len(new_classes)} new smali classes")

    # ---- ABI gate --------------------------------------------------------
    refs, types = references_of(new_smali)
    problems = abi_gate(refs, types, base_decls, base_classes,
                        own_prefix="com/android/incallui/voicefx/")
    if problems:
        die("ABI gate FAILED — the base APK does not provide:\n  " + "\n  ".join(problems))
    checked = len([r for r in refs if r[0].startswith("com/android/")])
    log(f"ABI gate PASS ({checked} dialer member references, "
        f"{len([t for t in types if t.startswith('com/android/')])} dialer types)")
    report = [
        f"- Base: released v1.4 module (zip md5 `{base_zip_md5}`, APK md5 `{base_apk_md5}`)",
        f"- Patched dex: `{target_dex}` (of {', '.join(dex_names)})",
        f"- Classes replaced: {', '.join(REPLACE_PREFIXES)}",
        f"- Classes added: {len(new_classes)} ({', '.join(new_classes)})",
        f"- ABI gate: **PASS** — {checked} dialer member references and "
        f"{len([t for t in types if t.startswith('com/android/')])} dialer types verified "
        "against the APK's own smali",
    ]

    # ---- splice ----------------------------------------------------------
    removed = []
    for p in sorted(target_tree.rglob("*.smali")):
        rel = p.relative_to(target_tree).as_posix()[:-len(".smali")]
        if rel.startswith(ALLOWED_PREFIXES) or rel in MUST_EXIST:
            p.unlink()
            removed.append(rel)
    for p in new_smali.rglob("*.smali"):
        dest = target_tree / p.relative_to(new_smali)
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(p, dest)
    log(f"removed {len(removed)} old classes, wrote {len(new_classes)} new ones")

    patched_dex = work / f"patched_{target_dex}"
    run([java, "-Xmx4g", "-jar", str(smali_jar), "assemble", str(target_tree),
         "-o", str(patched_dex)], "smali assemble")
    if patched_dex.stat().st_size < 100_000:
        die("assembled dex looks empty")

    # The assembled dex must contain exactly the classes we spliced in.
    verify_tree = work / "verify_smali"
    disassemble(java, baksmali_jar, patched_dex, verify_tree, "--skip-debug")
    verify_classes = index_classes(verify_tree)
    missing = [c for c in new_classes if c not in verify_classes]
    if missing:
        die(f"assembled dex is missing {missing}")
    stale = [c for c in removed if c in verify_classes and c not in new_classes]
    if stale:
        die(f"assembled dex still contains removed classes {stale}")
    log("assembled dex verified: all new classes present, no stale ones")

    # ---- rebuild + sign --------------------------------------------------
    unsigned = work / "unsigned.apk"
    sig_suffixes = (".SF", ".RSA", ".DSA", ".EC")
    patched_bytes = patched_dex.read_bytes()
    with zipfile.ZipFile(apk_path) as zin, zipfile.ZipFile(unsigned, "w") as zout:
        for item in zin.infolist():
            name = item.filename
            if name.startswith("META-INF/") and (name.endswith(sig_suffixes)
                                                 or name.endswith("MANIFEST.MF")):
                continue
            info = zipfile.ZipInfo(name, date_time=item.date_time)
            info.compress_type = item.compress_type
            info.external_attr = item.external_attr
            zout.writestr(info, patched_bytes if name == target_dex else zin.read(name))

    aligned = work / "aligned.apk"
    run([zipalign, "-f", "-p", "4", str(unsigned), str(aligned)], "zipalign")
    signed = work / "AxionVoiceDialer.apk"
    run([apksigner, "sign", "--ks", args.keystore, "--ks-key-alias", args.ks_alias,
         "--ks-pass", f"pass:{args.ks_pass}", "--key-pass", f"pass:{args.key_pass}",
         "--v1-signing-enabled", "true", "--v2-signing-enabled", "true",
         "--out", str(signed), str(aligned)], "apksigner sign")
    run([apksigner, "verify", "--verbose", str(signed)], "apksigner verify")
    certs = run([apksigner, "verify", "--print-certs", str(signed)], "apksigner print-certs")
    m = re.search(r"SHA-256 digest:\s*([0-9a-fA-F]+)", certs)
    cert = m.group(1).lower() if m else "UNKNOWN"
    same_cert = cert == EXPECTED_CERT_SHA256
    if not same_cert and not args.allow_different_cert:
        die(f"signing cert {cert} differs from the v1.4 cert {EXPECTED_CERT_SHA256}. "
            "Use the original keystore, or pass --allow-different-cert.")
    if same_cert:
        log("signed with the v1.4 key: clean in-place upgrade")
        report.append(f"- Signing: same certificate as v1.4 (`{cert}`) — clean in-place upgrade")
    else:
        log(f"WARNING: new signing key ({cert})")
        report.append(f"- Signing: **new** certificate `{cert}` — uninstall the old module and "
                      "reboot before installing this one")

    # ---- verify the APK --------------------------------------------------
    pkg = ""
    if aapt2.exists():
        pkg = run([aapt2, "dump", "packagename", signed], "aapt2 packagename").strip()
    else:
        with zipfile.ZipFile(signed) as z:
            pkg = EXPECTED_PACKAGE if EXPECTED_PACKAGE.encode("utf-16-le") in \
                z.read("AndroidManifest.xml") else "UNKNOWN"
    if pkg != EXPECTED_PACKAGE:
        die(f"rebuilt APK package is '{pkg}', expected '{EXPECTED_PACKAGE}'")
    with zipfile.ZipFile(signed) as z:
        rebuilt_dex = sorted(n for n in z.namelist() if re.fullmatch(r"classes(\d*)\.dex", n))
        if rebuilt_dex != dex_names:
            die(f"dex set changed: {rebuilt_dex} != {dex_names}")
        if z.getinfo(target_dex).CRC == zipfile.ZipFile(apk_path).getinfo(target_dex).CRC:
            die("patched dex is identical to the base one — nothing was replaced")
        if z.getinfo(target_dex).file_size < 100_000:
            die("patched dex is suspiciously small")
    log(f"APK verified: package {pkg}, dex set unchanged, patched dex is new")
    report.append(f"- Package: `{pkg}`; dex set unchanged ({', '.join(dex_names)})")

    # ---- module zip ------------------------------------------------------
    shutil.copyfile(signed, apk_path)
    for script in MODULE_SCRIPTS:
        src = here / "module-template" / script
        if not src.exists():
            die(f"module template is missing {script}")
        shutil.copyfile(src, mod / script)
        (mod / script).chmod(0o755)
    log(f"installed scripts from the template: {', '.join(MODULE_SCRIPTS)}")
    nums = [int(x) for x in re.findall(r"\d+", version)]
    version_code = (nums[0] * 100 + nums[1]) if len(nums) > 1 else 100
    (mod / "module.prop").write_text(
        "id=axionvoice_dialer\n"
        "name=VoiceFX Dialer\n"
        f"version={version}\n"
        f"versionCode={version_code}\n"
        "author=Rocker14427c\n"
        "description=Side-by-side dialer (com.axionvoice.dialer) with a realtime in-call voice "
        "changer: phase-vocoder pitch shift with independent formant control. The stock Phone app "
        "is untouched; uninstalling the module removes this app, its data and returns the Phone "
        "role to the previous dialer.\n")
    for f in MODULE_FILES:
        if not (mod / f).exists():
            die(f"module is missing {f}")
    for pattern in MODULE_PATTERNS:
        if not list(mod.glob(pattern)):
            die(f"the base module has nothing matching {pattern}; check its layout")

    apk_rel = apk_path.relative_to(mod).as_posix()
    out_zip = out_dir / f"VoiceFX-Dialer-Module-{version}.zip"
    with zipfile.ZipFile(out_zip, "w", zipfile.ZIP_DEFLATED) as zout:
        for p in sorted(mod.rglob("*")):
            if not p.is_file():
                continue
            rel = p.relative_to(mod).as_posix()
            info = zipfile.ZipInfo(rel, date_time=(2026, 1, 1, 0, 0, 0))
            info.external_attr = ((0o100755 if p.suffix == ".sh" else 0o100644) << 16)
            info.compress_type = zipfile.ZIP_DEFLATED
            zout.writestr(info, p.read_bytes())

    # ---- validate the zip the way the module manager will ----------------
    with zipfile.ZipFile(out_zip) as z:
        names = set(z.namelist())
        for required in MODULE_FILES:
            if required not in names:
                die(f"{required} missing from the module zip")
        if apk_rel not in names:
            die(f"{apk_rel} missing from the module zip")
        for pattern in MODULE_PATTERNS:
            if not any(fnmatch.fnmatch(n, pattern) for n in names):
                die(f"nothing in the module zip matches {pattern}")
        if any(n.startswith("/") or ".." in n for n in names):
            die("module zip has unsafe paths")
        if f"version={version}" not in z.read("module.prop").decode():
            die("module.prop was not bumped")
        for n in names:
            if n.endswith(".sh"):
                if ((z.getinfo(n).external_attr >> 16) & 0o111) == 0:
                    die(f"{n} is not executable in the zip")
        if z.testzip() is not None:
            die("module zip is corrupt")
    log("module zip validated (layout, paths, exec bits, crc)")

    zip_md5 = md5_of(out_zip)
    apk_md5 = md5_of(signed)
    shutil.copyfile(signed, out_dir / "AxionVoiceDialer.apk")
    (out_dir / "md5.txt").write_text(
        f"{zip_md5}  {out_zip.name}\n{apk_md5}  AxionVoiceDialer.apk\n")
    report_path = out_dir / f"BUILD_REPORT_{version.upper().replace('.', '')}.md"
    report_path.write_text("\n".join([
        f"# VoiceFX Dialer {version} — build report",
        "",
        "Engine: phase vocoder with identity phase locking, two-pass synthesis and an",
        "independent formant warp; the voice classes are replaced inside the released",
        "v1.4 APK and every other byte of it is untouched.",
        "",
        *report,
        f"- Module zip md5: `{zip_md5}`",
        f"- APK md5: `{apk_md5}`",
        f"- APK sha256: `{sha256_of(signed)}`",
        "",
        "Install: flash the zip in the module manager, reboot.",
        "Uninstall: remove the module, reboot — the uninstaller returns the Phone role,",
        "uninstalls the package and wipes its data.",
        "",
    ]))
    log("BUILD OK")
    log(f"  module: {out_zip}  md5={zip_md5}")
    log(f"  apk   : {out_dir / 'AxionVoiceDialer.apk'}  md5={apk_md5}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
