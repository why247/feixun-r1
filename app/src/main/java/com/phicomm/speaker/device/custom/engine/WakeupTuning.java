package com.phicomm.speaker.device.custom.engine;

import android.content.Context;

import com.unisound.vui.common.config.ANTConfigPreference;
import com.unisound.vui.util.LogMgr;

import java.io.File;

/**
 * v79: 「小爱同学」主唤醒门限放宽, 照顾小孩的声音。
 *
 * <p>原厂 assets/config/config.mg: wakeup_benchmark=-9.1(安静时), 播音乐时反而更宽(-11.3/-12.3/-13.3)。
 * 真机日志里小孩喊 -11.44/-11.5 被判「Not a main wakeup word」, 大人一般 -2.4~+2.9。
 * 这里把生效门限夹到不高于 {@link #DEFAULT_BENCHMARK}(只放宽, 不收紧; 音乐大音量时原厂更宽的值保持)。
 *
 * <p>设备 files/ai_config.ini 可调: {@code wakeup_benchmark = -12.0}(越小越灵敏、越容易误唤醒);
 * 写 {@code wakeup_benchmark = off} 恢复原厂。
 */
public final class WakeupTuning {
    private static final String TAG = "NLUDispatcher";
    private static final String INI_KEY = "wakeup_benchmark";
    static final float DEFAULT_BENCHMARK = -12.0f;

    private static volatile Float target;   // NaN = off
    private static volatile float lastLogged = Float.NaN;

    private WakeupTuning() {
    }

    private static float target(Context ctx) {
        Float t = target;
        if (t != null) return t;
        float v = DEFAULT_BENCHMARK;
        String src = "default";
        try {
            if (ctx != null) {
                String s = UniCarFix.readIni(new File(ctx.getFilesDir(), "ai_config.ini"), INI_KEY);
                if (s != null && s.length() > 0) {
                    String l = s.trim().toLowerCase();
                    if (l.equals("off") || l.equals("false") || l.equals("no")) { v = Float.NaN; src = "ini off"; }
                    else {
                        float f = Float.parseFloat(l);
                        if (f >= -30f && f <= 5f) { v = f; src = "ini"; }
                    }
                }
            }
        } catch (Throwable ignored) { }
        target = v;
        LogMgr.i(TAG, "wakeup-tune: target " + (Float.isNaN(v) ? "OFF" : String.valueOf(v)) + " (" + src + ", ai_config.ini " + INI_KEY + "=-12.0|off)");
        return v;
    }

    /** 每次唤醒判定前调用: 生效门限高于目标时压到目标值。 */
    public static void clamp(Context ctx) {
        try {
            float t = target(ctx);
            if (Float.isNaN(t)) return;
            float cur = ANTConfigPreference.effectWakeupBenchmark;
            if (cur > t) {
                ANTConfigPreference.effectWakeupBenchmark = t;
                if (ANTConfigPreference.wakeupBenchmark > t) ANTConfigPreference.wakeupBenchmark = t;
                if (ANTConfigPreference.wakeupBenchmarkForContinues > t) ANTConfigPreference.wakeupBenchmarkForContinues = t;
                if (ANTConfigPreference.wakeupBenchmarkForMusicPlaying > t) ANTConfigPreference.wakeupBenchmarkForMusicPlaying = t;
                if (lastLogged != cur) {
                    lastLogged = cur;
                    LogMgr.i(TAG, "wakeup-tune: benchmark " + cur + " -> " + t);
                }
            }
        } catch (Throwable ignored) { }
    }
}
