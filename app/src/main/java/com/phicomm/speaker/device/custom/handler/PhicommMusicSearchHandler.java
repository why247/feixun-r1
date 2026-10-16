package com.phicomm.speaker.device.custom.handler;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.phicomm.speaker.device.R;
import com.phicomm.speaker.device.custom.music.MusicPrefetcher;
import com.phicomm.speaker.device.custom.music.NetEaseMusicClient;
import com.phicomm.speaker.device.custom.music.QQMusicClient;
import com.phicomm.speaker.device.custom.music.NetEaseMusicClient.MusicPlayResult;
import com.phicomm.speaker.device.utils.LogUtils;
import com.unisound.ant.device.bean.MusicItem;
import com.unisound.vui.engine.ANTHandlerContext;
import com.unisound.vui.handler.SessionRegister;
import com.unisound.vui.handler.SimpleUserEventInboundHandler;
import com.unisound.vui.util.ExoConstants;
import com.unisound.vui.util.LogMgr;
import com.unisound.vui.common.media.UniMediaPlayer;

import java.util.ArrayList;
import java.util.List;

import nluparser.scheme.NLU;
import nluparser.scheme.MusicResult;
import nluparser.scheme.Result;
import nluparser.scheme.SName;

/**
 * 音乐搜索 Handler
 * 
 * 拦截 service == MUSIC 的 NLU 事件，调用网易云音乐 API 搜索并播放
 * 
 * 优先级: 310 > DefaultMusicHandler 300，抢先消费事件
 */
public class PhicommMusicSearchHandler extends SimpleUserEventInboundHandler<NLU> {
    private static final String TAG = "PhicommMusicSearch";
    
    // 网易云音乐客户端
    private NetEaseMusicClient musicClient;
    
    // 用于 Toast 显示（需要主线程）
    private Handler mainHandler;
    
    // 当前播放结果（用于打断处理）
    private MusicPlayResult currentResult;
    
    // 是否正在播放音乐
    private boolean isPlaying = false;

    // ── 连播队列 ──
    // 搜到的其余候选, 一首播完自动下一首; 被唤醒打断后保留 5 分钟, 可以说「下一首」继续。
    private final java.util.LinkedList<NetEaseMusicClient.MusicSearchResult> queue =
            new java.util.LinkedList<NetEaseMusicClient.MusicSearchResult>();
    private long queueKeptUntil = 0;
    private volatile int playGen = 0;
    private boolean listenerAdded = false;
    private static final String[] NEXT_WORDS = {"下一首", "换一首", "下一个", "换一个", "切歌", "下首"};
    private static final int MAX_QUEUE = 15;

    // ── 播放控制(暂停/继续/再放一遍/单曲循环/音量) ──
    // 唤醒/打断时原来的逻辑会停掉音乐(焦点也会让 MediaPlayer stop), 所以这里记住最后一首和停下的位置,
    // 「继续」= 重新开播并跳回原位置; 原厂 setting.mp 的暂停/继续只控制原厂自己的播放器, 管不到这里。
    static final int CTL_NONE = 0, CTL_PAUSE = 1, CTL_RESUME = 2, CTL_REPLAY = 3, CTL_LOOP_ON = 4,
            CTL_LOOP_OFF = 5, CTL_VOL_UP = 6, CTL_VOL_DOWN = 7, CTL_NEXT = 8;
    private static final String[] CTL_NAMES = {"none", "pause", "resume", "replay", "loop_on", "loop_off",
            "vol_up", "vol_down", "next"};
    /** 停下后这么久内还能说「继续/再放一遍/大声点」接着放 */
    private static final long RESUMABLE_MS = 10 * 60 * 1000L;
    /** 直链超过这么久就重新解析(QQ vkey 直链有时效) */
    private static final long URL_FRESH_MS = 15 * 60 * 1000L;
    private volatile MusicPlayResult lastTrack;                         // 最近在放/刚停下的那首
    private volatile NetEaseMusicClient.MusicSearchResult lastCand;     // 它的候选(用于重新解链), 可能为 null
    private volatile long lastTrackUrlAt = 0;                           // 直链解析时间
    private volatile long lastStoppedAt = 0;
    private volatile int lastPosMs = 0;
    private volatile long trackStartedAt = 0;                           // 墙钟: 开播时刻(已扣除 seek 位置)
    private volatile boolean userPaused = false;                        // 用户说了「暂停」
    private volatile boolean loopOne = false;                           // 单曲循环
    private volatile int pendingSeekMs = 0;
    /** TTS 播完之后做什么(控制词的简短回复用) */
    private static final int AFTER_NONE = 0, AFTER_EXIT = 1, AFTER_RESUME = 2;
    private volatile int afterTts = AFTER_NONE;

