#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
QQ 音乐扫码登录 -> qq_cred.json -> 推到斐讯 R1 (app 私有 files/ 目录)。Windows / macOS / Linux 通用。

依赖: pip install requests qrcode     (qrcode 可选, 只用来在终端画微信二维码)
用法:
  python tools/qq_login.py --adb 192.168.10.204:5555            # 手机 QQ 扫码(默认)
  python tools/qq_login.py --wx --adb 192.168.10.204:5555       # 微信扫码
  python tools/qq_login.py --adb 192.168.10.204:5555 --restart  # 推完顺便重启音箱 app

流程:
  1. 显示二维码(QQ: 保存 png 并自动打开; 微信: 终端字符二维码 + png)
  2. 手机确认后换到 musicid + musickey(+ refresh_key / refresh_token / loginType 等刷新所需字段)
  3. 用会员链接自检一次(只打印成功/失败, 不打印凭证)
  4. 写临时 qq_cred.json -> adb push /data/local/tmp -> run-as 拷进 files/qq_cred.json(chmod 600)
     -> 删除 /data/local/tmp 副本和电脑上的临时文件

协议参考 L-1124/QQMusicApi (qqmusic-api-python 0.8.x login 模块):
  QQ:  ptqrshow -> ptqrlogin 轮询 -> check_sig 拿 p_skey -> graph.qq.com/oauth2.0/authorize 拿 code
       -> musicu.fcg QQConnectLogin.LoginServer.QQLogin (tmeLoginType=2)
  微信: open.weixin.qq.com qrconnect 拿 uuid -> lp.open.weixin.qq.com 长轮询拿 code
       -> musicu.fcg music.login.LoginServer.Login {code, strAppid} (tmeLoginType=1)

