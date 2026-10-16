# 编译 R1 APK（Windows / macOS / Linux 通用）

**规则：不要再手改 smali。** 所有改动只改 `app/src/main/java/...` 的 Java 源码，
然后用 `build_from_java.py` 从源码生成 smali → dex → Janus APK。
v65 / v67 出过的问题（`PhicommChatHandler.onASREventEngineInitDone` 把 `p0`(this) 当成 creds 寄存器复用，
导致 `useGadget` 永远 false、Muse 不执行、失败还说「模型调用失败」）都是手改 smali 带进来的；
从 Java 编译就不会有这类寄存器错误。

## 一次性准备

1. JDK 11 或更新（17 也行）。`java -version`、`javac -version` 能出版本号；或设好 `JAVA_HOME`。
2. Python 3（`python --version`）。
3. 联网：第一次运行会自动下载到 `tools/`（已在 .gitignore）：
   - android-22 的 `android.jar`（dl.google.com）
   - `r8.jar`（里面带 d8，dl.google.com maven）
   - dex2jar 2.4（GitHub releases）
   - androidx annotation 1.0.1

不需要 Gradle、不需要 Android Studio、不需要 Android SDK。

## 每次编译

```bat
git fetch
git checkout night-opt
git pull
python build_from_java.py 68
```

（`68` 是版本号，随便递增。）产物：`build_out\r1_v68.apk`。

脚本做的事：
1. `original_v2/classes.dex` → dex2jar → `tools/base.jar`（原厂类当编译 classpath，去掉 custom/）
2. `javac` 编译 `custom/**.java` + `ExampleANTEngineInitializer.java` + `NLUDispatcher.java`
3. `d8 --min-api 22` 转 dex
4. `baksmali` 反成 smali，**整个替换** `custom/` 目录和上面两个类（在 `build_out/smali` 副本里换，仓库的 `original_v2` 不动）
5. 自检：`GadgetClient` / `PhicommChatHandler` / `LosslessResolver` 必须是新编出来的
6. `smali` 汇编 → `build_janus_v2.py` 拼到 `device_original.apk` 前面

任何一步失败会打印「[build] 失败: …」并停下，把整段输出发回来即可。

## 安装

可选让脚本直接装：

```bat
python build_from_java.py 68 --install 192.168.10.204:5555
```

或手动：

```bat
adb connect 192.168.10.204:5555
adb push build_out\r1_v68.apk /sdcard/r1.apk
adb shell "CLASSPATH=/system/framework/pm.jar app_process /system/bin com.android.commands.pm.Pm install -r /sdcard/r1.apk"
adb reboot
```

`-r` 会保留 `files/` 下的 ai_config.ini 和 Muse 配对文件，不用重推。

## 看日志

```bat
adb logcat -d -v time -s Gadget PhicommChat OpenAIClient MusicClient PhicommMusicSearch > r1.log
```

- `MusicClient: lossless ok src=kuwo-mobi q=flac` / `src=gd-netease q=flac(1411k)` → 正在放无损
- `MusicClient: resolved src=kuwo-anti q=mp3(standard)` → 无损没拿到，回落到原来的酷我 MP3
- `MusicClient: resolved src=qq q=flac/320/128` → QQ 音乐会员直链
- `PhicommMusicSearch: prefetch prepared in …ms` / `music playing started (prefetched…)` → 播报期间已缓冲, 播报完立即开播
- `Gadget: ...` → Muse 对话（`first text after …ms` / `reply ready (…) in …ms` 看流式提前返回耗时）

## QQ 音乐会员（可选）

首次扫码登录并把凭证推进音箱（详见 [QQ_LOGIN.md](QQ_LOGIN.md)）：

```bat
pip install requests qrcode
python tools\qq_login.py --adb 192.168.10.204:5555 --restart        # 手机 QQ 扫码
python tools\qq_login.py --wx --adb 192.168.10.204:5555 --restart   # 微信扫码
```

有凭证时放歌走 QQ 音乐（flac → 320k → 128k），没有/失效自动回到酷我链路。
日志：`MusicClient: resolved src=qq q=flac in …ms`。

## 注意

- `original_v2/smali` 是 upstream v91 的输出，除 `custom/` 和上面两个类之外的原厂 smali 都从这里来。
  如果你本地 `original_v2/smali` 里有手改过的东西，以仓库版本为准（`git checkout original_v2`）。
- `MqttTransportChannel` 的 Java 源码在 dex2jar classpath 下编不过（丢了 throws 声明），fork 也没改过它，
  所以沿用 `original_v2/smali` 里已有的版本。
- Gradle 方式（`build_and_deploy.sh`，macOS）仍保留，结果等价；Windows 上用本脚本。
