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
python build_from_java.py 71
```

（`71` 是版本号，随便递增。）产物：`build_out\r1_v71.apk`。

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
python build_from_java.py 71 --install 192.168.10.204:5555
```

或手动：

```bat
adb connect 192.168.10.204:5555
adb push build_out\r1_v71.apk /sdcard/r1.apk
adb shell "CLASSPATH=/system/framework/pm.jar app_process /system/bin com.android.commands.pm.Pm install -r /sdcard/r1.apk"
adb reboot
```

`-r` 会保留 `files/` 下的 ai_config.ini 和 Muse 配对文件，不用重推。

## 看日志

```bat
adb logcat -d -v time -s Gadget PhicommChat OpenAIClient MusicClient PhicommMusicSearch PostTeaser PersonaManager > r1.log
```

- `MusicClient: lossless ok src=kuwo-mobi q=flac` / `src=gd-netease q=flac(1411k)` → 正在放无损
- `MusicClient: resolved src=kuwo-anti q=mp3(standard)` → 无损没拿到，回落到原来的酷我 MP3
- `MusicClient: resolved src=qq q=flac/320/128` → QQ 音乐会员直链
- `PhicommMusicSearch: prefetch prepared in …ms` / `music playing started (prefetched…)` → 播报期间已缓冲, 播报完立即开播
- 点歌提速(v71): `PhicommMusicSearch: timing: search N candidates in …ms` → 搜索耗时; `first track: qq parallel #k ok in …ms` / `qq parallel all N miss` / `qq budget 3000ms used up -> fallback chain` / `late qq #k ok` → 首曲 3 候选并行取直链结果
  - `timing: resolved …ms after intent -> announce` + `announce: 为您播放…` → 首曲解析好后一律播报, 播报期间缓冲; `timing: music playing …ms after intent` → 意图到真正出声
  - `next prefetch: resolved in …ms, warm-up …ms` / `next: use pre-resolved url` → 下一首预解析(连播/「下一首」不播报)
- `Gadget: ...` → Muse 对话（`first text after …ms` 看 Muse 首字耗时）
- 按句流式播报（Muse 回答边到边念）：
  - `Gadget: stream: first sentence ready after …ms (len=N)` → 发问后第一句切出耗时
  - `Gadget: stream: sentence #k len=N at …ms` → 第 k 句切出
  - `Gadget: stream end (message_done|text quiet 3000ms|deadline 60000ms): sentences=N chars=M in …ms` → 流结束原因
  - `Gadget: stream broken after N sentence(s), keep partial` → 中途断线, 已播的不重问, 下轮重连
  - `PhicommChat: stream turn T begin` / `stream: first sentence ready in …ms (len=N)` / `stream: queued #k len=N`
  - `PhicommChat: stream: speak first sentence …ms after question` → 发问到第一句真正开口(v73 起无垫话、无提示音, 第一句到了直接播)
  - `PhicommChat: stream: speak #k len=N` / `stream: #k TTS end` → 每句开播 / 结束
  - `PhicommChat: stream: next sentence not here yet (…), wait, no ASR` → 中间句播完下一句未到, 等待(不进 ASR)
  - `PhicommChat: stream: all N sentence(s) spoken in …ms -> multi-turn/wakeup` → 最后一句结束, 才进多轮 ASR / 唤醒
  - `PhicommChat: stream turn T cancelled (new question|interrupt …|exit), drop K queued sentence(s)` / `stream: drop stale sentence of turn …` → 打断/新一轮丢弃旧句
  - `PhicommChat: stream: TTS end of #k not received, advance anyway` → 某句结束事件丢失, 看门狗推进(8s+0.35s/字)
  - 日志只打序号/长度/耗时, 不打 token 和凭证; 句子原文写 /sdcard/eav_event.log 的开关 v73 起默认关(PhicommChatHandler.DEBUG_LOG_FILE=false, 调试时改 true)
- v73 新日志关键词:
  - `Gadget: pre-chat drain: clean probe …ms` → 上一轮干净结束, 发问前只探测 30ms; `… dropped N leftover frames in …ms (after clean turn)` → 干净结束后仍有残余帧(已退回 150ms 读完); `stream end (message_done): … clean` → 本轮被判为干净结束
  - `PhicommChat: chat: drop stale reply gen G (now H) at …` → 打断/退出/新一轮后, 旧轮次大模型结果被丢弃(不再主线程忙等 2s)
  - `PhicommMusicSearch: music pool busy (N), run on temp thread` → 音乐后台线程池 4 个都忙, 临时线程兜底(偶发正常, 频繁说明有任务卡住)
- v74 新日志关键词:
  - `Gadget: stream: first sentence ready after …ms (len=N)` → v74 起第一句只要有句末标点且有效字≥1 就切(「好的！」不再被扣留), len 可能很小; 第二句起仍需≥4 个有效字
  - `Gadget: event delta.text_append (first of turn; rest not logged)` → 每轮只记首个文字碎片事件; `Gadget: stream end (…): text_append=N sentences=M …` → 本轮碎片总数
  - `NativeANTEngine: doStopWakeup isWakeUp:true|false`(紧挨 `stream: speak first sentence`) → 等 Muse 期间唤醒是否在跑(true=期间可喊「小爱同学」打断, 首句开播时才停)

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
