package com.phicomm.speaker.device.custom.music;

import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;

import com.unisound.vui.common.media.UniMediaPlayer;
import com.unisound.vui.util.LogMgr;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * TTS 播报期间就让原厂 UniMediaPlayer 的 MediaPlayer 开始 prepare(缓冲), TTS 一结束立刻 start。
 *
 * UniMediaPlayer.playUrl() 是 prepareAsync + onPrepared 里直接 start, 没有"只缓冲不播"的入口,
 * 所以这里用反射拿它内部的 mMediaPlayer, 按 playUrl 同样的步骤准备, 但把 start 推迟到 startWhenReady()。
 * 播放标签(playTag)/状态回调(callBackPlayerState)/音频焦点(requestFocus) 都走原厂私有方法,
 * 现有的「播完(4)自动下一首」监听保持不变。
 *
 * 任何一步反射失败、prepare 出错、或 15 秒还没 prepare 好(播放器被提示音等抢走), 都回落到
 * 原来的 UniMediaPlayer.playUrl(url, tag)。所有操作都在主线程执行。
 */
public final class MusicPrefetcher {
    private static final String TAG = "PhicommMusicSearch";
    private static final long PREPARE_WAIT_MS = 15_000;

    private static final Handler main = new Handler(Looper.getMainLooper());

    private static int gen = 0;
    private static String url;
    private static String tag;
    private static boolean active, prepared, failed, startRequested, started;
    private static long t0;
    private static Runnable pendingFallback;

    private MusicPrefetcher() {
    }

    /** 开始缓冲(不播)。 */
    public static void prepare(final String u, final String playTag) {
        main.post(new Runnable() {
            @Override
            public void run() {
                doPrepare(u, playTag);
            }
        });
    }

    /**
     * TTS 结束时调用: 已缓冲好立即 start; 还在缓冲则 prepare 完成即播;
     * 没有对应的缓冲(或失败) 则执行 fallback(原来的 playUrl)。
     */
    public static void startWhenReady(final String u, final Runnable fallback) {
        main.post(new Runnable() {
            @Override
            public void run() {
                doStartWhenReady(u, fallback);
            }
        });
    }

    /** 打断/换歌: 作废在途缓冲。 */
    public static void cancel() {
        main.post(new Runnable() {
            @Override
            public void run() {
                doCancel();
            }
        });
    }