    // ── 点歌提速 ──
    /** 首曲: 前几个 QQ 候选并行取直链 */
    private static final int QQ_PARALLEL = 3;
    /** 首曲 QQ 总预算: 超过才进入原有回退链(酷我车机FLAC→GD→酷我MP3→gequbao) */
    private static final long QQ_FIRST_BUDGET_MS = 3000;
    /** 有更靠后的候选先成功时, 再等排在前面的(更匹配)这么久 */
    private static final long QQ_PREFER_EARLIER_MS = 300;
    /** 意图确认后这么久还没解析好就先说「好的」 */
    private static final long ACK_DELAY_MS = 500;
    private static final String ACK_TEXT = "好的";
    private static final java.util.concurrent.ExecutorService RESOLVE_POOL =
            java.util.concurrent.Executors.newCachedThreadPool(new java.util.concurrent.ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "MusicResolve");
                    t.setDaemon(true);
                    return t;
                }
            });
    /** 每次点歌 +1; 打断时也 +1, 在途的搜索结果作废 */
    private volatile int searchGen = 0;
    private final Object ackLock = new Object();
    private static final int ACK_NONE = 0, ACK_PENDING = 1, ACK_SPEAKING = 2, ACK_DONE = 3,
            ACK_READY_WAIT = 4, ACK_FAIL_WAIT = 5;
    private int ackState = ACK_NONE;
    private long intentAt = 0;
    private boolean firstStartLogged = true;
    /** 下一首预解析: 当前曲开播后后台解析队首并预热 */
    private volatile NetEaseMusicClient.MusicSearchResult preCand;
    private volatile String preUrl;
    private volatile long preAt;
    private volatile boolean preRunning = false;
    private static final long PRE_FRESH_MS = 10 * 60 * 1000L;
    
    public PhicommMusicSearchHandler() {
        this.sessionName = SessionRegister.SESSION_MUSIC;
        this.musicClient = new NetEaseMusicClient();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }
    
    @Override
    public void initPriority() {
        // 比 DefaultMusicHandler (300) 高，抢先拦截
        setPriority(310);
    }
    
    @Override
    public boolean acceptInboundEvent0(NLU evt) {
        // 拦截音乐意图、设置意图
        // NLU 会将"播放xxx"识别为 chat 而非 music（因为服务不可用）
        if (evt == null) return false;
        String service = evt.getService();
        String text = evt.getText();
        
        boolean isMusic = "cn.yunzhisheng.music".equals(service);
        boolean isSettingMp = "cn.yunzhisheng.setting.mp".equals(service);
        boolean isSetting = "cn.yunzhisheng.setting".equals(service);
        boolean isChat = "cn.yunzhisheng.chat".equals(service);
        int cmd = controlCommand(text);
        
        // 「下一首」: 有连播队列时由这里接管
        if (text != null && isNextIntent(text) && hasQueue()) {
            LogMgr.d(TAG, "acceptInboundEvent0: next intent with queue: " + text);
            return true;
        }

        // setting.mp(播放控制): 只接管「下一首/换一首」(上面已处理); 暂停/继续/循环只在
        // 我们自己的播放器有歌(在放或 10 分钟内停下)时接管, 其余一律交回原厂, 绝不当歌名搜索
        if (isSettingMp) {
            boolean own = cmd >= CTL_PAUSE && cmd <= CTL_LOOP_OFF && hasMusicContext();
            LogMgr.d(TAG, "acceptInboundEvent0: setting.mp text=" + text + " cmd=" + CTL_NAMES[cmd]
                    + (own ? " -> own player" : " -> original handler"));
            return own;
        }

        // 原厂 setting(音量等): 只在我们的音乐刚被打断时接管音量词(调完音量接着放), 否则交回原厂
        if (isSetting) {
            boolean own = (cmd == CTL_VOL_UP || cmd == CTL_VOL_DOWN) && hasMusicContext() && !userPaused;
            if (own) LogMgr.d(TAG, "acceptInboundEvent0: setting volume during own music: " + text);
            return own;
        }

        // 控制词(暂停/继续/继续播放/再放一遍/单曲循环/大声点...)走 chat 或 music: 一律由这里处理, 不点歌、不进大模型
        if ((isChat || isMusic) && cmd != CTL_NONE) {
            LogMgr.d(TAG, "acceptInboundEvent0: control word " + CTL_NAMES[cmd] + ": " + text);
            return true;
        }

        // music 直接拦截
        if (isMusic) {
            LogMgr.d(TAG, "acceptInboundEvent0: service=" + service + ", text=" + text + ", accept=true");
            return true;
        }
        
        // chat 意图: 只有明确包含音乐关键词才拦截
        if (isChat && text != null && !text.isEmpty()) {
            // 检查是否包含音乐关键词(必须以这些词开头或包含)
            if (isMusicIntent(text)) {
                LogMgr.d(TAG, "acceptInboundEvent0: chat 意图包含音乐关键词,拦截: " + text);
                return true;
            }
            // 其他 chat 意图全部放行给 PhicommChatHandler(大模型)
            LogMgr.d(TAG, "acceptInboundEvent0: chat 意图不包含音乐关键词,放行: " + text);
            return false;
        }
        
        // 其他 service 不拦截
        LogMgr.d(TAG, "acceptInboundEvent0: service=" + service + ", accept=false");
        return false;
    }
    
    /**
     * 判断是否是音乐意图
     * 只有明确包含音乐关键词才认为是音乐意图
     * 排除包含否定词的文本（如"不要唱歌"、"别唱了"）
     */
    private boolean isMusicIntent(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        
        // 排除否定词（停止类意图应该走大模型处理）
        String[] negativeKeywords = {
            "不要", "别", "停止", "停了", "结束", "关掉", "不要唱", 
            "不要放", "别唱", "别放", "不要听", "别听", "不要播放"
        };
        for (String neg : negativeKeywords) {
            if (text.contains(neg)) {
                LogMgr.d(TAG, "isMusicIntent: 包含否定词 '" + neg + "', 排除: " + text);
                return false;
            }
        }
        
        // 音乐关键词列表（必须以这些词开头或包含）
        String[] musicKeywords = {
            "播放", "放一首", "来一首", "来一个", "我想听", "唱一", 
            "听音乐", "听歌", "放歌", "放音乐", "来点音乐", 
            "放个歌", "给我唱", "唱首", "唱歌"
        };
        
        for (String keyword : musicKeywords) {
            // 检查是否包含关键词
            if (text.contains(keyword)) {
                LogMgr.d(TAG, "isMusicIntent: 匹配音乐关键词 '" + keyword + "': " + text);
                return true;
            }
        }
        
        return false;
    }
    
    @Override
    public boolean onASREventEngineInitDone(ANTHandlerContext ctx) {
        this.ctx = ctx;
        try {
            // QQ 音乐凭证(files/qq_cred.json) + 每日续期线程
            QQMusicClient.get().init(ctx.androidContext().getFilesDir());
        } catch (Throwable t) {
            LogMgr.e(TAG, "QQMusicClient init failed: " + t);
        }
        return super.onASREventEngineInitDone(ctx);
    }
    
    @Override
    public void eventReceived(final NLU evt, final ANTHandlerContext ctx) throws Exception {
        super.eventReceived(evt, ctx);

        String raw = null;
        try { raw = evt.getText(); } catch (Throwable ignored) { }
        com.phicomm.speaker.device.custom.engine.VoiceTiming.intent("music", raw);
        if (raw != null && isNextIntent(raw) && hasQueue()) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    playNextNow(ctx);
                }
            }).start();
            return;
        }

        final int cmd = controlCommand(raw);
        if (cmd != CTL_NONE) {
            LogMgr.d(TAG, "control " + CTL_NAMES[cmd] + " (" + raw + "), music ctx=" + hasMusicContext()
                    + " playing=" + isPlaying + " userPaused=" + userPaused);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    handleControl(cmd, ctx);
                }
            }).start();
            return;
        }

        // 解析关键词
        String keyword = parseKeyword(evt);
        LogMgr.d(TAG, "music search keyword: " + keyword);
        
        if (keyword == null || keyword.trim().isEmpty()) {
            ctx.playTTS("抱歉，没有听到歌曲名称");
            return;
        }
        // 儿童安全: 点歌关键词里有不适合小朋友的内容(DJ/喊麦/社会摇/恐怖…)直接婉拒, 不去搜
        String bad = kidUnsafeWord(keyword, KID_BLOCK_KEYWORD);
        if (bad != null) {
            LogMgr.d(TAG, "kid filter: keyword blocked (" + bad + "): " + keyword);
            currentResult = null;
            speak(ctx, "这个不太适合小朋友，我们换一首儿歌吧", AFTER_EXIT);
            return;
        }
        
        // 异步搜索 + 播放（网络操作不能在主线程）; 500ms 内没解析好就先说「好的」(与搜索并行)
        final int sg = ++searchGen;
        synchronized (ackLock) {
            ackState = ACK_PENDING;
            intentAt = System.currentTimeMillis();
            firstStartLogged = false;
        }
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                speakAckIfPending(ctx, sg);
            }
        }, ACK_DELAY_MS);
        new Thread(new Runnable() {
            @Override
            public void run() {
                searchAndPlay(ctx, keyword.trim(), sg);
            }
        }).start();
    }

    /** 主线程: 搜索还没出结果 -> 先说「好的」。 */
    private void speakAckIfPending(ANTHandlerContext ctx, int sg) {
        synchronized (ackLock) {
            if (sg != searchGen || ackState != ACK_PENDING) return;
            ackState = ACK_SPEAKING;
        }
        LogMgr.d(TAG, "timing: ack \"" + ACK_TEXT + "\" at " + (System.currentTimeMillis() - intentAt)
                + "ms after intent (search still running)");
        try {
            ctx.stopWakeup();
            ctx.stopASR();
        } catch (Throwable ignored) { }
        ctx.playTTS(ACK_TEXT);
    }
    
    /**
     * 解析 NLU 结果获取搜索关键词
     */
    private String parseKeyword(NLU evt) {
        StringBuilder keyword = new StringBuilder();
        
        try {
            if (evt.getData() != null) {
                Result raw = evt.getData().getResult();
                if (raw instanceof MusicResult) {
                    MusicResult result = (MusicResult) raw;
                    
                    // 获取歌曲列表
                    List<MusicResult.Music> musicList = result.getMusicinfo();
                    if (musicList != null && !musicList.isEmpty()) {
                        MusicResult.Music music = musicList.get(0);
                        
                        // 优先使用歌曲名
                        String title = music.getTitle();
                        if (title != null && !title.isEmpty()) {
                            keyword.append(title);
                        }
                        
                        // 如果有歌手名，追加
                        String artist = music.getArtist();
                        if (artist != null && !artist.isEmpty()) {
                            if (keyword.length() > 0) keyword.append(" ");
                            keyword.append(artist);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            LogMgr.e(TAG, "parseKeyword error: " + t);
        }
        
        String result = keyword.toString().trim();
        if (result.isEmpty()) {
            // fallback: 从 NLU 文本中提取关键词
            try {
                String text = evt.getText();
                if (text != null && !text.isEmpty()) {
                    // 去除"播放"等前缀
                    result = text.replaceAll("^(播放|放|唱|来一首|来一个|我想听|我想唱)", "").trim();
                }
            } catch (Exception e) {
                LogMgr.e(TAG, "getText fallback failed: " + e);
            }
        }
        
        return result;
    }
    
    /**
     * 搜索并播放。首曲: 前 3 个 QQ 候选并行取直链(F000 无损优先, 不降级选源), 取第一个可播的,
     * 总预算约 3 秒; 超时/都不行才进入原有回退链。确认播报只说一次: 500ms 内解析好 -> 「为您播放…」;
     * 否则已先说「好的」, 解析好后直接开播。
     */
    private void searchAndPlay(ANTHandlerContext ctx, String keyword, int sg) {
        LogMgr.d(TAG, "searchAndPlay: " + keyword);
        MusicPlayResult result = null;
        try {
            // 候选列表(QQ 音乐优先, 否则酷我): 第一首能播的立刻播, 其余进连播队列
            long t0 = System.currentTimeMillis();
            List<NetEaseMusicClient.MusicSearchResult> cands = kidFilter(musicClient.candidates(keyword));
            LogMgr.d(TAG, "timing: search " + cands.size() + " candidates in " + (System.currentTimeMillis() - t0) + "ms");
            int[] picked = new int[]{-1};
            result = resolveFirst(cands, picked, sg);
            int i = picked[0];
            LogMgr.d(TAG, "first track resolved in " + (System.currentTimeMillis() - t0) + "ms, index " + i);
            if (sg != searchGen) {
                LogMgr.d(TAG, "search superseded/interrupted, drop result");
                return;
            }
            synchronized (queue) {
                queue.clear();
                if (result != null) {
                    for (int k = i + 1; k < cands.size() && queue.size() < MAX_QUEUE; k++) {
                        queue.add(cands.get(k));
                    }
                }
            }
            if (result == null) {
                // 酷我不行再退回 gequbao(单曲, 无队列)
                result = musicClient.searchAndGetFirst(keyword);
                if (result != null && kidUnsafeWord(result.name + " " + result.artist, KID_BLOCK_TRACK) != null) {
                    LogMgr.d(TAG, "kid filter: drop gequbao result " + result.name);
                    result = null;
                }
            }
            LogMgr.d(TAG, "queue size after search: " + queue.size());
        } catch (Exception e) {
            LogMgr.e(TAG, "searchAndPlay error: " + e.getMessage(), e);
            result = null;
        }
        if (sg != searchGen) {
            LogMgr.d(TAG, "search superseded/interrupted, drop result");
            return;
        }
        deliverResult(ctx, result, sg);
    }

    /** 搜索结束: 按「好的」是否已说出决定 播报歌名 / 等「好的」说完 / 直接开播。 */
    private void deliverResult(ANTHandlerContext ctx, MusicPlayResult result, int sg) {
        int st;
        synchronized (ackLock) {
            if (sg != searchGen) return;
            st = ackState;
            if (st == ACK_PENDING) ackState = ACK_NONE;                       // 来得及: 不说「好的」
            else if (st == ACK_SPEAKING) ackState = result != null ? ACK_READY_WAIT : ACK_FAIL_WAIT;
            else if (st == ACK_DONE) ackState = ACK_NONE;
        }
        long sinceIntent = System.currentTimeMillis() - intentAt;
        if (result == null) {
            LogMgr.e(TAG, "search failed (" + sinceIntent + "ms after intent)");
            currentResult = null;
            if (st == ACK_SPEAKING) return;                 // 「好的」播完再说没找到
            ctx.playTTS("抱歉，未找到相关歌曲");
            return;
        }
        currentResult = result;
        LogMgr.d(TAG, "found: " + result.name + " - " + result.artist + " url=" + result.playUrl);
        if (st == ACK_SPEAKING) {
            // 「好的」还在说: 先缓冲, 它说完立即开播(不再报歌名)
            LogMgr.d(TAG, "timing: resolved " + sinceIntent + "ms after intent, ack still speaking -> prefetch");
            MusicPrefetcher.prepare(result.playUrl, "music_search");
            return;
        }
        if (st == ACK_DONE) {
            // 「好的」已说完、在等结果: 直接开播
            LogMgr.d(TAG, "timing: resolved " + sinceIntent + "ms after intent, ack done -> play now");
            try {
                ctx.stopWakeup();
                ctx.stopASR();
            } catch (Throwable ignored) { }
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    playMusic(PhicommMusicSearchHandler.this.ctx, new ArrayList<MusicItem>());
                }
            });
            return;
        }
        // 500ms 内就解析好了: 只播报歌名(不说「好的」), 播报期间缓冲
        LogMgr.d(TAG, "timing: resolved " + sinceIntent + "ms after intent -> announce");
        ctx.stopWakeup();
        ctx.stopASR();
        MusicPrefetcher.prepare(result.playUrl, "music_search");
        String ttsText = announceText(result.name, result.artist);
        LogMgr.d(TAG, "announce: " + ttsText);
        ctx.playTTS(ttsText);
    }

    /**
     * 首曲解析。候选是 QQ: 前 QQ_PARALLEL 个并行问 QQ, 预算 QQ_FIRST_BUDGET_MS 内取第一个可播的
     * (更靠前的候选再多等 300ms); 不行再进入原有回退链(这几个直接走酷我链路, 不重复问 QQ;
     * 期间 QQ 迟到的结果仍可用)。非 QQ 候选: 原有串行逻辑不变。picked[0] 返回所选下标。
     */
    private MusicPlayResult resolveFirst(final List<NetEaseMusicClient.MusicSearchResult> cands, int[] picked, int sg) {
        int n = Math.min(cands.size(), 12);
        if (n == 0) return null;
        long t0 = System.currentTimeMillis();
        int par = 0;
        while (par < Math.min(QQ_PARALLEL, n) && "qq".equals(cands.get(par).source)) par++;
        List<java.util.concurrent.Future<String>> fs = new ArrayList<java.util.concurrent.Future<String>>();
        for (int k = 0; k < par; k++) {
            final NetEaseMusicClient.MusicSearchResult c = cands.get(k);
            fs.add(RESOLVE_POOL.submit(new java.util.concurrent.Callable<String>() {
                @Override
                public String call() {
                    return QQMusicClient.get().resolve(c);
                }
            }));
        }
        try {
            if (par > 0) {
                long deadline = t0 + QQ_FIRST_BUDGET_MS;
                long firstOkAt = 0;
                while (true) {
                    if (sg != searchGen) return null;
                    int best = -1;
                    boolean earlierPending = false;
                    boolean allDone = true;
                    for (int k = 0; k < par; k++) {
                        java.util.concurrent.Future<String> f = fs.get(k);
                        if (!f.isDone()) {
                            allDone = false;
                            if (best < 0) earlierPending = true;
                            continue;
                        }
                        if (best < 0 && futureUrl(f) != null) best = k;
                    }
                    long now = System.currentTimeMillis();
                    if (best >= 0) {
                        if (firstOkAt == 0) firstOkAt = now;
                        if (!earlierPending || now - firstOkAt >= QQ_PREFER_EARLIER_MS || now >= deadline) {
                            picked[0] = best;
                            LogMgr.d(TAG, "first track: qq parallel #" + best + " ok in " + (now - t0) + "ms");
                            return NetEaseMusicClient.toPlay(cands.get(best), futureUrl(fs.get(best)));
                        }
                    }
                    if (allDone && best < 0) {
                        LogMgr.d(TAG, "first track: qq parallel all " + par + " miss in " + (now - t0) + "ms -> fallback chain");
                        break;
                    }
                    if (now >= deadline) {
                        LogMgr.d(TAG, "first track: qq budget " + QQ_FIRST_BUDGET_MS + "ms used up -> fallback chain");
                        break;
                    }
                    try { Thread.sleep(40); } catch (InterruptedException e) { return null; }
                }
            }
            // 原有回退链(串行): 已并行问过 QQ 的候选直接走酷我链路; 每步前看一眼 QQ 迟到的结果
            for (int i = 0; i < n; i++) {
                if (sg != searchGen) return null;
                for (int k = 0; k < par; k++) {
                    String late = futureUrl(fs.get(k));
                    if (late != null) {
                        picked[0] = k;
                        LogMgr.d(TAG, "first track: late qq #" + k + " ok in " + (System.currentTimeMillis() - t0) + "ms");
                        return NetEaseMusicClient.toPlay(cands.get(k), late);
                    }
                }
                NetEaseMusicClient.MusicSearchResult c = cands.get(i);
                String url = i < par ? musicClient.qqMissKuwoChain(c) : musicClient.resolve(c);
                if (url != null) {
                    picked[0] = i;
                    return NetEaseMusicClient.toPlay(c, url);
                }
            }
            return null;
        } finally {
            for (java.util.concurrent.Future<String> f : fs) f.cancel(false);
        }
    }

    private static String futureUrl(java.util.concurrent.Future<String> f) {
        if (!f.isDone() || f.isCancelled()) return null;
        try {
            return f.get();
        } catch (Throwable t) {
            return null;
        }
    }
    
    /**
     * 播放音乐
     * 
     * 使用原厂 UniMediaPlayer 播放，它是单例，内部封装了 Android MediaPlayer
     * 参考 DefaultChatHandler 的音频播放方式
     */
    private void playMusic(ANTHandlerContext ctx, List<MusicItem> musicList) {
        try {
            String playUrl = currentResult != null ? currentResult.playUrl : null;
            if (playUrl == null) {
                ctx.playTTS("抱歉，播放地址无效");
                return;
            }
            
            LogMgr.d(TAG, "playUrl: " + playUrl);
            
            // 原厂 UniMediaPlayer: 播报期间已在缓冲(MusicPrefetcher), 好了就立刻 start,
            // 没好就 prepare 完成即播; 缓冲不可用时回落到原来的 playUrl
            ensureListener();
            playGen++;
            isPlaying = true;
            loopOne = false;              // 新点的歌: 单曲循环只对当时那首有效
            rememberTrack(currentResult);
            final String u = playUrl;
            MusicPrefetcher.startWhenReady(u, new Runnable() {
                @Override
                public void run() {
                    UniMediaPlayer.getInstance().playUrl(u, "music_search");
                    LogMgr.d(TAG, "music playing started (playUrl)");
                }
            });
            
        } catch (Exception e) {
            LogMgr.e(TAG, "playMusic error: " + e.getMessage(), e);
        }
    }
    
    @Override
    public boolean onTTSEventPlayingEnd(ANTHandlerContext ctx) {
        if (!this.eventReceived) {
            return super.onTTSEventPlayingEnd(ctx);
        }
        
        // 点歌确认「好的」播完
        int ack;
        synchronized (ackLock) {
            ack = ackState;
            if (ack == ACK_SPEAKING) ackState = ACK_DONE;
            else if (ack == ACK_READY_WAIT || ack == ACK_FAIL_WAIT) ackState = ACK_NONE;
        }
        if (ack == ACK_SPEAKING) {
            LogMgr.d(TAG, "ack TTS end, still searching (" + (System.currentTimeMillis() - intentAt) + "ms)");
            return true;   // 搜到后 deliverResult 直接开播
        }
        if (ack == ACK_READY_WAIT) {
            LogMgr.d(TAG, "ack TTS end, result ready -> play");
            playMusic(ctx, new ArrayList<MusicItem>());
            return true;
        }
        if (ack == ACK_FAIL_WAIT) {
            currentResult = null;
            ctx.playTTS("抱歉，未找到相关歌曲");
            return true;
        }

        // 控制词的简短回复播完
        int after = afterTts;
        if (after != AFTER_NONE) {
            afterTts = AFTER_NONE;
            if (after == AFTER_RESUME && lastTrack != null) {
                resumeTrack(ctx, true, "after tts");
            } else {
                exit();
                try { ctx.enterWakeup(false); } catch (Throwable ignored) { }
            }
            return true;
        }

        // TTS 播报结束后，开始播放音乐
        if (currentResult != null && currentResult.playUrl != null) {
            List<MusicItem> musicList = new ArrayList<>();
            playMusic(ctx, musicList);
        } else {
            exit();
        }
        return true;
    }
    
    @Override
    public void doInterrupt(ANTHandlerContext ctx, String interruptType) {
        if (this.eventReceived) {
            searchGen++;   // 在途的点歌结果作废(不再播报/开播)
            synchronized (ackLock) {
                ackState = ACK_NONE;
            }
            ctx.cancelTTS();
            
            // 停止音乐播放（如果实现了）
            stopMusic();
            
            if (!ExoConstants.DO_ONE_SHOT_INTERRUPT.equals(interruptType)) {
                ctx.enterWakeup(false);
            }
            reset();
        }
    }
    
    private boolean isNextIntent(String text) {
        for (String w : NEXT_WORDS) if (text.contains(w)) return true;
        return false;
    }

    private boolean hasQueue() {
        synchronized (queue) {
            if (queue.isEmpty()) return false;
            if (!isPlaying && System.currentTimeMillis() > queueKeptUntil) {
                queue.clear();
                return false;
            }
            return true;
        }
    }

    /** 播放器回调: 一首自然播完(4)或出错, 自动接下一首。 */
    private void ensureListener() {
        if (listenerAdded) return;
        listenerAdded = true;
        UniMediaPlayer.getInstance().addIMediaPlayerStateListener(
                new com.unisound.vui.common.media.IMediaPlayerStateListener() {
                    @Override
                    public void onPlayerState(int state, String tag) {
                        if ("music_search".equals(tag) || isPlaying) {
                            LogMgr.d(TAG, "player state=" + state + " tag=" + tag + " isPlaying=" + isPlaying
                                    + " queue=" + queueSize());
                        }
                        if (!"music_search".equals(tag) || !isPlaying) return;
                        if (state == 1) {
                            onTrackStarted();
                            return;
                        }
                        // 出错时 onError 返回 false, MediaPlayer 随后也会回调 onCompletion(4), 所以只认 4
                        if (state != 4) return;
                        final int gen = playGen;
                        if (loopOne && lastTrack != null) {
                            LogMgr.d(TAG, "track ended, single loop -> replay " + lastTrack.name);
                            new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    if (gen == playGen && isPlaying) replayTrack(null, 0, "loop", false);
                                }
                            }).start();
                            return;
                        }
                        LogMgr.d(TAG, "track ended (state=" + state + "), auto next");
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                playNextSilent(gen);
                            }
                        }).start();
                    }
                });
    }

    /** 从队列取下一首能播的(后台线程调用, 有网络请求)。 */
    private MusicPlayResult nextFromQueue() {
        while (true) {
            NetEaseMusicClient.MusicSearchResult c;
            synchronized (queue) {
                c = queue.poll();
            }
            if (c == null) return null;
            long t0 = System.currentTimeMillis();
            String url = null;
            if (c == preCand && preUrl != null && t0 - preAt < PRE_FRESH_MS) {
                url = preUrl;
                LogMgr.d(TAG, "next: use pre-resolved url (" + ((t0 - preAt) / 1000) + "s old): " + c.name);
                long at = preAt;
                preCand = null;
                preUrl = null;
                MusicPlayResult pr = NetEaseMusicClient.toPlay(c, url);
                pr.resolvedAt = at;
                return pr;
            }
            try {
                url = musicClient.resolve(c);
            } catch (Throwable t) {
                LogMgr.e(TAG, "next resolve error: " + t);
            }
            LogMgr.d(TAG, "next resolve " + (url != null ? "ok" : "miss") + " in "
                    + (System.currentTimeMillis() - t0) + "ms: " + c.name + " (queue left " + queueSize() + ")");
            if (url != null) return NetEaseMusicClient.toPlay(c, url);
        }
    }

    private int queueSize() {
        synchronized (queue) {
            return queue.size();
        }
    }

    /** 自动连播: 不报歌名, 直接播。 */
    private void playNextSilent(final int gen) {
        final MusicPlayResult next = nextFromQueue();
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (gen != playGen || !isPlaying) return;   // 期间被打断/换歌了
                if (next == null) {
                    LogMgr.d(TAG, "queue finished");
                    isPlaying = false;
                    currentResult = null;
                    lastPosMs = 0;
                    lastStoppedAt = System.currentTimeMillis();   // 还能说「再放一遍」
                    return;
                }
                currentResult = next;
                rememberTrack(next);
                LogMgr.d(TAG, "auto next: " + next.name + " - " + next.artist);
                playGen++;
                UniMediaPlayer.getInstance().playUrl(next.playUrl, "music_search");
            }
        });
    }

    /** 说「下一首/换一首」: 不报歌名, 直接播; 播上后回到唤醒监听。 */
    private void playNextNow(final ANTHandlerContext ctx) {
        final MusicPlayResult next = nextFromQueue();
        if (next == null) {
            ctx.playTTS("后面没有歌了，你可以说播放加歌名");
            return;
        }
        ctx.stopASR();
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                currentResult = next;
                rememberTrack(next);
                loopOne = false;
                LogMgr.d(TAG, "user next: " + next.name + " - " + next.artist + " (queue left " + queueSize() + ")");
                ensureListener();
                MusicPrefetcher.cancel();
                playGen++;
                isPlaying = true;
                UniMediaPlayer.getInstance().playUrl(next.playUrl, "music_search");
                try {
                    ctx.enterWakeup(false);
                } catch (Throwable t) {
                    LogMgr.e(TAG, "enterWakeup after next failed: " + t);
                }
            }
        });
    }

    // ── 儿童安全过滤 ──
    /** 点歌关键词含这些词直接婉拒 */
    static final String[] KID_BLOCK_KEYWORD = {
            "DJ", "dj", "Dj", "喊麦", "社会摇", "土嗨", "慢摇", "蹦迪", "夜店", "嗨曲", "重低音",
            "恐怖", "鬼故事", "灵异", "吓人", "血腥", "杀人", "鬼片", "色情", "黄色歌", "脏话", "骂人"
    };
    /** 搜索结果(歌名/歌手/专辑)含这些词的版本不放给小朋友 */
    static final String[] KID_BLOCK_TRACK = {
            "DJ", "dj", "Dj", "喊麦", "社会摇", "土嗨", "慢摇", "蹦迪", "夜店", "嗨曲", "重低音", "车载",
            "Remix", "remix", "REMIX", "Explicit", "恐怖", "鬼故事", "灵异", "吓人", "血腥"
    };

    static String kidUnsafeWord(String s, String[] words) {
        if (s == null) return null;
        for (String w : words) if (s.contains(w)) return w;
        return null;
    }

    /** 所有搜索结果都过儿童过滤(不只泛指词), 返回新列表。 */
    static List<NetEaseMusicClient.MusicSearchResult> kidFilter(List<NetEaseMusicClient.MusicSearchResult> in) {
        List<NetEaseMusicClient.MusicSearchResult> out = new ArrayList<NetEaseMusicClient.MusicSearchResult>();
        int dropped = 0;
        for (NetEaseMusicClient.MusicSearchResult r : in) {
            String all = r.name + " " + r.artist + " " + (r.album == null ? "" : r.album);
            if (kidUnsafeWord(all, KID_BLOCK_TRACK) != null) {
                dropped++;
                continue;
            }
            out.add(r);
        }
        if (dropped > 0) LogMgr.d(TAG, "kid filter: dropped " + dropped + " of " + in.size() + " candidates");
        return out;
    }

    // ── 首曲播报 ──
    /** 儿歌厂牌/频道关键词: 歌手名含这些词时按「{厂牌}{歌名}」播报, 不说「演唱的」。 */
    static final String[] KID_LABEL_WORDS = {
            "儿歌", "宝宝", "宝贝", "童谣", "少儿", "幼儿", "启蒙", "早教", "乐园", "动画",
            "贝乐虎", "宝宝巴士", "兔小贝", "小伴龙", "碧优蒂", "汪汪队",
            "儿歌多多", "贝瓦", "巧虎", "碰碰狐", "小猪佩奇", "儿童", "亲子", "胎教", "故事", "童声"
    };

    /**
     * 「为您播放{歌手}演唱的{歌名}」/ 儿歌厂牌「为您播放{厂牌}{歌名}」/ 无歌手「为您播放{歌名}」。
     * 歌名去掉括号内容; 多个歌手只取第一个。
     */
    static String announceText(String name, String artist) {
        String n = cleanTitle(name);
        if (n.isEmpty()) n = name == null ? "" : name.trim();
        String a = firstArtist(artist);
        if (a.isEmpty() || a.contains("未知") || "群星".equals(a) || "佚名".equals(a)
                || "Various Artists".equalsIgnoreCase(a)) {
            return "为您播放" + n;
        }
        for (String w : KID_LABEL_WORDS) {
            if (a.contains(w)) return "为您播放" + a + n;
        }
        return "为您播放" + a + "演唱的" + n;
    }

    static String cleanTitle(String name) {
        if (name == null) return "";
        String s = name;
        String prev;
        do {   // 去掉各种括号及其内容(可嵌套)
            prev = s;
            s = s.replaceAll("\\([^()]*\\)|（[^（）]*）|【[^【】]*】|\\[[^\\[\\]]*\\]|<[^<>]*>", "");
        } while (!s.equals(prev));
        return s.replace("《", "").replace("》", "").replaceAll("\\s+", " ").trim();
    }

    static String firstArtist(String artist) {
        if (artist == null) return "";
        String[] parts = artist.split("[&/、,，;；]");
        return parts.length == 0 ? "" : parts[0].trim();
    }

    // ── 播放控制 ──────────────────────────────────────────────────────

    /**
     * 识别播放控制词(整句匹配, 去掉标点和语气词), 不是控制词返回 CTL_NONE。
     * 「继续播放/暂停播放」里虽然有「播放」, 也不会再被当成点歌。
     */
    static int controlCommand(String text) {
        if (text == null) return CTL_NONE;
        String t = text.replaceAll("[\\s，。！？、,.!?~～…]+", "");
        t = t.replaceAll("^(请你|请|帮我|麻烦你|麻烦|你)", "");
        t = t.replaceAll("(吧|啊|呀|啦|哦|呢|嘛|好吗|好不好|可以吗)+$", "");
        if (t.isEmpty() || t.length() > 12) return CTL_NONE;
        if (t.matches("(下一首|下首|换一首|换首歌|换一首歌|切歌|下一曲|换个歌|换一个歌)(歌)?")) return CTL_NEXT;
        if (t.matches("(取消|关闭|关掉|不要|别|退出)(单曲)?循环(播放)?|顺序播放|列表循环|列表播放")) return CTL_LOOP_OFF;
        if (t.matches("单曲循环(播放)?|循环播放(这首歌?)?|循环这首歌?|这首歌?循环(播放)?|一直(放|播|听)这首歌?"))
            return CTL_LOOP_ON;
        if (t.matches("(再|重新)(放|播|听|唱|来)(一遍|一次|一下)?(这首歌?)?|重播|重新播放(这首歌?)?|从头(播放|放|播|开始)"))
            return CTL_REPLAY;
        if (t.matches("暂停(播放|一下|音乐|一会儿?)?|停(一下|下|下来)?|停止(播放|音乐)?|(别|不要)(放|播|唱)了"
                + "|不听了|(关掉|关闭)(音乐|歌曲?)|先停(一下)?")) return CTL_PAUSE;
        if (t.matches("继续(播放|放|播|唱|听)?(音乐|歌曲?)?|接着(放|播|唱|听)|恢复播放|播放继续|开始播放|播放"))
            return CTL_RESUME;
        if (t.matches("(再|把)?(大声|大点声|开大|调大|声音大|音量大|声音调大|音量调大|声音开大|音量开大|调大音量|调大声音"
                + "|声音调高|音量调高|调高音量)(一)?(点|些)?(儿)?(声)?|太小声了?")) return CTL_VOL_UP;
        if (t.matches("(再|把)?(小声|小点声|关小|调小|声音小|音量小|声音调小|音量调小|声音关小|音量关小|调小音量|调小声音"
                + "|声音调低|音量调低|调低音量)(一)?(点|些)?(儿)?(声)?|太大声了?|太吵了?")) return CTL_VOL_DOWN;
        return CTL_NONE;
    }

    /** 我们自己的播放器有歌: 正在放, 或 10 分钟内停下(可「继续/再放一遍」)。 */
    private boolean hasMusicContext() {
        if (isPlaying) return true;
        return lastTrack != null && lastStoppedAt > 0
                && System.currentTimeMillis() - lastStoppedAt < RESUMABLE_MS;
    }

    private void rememberTrack(MusicPlayResult r) {
        if (r == null) return;
        lastTrack = r;
        lastCand = r.cand;
        lastTrackUrlAt = r.resolvedAt > 0 ? r.resolvedAt : System.currentTimeMillis();
        userPaused = false;
    }

    /** 播放器回调 state=1(开播): 需要的话跳回原位置, 记下开播时刻(估算位置用)。 */
    private void onTrackStarted() {
        int seek = pendingSeekMs;
        pendingSeekMs = 0;
        if (seek > 0) {
            boolean ok = MusicPrefetcher.seekTo(seek);
            LogMgr.d(TAG, "resume: seek to " + (seek / 1000) + "s " + (ok ? "ok" : "failed"));
            trackStartedAt = System.currentTimeMillis() - (ok ? seek : 0);
        } else {
            trackStartedAt = System.currentTimeMillis();
        }
        if (!firstStartLogged) {
            firstStartLogged = true;
            LogMgr.d(TAG, "timing: music playing " + (System.currentTimeMillis() - intentAt) + "ms after intent");
        }
        prefetchNext();
    }

    /** 当前曲开播后: 后台解析队首(下一首)并预热直链, 切歌/连播时直接用。 */
    private void prefetchNext() {
        final NetEaseMusicClient.MusicSearchResult c;
        synchronized (queue) {
            c = queue.peek();
        }
        if (c == null || preRunning) return;
        if (c == preCand && preUrl != null && System.currentTimeMillis() - preAt < PRE_FRESH_MS) return;
        preRunning = true;
        RESOLVE_POOL.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    long t0 = System.currentTimeMillis();
                    String u = musicClient.resolve(c);
                    long t1 = System.currentTimeMillis();
                    if (u == null) {
                        LogMgr.d(TAG, "next prefetch: resolve miss in " + (t1 - t0) + "ms: " + c.name);
                        return;
                    }
                    preCand = c;
                    preUrl = u;
                    preAt = t1;
                    long warm = warmUp(u);
                    LogMgr.d(TAG, "next prefetch: resolved in " + (t1 - t0) + "ms, warm-up "
                            + (warm >= 0 ? warm + "ms" : "failed") + ": " + c.name);
                } catch (Throwable t) {
                    LogMgr.e(TAG, "next prefetch error: " + t);
                } finally {
                    preRunning = false;
                }
            }
        });
    }

    /**
     * 预热: 只取直链开头 64KB(DNS/TCP/CDN 回源提前做掉)。原厂只有一个 MediaPlayer, 正在放当前曲,
     * 没法真正把下一首缓冲进播放器。返回耗时, 失败 -1。
     */
    private static long warmUp(String u) {
        long t0 = System.currentTimeMillis();
        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("Range", "bytes=0-65535");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; U; Android 5.1.1; Phicomm R1)");
            java.io.InputStream in = conn.getInputStream();
            byte[] b = new byte[8192];
            int total = 0, r;
            while (total < 65536 && (r = in.read(b)) > 0) total += r;
            in.close();
            return System.currentTimeMillis() - t0;
        } catch (Throwable t) {
            return -1;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 当前播放位置: 优先问 MediaPlayer, 取不到用开播时刻估算。 */
    private int estimatePosMs() {
        int p = MusicPrefetcher.currentPositionMs();
        if (p > 1000) return p;
        long wall = trackStartedAt > 0 ? System.currentTimeMillis() - trackStartedAt : 0;
        return (int) Math.max(0, Math.min(wall, 3600_000L));
    }

    /** 简短回复, 播完按 after 处理(退出到唤醒 / 接着放)。 */
    private void speak(ANTHandlerContext ctx, String text, int after) {
        afterTts = after;
        try {
            ctx.stopWakeup();
            ctx.stopASR();
        } catch (Throwable ignored) { }
        ctx.playTTS(text);
    }

    /** 不说话, 直接回到唤醒监听(音乐继续放, 会话保持, 与「下一首」相同)。 */
    private void backToWakeup(ANTHandlerContext ctx) {
        try { ctx.enterWakeup(false); } catch (Throwable t) { LogMgr.e(TAG, "enterWakeup failed: " + t); }
    }

    /** 处理控制词(后台线程)。 */
    private void handleControl(int cmd, ANTHandlerContext ctx) {
        boolean ctxOk = hasMusicContext() && lastTrack != null;
        boolean nowPlaying = false;
        try {
            nowPlaying = isPlaying && UniMediaPlayer.getInstance().isPlaying();
        } catch (Throwable ignored) { }
        switch (cmd) {
            case CTL_PAUSE: {
                if (isPlaying) stopMusic();
                userPaused = true;
                LogMgr.d(TAG, "control pause: stopped, pos=" + (lastPosMs / 1000) + "s, resumable=" + ctxOk);
                speak(ctx, "好的", AFTER_EXIT);
                return;
            }
            case CTL_RESUME: {
                if (nowPlaying) { backToWakeup(ctx); return; }
                if (!ctxOk) { speak(ctx, "现在没有可以继续的歌，你可以说播放加歌名", AFTER_EXIT); return; }
                replayTrack(ctx, lastPosMs, "resume", true);
                return;
            }
            case CTL_REPLAY: {
                if (!ctxOk) { speak(ctx, "现在没有在放的歌，你可以说播放加歌名", AFTER_EXIT); return; }
                lastPosMs = 0;
                replayTrack(ctx, 0, "replay", true);
                return;
            }
            case CTL_LOOP_ON:
            case CTL_LOOP_OFF: {
                boolean on = cmd == CTL_LOOP_ON;
                if (!ctxOk) {
                    loopOne = false;
                    speak(ctx, on ? "现在没有在放的歌，你可以说播放加歌名" : "好的", AFTER_EXIT);
                    return;
                }
                loopOne = on;
                LogMgr.d(TAG, "control loop " + (on ? "on" : "off") + ": " + lastTrack.name);
                if (nowPlaying) { backToWakeup(ctx); return; }   // 正在放: 不出声打扰, 只改模式
                if (isPlaying) stopMusic();
                speak(ctx, on ? "好的，单曲循环" : "好的，按顺序播放", userPaused ? AFTER_EXIT : AFTER_RESUME);
                return;
            }
            case CTL_VOL_UP:
            case CTL_VOL_DOWN: {
                boolean up = cmd == CTL_VOL_UP;
                boolean atLimit = false;
                try {   // 原厂音量能力(和原厂「大声点/小声点」同一个实现)
                    com.unisound.ant.device.controlor.DefaultVolumeOperator vo =
                            com.unisound.ant.device.controlor.DefaultVolumeOperator.getInstance(ctx.androidContext());
                    atLimit = up ? vo.isMaxMusicVolume() : vo.isMinMusicVolume();
                    if (!atLimit) {
                        if (up) vo.setVolumeRaise(); else vo.setVolumeLower();
                    }
                    LogMgr.d(TAG, "control volume " + (up ? "up" : "down") + (atLimit ? " (at limit)" : "")
                            + " -> " + vo.getCurrentVolume() + "/" + vo.getMaxVolume());
                } catch (Throwable t) {
                    LogMgr.e(TAG, "volume operator failed: " + t);
                }
                if (nowPlaying) { backToWakeup(ctx); return; }
                boolean resume = ctxOk && !userPaused;
                if (atLimit) {
                    speak(ctx, up ? "已经是最大声了" : "已经是最小声了", resume ? AFTER_RESUME : AFTER_EXIT);
                } else if (resume) {
                    replayTrack(ctx, lastPosMs, "volume", true);   // 调完直接接着放, 不说话
                } else {
                    speak(ctx, "好的", AFTER_EXIT);
                }
                return;
            }
            case CTL_NEXT: {
                if (hasQueue()) { playNextNow(ctx); return; }
                speak(ctx, "后面没有歌了，你可以说播放加歌名", AFTER_EXIT);
                return;
            }
            default:
                speak(ctx, "好的", AFTER_EXIT);
        }
    }

    /** TTS 播完后「接着放」(主线程回调里调用, 网络部分放到后台线程)。 */
    private void resumeTrack(final ANTHandlerContext ctx, final boolean wakeupAfter, final String why) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                replayTrack(ctx, lastPosMs, why, wakeupAfter);
            }
        }).start();
    }

    /**
     * 重新开播 lastTrack 并跳到 posMs(后台线程调用)。直链超过 15 分钟先重新解析。
     * ctx 非空: 先 stopASR, 开播后 enterWakeup(false)(与「下一首」相同, 不出声)。
     */
    private void replayTrack(final ANTHandlerContext ctx, final int posMs, final String why, final boolean wakeupAfter) {
        MusicPlayResult t = lastTrack;
        if (t == null) return;
        long t0 = System.currentTimeMillis();
        if (System.currentTimeMillis() - lastTrackUrlAt > URL_FRESH_MS && lastCand != null) {
            String u = null;
            try {
                u = musicClient.resolve(lastCand);
            } catch (Throwable e) {
                LogMgr.e(TAG, "re-resolve error: " + e);
            }
            LogMgr.d(TAG, why + ": url older than " + (URL_FRESH_MS / 60000) + "min, re-resolve "
                    + (u != null ? "ok" : "miss (use old url)") + " in " + (System.currentTimeMillis() - t0) + "ms");
            if (u != null) t = NetEaseMusicClient.toPlay(lastCand, u);
        }
        final MusicPlayResult track = t;
        if (ctx != null) {
            try { ctx.stopASR(); } catch (Throwable ignored) { }
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                currentResult = track;
                rememberTrack(track);
                ensureListener();
                MusicPrefetcher.cancel();
                playGen++;
                isPlaying = true;
                pendingSeekMs = posMs > 3000 ? posMs : 0;
                LogMgr.d(TAG, why + ": play " + track.name + " from " + (pendingSeekMs / 1000) + "s");
                UniMediaPlayer.getInstance().playUrl(track.playUrl, "music_search");
                if (ctx != null && wakeupAfter) {
                    try {
                        ctx.enterWakeup(false);
                    } catch (Throwable e) {
                        LogMgr.e(TAG, "enterWakeup after " + why + " failed: " + e);
                    }
                }
            }
        });
    }

    /**
     * 停止音乐播放
     */
    private void stopMusic() {
        if (isPlaying) {
            lastPosMs = estimatePosMs();
            lastStoppedAt = System.currentTimeMillis();
        }
        LogMgr.d(TAG, "stopMusic (queue kept 5min, size=" + queueSize() + ", pos=" + (lastPosMs / 1000) + "s)");
        MusicPrefetcher.cancel();
        playGen++;                       // 让在途的"自动下一首"作废
        queueKeptUntil = System.currentTimeMillis() + 5 * 60 * 1000L;  // 队列留 5 分钟给「下一首」
        if (isPlaying) {
            isPlaying = false;
            try {
                UniMediaPlayer.getInstance().stop();
            } catch (Throwable ignored) {}
            isPlaying = false;
        }
        currentResult = null;
    }
    
    private void exit() {
        try {
            if (this.ctx != null) {
                this.ctx.cancelTTS();
                // 注意：不要调用 cancelEngine()，它会取消整个 ANT 引擎导致无法唤醒
                // 只需调用 reset() 清空会话状态即可
                // this.ctx.cancelEngine();  // ❌ 会导致无法再次唤醒
            }
        } catch (Throwable ignored) {}
        reset();
        currentResult = null;
    }
}
