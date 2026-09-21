# -*- coding: utf-8 -*-
"""
编译 广告过滤 App —— 不用 Gradle、不用 Android Studio，
直接用 Android SDK 的命令行工具：aapt2 -> javac -> d8 -> zipalign -> 签名

用法：  python build.py
"""
import subprocess, os, sys, shutil, zipfile, glob, datetime

ROOT  = r'D:\Thirty_Code\Android\RUN'
SDK   = os.path.join(ROOT, 'toolchain', 'android-sdk')
BT    = os.path.join(SDK, 'build-tools', '34.0.0')
APP   = os.path.join(ROOT, 'app')

AAPT2     = os.path.join(BT, 'aapt2.exe')
# 直接调 jar，避免 Windows 下 .bat 脚本的调用坑
D8_JAR    = os.path.join(BT, 'lib', 'd8.jar')
ZIPALIGN  = os.path.join(BT, 'zipalign.exe')
ANDROID_JAR = os.path.join(SDK, 'platforms', 'android-35', 'android.jar')
SIGNER    = os.path.join(ROOT, 'toolchain', 'bin', 'uber-apk-signer.jar')

BUILD = os.path.join(APP, 'build')
OBJ   = os.path.join(BUILD, 'obj')
GEN   = os.path.join(BUILD, 'gen')
OUT   = os.path.join(APP, 'output')

log = []
def L(m=''):
    line = f"{datetime.datetime.now():%H:%M:%S}  {m}"
    log.append(line)
    try: print(line)
    except Exception: pass

def flush():
    with open(os.path.join(BUILD, 'build_log.txt'), 'w', encoding='utf-8') as f:
        f.write("\n".join(log))

def run(cmd, cwd=None):
    L("    $ " + " ".join(str(c) for c in cmd)[:160])
    r = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True,
                       encoding='utf-8', errors='replace')
    for ln in (r.stdout or '').splitlines():
        if ln.strip(): L("      | " + ln.strip()[:200])
    for ln in (r.stderr or '').splitlines():
        if ln.strip(): L("      ! " + ln.strip()[:200])
    return r.returncode

def main():
    # 检查工具
    for name, path in [('aapt2', AAPT2), ('d8.jar', D8_JAR), ('zipalign', ZIPALIGN),
                       ('android.jar', ANDROID_JAR), ('signer', SIGNER)]:
        if not os.path.exists(path):
            L(f"缺少 {name}: {path}")
            flush(); sys.exit(1)

    for d in (BUILD, OBJ, GEN, OUT):
        os.makedirs(d, exist_ok=True)

    L("=" * 70)
    L("1/6  aapt2 编译资源")
    L("=" * 70)
    res_zip = os.path.join(BUILD, 'res.zip')
    if os.path.exists(res_zip): os.remove(res_zip)
    rc = run([AAPT2, 'compile', '--dir', os.path.join(APP, 'res'), '-o', res_zip])
    if rc != 0: L("资源编译失败"); flush(); sys.exit(1)

    L("")
    L("=" * 70)
    L("2/6  aapt2 链接（生成 APK 骨架 + resources.arsc）")
    L("=" * 70)
    base_apk = os.path.join(BUILD, 'base.apk')
    if os.path.exists(base_apk): os.remove(base_apk)
    link_cmd = [AAPT2, 'link', '-I', ANDROID_JAR,
              '--manifest', os.path.join(APP, 'AndroidManifest.xml'),
              '-o', base_apk, '--java', GEN,
              # targetSdk 33 而非 35：Android 14 起要求前台服务必须声明
              # foregroundServiceType，33 不受该限制，能少一类崩溃
              '--min-sdk-version', '24', '--target-sdk-version', '33']
    assets_dir = os.path.join(APP, 'assets')
    if os.path.isdir(assets_dir):
        L(f"    打包 assets: {assets_dir}")
        link_cmd += ['-A', assets_dir]
    link_cmd += [res_zip]
    rc = run(link_cmd)
    if rc != 0: L("链接失败"); flush(); sys.exit(1)

    L("")
    L("=" * 70)
    L("3/6  javac 编译 Java 源码")
    L("=" * 70)
    sources = glob.glob(os.path.join(APP, 'src', 'com', 'lab', 'adfilter', '*.java'))
    L(f"    源文件: {[os.path.basename(s) for s in sources]}")
    rc = run(['javac', '-encoding', 'UTF-8', '-source', '11', '-target', '11',
              '-classpath', ANDROID_JAR, '-d', OBJ] + sources)
    if rc != 0: L("Java 编译失败"); flush(); sys.exit(1)

    L("")
    L("=" * 70)
    L("4/6  d8 转换为 DEX")
    L("=" * 70)
    classes = glob.glob(os.path.join(OBJ, 'com', 'lab', 'adfilter', '*.class'))
    if not classes:
        L("没有生成 class 文件"); flush(); sys.exit(1)
    dex_out = os.path.join(BUILD, 'dex')
    if os.path.isdir(dex_out): shutil.rmtree(dex_out)
    os.makedirs(dex_out)
    # d8.jar 无可执行主清单，须用 -cp 指定主类 com.android.tools.r8.D8
    rc = run(['java', '-cp', D8_JAR, 'com.android.tools.r8.D8',
              '--lib', ANDROID_JAR, '--output', dex_out] + classes)
    dex_file = os.path.join(dex_out, 'classes.dex')
    if rc != 0 or not os.path.exists(dex_file):
        L("DEX 生成失败"); flush(); sys.exit(1)
    L(f"    -> classes.dex  {os.path.getsize(dex_file)} bytes")

    L("")
    L("=" * 70)
    L("5/6  打包 + 对齐")
    L("=" * 70)
    unsigned = os.path.join(BUILD, 'unsigned.apk')
    shutil.copy(base_apk, unsigned)
    with zipfile.ZipFile(unsigned, 'a', zipfile.ZIP_DEFLATED) as z:
        z.write(dex_file, 'classes.dex')
    L(f"    已写入 classes.dex -> {os.path.getsize(unsigned)} bytes")

    aligned = os.path.join(BUILD, 'aligned.apk')
    if os.path.exists(aligned): os.remove(aligned)
    rc = run([ZIPALIGN, '-p', '4', unsigned, aligned])
    if rc != 0 or not os.path.exists(aligned):
        L("对齐失败"); flush(); sys.exit(1)

    L("")
    L("=" * 70)
    L("6/6  签名")
    L("=" * 70)
    for f in glob.glob(os.path.join(OUT, '*.apk')):
        os.remove(f)
    rc = run(['java', '-jar', SIGNER, '-a', aligned, '-o', OUT, '--allowResign'])

    L("")
    L("=" * 70)
    L("结果")
    L("=" * 70)
    found = False
    for f in sorted(glob.glob(os.path.join(OUT, '*.apk'))):
        if f.endswith('.idsig'): continue
        L(f"  [OK] {os.path.basename(f)}   {os.path.getsize(f):,} bytes")
        L(f"       {f}")
        found = True
    if not found:
        L("  未生成 APK")
    flush()

if __name__ == '__main__':
    main()
