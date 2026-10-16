# QQ 音乐会员登录（音箱优先用 QQ 音乐放歌）

音箱有 `files/qq_cred.json` 时，放歌顺序是：

**QQ 音乐（无损 F000 → 320k M800 → 128k M500）→ 酷我车机 FLAC → GD 网易 FLAC → 酷我 MP3 → gequbao**

凭证只存在音箱 app 的私有目录（`/data/data/com.phicomm.speaker.device/files/qq_cred.json`，权限 600），
不进仓库、不留在电脑上。音箱每天自动续期一次 musickey；续期失败继续用旧值；
QQ 返回「登录失效」（1000/104400/104401）时自动退回酷我链路，不打断播放。

## 一次性准备（Windows）

1. Python 3（`python --version`）
2. `pip install requests qrcode`（`qrcode` 可选，只用于在终端里画微信二维码）
3. adb（platform-tools）在 PATH 里，电脑和音箱同一局域网

## 首次扫码登录

```bat
git checkout night-opt
git pull
python tools\qq_login.py --adb 192.168.10.204:5555 --restart
```

- 默认是 **手机 QQ 扫码**：脚本会保存二维码 png 并自动打开，用手机 QQ 扫码、在手机上点「同意/登录」。
- 用 **微信** 登录 QQ 音乐的账号：加 `--wx`
  ```bat
  python tools\qq_login.py --wx --adb 192.168.10.204:5555 --restart
  ```
- 脚本会做一次会员链接自检（只打印能拿到的音质，如 `F000, M800, M500`），然后：
  1. 写临时 `qq_cred.json` → `adb push` 到 `/data/local/tmp`
  2. `run-as com.phicomm.speaker.device` 拷到 `files/qq_cred.json` 并 `chmod 600`
  3. 删除 `/data/local/tmp` 副本和电脑上的临时文件
- `--restart` 会 `am force-stop` 让 app 重新加载；不加也行，音箱下次取链接时会发现文件变化自动重载。
- 脚本只打印凭证的长度和前 4 位，不会把 key 打到终端。

## 什么时候需要重新扫码

- 日志出现 `qq refresh skipped ... no refresh_key` 或连续 `qq refresh failed`，且 `qq vkey: credential rejected`
- 换了 QQ 音乐账号

重新跑一遍上面的命令即可（会覆盖旧文件）。

## 看日志

```bat
adb logcat -d -v time -s MusicClient PhicommMusicSearch > music.log
```

- `qq cred loaded: loginType=2 key_len=… refresh_key=yes age_h=…` → 凭证已加载
- `candidates src=qq '拔萝卜': 20` → 搜索走了 QQ
- `resolved src=qq q=flac in 230ms: 拔萝卜 - 贝乐虎儿歌` → QQ 无损直链（q=flac/320/128）
- `qq miss -> kuwo chain ok` → 这首 QQ 没拿到，已退回酷我链路
- `qq refresh ok (daily) …` / `qq refresh failed … keep old key` → 每日续期结果

## 关掉 QQ 链路

删掉凭证即可，音箱自动回到酷我链路：

```bat
adb shell "run-as com.phicomm.speaker.device rm files/qq_cred.json"
```
