package com.phicomm.speaker.device.custom.handler;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.phicomm.speaker.device.custom.ai.ConversationHistory;
import com.phicomm.speaker.device.custom.ai.OpenAIClient;
import com.phicomm.speaker.device.custom.gadget.GadgetClient;
import com.phicomm.speaker.device.custom.ai.PersonaConversationManager;
import com.phicomm.speaker.device.custom.config.AIConfig;
import com.phicomm.speaker.device.custom.tts.DoubaoVoicePlayer;
import com.phicomm.speaker.device.custom.engine.PlaybackStateMonitor;
import com.phicomm.speaker.device.custom.persona.PersonaConfig;
import com.phicomm.speaker.device.custom.persona.PersonaManager;
import com.phicomm.speaker.device.custom.teaser.PostDialogueTeaser;
import com.unisound.vui.engine.ANTHandlerContext;
import com.unisound.vui.handler.SessionRegister;
import com.unisound.vui.handler.SimpleUserEventInboundHandler;
import com.unisound.vui.util.LogMgr;
import com.unisound.vui.util.ThreadUtils;

import nluparser.scheme.NLU;

import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 大模型 Chat Handler
 * 
 * 拦截 service == cn.yunzhisheng.chat 的 NLU 事件
 * 优先级 315,高于 PhicommMusicSearchHandler(310) 和 PhicommWeatherHandler(310)
 * 
 * 职责:
 * 1. 快速判断是否音乐/天气意图,如果是则放行
 * 2. 成语接龙游戏(最高优先级)
 * 3. 其他闲聊/问答意图,调用大模型 API 生成回复
 * 4. TTS 播报大模型回复
 * 
 * 工作流程:
 * acceptInboundEvent0: 关键词快速判断 → 决定是否消费
 * eventReceived: 异步调用大模型 → ctx.playTTS(回复)
 * onTTSEventPlayingEnd: TTS 结束后 → 根据模式决定进入 ASR 或 Wakeup
 */
public class PhicommChatHandler extends SimpleUserEventInboundHandler<NLU> {
    private static final String TAG = "PhicommChat";
    private static final String LOG_FILE = "/sdcard/eav_event.log";
    
    /**
     * 写入日志到文件
     */
    private static void writeLog(String message) {
        try {
            FileWriter fw = new FileWriter(LOG_FILE, true);
            String timestamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
            fw.append(timestamp).append(" [").append(TAG).append("] ").append(message).append("\n");
            fw.close();
        } catch (Exception e) {
            LogMgr.e(TAG, "写入日志失败: " + e);
        }
    }
    
    // 音乐关键词(如果包含这些词,放行给 PhicommMusicSearchHandler)
    private static final String[] MUSIC_KEYWORDS = {
        "播放", "放一首", "来一首", "来一个", "我想听", "唱一", "听音乐", "听歌",
        "放歌", "放音乐", "来点音乐", "放个歌", "给我唱", "唱首"
    };
    
    // 天气关键词(如果包含这些词,放行给 PhicommWeatherHandler)
    private static final String[] WEATHER_KEYWORDS = {
        "天气", "温度", "下雨", "晴天", "阴天", "多云", "几度", "摄氏度",
        "刮风", "下雪", "雾霾", "空气质量", "天气预报", "今天天气", "明天天气"
    };
    
    // AI 客户端
    private OpenAIClient openAIClient;

    // Gadget 直连: gadget_token 非空即启用, 直连 Brio(失败回落 OpenAI 池)
    private GadgetClient gadgetClient;
    private boolean useGadget = false;
    
    // 当前大模型回复(用于打断处理)
    private String currentResponse;

    // ⭐ v83: 最近一轮的用户原话(回复播完后交给 PostDialogueTeaser 做事后调侃)
    private volatile String lastUserInput;

    // ⭐ v84: 只有本轮回复确实由【大模型成功生成】才置 true(在 playTTS 前设置,
    //    playingEnd 消费后清除)。传统指令(音乐/音量/关机等)由其他 Handler 处理,
    //    根本不走这里; 即使有杂散 TTS 事件飘进本 Handler, 没有这个标记也绝不调侃。
    private volatile boolean pendingTease = false;
    
    // 上下文
    private ANTHandlerContext ctx;
    
    // 成语接龙游戏状态
    private boolean isIdiomGameMode = false;  // 是否在成语接龙模式
    private int idiomGameTurn = 0;  // 当前轮数
    private String lastUserIdiom = null;  // 用户说的最后一个成语
    // 多轮对话(成语接龙/英语陪练师等)静音超时: 15 秒后退出, 让出 MIC 给唤醒
    private static final int MAX_IDLE_TIMEOUT = 15000;
    
    // 超时处理器
    private Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private Runnable timeoutRunnable;
    
    // 多轮对话状态(仅大模型场景)
    private boolean isMultiTurnMode = false;  // 是否在多轮对话模式
    private ConversationHistory conversationHistory;  // 对话历史管理器(内置10分钟惰性清理)
    
    // ⭐ 异步任务追踪标志,用于人格切换时等待任务完成
    private volatile boolean isProcessingRequest = false;

    // 「嗯，我想想」: 调 Muse 前先说一句。它的 TTS 结束事件要吞掉(不能触发多轮 ASR /
    // handleTtsPlayingEnd), 正式回答等它说完再播(不互相打断丢字)。
    private static final String FILLER_TEXT = "嗯，我想想";
    /** 兜底: 这么久还没收到「嗯，我想想」的结束事件就当它播完了 */
    private static final long FILLER_MAX_MS = 6000;
    private final Object fillerLock = new Object();
    private boolean fillerPlaying = false;
    private long fillerStartedAt = 0;
    
    public PhicommChatHandler() {
        this.sessionName = SessionRegister.SESSION_CHAT;
    }
    
    @Override
    public void initPriority() {
        // 优先级 315,高于 MusicSearchHandler(310) 和 WeatherHandler(310)
        setPriority(315);
    }
    
