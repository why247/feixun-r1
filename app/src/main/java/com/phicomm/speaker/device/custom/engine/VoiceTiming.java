package com.phicomm.speaker.device.custom.engine;

import com.unisound.vui.util.LogMgr;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 语音链路时间点(量化唤醒 -> ASR 结束 -> 意图 -> 开口/开播)。日志 tag 统一 PhicommChat, 前缀 "timing:"。
 * 只记时间和长度, 不记识别文字以外的任何内容。
 */
public final class VoiceTiming {
    private static final String TAG = "PhicommChat";
    private static volatile long wakeAt = 0;
    private static volatile long recStartAt = 0;
    private static volatile long speechEndAt = 0;
    private static volatile long recStopAt = 0;
    private static volatile long asrEndAt = 0;

    private VoiceTiming() {
    }

    private static String clock(long t) {
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date(t));
    }

    private static String since(String what, long from, long now) {
        return from > 0 && now >= from ? ", +" + (now - from) + "ms since " + what : "";
    }

    /** 唤醒词命中 */
    public static void wake(String word) {
        long now = System.currentTimeMillis();
        wakeAt = now;
        recStartAt = speechEndAt = recStopAt = asrEndAt = 0;
        LogMgr.d(TAG, "timing: wakeup at " + clock(now) + (word == null ? "" : " word_len=" + word.length()));
    }

    /** ASR 开始录音(唤醒后/多轮) */
    public static void recordingStart() {
        long now = System.currentTimeMillis();
        recStartAt = now;
        speechEndAt = recStopAt = asrEndAt = 0;
        LogMgr.d(TAG, "timing: ASR recording start at " + clock(now) + since("wakeup", wakeAt, now));
    }

    /** VAD 判定说完 */
    public static void speechEnd() {
        long now = System.currentTimeMillis();
        speechEndAt = now;
        LogMgr.d(TAG, "timing: ASR speech end at " + clock(now) + since("wakeup", wakeAt, now)
                + since("recording start", recStartAt, now));
    }

    /** 停止录音 */
    public static void recordingStop() {
        long now = System.currentTimeMillis();
        recStopAt = now;
        LogMgr.d(TAG, "timing: ASR recording stop at " + clock(now) + since("speech end", speechEndAt, now));
    }

    /** 识别/云端结果结束(取第一次) */
    public static void asrEnd(String what) {
        long now = System.currentTimeMillis();
        boolean first = asrEndAt == 0;
        if (first) asrEndAt = now;
        LogMgr.d(TAG, "timing: ASR end (" + what + ") at " + clock(now) + since("wakeup", wakeAt, now)
                + since("speech end", speechEndAt, now) + (first ? "" : " (not first)"));
    }

    /** 距本轮 ASR 录音开始的毫秒数, 没有记录返回 -1(v76 控制词早分发日志用) */
    public static long sinceRecordingStart() {
        long rs = recStartAt;
        return rs > 0 ? System.currentTimeMillis() - rs : -1;
    }

    /** handler 拿到 NLU 意图 */
    public static void intent(String who, String text) {
        long now = System.currentTimeMillis();
        LogMgr.d(TAG, "timing: intent -> " + who + " at " + clock(now) + since("wakeup", wakeAt, now)
                + since("speech end", speechEndAt, now) + since("ASR end", asrEndAt, now)
                + " text_len=" + (text == null ? 0 : text.length()));
    }
}
