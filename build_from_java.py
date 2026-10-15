#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从 Java 源码一键生成 R1 Janus APK —— 不再手改 smali。 Windows / macOS / Linux 通用。

流程:
  1. dex2jar: original_v2/classes.dex -> tools/base.jar (原厂类当编译 classpath, 去掉 custom/)
  2. javac  : 编译 app/src/main/java/com/phicomm/speaker/device/custom/**.java
              + ui/ExampleANTEngineInitializer.java + vui/handler/filter/NLUDispatcher.java
  3. d8     : .class -> classes.dex  (min-api 22)
  4. baksmali: dex -> smali, 整个替换 build_out/smali 里的 custom/ 和上面两个类
              (从 original_v2/smali 复制一份到 build_out/smali 再改, 仓库里的 original_v2 不动)
  5. smali  : 汇编成最终 dex
  6. build_janus_v2.py: 拼到 device_original.apk 前面 -> build_out/r1_v<版本>.apk

需要: JDK 11 或更新 (java/javac 在 PATH 里, 或设 JAVA_HOME); Python 3.
首次运行会自动下载到 tools/: android-22 android.jar, r8.jar(d8), dex2jar。

用法:
  python build_from_java.py 68
  python build_from_java.py 68 --install 192.168.10.204:5555   (可选: 推送安装并重启)
"""
import os
import shutil
import subprocess
import sys
import urllib.request
import zipfile

ROOT = os.path.dirname(os.path.abspath(__file__))
TOOLS = os.path.join(ROOT, "tools")
OUT = os.path.join(ROOT, "build_out")
JAVA_SRC = os.path.join(ROOT, "app", "src", "main", "java")
ORIG_DEX = os.path.join(ROOT, "original_v2", "classes.dex")
ORIG_SMALI = os.path.join(ROOT, "original_v2", "smali")
DEVICE_APK = os.path.join(ROOT, "device_original.apk")
BAKSMALI = os.path.join(ROOT, "baksmali.jar")
SMALI = os.path.join(ROOT, "smali.jar")
JANUS = os.path.join(ROOT, "build_janus_v2.py")

ANDROID_ZIP = "https://dl.google.com/android/repository/android-22_r02.zip"
R8_JAR = "https://dl.google.com/android/maven2/com/android/tools/r8/8.5.35/r8-8.5.35.jar"
D2J_ZIP = "https://github.com/pxb1988/dex2jar/releases/download/v2.4/dex-tools-v2.4.zip"
ANNOT_JAR = "https://dl.google.com/android/maven2/androidx/annotation/annotation/1.0.1/annotation-1.0.1.jar"

CUSTOM_PKG = os.path.join("com", "phicomm", "speaker", "device", "custom")
EXTRA_SOURCES = [
    os.path.join("com", "phicomm", "speaker", "device", "ui", "ExampleANTEngineInitializer.java"),
    os.path.join("com", "unisound", "vui", "handler", "filter", "NLUDispatcher.java"),
]
# 这两个类编出来的 smali 也要替换 (含内部类 $1 $2 ...)
EXTRA_SMALI_PREFIX = [
    (os.path.join("com", "phicomm", "speaker", "device", "ui"), "ExampleANTEngineInitializer"),
    (os.path.join("com", "unisound", "vui", "handler", "filter"), "NLUDispatcher"),
]
SEP = ";" if os.name == "nt" else ":"


def log(msg):
    print("[build] " + msg, flush=True)


def die(msg):
    print("[build] 失败: " + msg, flush=True)
    sys.exit(1)


def tool(name):
    home = os.environ.get("JAVA_HOME")
    if home:
        p = os.path.join(home, "bin", name + (".exe" if os.name == "nt" else ""))
        if os.path.exists(p):
            return p
    p = shutil.which(name)
    if not p:
        die("找不到 %s, 请装 JDK 11+ 并加入 PATH 或设置 JAVA_HOME" % name)
    return p


def run(cmd, cwd=None):
    log(" ".join(os.path.basename(c) if i == 0 else c for i, c in enumerate(cmd))[:300])
    r = subprocess.run(cmd, cwd=cwd)
    if r.returncode != 0:
        die("命令返回 %d" % r.returncode)


def download(url, dest):
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        return
    log("下载 " + url)
    tmp = dest + ".part"
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=300) as r, open(tmp, "wb") as f:
        shutil.copyfileobj(r, f)
    os.replace(tmp, dest)


def ensure_tools():
    os.makedirs(TOOLS, exist_ok=True)
    aj = os.path.join(TOOLS, "android.jar")
    if not os.path.exists(aj):
        z = os.path.join(TOOLS, "android-22.zip")
        download(ANDROID_ZIP, z)
        with zipfile.ZipFile(z) as zf:
            name = [n for n in zf.namelist() if n.endswith("/android.jar")][0]
            with zf.open(name) as src, open(aj, "wb") as dst:
                shutil.copyfileobj(src, dst)
        os.remove(z)
    r8 = os.path.join(TOOLS, "r8.jar")
    download(R8_JAR, r8)
    annot = os.path.join(TOOLS, "annotation-1.0.1.jar")
    download(ANNOT_JAR, annot)
    d2j_dir = os.path.join(TOOLS, "dex-tools-v2.4")
    if not os.path.isdir(d2j_dir):
        z = os.path.join(TOOLS, "dex-tools.zip")
        download(D2J_ZIP, z)
        with zipfile.ZipFile(z) as zf:
            zf.extractall(TOOLS)
        os.remove(z)
    return aj, r8, annot, d2j_dir


def make_base_jar(d2j_dir):
    """原厂 dex -> jar, 去掉 custom/ (要用我们自己的源码)。"""
    base = os.path.join(TOOLS, "base.jar")
    if os.path.exists(base) and os.path.getmtime(base) > os.path.getmtime(ORIG_DEX):
        return base
    full = os.path.join(TOOLS, "orig_full.jar")
    libdir = os.path.join(d2j_dir, "lib")
    cp = SEP.join(os.path.join(libdir, f) for f in os.listdir(libdir) if f.endswith(".jar"))
    run([tool("java"), "-Xmx2g", "-cp", cp, "com.googlecode.dex2jar.tools.Dex2jarCmd",
         "-f", "-o", full, ORIG_DEX])
    with zipfile.ZipFile(full) as zin, zipfile.ZipFile(base, "w", zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            if item.filename.startswith("com/phicomm/speaker/device/custom/"):
                continue
            zout.writestr(item, zin.read(item.filename))
    os.remove(full)
    return base


def collect_sources():
    srcs = []
    for dp, _, files in os.walk(os.path.join(JAVA_SRC, CUSTOM_PKG)):
        for f in files:
            if f.endswith(".java"):
                srcs.append(os.path.join(dp, f))
    for rel in EXTRA_SOURCES:
        p = os.path.join(JAVA_SRC, rel)
        if os.path.exists(p):
            srcs.append(p)
    return sorted(srcs)


def main():
    if len(sys.argv) < 2 or not sys.argv[1].isdigit():
        print(__doc__)
        sys.exit(1)
    ver = sys.argv[1]
    install = None
    if "--install" in sys.argv:
        install = sys.argv[sys.argv.index("--install") + 1]

    for p in (ORIG_DEX, ORIG_SMALI, DEVICE_APK, BAKSMALI, SMALI, JANUS):
        if not os.path.exists(p):
            die("缺少 " + os.path.relpath(p, ROOT))

    aj, r8, annot, d2j_dir = ensure_tools()
    base = make_base_jar(d2j_dir)

    classes = os.path.join(OUT, "classes")
    dexdir = os.path.join(OUT, "dex")
    newsmali = os.path.join(OUT, "smali_new")
    smali_tree = os.path.join(OUT, "smali")
    for d in (classes, dexdir, newsmali, smali_tree):
        shutil.rmtree(d, ignore_errors=True)
        os.makedirs(d)

    # 2. javac  (源文件列表写进 @argfile, 避免 Windows 命令行过长)
    srcs = collect_sources()
    log("编译 %d 个 Java 文件" % len(srcs))
    argfile = os.path.join(OUT, "sources.txt")
    with open(argfile, "w", encoding="utf-8") as f:
        for s in srcs:
            f.write('"' + s.replace("\\", "/") + '"\n')
    run([tool("javac"), "-nowarn", "-encoding", "UTF-8", "-source", "8", "-target", "8",
         "-bootclasspath", aj, "-cp", base + SEP + annot, "-d", classes, "@" + argfile])

    # 3. d8  (先把 .class 打成 jar 再喂给 d8, 避免命令行过长)
    cjar = os.path.join(OUT, "classes.jar")
    with zipfile.ZipFile(cjar, "w", zipfile.ZIP_DEFLATED) as zf:
        for dp, _, files in os.walk(classes):
            for f in files:
                if f.endswith(".class"):
                    full = os.path.join(dp, f)
                    zf.write(full, os.path.relpath(full, classes).replace("\\", "/"))
    run([tool("java"), "-Xmx2g", "-cp", r8, "com.android.tools.r8.D8", "--release",
         "--min-api", "22", "--lib", aj, "--classpath", base, "--output", dexdir, cjar])

    # 4. baksmali + 替换
    run([tool("java"), "-jar", BAKSMALI, "d", "-a", "22", os.path.join(dexdir, "classes.dex"),
         "-o", newsmali])
    log("复制 original_v2/smali -> build_out/smali")
    shutil.rmtree(smali_tree)
    shutil.copytree(ORIG_SMALI, smali_tree)
    shutil.rmtree(os.path.join(smali_tree, CUSTOM_PKG), ignore_errors=True)
    shutil.copytree(os.path.join(newsmali, CUSTOM_PKG), os.path.join(smali_tree, CUSTOM_PKG))
    for rel_dir, cls in EXTRA_SMALI_PREFIX:
        src_dir = os.path.join(newsmali, rel_dir)
        dst_dir = os.path.join(smali_tree, rel_dir)
        if not os.path.isdir(src_dir):
            continue
        for f in os.listdir(dst_dir):
            if f == cls + ".smali" or f.startswith(cls + "$"):
                os.remove(os.path.join(dst_dir, f))
        for f in os.listdir(src_dir):
            if f == cls + ".smali" or f.startswith(cls + "$"):
                shutil.copy2(os.path.join(src_dir, f), dst_dir)

    # 自检: 关键类必须来自源码
    must = [os.path.join(CUSTOM_PKG, "gadget", "GadgetClient.smali"),
            os.path.join(CUSTOM_PKG, "handler", "PhicommChatHandler.smali"),
            os.path.join(CUSTOM_PKG, "music", "LosslessResolver.smali")]
    for m in must:
        if not os.path.exists(os.path.join(smali_tree, m)):
            die("自检失败, 缺少 " + m)
    log("自检通过: GadgetClient / PhicommChatHandler / LosslessResolver 均由 Java 编译生成")

    # 5. smali 汇编
    final_dex = os.path.join(OUT, "classes_v%s.dex" % ver)
    run([tool("java"), "-Xmx2g", "-jar", SMALI, "a", "-a", "22", "-o", final_dex, smali_tree])

    # 6. Janus
    apk = os.path.join(OUT, "r1_v%s.apk" % ver)
    run([sys.executable, JANUS, DEVICE_APK, final_dex, apk])
    log("完成: " + os.path.relpath(apk, ROOT))

    if install:
        adb = shutil.which("adb") or die("找不到 adb")
        run([adb, "connect", install])
        run([adb, "-s", install, "push", apk, "/sdcard/r1.apk"])
        run([adb, "-s", install, "shell", "CLASSPATH=/system/framework/pm.jar app_process /system/bin "
             "com.android.commands.pm.Pm install -r /sdcard/r1.apk"])
        run([adb, "-s", install, "reboot"])
        log("已安装并重启")


if __name__ == "__main__":
    main()