    @Override
    public boolean onASREventEngineInitDone(ANTHandlerContext ctx) {
        // 保存上下文
        this.ctx = ctx;
        
        // 在 ASR 引擎初始化完成时,初始化 AIConfig 和 OpenAIClient
        Context context = ctx.androidContext();
        AIConfig config = AIConfig.load(context);
        this.openAIClient = new OpenAIClient(config);

        // Gadget 直连: gadget_token 非空即启用, 直连 Brio; 失败时回落 OpenAI 池
        // 优先用 Muse 蓝牙配对得到的设备凭证 (files/muse_pairing.json + muse_identity.json)
        com.phicomm.speaker.device.custom.gadget.DeviceCredentials creds =
                com.phicomm.speaker.device.custom.gadget.DeviceCredentials.load(context.getFilesDir());
        String gadgetToken = config.getGadgetToken();
        if (creds != null) {
            this.useGadget = true;
            this.gadgetClient = new GadgetClient(creds);
            LogMgr.d(TAG, "Gadget: device credentials loaded, node=" + creds.nodeId());
            this.gadgetClient.startKeepAlive();   // 开机就连好 + 保活, 问话时不用再建连
        } else {
            this.useGadget = gadgetToken != null && !gadgetToken.isEmpty();
            if (this.useGadget) {
                this.gadgetClient = new GadgetClient(gadgetToken);
            }
            LogMgr.d(TAG, "Gadget: no device credentials, useGadget=" + this.useGadget);
        }
        
        LogMgr.d(TAG, "PhicommChatHandler initialized with model: " + config.getModel());
        return super.onASREventEngineInitDone(ctx);
    }
    
    /** 调 Muse 前立刻说「嗯，我想想」(替换原来的嘀声)。 */
    private void playFiller(ANTHandlerContext ctx) {
        synchronized (fillerLock) {
            fillerPlaying = true;
            fillerStartedAt = System.currentTimeMillis();
        }
        try {
            ctx.playTTS(FILLER_TEXT);
            LogMgr.d(TAG, "filler TTS: " + FILLER_TEXT);
        } catch (Throwable t) {
            LogMgr.e(TAG, "filler TTS failed: " + t);
            synchronized (fillerLock) {
                fillerPlaying = false;
                fillerLock.notifyAll();
            }
        }
    }

