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
        
        // 「下一首」: 有连播队列时由这里接管
        if (text != null && isNextIntent(text) && hasQueue()) {
            LogMgr.d(TAG, "acceptInboundEvent0: next intent with queue: " + text);
            return true;
        }

        // music 和 setting.mp 直接拦截
        if (isMusic || isSettingMp) {
            LogMgr.d(TAG, "acceptInboundEvent0: service=" + service + ", text=" + text + ", accept=true");
            return true;
        }
        
        // chat 意图: 只有明确包含音乐关键词才拦截
        boolean isChat = "cn.yunzhisheng.chat".equals(service);
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
                        // 出错时 onError 返回 false, MediaPlayer 随后也会回调 onCompletion(4), 所以只认 4
                        if (state != 4) return;
                        final int gen = playGen;
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
                    return;
                }
                currentResult = next;
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

    /**
     * 停止音乐播放
     */
    private void stopMusic() {
        LogMgr.d(TAG, "stopMusic (queue kept 5min, size=" + queueSize() + ")");
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
