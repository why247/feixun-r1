package com.phicomm.speaker.device.custom.handler;

import java.util.regex.Pattern;
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
    private static final java.util.concurrent.ExecutorService RESOLVE_POOL =
            java.util.concurrent.Executors.newCachedThreadPool(new java.util.concurrent.ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "MusicResolve");
                    t.setDaemon(true);
                    return t;
                }
            });
    /**
     * 点歌/切歌/控制/连播/续播的后台任务共享线程池(替代每次 new Thread): 最多 4 个线程, 空闲 30s 回收,
     * 不排队(SynchronousQueue, 「暂停」等控制不会排在慢搜索后面)。和 RESOLVE_POOL 分开: searchAndPlay
     * 会在 RESOLVE_POOL 上提交并等待, 同池可能互等。4 个都忙时(极少)退回临时线程并记日志, 不丢任务。
     */
    private static final java.util.concurrent.ThreadPoolExecutor MUSIC_POOL =
            new java.util.concurrent.ThreadPoolExecutor(0, 4, 30L, java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.SynchronousQueue<Runnable>(),
                    new java.util.concurrent.ThreadFactory() {
                        private final java.util.concurrent.atomic.AtomicInteger n =
                                new java.util.concurrent.atomic.AtomicInteger();
                        @Override
                        public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "MusicTask-" + n.incrementAndGet());
                            t.setDaemon(true);
                            return t;
                        }
                    },
                    new java.util.concurrent.RejectedExecutionHandler() {
                        @Override
                        public void rejectedExecution(Runnable r, java.util.concurrent.ThreadPoolExecutor e) {
                            LogMgr.d(TAG, "music pool busy (" + e.getActiveCount() + "), run on temp thread");
                            Thread t = new Thread(r, "MusicTaskOverflow");
                            t.setDaemon(true);
                            t.start();
                        }
                    });

    private static void runMusicTask(Runnable r) {
        MUSIC_POOL.execute(r);
    }

    /** 每次点歌 +1; 打断时也 +1, 在途的搜索结果作废 */
    private volatile int searchGen = 0;
    private volatile long intentAt = 0;
    private volatile boolean firstStartLogged = true;
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
        INSTANCE = this;
    }

    /** v76: NLUDispatcher 控制词早分发要问「这句我们的播放器会不会接管」 */
    private static volatile PhicommMusicSearchHandler INSTANCE;

    /**
     * v76 控制词秒响应: text 整句是控制词, 且 acceptInboundEvent0 会由我们的播放器接管时, 返回分发用的
     * service(与 acceptInboundEvent0 的分支一一对应); 否则 null(NLUDispatcher 照原流程等云端)。
     * 下一首: 有连播队列; 暂停/继续/重播/循环: 有音乐上下文(在放或 10 分钟内停下); 音量: 有上下文且没被用户暂停。
     */
    public static String earlyControlService(String text) {
        PhicommMusicSearchHandler h = INSTANCE;
        if (h == null || text == null) return null;
        int cmd = controlCommand(text);
        if (cmd == CTL_NONE) return null;
        if (cmd == CTL_NEXT) {
            return h.isNextIntent(text) && h.hasQueue() ? "cn.yunzhisheng.setting.mp" : null;
        }
        if (cmd >= CTL_PAUSE && cmd <= CTL_LOOP_OFF) {
            return h.hasMusicContext() ? "cn.yunzhisheng.setting.mp" : null;
        }
        if (cmd == CTL_VOL_UP || cmd == CTL_VOL_DOWN) {
            return h.hasMusicContext() && !h.userPaused ? "cn.yunzhisheng.setting" : null;
        }
        return null;
    }

    /**
     * v76: 唤醒后识别的 VAD 尾点(说完后静音多久算说完)。原厂 asrVadTimeoutBackSil=800ms, doEnterASR 每次读这个
     * 静态值, 只能全局改(唤醒后首轮、多轮对话、点歌都一样), 所以只保守地降到 700ms(-100ms/句);
     * 小孩说话中间停顿 0.7s 以上仍可能被切句。控制词已由本地结果提前分发, 不依赖这个值。
     */
    private static final int VAD_BACK_SIL_MS = 700;

    private static void tuneVadBackSil() {
        try {
            int old = com.unisound.vui.common.config.ANTConfigPreference.asrVadTimeoutBackSil;
            if (old > VAD_BACK_SIL_MS) {
                com.unisound.vui.common.config.ANTConfigPreference.asrVadTimeoutBackSil = VAD_BACK_SIL_MS;
                LogMgr.d(TAG, "VAD back sil " + old + " -> " + VAD_BACK_SIL_MS + "ms (asr)");
            } else {
                LogMgr.d(TAG, "VAD back sil keep " + old + "ms (asr)");
            }
        } catch (Throwable t) {
            LogMgr.e(TAG, "VAD back sil tune failed: " + t);
        }
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
        tuneVadBackSil();
        prewarmAsync("boot");
        return super.onASREventEngineInitDone(ctx);
    }
    
    @Override
    public void eventReceived(final NLU evt, final ANTHandlerContext ctx) throws Exception {
        super.eventReceived(evt, ctx);

        String raw = null;
        try { raw = evt.getText(); } catch (Throwable ignored) { }
        com.phicomm.speaker.device.custom.engine.VoiceTiming.intent("music", raw);
        if (raw != null && isNextIntent(raw) && hasQueue()) {
            runMusicTask(new Runnable() {
                @Override
                public void run() {
                    playNextNow(ctx);
                }
            });
            return;
        }

        final int cmd = controlCommand(raw);
        if (cmd != CTL_NONE) {
            LogMgr.d(TAG, "control " + CTL_NAMES[cmd] + " (" + raw + "), music ctx=" + hasMusicContext()
                    + " playing=" + isPlaying + " userPaused=" + userPaused);
            runMusicTask(new Runnable() {
                @Override
                public void run() {
                    handleControl(cmd, ctx);
                }
            });
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
        
        // 异步搜索 + 播放（网络操作不能在主线程）; 解析出直链后播报「为您播放…」, 播报期间缓冲
        final int sg = ++searchGen;
        intentAt = System.currentTimeMillis();
        firstStartLogged = false;
        prewarmAsync("intent");   // v76: 与搜索并行做 DNS / GD 台 TLS 预热, 首曲解析直接复用连接
        runMusicTask(new Runnable() {
            @Override
            public void run() {
                searchAndPlay(ctx, keyword.trim(), sg);
            }
        });
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
     * 总预算约 3 秒; 超时/都不行才进入原有回退链。解析好后一律播报「为您播放…」(播报期间预缓冲,
     * 播完 onTTSEventPlayingEnd 立即开播)。
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

    /** 搜索结束: 首曲一律播报「为您播放…」, 播报期间缓冲, TTS 结束(onTTSEventPlayingEnd)立刻开播。 */
    private void deliverResult(ANTHandlerContext ctx, MusicPlayResult result, int sg) {
        if (sg != searchGen) return;
        long sinceIntent = System.currentTimeMillis() - intentAt;
        if (result == null) {
            LogMgr.e(TAG, "search failed (" + sinceIntent + "ms after intent)");
            currentResult = null;
            ctx.playTTS("抱歉，未找到相关歌曲");
            return;
        }
        currentResult = result;
        LogMgr.d(TAG, "found: " + result.name + " - " + result.artist + " url=" + result.playUrl);
        LogMgr.d(TAG, "timing: resolved " + sinceIntent + "ms after intent -> announce");
        // 停止唤醒和 ASR
        ctx.stopWakeup();
        ctx.stopASR();
        MusicPrefetcher.prepare(result.playUrl, "music_search");
        final java.util.concurrent.Future<String> hq = result.pendingHq;
        result.pendingHq = null;
        if (hq != null) {
            // v76: 曲目已确定(MP3 可播), 先播报; MP3 先缓冲兜底, 无损到了换缓冲(onHqDecided)
            final MusicPlayResult r = result;
            final ANTHandlerContext c = ctx;
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    hqPending = r;
                    hqTtsEnded = false;
                }
            });
            startHqWaiter(c, r, hq, sg);
        }
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
                if (i >= par && !"qq".equals(c.source)) {
                    // v76: 酷我候选: 无损与 MP3 并行(选用顺序不变), MP3 先好就先播报
                    MusicPlayResult r = resolveKuwoParallel(c, sg);
                    if (r != null) {
                        picked[0] = i;
                        return r;
                    }
                    continue;
                }
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

    /** v76: 后台预热(DNS + GD 台 TLS 连接进池), 不发 API 请求, 失败无影响。 */
    private void prewarmAsync(final String why) {
        try {
            RESOLVE_POOL.submit(new Runnable() {
                @Override
                public void run() {
                    long t0 = System.currentTimeMillis();
                    try {
                        musicClient.prewarm();
                    } catch (Throwable t) {
                        LogMgr.d(TAG, "prewarm (" + why + ") error: " + t);
                    }
                    LogMgr.d(TAG, "timing: prewarm (" + why + ") done in " + (System.currentTimeMillis() - t0) + "ms");
                }
            });
        } catch (Throwable ignored) { }
    }

    /** 等 future 完成(每 40ms 看一次是否被打断), 返回直链或 null。 */
    private String waitUrl(java.util.concurrent.Future<String> f, int sg) {
        while (!f.isDone()) {
            if (sg != searchGen) return null;
            try { Thread.sleep(40); } catch (InterruptedException e) { return null; }
        }
        return futureUrl(f);
    }

    /** 无损先到时, 在 MP3 已可播之后再给它这么久(省一次「先缓冲 MP3 再换无损」) */
    private static final long HQ_GRACE_MS = 300;
    /** 播报已开始、等无损的上限(无损两跳各自超时 5s 连接 + 8s 读, 正常远小于此), 超过用 MP3 */
    private static final long HQ_WAIT_MAX_MS = 30000;

    /**
     * v76 首曲(非 QQ 候选)并行解析: 原来 kuwoResolve 串行「无损(车机FLAC→GD) → 酷我 MP3」, 现在两段同时发,
     * 选用顺序不变: 无损拿到就用无损; 无损落空才用 MP3; MP3 落空只看无损。
     * MP3 先到而无损仍在路上: 返回 MP3 结果并挂 pendingHq —— 这首歌已确定能播(播报的就是实际曲名),
     * 调用方先播报, 无损到了再把缓冲换成无损(deliverResult/onHqDecided)。
     */
    private MusicPlayResult resolveKuwoParallel(final NetEaseMusicClient.MusicSearchResult c, int sg) {
        if (!NetEaseMusicClient.losslessEnabled) {
            String u = musicClient.resolve(c);
            return u == null ? null : NetEaseMusicClient.toPlay(c, u);
        }
        long t0 = System.currentTimeMillis();
        java.util.concurrent.Future<String> hq = RESOLVE_POOL.submit(new java.util.concurrent.Callable<String>() {
            @Override
            public String call() {
                return musicClient.losslessOnly(c);
            }
        });
        java.util.concurrent.Future<String> mp3 = RESOLVE_POOL.submit(new java.util.concurrent.Callable<String>() {
            @Override
            public String call() {
                return musicClient.kuwoMp3Only(c);
            }
        });
        long mp3OkAt = 0;
        while (true) {
            if (sg != searchGen) { hq.cancel(false); mp3.cancel(false); return null; }
            long now = System.currentTimeMillis();
            if (hq.isDone()) {
                String u = futureUrl(hq);
                if (u != null) {
                    mp3.cancel(false);
                    LogMgr.d(TAG, "first track: lossless ok in " + (now - t0) + "ms (parallel with mp3)");
                    return NetEaseMusicClient.toPlay(c, u);
                }
                String m = waitUrl(mp3, sg);
                LogMgr.d(TAG, "first track: lossless miss, mp3 " + (m != null ? "ok" : "miss") + " in "
                        + (System.currentTimeMillis() - t0) + "ms");
                return m == null ? null : NetEaseMusicClient.toPlay(c, m);
            }
            if (mp3.isDone()) {
                String m = futureUrl(mp3);
                if (m == null) {
                    String u = waitUrl(hq, sg);
                    LogMgr.d(TAG, "first track: mp3 miss, lossless " + (u != null ? "ok" : "miss") + " in "
                            + (System.currentTimeMillis() - t0) + "ms");
                    return u == null ? null : NetEaseMusicClient.toPlay(c, u);
                }
                if (mp3OkAt == 0) mp3OkAt = now;
                if (now - mp3OkAt >= HQ_GRACE_MS) {
                    MusicPlayResult r = NetEaseMusicClient.toPlay(c, m);
                    r.pendingHq = hq;
                    LogMgr.d(TAG, "first track: mp3 ready in " + (mp3OkAt - t0) + "ms, lossless pending -> announce early");
                    return r;
                }
            }
            try { Thread.sleep(40); } catch (InterruptedException e) { return null; }
        }
    }

    /** v76: 已播报、等无损决定的那首(主线程读写) */
    private MusicPlayResult hqPending;
    /** v76: 等无损期间 TTS 已结束(决定一到立即开播) */
    private boolean hqTtsEnded = false;

    /** 后台等无损结果, 回主线程决定用无损还是 MP3。 */
    private void startHqWaiter(final ANTHandlerContext ctx, final MusicPlayResult result,
                               final java.util.concurrent.Future<String> hq, final int sg) {
        runMusicTask(new Runnable() {
            @Override
            public void run() {
                long deadline = System.currentTimeMillis() + HQ_WAIT_MAX_MS;
                while (!hq.isDone() && sg == searchGen && System.currentTimeMillis() < deadline) {
                    try { Thread.sleep(40); } catch (InterruptedException e) { break; }
                }
                final boolean timedOut = !hq.isDone();
                final String u = futureUrl(hq);
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        onHqDecided(ctx, result, u, timedOut, sg);
                    }
                });
            }
        });
    }

    /** 主线程: 无损到了就把缓冲换成无损, 否则保持 MP3; TTS 已结束则立即开播。 */
    private void onHqDecided(ANTHandlerContext ctx, MusicPlayResult result, String hqUrl, boolean timedOut, int sg) {
        if (hqPending != result) return;
        hqPending = null;
        boolean ttsEnded = hqTtsEnded;
        hqTtsEnded = false;
        long since = System.currentTimeMillis() - intentAt;
        if (sg != searchGen || currentResult != result) {
            LogMgr.d(TAG, "lossless decision dropped (interrupted/superseded)");
            return;
        }
        if (hqUrl != null) {
            result.playUrl = hqUrl;
            result.resolvedAt = System.currentTimeMillis();
            LogMgr.d(TAG, "timing: lossless ready " + since + "ms after intent -> switch buffer to lossless"
                    + (ttsEnded ? " (TTS already ended)" : " (during TTS)"));
            MusicPrefetcher.prepare(hqUrl, "music_search");
        } else {
            LogMgr.d(TAG, "timing: lossless " + (timedOut ? "timeout" : "miss") + " " + since
                    + "ms after intent -> keep mp3");
        }
        if (ttsEnded && currentResult != null && currentResult.playUrl != null) {
            playMusic(ctx, new ArrayList<MusicItem>());
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

        // v76: 首曲已播报但无损还在解析: 等决定(MP3 已在缓冲兜底), 决定一到 onHqDecided 立即开播
        if (hqPending != null && hqPending == currentResult) {
            hqTtsEnded = true;
            LogMgr.d(TAG, "TTS end, lossless still resolving -> wait (mp3 buffered as fallback)");
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
            hqPending = null;
            hqTtsEnded = false;
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
                            runMusicTask(new Runnable() {
                                @Override
                                public void run() {
                                    if (gen == playGen && isPlaying) replayTrack(null, 0, "loop", false);
                                }
                            });
                            return;
                        }
                        LogMgr.d(TAG, "track ended (state=" + state + "), auto next");
                        runMusicTask(new Runnable() {
                            @Override
                            public void run() {
                                playNextSilent(gen);
                            }
                        });
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

    // controlCommand 用的正则: 预编译一次复用(原来每次调用编译 11 个, 每次交互调用 3 次)
    private static final Pattern CTL_STRIP_PUNCT = Pattern.compile("[\\s，。！？、,.!?~～…]+");
    private static final Pattern CTL_STRIP_PREFIX = Pattern.compile("^(请你|请|帮我|麻烦你|麻烦|你)");
    private static final Pattern CTL_STRIP_SUFFIX = Pattern.compile("(吧|啊|呀|啦|哦|呢|嘛|好吗|好不好|可以吗)+$");
    private static final Pattern CTL_P_NEXT = Pattern.compile("(下一首|下首|换一首|换首歌|换一首歌|切歌|下一曲|换个歌|换一个歌)(歌)?");
    private static final Pattern CTL_P_LOOP_OFF = Pattern.compile("(取消|关闭|关掉|不要|别|退出)(单曲)?循环(播放)?|顺序播放|列表循环|列表播放");
    private static final Pattern CTL_P_LOOP_ON = Pattern.compile("单曲循环(播放)?|循环播放(这首歌?)?|循环这首歌?|这首歌?循环(播放)?|一直(放|播|听)这首歌?");
    private static final Pattern CTL_P_REPLAY = Pattern.compile("(再|重新)(放|播|听|唱|来)(一遍|一次|一下)?(这首歌?)?|重播|重新播放(这首歌?)?|从头(播放|放|播|开始)");
    private static final Pattern CTL_P_PAUSE = Pattern.compile("暂停(播放|一下|音乐|一会儿?)?|停(一下|下|下来)?|停止(播放|音乐)?|(别|不要)(放|播|唱)了"
                + "|不听了|(关掉|关闭)(音乐|歌曲?)|先停(一下)?");
    private static final Pattern CTL_P_RESUME = Pattern.compile("继续(播放|放|播|唱|听)?(音乐|歌曲?)?|接着(放|播|唱|听)|恢复播放|播放继续|开始播放|播放");
    private static final Pattern CTL_P_VOL_UP = Pattern.compile("(再|把)?(大声|大点声|开大|调大|声音大|音量大|声音调大|音量调大|声音开大|音量开大|调大音量|调大声音"
                + "|声音调高|音量调高|调高音量)(一)?(点|些)?(儿)?(声)?|太小声了?");
    private static final Pattern CTL_P_VOL_DOWN = Pattern.compile("(再|把)?(小声|小点声|关小|调小|声音小|音量小|声音调小|音量调小|声音关小|音量关小|调小音量|调小声音"
                + "|声音调低|音量调低|调低音量)(一)?(点|些)?(儿)?(声)?|太大声了?|太吵了?");

    /**
     * 识别播放控制词(整句匹配, 去掉标点和语气词), 不是控制词返回 CTL_NONE。
     * 「继续播放/暂停播放」里虽然有「播放」, 也不会再被当成点歌。
     */
    static int controlCommand(String text) {
        if (text == null) return CTL_NONE;
        String t = CTL_STRIP_PUNCT.matcher(text).replaceAll("");
        t = CTL_STRIP_PREFIX.matcher(t).replaceAll("");
        t = CTL_STRIP_SUFFIX.matcher(t).replaceAll("");
        if (t.isEmpty() || t.length() > 12) return CTL_NONE;
        if (CTL_P_NEXT.matcher(t).matches()) return CTL_NEXT;
        if (CTL_P_LOOP_OFF.matcher(t).matches()) return CTL_LOOP_OFF;
        if (CTL_P_LOOP_ON.matcher(t).matches())
            return CTL_LOOP_ON;
        if (CTL_P_REPLAY.matcher(t).matches())
            return CTL_REPLAY;
        if (CTL_P_PAUSE.matcher(t).matches()) return CTL_PAUSE;
        if (CTL_P_RESUME.matcher(t).matches())
            return CTL_RESUME;
        if (CTL_P_VOL_UP.matcher(t).matches()) return CTL_VOL_UP;
        if (CTL_P_VOL_DOWN.matcher(t).matches()) return CTL_VOL_DOWN;
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

    /** v76: 不说话结束本次交互: 等同「好的」TTS 播完后的 AFTER_EXIT(exit + 回唤醒), 只是没有 TTS。 */
    private void silentExit(final ANTHandlerContext ctx, final String why) {
        afterTts = AFTER_NONE;
        LogMgr.d(TAG, "control " + why + ": silent (no TTS) -> wakeup");
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                exit();
                try { ctx.enterWakeup(false); } catch (Throwable t) { LogMgr.e(TAG, "enterWakeup failed: " + t); }
            }
        });
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
                silentExit(ctx, "pause");   // v76: 小爱风格, 暂停/停止不说「好的」、不放提示音
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
        runMusicTask(new Runnable() {
            @Override
            public void run() {
                replayTrack(ctx, lastPosMs, why, wakeupAfter);
            }
        });
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