    // ------------------------------------------------------------------
    private static void doPrepare(String u, String playTag) {
        doCancel();
        final int my = ++gen;
        url = u;
        tag = playTag;
        prepared = failed = startRequested = started = false;
        pendingFallback = null;
        t0 = System.currentTimeMillis();
        try {
            final UniMediaPlayer up = UniMediaPlayer.getInstance();
            final MediaPlayer mp = player(up);
            if (mp == null) throw new IllegalStateException("no mMediaPlayer");
            callState(up, 3);                         // 同 playUrl: 先报 stop
            setField(up, "playTag", playTag);
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer m) {
                    LogMgr.d(TAG, "player completion (prefetched track)");
                    callState(up, 4);
                    abandonFocus(up);
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer m, int what, int extra) {
                    LogMgr.e(TAG, "player error what=" + what + " extra=" + extra
                            + (started ? "" : " (before start)"));
                    if (my == gen && !started) {
                        failed = true;
                        if (startRequested) runFallback("prepare error");
                        return true;          // 还没开播: 自己处理, 不触发 completion(4)
                    }
                    callState(up, -1001);
                    return false;
                }
            });
            try {
                mp.stop();
            } catch (Throwable ignored) {
            }
            mp.reset();
            mp.setDataSource(u);
            mp.setAudioStreamType(3);
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer m) {
                    if (my != gen) return;
                    prepared = true;
                    LogMgr.d(TAG, "prefetch prepared in " + (System.currentTimeMillis() - t0) + "ms"
                            + (startRequested ? ", TTS already ended -> start now" : ", waiting TTS end"));
                    if (startRequested && !started) doStart();
                }
            });
            mp.prepareAsync();
            mp.setVolume(1.0f, 1.0f);
            active = true;
            LogMgr.d(TAG, "prefetch start buffering during TTS");
        } catch (Throwable t) {
            failed = true;
            active = false;
            LogMgr.e(TAG, "prefetch failed, will use playUrl: " + t);
        }
    }

    private static void doStartWhenReady(String u, Runnable fallback) {
        pendingFallback = fallback;
        if (!active || failed || u == null || !u.equals(url)) {
            LogMgr.d(TAG, "prefetch not usable (active=" + active + " failed=" + failed + "), playUrl");
            runFallback("no prefetch");
            return;
        }
        startRequested = true;
        if (prepared) {
            LogMgr.d(TAG, "TTS end, prefetched ready -> start");
            doStart();
            return;
        }
        LogMgr.d(TAG, "TTS end, still buffering (" + (System.currentTimeMillis() - t0) + "ms) -> start on prepared");
        final int my = gen;
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (my == gen && !started && pendingFallback != null) {
                    LogMgr.e(TAG, "prefetch not prepared in " + PREPARE_WAIT_MS + "ms, playUrl");
                    runFallback("timeout");
                }
            }
        }, PREPARE_WAIT_MS);
    }

    private static void doStart() {
        try {
            UniMediaPlayer up = UniMediaPlayer.getInstance();
            MediaPlayer mp = player(up);
            requestFocus(up);
            mp.start();
            started = true;
            pendingFallback = null;
            callState(up, 1);
            LogMgr.d(TAG, "music playing started (prefetched, " + (System.currentTimeMillis() - t0)
                    + "ms after search)");
        } catch (Throwable t) {
            LogMgr.e(TAG, "prefetch start failed: " + t);
            failed = true;
            runFallback("start error");
        }
    }

    private static void runFallback(String why) {
        Runnable f = pendingFallback;
        pendingFallback = null;
        active = false;
        gen++;                       // 让在途 onPrepared 作废
        if (f != null) {
            LogMgr.d(TAG, "fallback playUrl (" + why + ")");
            try {
                f.run();
            } catch (Throwable t) {
                LogMgr.e(TAG, "fallback failed: " + t);
            }
        }
    }

    private static void doCancel() {
        gen++;
        pendingFallback = null;
        if (active && !started) {
            try {
                UniMediaPlayer up = UniMediaPlayer.getInstance();
                // 播放器还归我们(标签没被别人改)才 reset, 免得打断提示音等其它声音
                if (tag != null && tag.equals(up.getPlayTag())) {
                    MediaPlayer mp = player(up);
                    if (mp != null && !mp.isPlaying()) mp.reset();
                    setField(up, "playTag", null);
                }
            } catch (Throwable ignored) {
            }
            LogMgr.d(TAG, "prefetch cancelled");
        }
        active = false;
    }

    // ------------------------------------------------------------------ 反射
    private static MediaPlayer player(UniMediaPlayer up) throws Exception {
        Field f = UniMediaPlayer.class.getDeclaredField("mMediaPlayer");
        f.setAccessible(true);
        return (MediaPlayer) f.get(up);
    }

    private static void setField(UniMediaPlayer up, String name, Object v) throws Exception {
        Field f = UniMediaPlayer.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(up, v);
    }

    private static void callState(UniMediaPlayer up, int state) {
        try {
            Method m = UniMediaPlayer.class.getDeclaredMethod("callBackPlayerState", int.class);
            m.setAccessible(true);
            m.invoke(up, state);
        } catch (Throwable t) {
            LogMgr.e(TAG, "callBackPlayerState(" + state + ") failed: " + t);
        }
    }

    private static void requestFocus(UniMediaPlayer up) {
        try {
            Method m = UniMediaPlayer.class.getDeclaredMethod("requestFocus");
            m.setAccessible(true);
            m.invoke(up);
        } catch (Throwable t) {
            LogMgr.e(TAG, "requestFocus failed: " + t);
        }
    }

    private static void abandonFocus(UniMediaPlayer up) {
        try {
            Field am = UniMediaPlayer.class.getDeclaredField("mAudioManager");
            Field l = UniMediaPlayer.class.getDeclaredField("mAudioFocusChangeListener");
            am.setAccessible(true);
            l.setAccessible(true);
            ((android.media.AudioManager) am.get(up)).abandonAudioFocus(
                    (android.media.AudioManager.OnAudioFocusChangeListener) l.get(up));
        } catch (Throwable ignored) {
        }
    }
}
