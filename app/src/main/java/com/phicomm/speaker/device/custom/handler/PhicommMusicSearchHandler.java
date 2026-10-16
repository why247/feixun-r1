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
        
        // 异步搜索 + 播放（网络操作不能在主线程）
        new Thread(new Runnable() {
            @Override
            public void run() {
                searchAndPlay(ctx, keyword.trim());
            }
        }).start();
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
     * 搜索并播放
     */
    private void searchAndPlay(ANTHandlerContext ctx, String keyword) {
        LogMgr.d(TAG, "searchAndPlay: " + keyword);
        
        try {
            // 候选列表(QQ 音乐优先, 否则酷我): 第一首能播的立刻播, 其余进连播队列
            long t0 = System.currentTimeMillis();
            MusicPlayResult result = null;
            List<NetEaseMusicClient.MusicSearchResult> cands = musicClient.candidates(keyword);
            int i = 0;
            for (; i < cands.size() && i < 12; i++) {
                String url = musicClient.resolve(cands.get(i));
                if (url != null) {
                    result = NetEaseMusicClient.toPlay(cands.get(i), url);
                    break;
                }
            }
            LogMgr.d(TAG, "first track resolved in " + (System.currentTimeMillis() - t0) + "ms, tried " + (i + 1));
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
            }
            LogMgr.d(TAG, "queue size after search: " + queue.size());
            
            if (result == null) {
                LogMgr.e(TAG, "search failed");
                ctx.playTTS("抱歉，未找到相关歌曲");
                return;
            }
            
            currentResult = result;
            LogMgr.d(TAG, "found: " + result.name + " - " + result.artist + " url=" + result.playUrl);
            
            // 停止唤醒和 ASR
            ctx.stopWakeup();
            ctx.stopASR();
            
            // 播报期间就开始缓冲, TTS 结束(onTTSEventPlayingEnd)立刻开播; 只有第一首播报
            MusicPrefetcher.prepare(result.playUrl, "music_search");
            String ttsText = announceText(result.name, result.artist);
            LogMgr.d(TAG, "announce: " + ttsText);
            ctx.playTTS(ttsText);
            
        } catch (Exception e) {
            LogMgr.e(TAG, "searchAndPlay error: " + e.getMessage(), e);
            ctx.playTTS("抱歉，播放失败");
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
