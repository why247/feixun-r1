#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把腾讯云实时语音识别的密钥写进斐讯 R1 (app files/tencent_asr.json), 只用 Python 标准库。

用法:
  python tools/tencent_asr_push.py --adb 192.168.10.204:5555 --restart
  python tools/tencent_asr_push.py --adb 192.168.10.204:5555 --engine 16k_zh      # 只用普通话免费额度
  python tools/tencent_asr_push.py --adb 192.168.10.204:5555 --remove --restart   # 删除配置, 回到纯云知声

流程:
  1. 在本机终端里输入 AppID / SecretId / SecretKey(SecretId/SecretKey 输入时不回显, 脚本绝不打印)
  2. 自检: 用 1 秒静音连一次腾讯 wss, 报告 code(0=可用, 4002=密钥错, 4003=未开通, 4004=该引擎没有可用额度…)
  3. 写临时 tencent_asr.json(权限 600) -> adb push /data/local/tmp -> run-as 拷进 files/(chmod 600)
     -> 删除 /data/local/tmp 副本和电脑上的临时文件
  不要把密钥发到聊天里, 也不要提交到 git(.gitignore 已忽略 tencent_asr*.json)。

引擎(engine_model_type):
  16k_zh_en_2.0  默认。大模型2.0, 中英粤 + 31 种方言(含潮汕话)。没有免费额度, 需买资源包或开后付费
  16k_zh_en      大模型1.0, 同样支持潮汕话, 没有免费额度, 价格约为 2.0 的 4~5 倍
  16k_zh         通用普通话, 每月 5 小时免费额度只覆盖这类通用引擎, 不认潮汕话