安全: 本脚本从不打印 musickey / refresh_key 等内容, 只打印长度和前 4 位。
"""
import argparse
import json
import os
import random
import re
import shutil
import subprocess
import sys
import tempfile
import time
import uuid

try:
    import requests
except ImportError:
    print("缺少 requests: 请先运行  pip install requests qrcode")
    sys.exit(1)

PKG = "com.phicomm.speaker.device"
MUSICU = "https://u.y.qq.com/cgi-bin/musicu.fcg"
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36")
QQ_APPID = "716027609"
QQ_3RD_AID = "100497308"
WX_APPID = "wx48db31d50e334801"
# 登录失效类错误码(与 QQMusicApi / Q-music 一致)
REJECT_CODES = (1000, 104400, 104401)
# 自检用的歌: 拔萝卜(贝乐虎儿歌), 有 flac/320/128 三档
TEST_MID = "000gsTbY4LFNnl"


def hash33(s, h=0):
    for c in s:
        h = (h << 5) + h + ord(c)
    return 2147483647 & h


def mask(v):
    v = "" if v is None else str(v)
    return "len=%d head=%s" % (len(v), v[:4] + "…" if v else "-")


def log(msg):
    print("[qq_login] " + msg, flush=True)


def die(msg):
    print("[qq_login] 失败: " + msg, flush=True)
    sys.exit(1)


# ---------------------------------------------------------------- musicu
def comm_variants(login_type, cred=None):
    """依次尝试的 comm: 安卓客户端(旧版 QQMusicApi 实测可用) -> 网页。"""
    base_app = {"ct": "11", "cv": "13020508", "v": "13020508", "tmeAppID": "qqmusic",
                "format": "json", "inCharset": "utf-8", "outCharset": "utf-8",
                "uid": str(random.randint(1000000000, 9999999999)),
                "tmeLoginType": str(login_type)}
    base_web = {"ct": "24", "cv": "4747474", "platform": "yqq.json", "chid": "0",
                "format": "json", "inCharset": "utf-8", "outCharset": "utf-8",
                "notice": "0", "needNewCode": "1", "tmeLoginType": str(login_type)}
    if cred:
        base_app.update({"qq": str(cred["musicid"]), "authst": cred["musickey"]})
        base_web.update({"uin": str(cred["musicid"]), "g_tk": str(hash33(cred["musickey"], 5381))})
    return [base_app, base_web]


def musicu_call(module, method, param, login_type, cred=None):
    """返回 (code, data)。逐个 comm 试, 第一个 code==0 即返回。"""
    last = (None, {})
    cookies = {}
    if cred:
        cookies = {"uin": str(cred["musicid"]), "qqmusic_uin": str(cred["musicid"]),
                   "qqmusic_key": cred["musickey"], "qm_keyst": cred["musickey"]}
    for comm in comm_variants(login_type, cred):
        body = {"comm": comm, "req_0": {"module": module, "method": method, "param": param}}
        try:
            r = requests.post(MUSICU, data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
                              headers={"User-Agent": UA, "Content-Type": "application/json",
                                       "Referer": "https://y.qq.com/"},
                              cookies=cookies, timeout=15)
            j = r.json()
        except Exception as e:  # noqa
            last = (None, {"err": str(e)})
            continue
        req = j.get("req_0") or {}
        code = req.get("code", j.get("code"))
        if code == 0:
            return 0, req.get("data") or {}
        last = (code, req.get("data") or {})
    return last


# ---------------------------------------------------------------- QR 显示
def open_file(path):
    try:
        if os.name == "nt":
            os.startfile(path)  # noqa
        elif sys.platform == "darwin":
            subprocess.Popen(["open", path])
        else:
            subprocess.Popen(["xdg-open", path], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except Exception:
        pass


def show_terminal_qr(text):
    try:
        import qrcode
    except ImportError:
        return False
    q = qrcode.QRCode(border=1)
    q.add_data(text)
    q.make(fit=True)
    q.print_ascii(invert=True)
    return True


# ---------------------------------------------------------------- QQ 扫码
def qq_login(tmpdir):
    s = requests.Session()
    s.headers["User-Agent"] = UA
    r = s.get("https://ssl.ptlogin2.qq.com/ptqrshow",
              params={"appid": QQ_APPID, "e": "2", "l": "M", "s": "3", "d": "72", "v": "4",
                      "t": str(random.random()), "daid": "383", "pt_3rd_aid": QQ_3RD_AID},
              headers={"Referer": "https://xui.ptlogin2.qq.com/"}, timeout=15)
    qrsig = r.cookies.get("qrsig")
    if not qrsig:
        die("拿不到 QQ 二维码(qrsig)")
    png = os.path.join(tmpdir, "qq_login_qr.png")
    with open(png, "wb") as f:
        f.write(r.content)
    log("已打开二维码图片: %s  —— 用手机 QQ 扫码并确认登录 QQ 音乐" % png)
    open_file(png)

    token = hash33(qrsig)
    deadline = time.time() + 180
    last = None
    while time.time() < deadline:
        time.sleep(2)
        r = s.get("https://ssl.ptlogin2.qq.com/ptqrlogin",
                  params={"u1": "https://graph.qq.com/oauth2.0/login_jump", "ptqrtoken": str(token),
                          "ptredirect": "0", "h": "1", "t": "1", "g": "1", "from_ui": "1",
                          "ptlang": "2052", "action": "0-0-%d" % int(time.time() * 1000),
                          "js_ver": "20102616", "js_type": "1", "pt_uistyle": "40",
                          "aid": QQ_APPID, "daid": "383", "pt_3rd_aid": QQ_3RD_AID, "has_onekey": "1"},
                  headers={"Referer": "https://xui.ptlogin2.qq.com/"},
                  cookies={"qrsig": qrsig}, timeout=15)
        m = re.search(r"ptuiCB\((.*?)\)", r.text)
        if not m:
            continue
        args = re.findall(r"'((?:\\.|[^'])*)'", m.group(1))
        code = args[0] if args else ""
        if code != last:
            last = code
            log({"66": "等待扫码…", "67": "已扫码, 请在手机上确认…", "65": "二维码已过期",
                 "68": "已拒绝", "0": "QQ 确认成功"}.get(code, "状态 " + code))
        if code in ("65", "68"):
            die("二维码过期或被拒绝, 请重新运行")
        if code != "0":
            continue
        url = args[2] if len(args) > 2 else ""
        sigx = re.findall(r"(?:\?|&)ptsigx=(.+?)&s_url", url)
        uin = re.findall(r"(?:\?|&)uin=(.+?)&service", url)
        if not sigx or not uin:
            die("解析 ptsigx/uin 失败")
        return qq_authorize(s, uin[0], sigx[0])
    die("等待扫码超时(3 分钟)")


def qq_authorize(s, uin, sigx):
    r = s.get("https://ssl.ptlogin2.graph.qq.com/check_sig",
              params={"uin": uin, "pttype": "1", "service": "ptqrlogin", "nodirect": "0",
                      "ptsigx": sigx, "s_url": "https://graph.qq.com/oauth2.0/login_jump",
                      "ptlang": "2052", "ptredirect": "100", "aid": QQ_APPID, "daid": "383",
                      "j_later": "0", "low_login_hour": "0", "regmaster": "0", "pt_login_type": "3",
                      "pt_aid": "0", "pt_aaid": "16", "pt_light": "0", "pt_3rd_aid": QQ_3RD_AID},
              headers={"Referer": "https://xui.ptlogin2.qq.com/"},
              allow_redirects=False, timeout=15)
    p_skey = r.cookies.get("p_skey") or s.cookies.get("p_skey", domain=".graph.qq.com") \
        or s.cookies.get("p_skey")
    if not p_skey:
        die("获取 p_skey 失败")
    r = s.post("https://graph.qq.com/oauth2.0/authorize",
               data={"response_type": "code", "client_id": QQ_3RD_AID,
                     "redirect_uri": "https://y.qq.com/portal/wx_redirect.html?login_type=1&surl=https://y.qq.com/",
                     "scope": "get_user_info,get_app_friends", "state": "state", "switch": "",
                     "from_ptlogin": "1", "src": "1", "update_auth": "1", "openapi": "1010_1030",
                     "g_tk": str(hash33(p_skey, 5381)), "auth_time": str(int(time.time()) * 1000),
                     "ui": str(uuid.uuid4())},
               allow_redirects=False, timeout=15)
    loc = r.headers.get("Location", "")
    code = re.findall(r"(?<=code=)(.+?)(?=&)", loc)
    if not code:
        die("获取 QQ 互联 code 失败")
    # 新版接口优先, 旧版兜底
    rc, data = musicu_call("QQConnectLogin.LoginServer", "QQLogin", {"code": code[0]}, 2)
    if rc != 0:
        log("QQLogin 返回 %s, 改试 music.login.LoginServer.Login" % rc)
        rc, data = musicu_call("music.login.LoginServer", "Login", {"code": code[0]}, 2)
    if rc != 0:
        die("QQ 音乐鉴权失败 code=%s" % rc)
    return data


# ---------------------------------------------------------------- 微信扫码
def wx_login(tmpdir):
    s = requests.Session()
    s.headers["User-Agent"] = UA
    r = s.get("https://open.weixin.qq.com/connect/qrconnect",
              params={"appid": WX_APPID,
                      "redirect_uri": "https://y.qq.com/portal/wx_redirect.html?login_type=2&surl=https://y.qq.com/",
                      "response_type": "code", "scope": "snsapi_login", "state": "STATE",
                      "href": "https://y.qq.com/mediastyle/music_v17/src/css/popup_wechat.css#wechat_redirect"},
              timeout=15)
    m = re.findall(r"uuid=(.+?)\"", r.text)
    if not m:
        die("获取微信二维码 uuid 失败")
    wid = m[0]
    img = s.get("https://open.weixin.qq.com/connect/qrcode/" + wid,
                headers={"Referer": "https://open.weixin.qq.com/connect/qrconnect"}, timeout=15)
    jpg = os.path.join(tmpdir, "wx_login_qr.jpg")
    with open(jpg, "wb") as f:
        f.write(img.content)
    if not show_terminal_qr("https://open.weixin.qq.com/connect/confirm?uuid=" + wid):
        log("(装了 qrcode 包可在终端直接显示二维码)")
    log("二维码图片: %s  —— 用微信扫码并确认" % jpg)
    open_file(jpg)

    deadline = time.time() + 180
    last = None
    while time.time() < deadline:
        try:
            r = s.get("https://lp.open.weixin.qq.com/connect/l/qrconnect",
                      params={"uuid": wid, "_": str(int(time.time()) * 1000)},
                      headers={"Referer": "https://open.weixin.qq.com/"}, timeout=40)
        except requests.exceptions.Timeout:
            continue
        m = re.search(r"window\.wx_errcode=(\d+);window\.wx_code='([^']*)'", r.text)
        if not m:
            time.sleep(1)
            continue
        code, wx_code = m.group(1), m.group(2)
        if code != last:
            last = code
            log({"408": "等待扫码…", "404": "已扫码, 请在手机上确认…", "402": "二维码已过期",
                 "403": "已拒绝", "405": "微信确认成功"}.get(code, "状态 " + code))
        if code in ("402", "403"):
            die("二维码过期或被拒绝, 请重新运行")
        if code == "405" and wx_code:
            rc, data = musicu_call("music.login.LoginServer", "Login",
                                   {"code": wx_code, "strAppid": WX_APPID}, 1)
            if rc != 0:
                die("QQ 音乐微信鉴权失败 code=%s" % rc)
            return data
        time.sleep(1)
    die("等待扫码超时(3 分钟)")


# ---------------------------------------------------------------- 凭证整理 / 自检
FIELDS = ["musicid", "musickey", "refresh_key", "refresh_token", "access_token", "openid",
          "unionid", "str_musicid", "expired_at", "encryptUin", "loginType",
          "keyExpiresIn", "musickeyCreateTime"]


def build_cred(data, login_type):
    cred = {k: data.get(k) for k in FIELDS if data.get(k) not in (None, "")}
    try:
        cred["musicid"] = int(cred.get("musicid") or cred.get("str_musicid") or 0)
    except ValueError:
        pass
    if not cred.get("musicid") or not cred.get("musickey"):
        die("返回里没有 musicid/musickey(字段: %s)" % ",".join(sorted(data.keys())))
    if not cred.get("loginType"):
        cred["loginType"] = 1 if str(cred["musickey"]).startswith("W_X") else login_type
    cred["saved_at"] = int(time.time() * 1000)
    return cred


def self_test(cred):
    lt = int(cred.get("loginType") or 2)
    comm = {"ct": "24", "cv": "0", "format": "json", "tmeAppID": "qqmusic",
            "uin": str(cred["musicid"]), "authst": cred["musickey"], "tmeLoginType": str(lt)}
    fn = ["F000%s.flac" % TEST_MID, "M800%s.mp3" % TEST_MID, "M500%s.mp3" % TEST_MID]
    body = {"comm": comm, "req_0": {"module": "vkey.GetVkeyServer", "method": "CgiGetVkey",
                                   "param": {"guid": str(random.randint(1000000000, 9999999999)),
                                             "songmid": [TEST_MID] * 3, "songtype": [0] * 3,
                                             "filename": fn, "loginflag": 1, "platform": "20",
                                             "uin": str(cred["musicid"])}}}
    try:
        j = requests.post(MUSICU, data=json.dumps(body).encode(), timeout=15,
                          headers={"User-Agent": UA, "Content-Type": "application/json"}).json()
        req = j.get("req_0") or {}
        if req.get("code") != 0:
            log("自检: vkey code=%s (凭证可能无效)" % req.get("code"))
            return
        got = [i.get("filename", "")[:4] for i in (req.get("data") or {}).get("midurlinfo", []) if i.get("purl")]
        log("自检: 可取链接的音质 = %s" % (", ".join(got) or "无"))
    except Exception as e:  # noqa
        log("自检失败(不影响写入): %s" % e)


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


def push_to_device(path, serial, restart):
    if serial and ":" in serial:
        subprocess.run([adb_bin(), "connect", serial], capture_output=True)
    tmp = "/data/local/tmp/qq_cred.json"
    try:
        adb(serial, "push", path, tmp)
        adb(serial, "shell", "chmod 644 " + tmp)
        out = adb(serial, "shell",
                  "run-as %s sh -c 'cat %s > files/qq_cred.json && chmod 600 files/qq_cred.json && echo COPY_OK'"
                  % (PKG, tmp))
        if "COPY_OK" not in out:
            die("run-as 拷贝失败: %s" % out.strip()[:200])
        size = adb(serial, "shell", "run-as %s sh -c 'wc -c < files/qq_cred.json'" % PKG).strip()
        log("已写入音箱 files/qq_cred.json (%s 字节)" % size)
    finally:
        adb(serial, "shell", "rm -f " + tmp, check=False)
        log("已删除 /data/local/tmp 副本")
    if restart:
        adb(serial, "shell", "am force-stop " + PKG, check=False)
        log("已停止 app, 守护进程会自动拉起(或手动重启音箱)")


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


def main():
    ap = argparse.ArgumentParser(description="QQ 音乐扫码登录并推送凭证到斐讯 R1")
    ap.add_argument("--wx", action="store_true", help="用微信扫码(默认手机 QQ)")
    ap.add_argument("--adb", metavar="SERIAL", help="adb 设备, 如 192.168.10.204:5555; 不填用默认设备")
    ap.add_argument("--restart", action="store_true", help="推送后停止 app 让它重新加载")
    ap.add_argument("--no-verify", action="store_true", help="跳过会员链接自检")
    a = ap.parse_args()

    tmpdir = tempfile.mkdtemp(prefix="qqlogin_")
    cred_path = os.path.join(tmpdir, "qq_cred.json")
    try:
        if a.wx:
            data, lt = wx_login(tmpdir), 1
        else:
            data, lt = qq_login(tmpdir), 2
        cred = build_cred(data, lt)
        log("登录成功: musicid %s, musickey %s, refresh_key %s, refresh_token %s, loginType=%s"
            % (mask(cred.get("musicid")), mask(cred.get("musickey")), mask(cred.get("refresh_key")),
               mask(cred.get("refresh_token")), cred.get("loginType")))
        if not cred.get("refresh_key"):
            log("注意: 返回里没有 refresh_key, 音箱端无法自动续期, 过期后需要重新扫码")
        if not a.no_verify:
            self_test(cred)
        fd = os.open(cred_path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(cred, f, ensure_ascii=False)
        push_to_device(cred_path, a.adb, a.restart)
    finally:
        for name in os.listdir(tmpdir):
            secure_delete(os.path.join(tmpdir, name))
        shutil.rmtree(tmpdir, ignore_errors=True)
        log("已删除电脑上的临时文件")


if __name__ == "__main__":
    main()
