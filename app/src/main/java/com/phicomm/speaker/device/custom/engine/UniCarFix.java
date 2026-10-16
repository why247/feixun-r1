package com.phicomm.speaker.device.custom.engine;

import android.content.Context;

import com.unisound.vui.engine.ANTEngine;
import com.unisound.vui.engine.ANTEngineOption;
import com.unisound.vui.util.LogMgr;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;

/**
 * v77: 云端每句都带 {@code "uniCarRet":{"returnCode":609,"message":"http post reuqest error"}} 的根因与开关。
 *
 * <p>来源(base.jar 反编译):
 * <ul>
 *   <li>NativeANTEngine.b()(TTS init done 2101 后调用) → d(): 强制 setASROption(1095 = ASR_OPT_WX_SERVICE, true),
 *       再把 ExoConstants.getServiceParam()(deviceToken + activateVer=v3.1)放进 1097 ASR_OPT_SUB_SERVICE_PARAM。</li>
 *   <li>com.unisound.sdk.an(NetRecognition)每次开识别拼参数: filterName=nlu2; 若 aR()(=1095) 为真且没有自定义
 *       additionalService, 追加 {@code additionalService=wx_adapt;}。</li>
 *   <li>云知声服务器收到 wx_adapt 后拿 deviceToken 去 POST 原厂 AIOS 后端(ANTConfigPreference 里同一个
 *       aios-home.hivoice.cn; 旧抓包里 uniCarRet.message 就是 "aios-home.hivoice.cn")。该域名已 NXDOMAIN,
 *       所以每句都是 609。请求是服务器发的, 不是设备发的; 结果在录音开始后 ~80ms 的第一个 partial 里就到了,
 *       不在「说完→出结果」的关键路径上。</li>
 * </ul>
 *
 * <p>做法: 引擎初始化完成后(以及任何一句结果里还看见 uniCarRet 时)把 1095 置 false, 下一次识别起服务器不再调
 * wx_adapt。云端 NLU(chat/setting/天气/闹钟等云知声自家技能)和 ASR 文字不受影响。
 *
 * <p>开关: 常量 {@link #DEFAULT_ENABLED}; 设备 files/ai_config.ini 里 {@code unicar_fix = false} 可关(恢复原厂 true)。
 * 日志 tag NLUDispatcher, 关键词 {@code unicar609:}。
 */
public final class UniCarFix {
    private static final String TAG = "NLUDispatcher";
    /** 默认开启修复(关闭 wx_adapt)。 */
    public static final boolean DEFAULT_ENABLED = true;
    private static final String INI_KEY = "unicar_fix";

    private static volatile Boolean enabled;
    private static volatile int applyCount = 0;
    private static volatile int seenCount = 0;
    private static volatile int cleanLogged = 0;
    private static volatile long lastSeenAt = 0;

    private UniCarFix() {
    }

    public static boolean isEnabled(Context ctx) {
        Boolean e = enabled;
        if (e != null) return e;
        boolean v = DEFAULT_ENABLED;
        String src = "default";
        try {
            if (ctx != null) {
                String s = readIni(new File(ctx.getFilesDir(), "ai_config.ini"), INI_KEY);
                if (s != null) {
                    s = s.trim().toLowerCase();
                    if (s.equals("false") || s.equals("0") || s.equals("off") || s.equals("no")) { v = false; src = "ini"; }
                    else if (s.equals("true") || s.equals("1") || s.equals("on") || s.equals("yes")) { v = true; src = "ini"; }
                }
            }
        } catch (Throwable ignored) { }
        enabled = v;
        LogMgr.i(TAG, "unicar609: fix " + (v ? "ON" : "OFF") + " (" + src + ", ai_config.ini " + INI_KEY + "=true|false)");
        return v;
    }

    /** 只读 key 本身这一行(不读其它任何字段)。 */
    private static String readIni(File f, String key) {
        if (f == null || !f.isFile()) return null;
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                String t = line.trim();
                if (t.startsWith("#") || t.startsWith(";")) continue;
                int eq = t.indexOf('=');
                if (eq <= 0) continue;
                if (!t.substring(0, eq).trim().equalsIgnoreCase(key)) continue;
                String v = t.substring(eq + 1);
                int hash = v.indexOf('#');
                if (hash >= 0) v = v.substring(0, hash);
                return v.trim();
            }
        } catch (Throwable ignored) {
        } finally {
            try { if (br != null) br.close(); } catch (Throwable ignored) { }
        }
        return null;
    }

    /** 关闭 ASR_OPT_WX_SERVICE(1095), 对下一次识别生效。 */
    public static void apply(ANTEngine engine, Context ctx, String why) {
        if (engine == null || !isEnabled(ctx)) return;
        try {
            Object before = null;
            try { before = engine.config().getOption(ANTEngineOption.ASR_OPT_WX_SERVICE); } catch (Throwable ignored) { }
            engine.config().setOption(ANTEngineOption.ASR_OPT_WX_SERVICE, Boolean.FALSE);
            applyCount++;
            LogMgr.i(TAG, "unicar609: wx_service " + before + " -> false (" + why + ", #" + applyCount + ")");
        } catch (Throwable t) {
            LogMgr.e(TAG, "unicar609: apply failed (" + why + "): " + t);
        }
    }

    /**
     * 每条云端结果调用。还带 uniCarRet → 说明 1095 又被原厂置回 true(或修复关着), 打日志并重新关闭;
     * 修复生效后的完整结果打一行 clean(前 5 次)。
     */
    public static void onNetResult(ANTEngine engine, Context ctx, String result) {
        if (result == null) return;
        try {
            if (result.indexOf("uniCarRet") >= 0) {
                long now = System.currentTimeMillis();
                if (now - lastSeenAt < 6000) return;   // 同一句的 partial/net_nlu/full 大致只算一次
                lastSeenAt = now;
                seenCount++;
                boolean on = isEnabled(ctx);
                if (seenCount <= 5 || seenCount % 20 == 0) {
                    LogMgr.w(TAG, "unicar609: uniCarRet still in result (turn #" + seenCount + ")"
                            + (on ? " -> reapply wx_service=false" : " (fix OFF, factory behaviour)"));
                    if (on) apply(engine, ctx, "seen in result");
                }
            } else if (result.indexOf("\"net_nlu\"") >= 0 && cleanLogged < 5) {
                cleanLogged++;
                LogMgr.i(TAG, "unicar609: clean net_nlu (no uniCarRet) #" + cleanLogged);
            }
        } catch (Throwable ignored) { }
    }
}