    /** 播正式回答前调用(工作线程): 「嗯，我想想」还在播就等它结束, 结束后立刻返回。 */
    private void waitFillerDone() {
        synchronized (fillerLock) {
            if (!fillerPlaying) return;
            long t0 = System.currentTimeMillis();
            long until = fillerStartedAt + FILLER_MAX_MS;
            while (fillerPlaying) {
                long left = until - System.currentTimeMillis();
                if (left <= 0) break;
                try {
                    fillerLock.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (fillerPlaying) {
                LogMgr.e(TAG, "filler end event not received in " + FILLER_MAX_MS + "ms, speak anyway");
                fillerPlaying = false;
            } else {
                LogMgr.d(TAG, "answer waited " + (System.currentTimeMillis() - t0) + "ms for filler to finish");
            }
        }
    }

    /** TTS 结束事件若属于「嗯，我想想」则吞掉并唤醒等待中的回答, 返回 true。 */
    private boolean consumeFillerEnd() {
        synchronized (fillerLock) {
            if (!fillerPlaying) return false;
            fillerPlaying = false;
            fillerLock.notifyAll();
        }
        LogMgr.d(TAG, "filler TTS end (ignored for multi-turn)");
        return true;
    }

    /** 「嗯，我想想」是否还在播(超过 FILLER_MAX_MS 视为已播完, 兜底丢失的结束事件)。 */
    private boolean isFillerPlaying() {
        synchronized (fillerLock) {
            if (!fillerPlaying) return false;
            if (System.currentTimeMillis() - fillerStartedAt >= FILLER_MAX_MS) {
                LogMgr.e(TAG, "filler end event not received in " + FILLER_MAX_MS + "ms, treat as done");
                fillerPlaying = false;
                fillerLock.notifyAll();
                return false;
            }
            return true;
        }
    }

    // ================= 按句流式播报 (Muse/Gadget) =================
    // 句子队列: 「嗯，我想想」播完立即播第一句, 每句 TTS 结束事件触发下一句;
    // 只有最后一句(流已结束且队列空)的结束才走 handleTtsPlayingEnd(多轮 ASR / 唤醒)。
    // 下一句还没到就等, 绝不进 ASR。streamTurn 是轮次 id: 新一轮/打断时 +1, 旧轮次残余句子全部丢弃。
    // 锁顺序: streamLock -> fillerLock (反过来不允许)。
    private final Object streamLock = new Object();
    private int streamTurn = 0;
    private boolean streamActive = false;     // 本轮由流式播报接管 TTS 结束事件
    private boolean streamEnded = false;      // Gadget 流已结束(不会再有新句子)
    private boolean sentenceSpeaking = false; // 有一句已交给 TTS, 等它的结束事件
    private int speakSeq = 0;                 // 每 playTTS 一句 +1, 给看门狗配对
    private int sentencesQueued = 0;
    private int sentencesSpoken = 0;
    private long streamStartedAt = 0;
    private final java.util.LinkedList<String> sentenceQueue = new java.util.LinkedList<String>();
    private final StringBuilder streamText = new StringBuilder();
    /** 句子结束事件看门狗: 基础时长 + 每字时长(很宽松, 只防结束事件丢失导致永远卡住) */
    private static final long SENTENCE_WATCHDOG_BASE_MS = 8000;
    private static final long SENTENCE_WATCHDOG_PER_CHAR_MS = 350;

    /** 开始新一轮流式播报, 返回轮次 id。 */
    private int beginStream() {
        synchronized (streamLock) {
            streamTurn++;
            streamActive = true;
            streamEnded = false;
            sentenceSpeaking = false;
            sentencesQueued = 0;
            sentencesSpoken = 0;
            sentenceQueue.clear();
            streamText.setLength(0);
            streamStartedAt = System.currentTimeMillis();
            LogMgr.d(TAG, "stream turn " + streamTurn + " begin");
            return streamTurn;
        }
    }

    /** 新一轮唤醒/提问、打断、退出: 作废当前轮次, 清空队列。 */
    private void cancelStream(String why) {
        synchronized (streamLock) {
            if (streamActive || !sentenceQueue.isEmpty()) {
                LogMgr.d(TAG, "stream turn " + streamTurn + " cancelled (" + why + "), drop "
                        + sentenceQueue.size() + " queued sentence(s)");
            }
            streamTurn++;
            streamActive = false;
            streamEnded = false;
            sentenceSpeaking = false;
            sentenceQueue.clear();
        }
    }

    /** 去掉 markdown 符号等不该念的东西; 没有可念内容返回空串。 */
    static String cleanForTts(String s) {
        if (s == null) return "";
        String t = s.replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1");   // [文字](链接) -> 文字
        t = t.replaceAll("(?m)^\\s*[-+*]\\s+", "");                       // 列表符号
        t = t.replaceAll("[*#`_~>|\\[\\]]", "");                          // * # ` _ ~ > | [ ]
        t = t.replaceAll("^[”’」』）)】\\s，,。、]+", "");                      // 切句后残留在句首的收尾符号
        t = t.replaceAll("\\s+", " ").trim();
        for (int i = 0; i < t.length(); i++) {
            if (Character.isLetterOrDigit(t.charAt(i))) return t;
        }
        return "";
    }

    /** Gadget 线程回调: 来了一句。 */
    private void offerSentence(int turn, String raw) {
        String text = cleanForTts(raw);
        if (text.isEmpty()) {
            LogMgr.d(TAG, "stream: skip empty sentence");
            return;
        }
        synchronized (streamLock) {
            if (turn != streamTurn || !streamActive) {
                LogMgr.d(TAG, "stream: drop stale sentence of turn " + turn + " (now " + streamTurn + ")");
                return;
            }
            sentencesQueued++;
            if (sentencesQueued == 1) {
                LogMgr.d(TAG, "stream: first sentence ready in "
                        + (System.currentTimeMillis() - streamStartedAt) + "ms (len=" + text.length() + ")");
            }
            LogMgr.d(TAG, "stream: queued #" + sentencesQueued + " len=" + text.length());
            sentenceQueue.add(text);
            if (streamText.length() > 0) streamText.append(' ');
            streamText.append(text);
        }
        if (advanceStream(turn, "sentence arrived")) runStreamFinalOnMain(turn);
    }

    /**
     * Gadget 流结束(chatStream 返回)。返回: -1 本轮已作废(打断/新一轮), 0 没有任何句子(需回落),
     * >0 句子数(播报由队列接管)。
     */
    private int finishStream(int turn) {
        int n;
        synchronized (streamLock) {
            if (turn != streamTurn) return -1;
            streamEnded = true;
            n = sentencesQueued;
            LogMgr.d(TAG, "stream: end, " + n + " sentence(s), "
                    + (System.currentTimeMillis() - streamStartedAt) + "ms since question");
            if (n == 0) {
                streamActive = false;
                return 0;
            }
        }
        if (advanceStream(turn, "stream end")) runStreamFinalOnMain(turn);
        return n;
    }

    /**
     * 推进队列: 能播就播下一句; 下一句没到就等; 流已结束且队列空且没在播 -> 返回 true,
     * 调用方负责走 handleTtsPlayingEnd()(多轮 ASR / 唤醒), 这是本轮唯一一次。
     */
    private boolean advanceStream(final int turn, String why) {
        final String next;
        final int idx;
        final int seq;
        final boolean first;
        synchronized (streamLock) {
            if (turn != streamTurn || !streamActive) return false;
            if (sentenceSpeaking) return false;
            if (isFillerPlaying()) return false;          // 等「嗯，我想想」说完
            next = sentenceQueue.poll();
            if (next == null) {
                if (!streamEnded) {
                    LogMgr.d(TAG, "stream: next sentence not here yet (" + why + "), wait, no ASR");
                    return false;
                }
                streamActive = false;                     // 本轮结束, 之后的 TTS 结束事件按普通流程
                LogMgr.d(TAG, "stream: all " + sentencesSpoken + " sentence(s) spoken in "
                        + (System.currentTimeMillis() - streamStartedAt) + "ms -> multi-turn/wakeup");
                return true;
            }
            sentenceSpeaking = true;
            idx = ++sentencesSpoken;
            seq = ++speakSeq;
            first = (idx == 1);
        }
        // 统一切到主线程调 playTTS: 不在 TTS 结束回调里同步重入引擎
        timeoutHandler.post(new Runnable() {
            @Override
            public void run() {
                synchronized (streamLock) {
                    if (turn != streamTurn || !streamActive) return;   // 排队期间被打断/新一轮
                }
                ANTHandlerContext c = PhicommChatHandler.this.ctx;
                if (c == null) return;
                if (first) {
                    c.stopWakeup();
                    c.stopASR();
                    pendingTease = !isIdiomGameMode;
                    LogMgr.d(TAG, "stream: speak first sentence "
                            + (System.currentTimeMillis() - streamStartedAt) + "ms after question");
                }
                synchronized (streamLock) {
                    currentResponse = streamText.toString();
                }
                LogMgr.d(TAG, "stream: speak #" + idx + " len=" + next.length());
                writeLog("=== 流式 TTS #" + idx + " === " + next);
                c.playTTS(next);
                timeoutHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        onSentenceWatchdog(turn, seq, idx);
                    }
                }, SENTENCE_WATCHDOG_BASE_MS + SENTENCE_WATCHDOG_PER_CHAR_MS * next.length());
            }
        });
        return false;
    }

    /** 某句的 TTS 结束事件迟迟不来: 当它播完了, 继续(防止永远卡在不进 ASR 的状态)。 */
    private void onSentenceWatchdog(int turn, int seq, int idx) {
        synchronized (streamLock) {
            if (turn != streamTurn || !streamActive || !sentenceSpeaking || seq != speakSeq) return;
            LogMgr.e(TAG, "stream: TTS end of #" + idx + " not received, advance anyway");
            sentenceSpeaking = false;
        }
        if (advanceStream(turn, "watchdog")) runStreamFinalOnMain(turn);
    }

    /** 不在 TTS 结束回调里时(流结束/看门狗), 到主线程走一次结束流程。 */
    private void runStreamFinalOnMain(final int turn) {
        timeoutHandler.post(new Runnable() {
            @Override
            public void run() {
                synchronized (streamLock) {
                    if (turn != streamTurn) return;   // 期间已被新一轮/打断作废
                }
                handleTtsPlayingEnd();
            }
        });
    }

    /**
     * TTS 结束事件分流: 不在流式轮次中返回 null(走原流程); 流式中返回 true(已处理,
     * 中间句子绝不触发 ASR), 若这是最后一句则在这里走 handleTtsPlayingEnd。
     */
    private Boolean onStreamTtsEnd() {
        int turn;
        synchronized (streamLock) {
            if (!streamActive) return null;
            turn = streamTurn;
            if (!sentenceSpeaking) {
                LogMgr.d(TAG, "stream: TTS end with no sentence playing, ignored (no ASR)");
                return Boolean.TRUE;
            }
            sentenceSpeaking = false;
            LogMgr.d(TAG, "stream: #" + sentencesSpoken + " TTS end");
        }
        if (advanceStream(turn, "tts end")) {
            return handleTtsPlayingEnd();
        }
        return Boolean.TRUE;
    }

    @Override
    public boolean acceptInboundEvent0(NLU evt) {
        // 只拦截 chat 意图
        if (evt == null) {
            LogMgr.d(TAG, "acceptInboundEvent0: evt is null, return false");
            return false;
        }
        
        String service = evt.getService();
        String text = evt.getText();
        LogMgr.d(TAG, "========================================");
        LogMgr.d(TAG, "acceptInboundEvent0 被调用!");
        LogMgr.d(TAG, "service=" + service);
        LogMgr.d(TAG, "text=" + text);
        LogMgr.d(TAG, "isIdiomGameMode=" + isIdiomGameMode);
        LogMgr.d(TAG, "========================================");
        
        // 注意:这里只处理 chat 意图
        // 如果是 music 或其他 service,需要其他 Handler 处理
        if (!"cn.yunzhisheng.chat".equals(service)) {
            LogMgr.d(TAG, "service 不是 chat, 放行");
            return false;
        }

        // ⭐ v86: 用户新交互到达 → 未播的调侃立即作废(调侃绝不跟用户抢话)
        PostDialogueTeaser.get().cancelPending();

        // ⭐ v86: 调侃 TTS 播放防护窗内收到的 chat 事件,是麦克风把自己的调侃收进去的
        //    回声 —— 消费掉但在 eventReceived 里直接吞掉, 绝不调大模型"自己回答自己"。
        if (PostDialogueTeaser.isEchoGuardActive()) {
            LogMgr.d(TAG, "[TEASE] 调侃播放窗口内收到 chat 事件(疑似自听回声), 吞掉: " + text);
            return true;
        }

        // 快速判断:是否应该放行给其他 Handler?
        if (text != null && !text.isEmpty()) {
            // 成语接龙模式下,不拦截任何输入(全部消费) ✅ 最高优先级
            if (isIdiomGameMode) {
                LogMgr.d(TAG, "成语接龙模式,拦截所有输入: " + text);
                return true;
            }
            
            // 检查是否是成语接龙启动指令(最高优先级) ✅
            if (text.contains("成语接龙") || text.contains("开始成语") || text.contains("成语游戏")) {
                LogMgr.d(TAG, "检测到成语接龙启动指令,拦截: " + text);
                return true;
            }
            
            // ⚠️ 重要: 先检查音乐/天气关键词,即使在多轮模式下也要放行
            // 包含音乐关键词 → 放行给 PhicommMusicSearchHandler(不保存记忆)
            if (containsAny(text, MUSIC_KEYWORDS)) {
                LogMgr.d(TAG, "包含音乐关键词,放行: " + text);
                return false;
            }
            
            // 包含天气关键词 → 放行给 PhicommWeatherHandler(不保存记忆)
            if (containsAny(text, WEATHER_KEYWORDS)) {
                LogMgr.d(TAG, "包含天气关键词,放行: " + text);
                return false;
            }
            
            // 多轮对话模式下,拦截所有输入(仅大模型场景)
            // 注意: 这个判断必须在音乐/天气之后,确保音乐/天气能正确放行
            if (isMultiTurnMode) {
                LogMgr.d(TAG, "多轮对话模式,拦截所有输入: " + text);
                return true;
            }
        }
        
        // 其他情况 → 消费,走大模型
        LogMgr.d(TAG, "拦截 chat 意图,走大模型: " + text);
        return true;
    }
    
    @Override
    public void eventReceived(final NLU evt, final ANTHandlerContext ctx) throws Exception {
        super.eventReceived(evt, ctx);

        // ⭐ v86: 调侃播放窗口内的 chat 事件 = 机器自己调侃的回声, 吞掉不处理,
        //    否则就会"听到自己的调侃,又把它回答一遍"
        if (PostDialogueTeaser.isEchoGuardActive()) {
            LogMgr.d(TAG, "[TEASE] 吞掉调侃回声, 不调大模型: " + evt.getText());
            reset();
            return;
        }

        // 新一轮提问: 作废上一轮还没播完/还在路上的流式句子
        cancelStream("new question");

        final String userInput = evt.getText();
        this.lastUserInput = userInput;
        LogMgr.d(TAG, "chat intent: " + userInput + ", isIdiomGameMode=" + isIdiomGameMode);
        
        // 取消超时定时器(用户说话了)
        if ((isIdiomGameMode || isMultiTurnMode) && timeoutRunnable != null) {
            timeoutHandler.removeCallbacks(timeoutRunnable);
        }
        
        // 异步调用大模型
        ThreadUtils.execute(new Runnable() {
            @Override
            public void run() {
                isProcessingRequest = true;  // ⭐ 标记任务开始
                try {
                    String response = null;
                    boolean streamHandled = false;   // 流式已接管播报(或本轮已作废), 不再走整段 playTTS
                    // v87: 豆包链可能随回复带回这条回答的语音(mp3), 有就用豆包自己的声音播
                    byte[] llmAudio = null;
                    String llmAudioFormat = null;
                    
                    // 检查是否是成语接龙启动指令
                    if (!isIdiomGameMode && (userInput.contains("成语接龙") || 
                                            userInput.contains("开始成语") || 
                                            userInput.contains("成语游戏"))) {
                        // 启动成语接龙游戏
                        isIdiomGameMode = true;
                        idiomGameTurn = 0;
                        lastUserIdiom = null;
                        
                        LogMgr.d(TAG, "成语接龙游戏启动");
                        
                        // 第一次:AI 先出词
                        String prompt = "本次开始成语接龙游戏,你先出一个成语。" +
                                       "要求:\n" +
                                       "1. 第一个成语难度别太高,用常见的成语\n" +
                                       "2. 只回复成语本身,不要解释,不要其他内容\n" +
                                       "3. 必须是四字成语\n" +
                                       "4. 只回复一个成语,不要回复多个";
                        
                        LogMgr.d(TAG, "=== 调用大模型(成语接龙启动) ===");
                        LogMgr.d(TAG, "Prompt: " + prompt);
                        
                        response = openAIClient.chat(prompt, null);
                        
                        LogMgr.d(TAG, "大模型返回: " + response);
                        
                        // 如果返回了多个成语,截取第一个
                        if (response != null && !response.isEmpty()) {
                            response = extractFirstIdiom(response);
                            LogMgr.d(TAG, "截取后第一个成语: " + response);
                        }
                        
                        if (response == null || response.isEmpty()) {
                            response = "一马当先";  // 默认词
                        }
                        
                        response = "好的,我们开始成语接龙!我先开始:" + response;
                        
                    } else if (isIdiomGameMode) {
                        // 检查退出指令
                        if (userInput.contains("退出") || userInput.contains("结束") || 
                            userInput.contains("停止") || userInput.contains("不玩了")) {
                            LogMgr.d(TAG, "用户请求退出成语接龙");
                            isIdiomGameMode = false;
                            idiomGameTurn = 0;
                            response = "好的,成语接龙游戏结束!你一共完成了 " + idiomGameTurn + " 轮对话,非常棒!";
                            
                            // 播报后退出游戏模式
                            ctx.stopWakeup();
                            ctx.stopASR();
                            currentResponse = response;
                            ctx.playTTS(response);
                            return;
                        }
                        
                        // 游戏中:用户接词,AI 继续
                        idiomGameTurn++;
                        lastUserIdiom = userInput;
                        
                        LogMgr.d(TAG, "成语接龙第 " + idiomGameTurn + " 轮,用户说: " + userInput);
                        
                        // 构建提示词:告诉 AI 用户说了什么,让 AI 接词
                        String prompt = "成语接龙游戏中。\n" +
                                       "用户说了:「" + userInput + "」\n" +
                                       "请你根据成语接龙规则(最后一个字作为下一个成语的第一个字)接一个成语。\n" +
                                       "要求:\n" +
                                       "1. 只回复成语本身,不要解释,不要其他内容\n" +
                                       "2. 必须是四字成语\n" +
                                       "3. 不能重复使用已经说过的成语\n" +
                                       "4. 只回复一个成语,不要回复多个";
                        
                        LogMgr.d(TAG, "=== 调用大模型(成语接龙第 " + idiomGameTurn + " 轮) ===");
                        LogMgr.d(TAG, "用户输入: " + userInput);
                        LogMgr.d(TAG, "Prompt: " + prompt);
                        
                        response = openAIClient.chat(prompt, null);
                        
                        LogMgr.d(TAG, "大模型返回: " + response);
                        
                        // 如果返回了多个成语,截取第一个
                        if (response != null && !response.isEmpty()) {
                            response = extractFirstIdiom(response);
                            LogMgr.d(TAG, "截取后成语: " + response);
                        }
                        
                        if (response == null || response.isEmpty()) {
                            response = "我想想...这个有点难,你赢了!";
                            isIdiomGameMode = false;
                        }
                        
                    } else {
                        // 普通对话模式 - 只有这里才会保存记忆
                        LogMgr.d(TAG, "=== 调用大模型(普通对话) ===");
                        LogMgr.d(TAG, "用户输入: " + userInput);
                        
                        // 检查是否是多轮对话的第一轮
                        if (!isMultiTurnMode) {
                            // 第一次大模型对话,初始化历史
                            isMultiTurnMode = true;
                            conversationHistory = new ConversationHistory();
                            LogMgr.d(TAG, "多轮对话模式启动(仅大模型场景)");
                        }
                        
                        // 检查清空记忆指令 - 清空当前 active persona 的历史
                        if (userInput.contains("清空记忆") || userInput.contains("清空对话")) {
                            String pid = PersonaManager.getCurrentPersonaId();
                            LogMgr.d(TAG, "用户请求清空 persona[" + pid + "] 对话记忆");
                            PersonaConversationManager.clear(pid);

                            // 退出多轮对话模式
                            isMultiTurnMode = false;
                            conversationHistory = null;
                            LogMgr.d(TAG, "已退出多轮对话模式");
                            
                            // 不调用大模型,直接返回
                            response = "成功清理";
                            
                            // 停止唤醒和 ASR
                            ctx.stopWakeup();
                            ctx.stopASR();
                            
                            // TTS 播报 - 使用原厂 TTS
                            currentResponse = response;
                            ctx.playTTS(response);
                            
                            LogMgr.d(TAG, "=== 记忆清空完成,退出多轮模式 ===");
                            return;
                        }
                        
                        // 检查 OpenAIClient 是否已初始化
                        if (openAIClient == null) {
                            LogMgr.e(TAG, "openAIClient is null! 尝试重新初始化...");
                            try {
                                Context context = ctx.androidContext();
                                AIConfig config = AIConfig.load(context);
                                openAIClient = new OpenAIClient(config);
                                LogMgr.d(TAG, "重新初始化成功, model=" + config.getModel() + ", url=" + config.getBaseUrl());
                            } catch (Exception ex) {
                                LogMgr.e(TAG, "重新初始化失败: " + ex);
                            }
                        }
                        
                        // ⭐ v90: 大模型请求期间就把"机器要说话"占住。
                        //    豆包链一轮要 10 秒左右, 期间普通模式的调侃延迟(2.5s)早就到了,
                        //    而回答的语音还没落盘开播 → 调侃和回答两个声音叠在一起播。
                        //    占住状态后调侃会自觉闭嘴, 播完由播放器/结束回调复位。
                        PlaybackStateMonitor.setTTSPlaying(true);
                        PostDialogueTeaser.get().cancelPending();

                        if (openAIClient != null) {
                            // ⭐ 按当前 active persona 取独立对话历史 + 对应 systemPrompt
                            String activePersonaId = PersonaManager.getCurrentPersonaId();
                            PersonaConfig activeCfg = PersonaManager.getCurrentPersonaConfig();
                            String activeSysPrompt = activeCfg != null ? activeCfg.getSystemPrompt() : null;
                            ConversationHistory personaHistory = PersonaConversationManager.getHistory(activePersonaId);
                            String historyText = personaHistory.getFormattedHistory();

                            LogMgr.d(TAG, "Active persona=" + activePersonaId
                                    + " hist_turns=" + personaHistory.size());

                            // 调用带 persona systemPrompt + 该 persona 历史
                            if (useGadget && gadgetClient != null) {
                                // Gadget 直连 Brio: 只回文字, 按句流式交给原厂 TTS 播放
                                final int turn = beginStream();
                                playFiller(ctx);      // 先说「嗯，我想想」, 让小朋友知道听到了
                                // 兜底: 「嗯，我想想」的结束事件丢了也要在 6 秒后开始播句子
                                timeoutHandler.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (advanceStream(turn, "filler backstop")) runStreamFinalOnMain(turn);
                                    }
                                }, FILLER_MAX_MS + 50);
                                try {
                                    response = gadgetClient.chatStream(
                                            activeSysPrompt, historyText, userInput,
                                            new GadgetClient.SentenceListener() {
                                                @Override
                                                public void onSentence(String sentence) {
                                                    offerSentence(turn, sentence);
                                                }
                                            });
                                } catch (Exception ge) {
                                    LogMgr.e(TAG, "Gadget 直连失败: " + ge);
                                }
                                int streamed = finishStream(turn);
                                if (streamed != 0) {
                                    // >0: 队列接管播报; -1: 本轮已被打断/新一轮作废, 什么都不播
                                    streamHandled = true;
                                    llmAudio = null;
                                    llmAudioFormat = null;
                                    if (streamed < 0) LogMgr.d(TAG, "stream turn cancelled, skip playback");
                                } else if (response == null || response.isEmpty()) {
                                    LogMgr.e(TAG, "Gadget 无回复, 回落 OpenAI");
                                    // 直连失败/超时: 回落走 OpenAI 池(v91 三级降级)
                                    OpenAIClient.Reply r = openAIClient.chatWithHistoryEx(
                                            userInput, historyText, activeSysPrompt, null);
                                    response = (r == null) ? null : r.text;
                                    llmAudio = (r == null) ? null : r.audio;
                                    llmAudioFormat = (r == null) ? null : r.audioFormat;
                                } else {
                                    llmAudio = null;
                                    llmAudioFormat = null;
                                }
                            } else {
                                OpenAIClient.Reply r = openAIClient.chatWithHistoryEx(
                                        userInput, historyText, activeSysPrompt, null);
                                response = (r == null) ? null : r.text;
                                llmAudio = (r == null) ? null : r.audio;
                                llmAudioFormat = (r == null) ? null : r.audioFormat;
                            }

                            LogMgr.d(TAG, "大模型返回: " + response);
                            LogMgr.d(TAG, "随回复带回的语音: "
                                    + (llmAudio == null ? "无(用原厂 TTS)" : llmAudio.length + " 字节"));

                            // 保存到该 persona 的历史
                            if (response != null && !response.isEmpty()) {
                                personaHistory.addTurn(userInput, response);
                                LogMgr.d(TAG, "保存到 persona[" + activePersonaId + "] 历史,轮数: "
                                        + personaHistory.size());
                            }
                        } else {
                            LogMgr.e(TAG, "openAIClient 仍为 null, 无法调用大模型");
                            response = null;
                        }
                        
                        LogMgr.d(TAG, "大模型返回: " + response);
                    }
                    
                    if (streamHandled) {
                        return;   // 句子队列负责播报和最后一句结束后的多轮 ASR
                    }
                    // 「嗯，我想想」还没说完就等它说完再开口(不互相打断)
                    waitFillerDone();
                    if (response != null && !response.isEmpty()) {
                        // 停止唤醒和 ASR
                        ctx.stopWakeup();
                        ctx.stopASR();
                        
                        currentResponse = response;
                        // ⭐ v84: 本轮回复来自大模型 → 标记允许事后调侃(playingEnd 时消费)
                        if (!isIdiomGameMode) {
                            pendingTease = true;
                        }

                        // ⭐ v87: 豆包链随回复带回了这条回答的语音 → 用豆包自己的声音播。
                        //    注意 MediaPlayer 播放不会触发引擎的 onTTSEventPlayingEnd,
                        //    所以播完必须手动走 handleTtsPlayingEnd(), 否则多轮监听
                        //    和事后调侃都会断在这一步。
                        if (llmAudio != null && llmAudio.length > 0) {
                            writeLog("=== 播放豆包语音 === " + response);
                            LogMgr.i(TAG, "豆包语音播放回答: " + llmAudio.length + " 字节");
                            final Context appCtx = ctx.androidContext();
                            DoubaoVoicePlayer.play(appCtx, llmAudio, llmAudioFormat,
                                    new DoubaoVoicePlayer.PlayCallback() {
                                        @Override
                                        public void onComplete() {
                                            LogMgr.d(TAG, "豆包语音播完 → 手动走 TTS 结束流程");
                                            handleTtsPlayingEnd();
                                        }

                                        @Override
                                        public void onError(String error) {
                                            LogMgr.e(TAG, "豆包语音播放失败, 退回原厂 TTS: " + error);
                                            if (currentResponse != null) {
                                                ctx.playTTS(currentResponse);
                                            }
                                        }
                                    });
                        } else {
                            writeLog("=== 调用原厂 TTS === " + response);
                            ctx.playTTS(response);
                        }
                    } else {
                        // API 调用失败 - 统一回复
                        LogMgr.e(TAG, "=== API 返回 null, openAIClient=" + openAIClient + " ===");
                        // v90: 没有语音要播了, 放行调侃(否则状态挂死 30s)
                        PlaybackStateMonitor.setTTSPlaying(false);
                        ctx.playTTS("我暂时连不上网络大脑，等一下再问我吧");
                        if (isIdiomGameMode) {
                            isIdiomGameMode = false;
                        }
                    }
                } catch (Exception e) {
                    LogMgr.e(TAG, "chat failed: " + e);
                    e.printStackTrace();
                    waitFillerDone();
                    PlaybackStateMonitor.setTTSPlaying(false);
                    ctx.playTTS("我暂时连不上网络大脑，等一下再问我吧");
                    if (isIdiomGameMode) {
                        isIdiomGameMode = false;
                    }
                } finally {
                    isProcessingRequest = false;  // ⭐ 标记任务结束
                }
            }
        });
    }
    
    @Override
    public boolean onTTSEventPlayingEnd(ANTHandlerContext ctx) {
        LogMgr.d(TAG, "TTS playing end (原厂), isIdiomGameMode=" + isIdiomGameMode + 
                 ", isMultiTurnMode=" + isMultiTurnMode);
        
        // 「嗯，我想想」的结束事件: 吞掉, 不进多轮 ASR / 调侃流程
        if (consumeFillerEnd()) {
            int turn;
            synchronized (streamLock) { turn = streamTurn; }
            if (advanceStream(turn, "filler end")) return handleTtsPlayingEnd();
            return true;
        }
        // 流式播报中: 中间句子的结束事件只触发下一句, 绝不进 ASR; 最后一句才走结束流程
        Boolean streamResult = onStreamTtsEnd();
        if (streamResult != null) return streamResult.booleanValue();
        // 注意: 使用讯飞 TTS 时,不会触发这个回调
        // 只有使用 ctx.playTTS() 时才会触发
        return handleTtsPlayingEnd();
    }
    
    /**
     * 处理 TTS 播放完成后的逻辑(讯飞 TTS 和原厂 TTS 共用)
     */
    private boolean handleTtsPlayingEnd() {
        LogMgr.d(TAG, "handleTtsPlayingEnd, isIdiomGameMode=" + isIdiomGameMode + 
                 ", isMultiTurnMode=" + isMultiTurnMode);
        
        // ⭐ v85 修复调侃失效: InitializeHandler 收不到 TTS 事件, setTTSPlaying(false) 是死代码,
        //    ttsPlaying 要等 30s 兜底超时才复位 → 调侃在 TTS 后 1.2s 检查时永远看到"音频在播"而 skip。
        //    ChatHandler 的 TTS 结束回调每轮都可靠触发, 在这里复位最稳。
        PlaybackStateMonitor.setTTSPlaying(false);

        // ⭐ v86: 先把调侃素材抓下来(reset() 会清掉 currentResponse/pendingTease)。
        //    调侃不再紧跟 TTS 播完就播 —— 那时引擎马上要进 ASR"监听下一句",
        //    调侃插在中间会被麦克风收走变成自问自答。
        final boolean teaseAfterAnswer = pendingTease && !isIdiomGameMode
                && currentResponse != null && lastUserInput != null;
        final String teaseUser = lastUserInput;

        // ⭐ v83/v84: 只调侃【大模型成功回复】的对话轮(pendingTease 在 playTTS 前置位,
        //    此处一次性消费)。传统指令(音乐/音量/关机等)不走大模型, 永远不会置位。
        pendingTease = false;
        
        if (isIdiomGameMode) {
            // 成语接龙模式:直接进入 ASR,继续聆听
            LogMgr.d(TAG, "成语接龙模式:进入 ASR 继续聆听");
            reset();  // 重置会话状态
            
            // 启动 15 秒静音超时定时器
            timeoutRunnable = new Runnable() {
                @Override
                public void run() {
                    if (isIdiomGameMode) {
                        LogMgr.d(TAG, "成语接龙超时,自动退出");
                        isIdiomGameMode = false;
                        idiomGameTurn = 0;
                        lastUserIdiom = null;
                        ctx.playTTS("超时未检测到语音,成语接龙游戏结束");
                        ctx.enterWakeup(false);
                    }
                }
            };
            timeoutHandler.postDelayed(timeoutRunnable, MAX_IDLE_TIMEOUT);
            
            // 延迟 500ms 再进入 ASR,确保 TTS 完全停止
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    ctx.enterASR();
                }
            }, 500);
            
            return true;
        } else if (isMultiTurnMode) {
            // 多轮对话模式:直接进入 ASR,继续聆听
            LogMgr.d(TAG, "多轮对话模式:进入 ASR 继续聆听");
            reset();

            // 启动 15 秒静音超时: 无人说话则退出多轮, 让出给唤醒/插嘴模式
            timeoutRunnable = new Runnable() {
                @Override
                public void run() {
                    if (isMultiTurnMode) {
                        LogMgr.d(TAG, "多轮对话超时 " + MAX_IDLE_TIMEOUT + "ms,退出多轮模式");
                        isMultiTurnMode = false;
                        conversationHistory = null;
                        ctx.enterWakeup(false);
                        // 重置交互时间, 让插嘴引擎在空闲阈值后接管
                        PersonaManager.recordInteraction();

                        // ⭐ v86: 引擎此刻已退回唤醒态(不再监听普通语音), 用户这轮对话
                        //    彻底结束 —— 这时调侃才开口, 不会插在"监听下一句"的中间,
                        //    也不会被 ASR 收走变成自问自答。中途只要用户说话, 本定时器
                        //    就会被 eventReceived 取消, 调侃顺延到下一轮结束再算。
                        if (teaseAfterAnswer) {
                            // v87: 调侃只看【用户那句话】, 不看豆包的回答
                            PostDialogueTeaser.get().maybeTease(ctx.androidContext(), teaseUser, 800);
                        }
                    }
                }
            };
            timeoutHandler.postDelayed(timeoutRunnable, MAX_IDLE_TIMEOUT);

            // 延迟 500ms 进入 ASR
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    ctx.enterASR();
                }
            }, 500);

            return true;
        } else {
            // 普通模式:正常退出,回到唤醒状态
            LogMgr.d(TAG, "普通模式:退出到唤醒状态");
            exit();

            // ⭐ v86: 唤醒态只听唤醒词, 延迟 2.5s 再调侃, 让引擎先安静回到待命
            if (teaseAfterAnswer) {
                final Context appCtx = (ctx != null) ? ctx.androidContext() : null;
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        PostDialogueTeaser.get().maybeTease(appCtx, teaseUser, 0);
                    }
                }, 2500);
            }
            return true;
        }
    }
    
    private void exit() {
        cancelStream("exit");
        try {
            this.ctx.cancelTTS();
            // 注意:不要调用 cancelEngine(),它会取消整个 ANT 引擎导致无法唤醒
            // 只需调用 reset() 清空会话状态即可
        } catch (Throwable ignored) {}
        
        // 取消超时定时器
        timeoutHandler.removeCallbacks(timeoutRunnable);
        
        // ⭐ 等待异步大模型任务完成(最多等待2秒)
        int waitCount = 0;
        while (isProcessingRequest && waitCount < 20) {
            try {
                Thread.sleep(100);
                waitCount++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        
        // 重置成语接龙状态
        isIdiomGameMode = false;
        idiomGameTurn = 0;
        lastUserIdiom = null;
        
        // 重置多轮对话状态
        isMultiTurnMode = false;
        conversationHistory = null;
        
        reset();
    }
    
    @Override
    public void doInterrupt(ANTHandlerContext ctx, String interruptType) {
        // 打断: 不管 eventReceived, 先作废流式轮次(清空队列, 丢弃之后到达的旧句子)
        cancelStream("interrupt " + interruptType);
        // 打断处理
        if (eventReceived) {
            LogMgr.d(TAG, "doInterrupt, isIdiomGameMode=" + isIdiomGameMode +
                     ", isMultiTurnMode=" + isMultiTurnMode);
            // ⭐ v86: 用户打断 = 新交互开始, 未播的调侃立即作废
            PostDialogueTeaser.get().cancelPending();
            synchronized (fillerLock) {   // 打断时「嗯，我想想」作废, 别让回答线程干等
                fillerPlaying = false;
                fillerLock.notifyAll();
            }
            ctx.cancelTTS();
            
            // 取消超时定时器
            timeoutHandler.removeCallbacks(timeoutRunnable);
            
            // ⭐ 等待异步大模型任务完成(最多等待2秒)
            int waitCount = 0;
            while (isProcessingRequest && waitCount < 20) {
                try {
                    Thread.sleep(100);
                    waitCount++;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            
            // 中断时退出成语接龙模式
            isIdiomGameMode = false;
            idiomGameTurn = 0;
            lastUserIdiom = null;
            
            // 中断时退出多轮对话模式
            isMultiTurnMode = false;
            conversationHistory = null;
            
            reset();
        }
    }
    
    @Override
    public void reset() {
        currentResponse = null;
        pendingTease = false;  // ⭐ v84: 会话重置时清掉调侃标记, 杜绝过期标记误触发
        // 注意:不要在这里重置 isIdiomGameMode,因为多轮对话需要保持状态
        super.reset();
    }
    
    /**
     * 判断文本是否包含任意关键词
     */
    private boolean containsAny(String text, String[] keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 从 AI 返回的文本中提取第一个成语
     * 如果 AI 返回了多个成语(如"一马当先，先见之明，明察秋毫")，只取第一个
     * 
     * @param text AI 返回的文本
     * @return 第一个四字成语
     */
    private String extractFirstIdiom(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        
        // 去除前后空白
        text = text.trim();
        
        // 如果文本长度 <= 4,直接返回
        if (text.length() <= 4) {
            return text;
        }
        
        // 查找第一个四字成语
        // 策略1: 如果包含中文逗号、顿号,截取第一个
        String[] separators = {"，", ",", "、", "。", ".", "！", "!", "？", "?"};
        int firstSeparatorIndex = text.length();
        
        for (String sep : separators) {
            int index = text.indexOf(sep);
            if (index != -1 && index < firstSeparatorIndex) {
                firstSeparatorIndex = index;
            }
        }
        
        // 如果找到了分隔符,截取前面的内容
        if (firstSeparatorIndex < text.length()) {
            String firstPart = text.substring(0, firstSeparatorIndex).trim();
            
            // 如果第一部分 <= 8 字(可能包含解释),直接返回
            if (firstPart.length() <= 8) {
                // 尝试提取其中的四字成语
                return extractFourCharIdiom(firstPart);
            }
        }
        
        // 如果没有分隔符,尝试直接提取四字成语
        return extractFourCharIdiom(text);
    }
    
    /**
     * 从文本中提取四字成语
     * 策略: 查找连续 4 个中文字符
     */
    private String extractFourCharIdiom(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        
        // 去除标点符号和空白
        StringBuilder chineseOnly = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // 中文字符范围: \u4e00-\u9fa5
            if (c >= '\u4e00' && c <= '\u9fa5') {
                chineseOnly.append(c);
            }
        }
        
        String cleaned = chineseOnly.toString();
        
        // 如果清理后 <= 4 字,直接返回
        if (cleaned.length() <= 4) {
            return cleaned.isEmpty() ? text : cleaned;
        }
        
        // 返回前 4 个字(假设第一个成语在最前面)
        return cleaned.substring(0, 4);
    }
}