"""
import argparse
import base64
import getpass
import hashlib
import hmac
import json
import os
import random
import shutil
import socket
import ssl
import subprocess
import sys
import tempfile
import time
import urllib.parse
import uuid

PKG = "com.phicomm.speaker.device"
HOST = "asr.cloud.tencent.com"
VAD_SILENCE_ENGINES = {"8k_zh", "8k_zh_finance", "16k_zh", "16k_zh_en", "8k_zh_large", "16k_en", "16k_en_large"}
CODE_HINT = {
    0: "可用",
    4001: "参数不合法(引擎名写错?)",
    4002: "鉴权失败: SecretId/SecretKey 不对, 或电脑时间不准",
    4003: "AppID 没有开通语音识别服务: 控制台 -> 语音识别 -> 开通",
    4004: "这个引擎没有可用额度: 大模型引擎没有免费额度, 需购买对应资源包或在控制台开启后付费",
    4005: "账户欠费",
    6001: "被识别为境外调用(关掉代理/VPN)",
}


def log(msg):
    print("[tencent_asr] " + msg, flush=True)


def die(msg):
    log("失败: " + msg)
    sys.exit(1)


# ---------------------------------------------------------------- 自检(1 秒静音)
def ws_check(appid, sid, skey, engine):
    ts = int(time.time())
    p = {
        "engine_model_type": engine,
        "expired": str(ts + 3600),
        "needvad": "1",
        "nonce": str(random.randint(1, 999999999)),
        "secretid": sid,
        "timestamp": str(ts),
        "voice_format": "1",
        "voice_id": str(uuid.uuid4()),
    }
    if engine in VAD_SILENCE_ENGINES:
        p["vad_silence_time"] = "500"
    q = "&".join("%s=%s" % (k, p[k]) for k in sorted(p))
    path = "/asr/v2/%s?%s" % (appid, q)
    sig = base64.b64encode(hmac.new(skey.encode(), (HOST + path).encode(), hashlib.sha1).digest()).decode()
    path += "&signature=" + urllib.parse.quote(sig, safe="")
    s = ssl.create_default_context().wrap_socket(socket.create_connection((HOST, 443), timeout=8),
                                                 server_hostname=HOST)
    try:
        key = base64.b64encode(os.urandom(16)).decode()
        s.sendall(("GET %s HTTP/1.1\r\nHost: %s\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                   "Sec-WebSocket-Key: %s\r\nSec-WebSocket-Version: 13\r\n\r\n" % (path, HOST, key)).encode())
        buf = b""
        while b"\r\n\r\n" not in buf:
            d = s.recv(4096)
            if not d:
                return -1, "连接被关闭"
            buf += d
        head, buf = buf.split(b"\r\n\r\n", 1)
        if b" 101 " not in head.split(b"\r\n")[0]:
            return -1, head.split(b"\r\n")[0].decode(errors="replace")

        def recv_msg():
            nonlocal buf
            while True:
                while len(buf) < 2:
                    d = s.recv(4096)
                    if not d:
                        raise EOFError
                    buf += d
                op, ln = buf[0] & 0x0F, buf[1] & 0x7F
                off = 2
                if ln == 126:
                    while len(buf) < 4:
                        buf += s.recv(4096)
                    ln, off = int.from_bytes(buf[2:4], "big"), 4
                elif ln == 127:
                    while len(buf) < 10:
                        buf += s.recv(4096)
                    ln, off = int.from_bytes(buf[2:10], "big"), 10
                while len(buf) < off + ln:
                    d = s.recv(4096)
                    if not d:
                        raise EOFError
                    buf += d
                data, buf = buf[off:off + ln], buf[off + ln:]
                if op == 0x8:
                    raise EOFError
                if op in (0x1, 0x2):
                    return data.decode(errors="replace")

        def send(op, payload):
            m = os.urandom(4)
            n = len(payload)
            h = bytes([0x80 | op])
            if n < 126:
                h += bytes([0x80 | n])
            elif n < 65536:
                h += bytes([0x80 | 126]) + n.to_bytes(2, "big")
            else:
                h += bytes([0x80 | 127]) + n.to_bytes(8, "big")
            s.sendall(h + m + bytes(b ^ m[i & 3] for i, b in enumerate(payload)))

        first = json.loads(recv_msg())
        code = int(first.get("code", -1))
        if code != 0:
            return code, first.get("message", "")
        for _ in range(5):            # 1 秒静音, 200ms 一包
            send(0x2, b"\0" * 6400)
            time.sleep(0.2)
        send(0x1, b'{"type":"end"}')
        deadline = time.time() + 8
        while time.time() < deadline:
            try:
                o = json.loads(recv_msg())
            except EOFError:
                break
            c = int(o.get("code", 0))
            if c != 0:
                return c, o.get("message", "")
            if o.get("final") == 1:
                return 0, "final ok"
        return 0, "握手成功(未等到 final)"
    finally:
        s.close()


# ---------------------------------------------------------------- adb
def adb_bin():
    a = shutil.which("adb")
    if not a:
        die("找不到 adb, 请装 platform-tools 并加入 PATH")
    return a


def adb(serial, *args, check=True):
    cmd = [adb_bin()] + (["-s", serial] if serial else []) + list(args)
    r = subprocess.run(cmd, capture_output=True, text=True)
    if check and r.returncode != 0:
        die("adb %s 失败: %s" % (args[0], (r.stderr or r.stdout).strip()[:200]))
    return (r.stdout or "") + (r.stderr or "")


def push_to_device(path, serial):
    tmp = "/data/local/tmp/tencent_asr.json"
    try:
        adb(serial, "push", path, tmp)
        adb(serial, "shell", "chmod 644 " + tmp)
        out = adb(serial, "shell",
                  "run-as %s sh -c 'cat %s > files/tencent_asr.json && chmod 600 files/tencent_asr.json && echo COPY_OK'"
                  % (PKG, tmp))
        if "COPY_OK" not in out:
            die("run-as 拷贝失败: %s" % out.strip()[:200])
        size = adb(serial, "shell", "run-as %s sh -c 'wc -c < files/tencent_asr.json'" % PKG).strip()
        log("已写入音箱 files/tencent_asr.json (%s 字节)" % size)
    finally:
        adb(serial, "shell", "rm -f " + tmp, check=False)
        log("已删除 /data/local/tmp 副本")


def secure_delete(path):
    try:
        n = os.path.getsize(path)
        with open(path, "r+b") as f:
            f.write(b"\0" * n)
            f.flush()
            os.fsync(f.fileno())
    except Exception:
        pass
    try:
        os.remove(path)
    except Exception:
        pass


def ask(prompt, secret=False):
    v = (getpass.getpass(prompt) if secret else input(prompt)).strip()
    if not v:
        die("没有输入")
    return v


def main():
    ap = argparse.ArgumentParser(description="把腾讯云语音识别密钥推送到斐讯 R1")
    ap.add_argument("--adb", metavar="SERIAL", help="adb 设备, 如 192.168.10.204:5555; 不填用默认设备")
    ap.add_argument("--engine", default="16k_zh_en_2.0",
                    help="识别引擎, 默认 16k_zh_en_2.0(支持潮汕话, 无免费额度); 16k_zh 用每月 5 小时免费额度但只认普通话")
    ap.add_argument("--timeout-ms", type=int, default=4000, help="说完后最多等腾讯结果多久(默认 4000), 超时回退云知声")
    ap.add_argument("--no-keep-native", action="store_true",
                    help="云知声识别成天气/闹钟/设置等原生技能时也改用腾讯文字(默认保留云知声原生技能)")
    ap.add_argument("--restart", action="store_true", help="推送后停止 app 让它重新加载(配置其实会自动重载)")
    ap.add_argument("--no-verify", action="store_true", help="跳过联网自检")
    ap.add_argument("--remove", action="store_true", help="删除音箱上的配置, 回到纯云知声识别")
    a = ap.parse_args()

    if a.adb and ":" in a.adb:
        subprocess.run([adb_bin(), "connect", a.adb], capture_output=True)

    if a.remove:
        adb(a.adb, "shell", "run-as %s rm -f files/tencent_asr.json" % PKG, check=False)
        log("已删除音箱 files/tencent_asr.json, 之后完全走云知声识别")
        if a.restart:
            adb(a.adb, "shell", "am force-stop " + PKG, check=False)
        return

    log("密钥在 腾讯云控制台 -> 访问管理 -> API密钥管理(https://console.cloud.tencent.com/cam/capi)")
    appid = ask("AppID(纯数字): ")
    if not appid.isdigit():
        die("AppID 应该是纯数字")
    sid = ask("SecretId(输入不显示): ", secret=True)
    skey = ask("SecretKey(输入不显示): ", secret=True)
    log("已读取: AppID %d 位, SecretId %d 位, SecretKey %d 位, 引擎 %s" % (len(appid), len(sid), len(skey), a.engine))

    if not a.no_verify:
        try:
            code, msg = ws_check(appid, sid, skey, a.engine)
        except Exception as e:  # noqa
            code, msg = -1, "%s" % type(e).__name__
        hint = CODE_HINT.get(code, msg)
        log("自检 code=%s: %s" % (code, hint))
        if code != 0:
            if input("自检没通过, 仍然写入音箱? [y/N] ").strip().lower() != "y":
                die("已取消, 没有写入任何东西")

    cfg = {
        "appid": appid,
        "secret_id": sid,
        "secret_key": skey,
        "engine": a.engine,
        "timeout_ms": a.timeout_ms,
        "vad_silence_ms": 500,
        "keep_native": not a.no_keep_native,
    }
    tmpdir = tempfile.mkdtemp(prefix="txasr_")
    path = os.path.join(tmpdir, "tencent_asr.json")
    try:
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(cfg, f, ensure_ascii=False)
        push_to_device(path, a.adb)
    finally:
        secure_delete(path)
        shutil.rmtree(tmpdir, ignore_errors=True)
        log("已删除电脑上的临时文件")
    if a.restart:
        adb(a.adb, "shell", "am force-stop " + PKG, check=False)
        log("已停止 app, 守护进程会自动拉起(或手动重启音箱)")
    log("完成。看日志: adb logcat -v time -s TencentASR NLUDispatcher PhicommChat")


if __name__ == "__main__":
    main()
