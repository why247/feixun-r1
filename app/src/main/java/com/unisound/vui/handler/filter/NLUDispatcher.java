package com.unisound.vui.handler.filter;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.StrictMode;
import android.text.TextUtils;
import com.google.gson.reflect.TypeToken;
import com.phicomm.speaker.device.R;
import com.unisound.vui.common.config.ANTConfigPreference;
import com.unisound.vui.common.network.NetUtil;
import com.unisound.vui.engine.ANTHandlerContext;
import com.unisound.vui.handler.ANTEventDispatcher;
import com.unisound.vui.util.*;

import java.util.List;
import nluparser.MixtureProcessor;
import nluparser.NluProcessor;
import nluparser.scheme.Intent;
import nluparser.scheme.LocalASR;
import nluparser.scheme.Mixture;
import nluparser.scheme.NLU;
import com.phicomm.speaker.device.custom.asr.TencentAsr;
import com.phicomm.speaker.device.custom.event.PersonaActivationEvent;
import com.phicomm.speaker.device.custom.persona.PersonaConfig;
import com.phicomm.speaker.device.custom.persona.PersonaManager;

import com.unisound.vui.engine.ANTPipeline;
import nluparser.scheme.Result;
import nluparser.scheme.SName;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class NLUDispatcher extends ANTEventDispatcher {

    /* renamed from: a  reason: collision with root package name */
    private boolean f423a;
    private boolean b;
    private final MixtureProcessor c;

    // ⭐ v82: 识别卡死看门狗。
    // 实测(15:55): 唤醒成功→本地识别"打开蓝牙"成功→VAD 超时停录音→管线等待云端最终结果,
    // 而云 ASR/NLU 服务器已死 → 永远等不到 → 设备卡在"识别中"(蓝灯闪) = 用户说的"死机"。
    // 录音停止后 8 秒内没有最终结果就强制 cancelEngine 回到待唤醒状态。
    private static final long RECOG_STALL_TIMEOUT_MS = 8000;
    private final Handler watchdogHandler = new Handler(Looper.getMainLooper());
    private Runnable recogStallWatchdog;

    private void cancelRecogStallWatchdog() {
        if (recogStallWatchdog != null) {
            watchdogHandler.removeCallbacks(recogStallWatchdog);
            recogStallWatchdog = null;
        }
    }

    private void scheduleRecogStallWatchdog(final ANTHandlerContext ctx) {
        cancelRecogStallWatchdog();
        recogStallWatchdog = new Runnable() {
            @Override
            public void run() {
                LogMgr.e("NLUDispatcher", "[WATCHDOG] recognition stalled > "
                        + RECOG_STALL_TIMEOUT_MS + "ms (dead cloud ASR?), force cancelEngine to recover");
                try {
                    ctx.cancelEngine();
                } catch (Throwable t) {
                    LogMgr.e("NLUDispatcher", "[WATCHDOG] cancelEngine failed: " + t);
                }
                recogStallWatchdog = null;
            }
        };
        watchdogHandler.postDelayed(recogStallWatchdog, RECOG_STALL_TIMEOUT_MS);
    }
    private NluProcessor d;
    private Handler e = new Handler();
    private Runnable f;

    public NLUDispatcher(MixtureProcessor mixtureProcessor) {
        this.c = mixtureProcessor;
        this.d = new NluProcessor.Builder().registerTypeMapper(SName.ERROR_REPORT, new TypeToken<NLU<Intent.NullIntent, Result.NullResult>>() {
            /* class com.unisound.vui.handler.filter.NLUDispatcher.AnonymousClass1 */
        }.getType()).build();
    }

    private void a() {
        if (this.f != null) {
            LogMgr.d("NLUDispatcher", "stop asr or nlu result timeout task");
            this.e.removeCallbacks(this.f);
            this.f = null;
        }
    }

    private void a(ANTHandlerContext aNTHandlerContext, boolean z) {
        aNTHandlerContext.engine().attr(AttributeKey.valueOf(NLUDispatcher.class, "RECOGNITION_HANDLED")).set(Boolean.valueOf(z));
    }

    private boolean a(float f2) {
        return f2 > ANTConfigPreference.FUNCTION_WAKEUP_BENCHMARK;
    }

    private boolean a(Context context, String str) {
        return UserPerferenceUtil.getCmopetitionWord(context).contains(str);
    }

    private boolean a(ANTHandlerContext aNTHandlerContext) {
        if (b(aNTHandlerContext)) {
            this.f423a = true;
            NLU c2 = c("bluetooth_error");
            c2.setText(aNTHandlerContext.androidContext().getString(R.string.tts_music_change_no_supported));
            c(aNTHandlerContext, c2);
            return true;
        } else if (c(aNTHandlerContext) && !this.b) {
            return false;
        } else {
            this.f423a = true;
            NLU c3 = c("-90002");
            c3.setText("no network");
            c(aNTHandlerContext, c3);
            return true;
        }
    }

    private boolean a(ANTHandlerContext aNTHandlerContext, String str, Mixture<Intent, Result> mixture) {
        NLU<Intent, Result> nlu = mixture.getNluList().get(0);
        if (a(mixture, nlu)) {
            LogMgr.d("NLUDispatcher", "handleNetFilterService return filter service ...");
            return false;
        }
        this.f423a = true;
        nlu.setLocalNLU(false);
        nlu.setAsrResult(str);
        b(aNTHandlerContext, nlu);
        return true;
    }

    private boolean a(ANTHandlerContext aNTHandlerContext, String str, NLU nlu) {
        if (a(aNTHandlerContext, nlu)) {
            return false;
        }
        this.f423a = true;
        nlu.setLocalNLU(true);
        nlu.setAsrResult(str);
        c(aNTHandlerContext, nlu);
        return true;
    }

    private boolean a(ANTHandlerContext aNTHandlerContext, LocalASR localASR) {
        aNTHandlerContext.fireUserEventTriggered(localASR);
        return true;
    }

    private boolean a(ANTHandlerContext aNTHandlerContext, NLU nlu) {
        return a(nlu.getService()) && !this.b && !b(aNTHandlerContext);
    }

    private boolean a(String str) {
        return a.a(str);
    }

    private boolean a(LocalASR localASR, NLU nlu) {
        return nlu.getResponseCode() == 0 && localASR.getScore() > ANTConfigPreference.recognizerScore;
    }

    private boolean a(Mixture<Intent, Result> mixture) {
        return mixture.getNetASRList() != null && "full".equals(mixture.getNetASRList().get(0).getResultType());
    }

    private boolean a(Mixture<Intent, Result> mixture, NLU nlu) {
        return a(nlu.getService()) && !a(mixture);
    }

    private void b(ANTHandlerContext aNTHandlerContext, NLU nlu) {
        LogMgr.d("NLUDispatcher", "preHandleNetNlu nlu :" + nlu);
        if (TextUtils.isEmpty(nlu.getText())) {
            c(aNTHandlerContext, c("-63551"));
        } else {
            c(aNTHandlerContext, nlu);
        }
    }

    private boolean b(float f2) {
        return f2 > ANTConfigPreference.effectWakeupBenchmark;
    }

    private boolean b(Context context, String str) {
        return !UserPerferenceUtil.getMainWakeupWord(context).contains(str);
    }

    private boolean b(ANTHandlerContext aNTHandlerContext) {
        return ((Integer) aNTHandlerContext.engine().unsafe().getOption(1001)).intValue() == 2;
    }

    private boolean b(String str) {
        return a(str);
    }

    private boolean b(Mixture<Intent, Result> mixture) {
        List<NLU<Intent, Result>> nluList = mixture.getNluList();
        return (nluList == null || nluList.size() == 0) ? false : true;
    }

    /* access modifiers changed from: private */
    /* access modifiers changed from: public */
    private NLU c(String str) {
        NLU nlu = new NLU();
        nlu.setService(SName.ERROR_REPORT);
        nlu.setCode(str);
        return nlu;
    }

    /* access modifiers changed from: private */
    /* access modifiers changed from: public */
    private void c(ANTHandlerContext aNTHandlerContext, NLU nlu) {
        // v75: 任何分发(腾讯路由/云知声原结果/本地结果/超时错误)都结束本轮: 作废扣留, 关掉腾讯会话(不再发音频)
        txHoldGen = 0;
        TencentAsr.get().finishRound("dispatched");
        a();
        aNTHandlerContext.pipeline().fireASREvent(1102);
        a(aNTHandlerContext, true);
        aNTHandlerContext.cancelEngine();
        aNTHandlerContext.fireUserEventTriggered(nlu);
        this.b = false;
        this.f423a = false;
    }

    private boolean c(Context context, String str) {
        return UserPerferenceUtil.getMainWakeupWord(context).contains(str);
    }

    private boolean c(ANTHandlerContext aNTHandlerContext) {
        return NetUtil.isNetworkConnected(aNTHandlerContext.androidContext());
    }

    private void d(final ANTHandlerContext aNTHandlerContext) {
        if (this.f == null) {
            LogMgr.d("NLUDispatcher", "start asr or nlu result timeout task");
            this.f = new Runnable() {
                /* class com.unisound.vui.handler.filter.NLUDispatcher.AnonymousClass2 */

                @Override
                public void run() {
                    LogMgr.d("NLUDispatcher", "process asr or nlu result timeout, dispatch network error nlu ");
                    NLU c =    c("-90002");
                    c.setText("no network");
                       c(aNTHandlerContext, c);
                }
            };
            this.e.postDelayed(this.f, 15000);
        }
    }

    /* access modifiers changed from: protected */
    @Override // com.unisound.vui.handler.ANTEventDispatcher
    public boolean onASRError(ANTHandlerContext ctx, String error) {
        d(ctx);
        LogMgr.i("NLUDispatcher", "-onASRError-" + error);
        JSONObject parseToJSONObject = JsonTool.parseToJSONObject(error);
        String jsonValue = JsonTool.getJsonValue(parseToJSONObject, "errorCode");
        String jsonValue2 = JsonTool.getJsonValue(parseToJSONObject, "errorMsg");
        if (b(jsonValue)) {
            this.b = true;
            return false;
        }
        this.f423a = true;
        NLU c2 = c(jsonValue);
        c2.setText(jsonValue2);
        c(ctx, c2);
        return false;
    }

    /* access modifiers changed from: protected */
    @Override // com.unisound.vui.handler.ANTEventDispatcher
    public boolean onASREventCancel(ANTHandlerContext ctx) {
        a();
        cancelRecogStallWatchdog();
        txDropHold("engine cancel");
        TencentAsr.get().finishRound("engine cancel");
        return false;
    }

    /* access modifiers changed from: protected */
    @Override // com.unisound.vui.handler.ANTEventDispatcher
    public boolean onASREventRecordingStart(ANTHandlerContext ctx) {
        LogMgr.i("NLUDispatcher", "onASREventRecordingStart");
        a();
        cancelRecogStallWatchdog();
        a(ctx, false);
        txDropHold("new recording");
        // v75: 只在 ASR 状态(唤醒后/多轮)开腾讯识别; 唤醒监听的录音不开(省额度)
        try {
            if (ctx.engine().isASR()) {
                TencentAsr.get().beginSession(ctx.androidContext().getFilesDir(), ctx.engine());
            } else {
                TencentAsr.get().finishRound("not asr");
            }
        } catch (Throwable t) {
            LogMgr.e(TX, "begin session failed: " + t);
        }
        return super.onASREventRecordingStart(ctx);
    }

    /* access modifiers changed from: protected */
    @Override // com.unisound.vui.handler.ANTEventDispatcher
    public boolean onASREventRecordingStop(ANTHandlerContext ctx) {
        LogMgr.i("NLUDispatcher", "onASREventRecordingStop -> schedule recog-stall watchdog");
        // ⭐ v82: 录音停止 = 等最终识别结果, 云端死了就会卡死, 挂看门狗兜底
        scheduleRecogStallWatchdog(ctx);
        TencentAsr.get().endAudio("recording stop");
        return super.onASREventRecordingStop(ctx);
    }

    /* v75: 云知声 VAD 判定说完 → 腾讯这边立即发 end(比 recording stop 早 0.5~1.3s) */
    @Override // com.unisound.vui.handler.ANTEventDispatcher
    public boolean onASREventSpeechEnd(ANTHandlerContext ctx) {
        TencentAsr.get().endAudio("speech end");
        return super.onASREventSpeechEnd(ctx);
    }

    /* access modifiers changed from: protected */
    @Override // com.unisound.vui.handler.ANTEventDispatcher
    public boolean onASREventRecognitionEnd(ANTHandlerContext ctx) {
        LogMgr.i("NLUDispatcher", "onASREventRecognitionEnd -> cancel recog-stall watchdog");
        cancelRecogStallWatchdog();
        return super.onASREventRecognitionEnd(ctx);
    }

    /* access modifiers changed from: protected */
    @Override // com.unisound.vui.handler.ANTEventDispatcher
    public boolean onASREventEnd(ANTHandlerContext ctx) {
        cancelRecogStallWatchdog();
        return super.onASREventEnd(ctx);
    }

    /**
     * 从原厂 ASR 结果 JSON 里提取识别文本。
     * 兼容已知结构: local_asr / net_asr / net_nlu / asr_recongize(asr_recognize) / text
     */
    private String extractRecognitionText(String result) {
        if (result == null) {
            return null;
        }
        String trimmed = result.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            // USC 流式返回可能是多段拼接 "{seg1}{seg2}", 整体解析不了时取最后一段
            if (trimmed.contains("}{")) {
                trimmed = trimmed.substring(trimmed.lastIndexOf("}{") + 1);
            }
            JSONObject root = new JSONObject(trimmed);
            JSONArray arr = null;
            if (root.has("local_asr")) {
                arr = root.getJSONArray("local_asr");
            } else if (root.has("net_asr")) {
                arr = root.getJSONArray("net_asr");
            } else if (root.has("net_nlu")) {
                arr = root.getJSONArray("net_nlu");
            }
            if (arr != null && arr.length() > 0) {
                JSONObject first = arr.getJSONObject(0);
                String t = first.optString("recognition_result", "");
                if (t.isEmpty()) {
                    t = first.optString("text", "");
                }
                if (!t.isEmpty()) {
                    return t.trim();
                }
            }
            String plain = root.optString("asr_recongize", "");
            if (plain.isEmpty()) {
                plain = root.optString("asr_recognize", "");
            }
            if (plain.isEmpty()) {
                plain = root.optString("text", "");
            }
            return plain.trim();
        } catch (Exception e) {
            LogMgr.e("NLUDispatcher", "[EAVES] extractRecognitionText failed: " + e.getMessage());
            return null;
        }
    }

    /* access modifiers changed from: protected */
    @Override // com.unisound.vui.handler.ANTEventDispatcher
    public boolean onASRResultLocal(ANTHandlerContext ctx, String result) {
        LogMgr.d("NLUDispatcher", "onASRResultLocal:" + result);

        if (this.f423a) {
            LogMgr.e("NLUDispatcher", "result has handled, local nlu handle return");
            return true;
        }

        d(ctx);
        LocalASR localASR = this.c.from(result).getLocalASRList().get(0);
        NLU from = this.d.from(localASR.getRecognitionResult());
        if (!a(localASR, from)) {
            return a(ctx);
        }
        if (a(ctx.androidContext(), from.getText())) {
            return false;
        }
        return a(ctx, result, from);
    }

    /* access modifiers changed from: protected */
    @Override // com.unisound.vui.handler.ANTEventDispatcher
    public boolean onASRResultNet(ANTHandlerContext ctx, String result) {
        LogMgr.d("NLUDispatcher", "onASRResultNet:" + result);

        if (this.f423a) {
            LogMgr.e("NLUDispatcher", "result has handled, net nlu handle return");
            return true;
        }

        // v75: 本轮有腾讯识别会话 → 扣住云知声这条「会被分发」的结果, 等腾讯最终文字再决定
        if (txTryHold(ctx, result)) {
            return true;
        }
        return processNetResult(ctx, result);
    }

    /** 原 onASRResultNet 的处理逻辑(未改动) */
    private boolean processNetResult(ANTHandlerContext ctx, String result) {
        String str;
        d(ctx);
        Mixture<Intent, Result> from = this.c.from(result);
        if (from == null) {
            try {
                str = (String) ((JSONObject) JsonTool.parseToJSONObject(result).getJSONArray("net_nlu").get(0)).get("text");
            } catch (JSONException e2) {
                LogMgr.e("NLUDispatcher", "unsupported domain 解析用户说的话出错 : " + e2.getMessage());
                str = result;
            }
            LogMgr.w("NLUDispatcher", "unsupported domain " + result);
            this.f423a = true;
            NLU c2 = c("unsupportedDomain");
            c2.setText(str);
            b(ctx, c2);
            return true;
        } else if (b(from)) {
            return a(ctx, result, from);
        } else {
            // hook

//            try {
//                if (android.os.Build.VERSION.SDK_INT > 9) {
//                    StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
//                    StrictMode.setThreadPolicy(policy);
//                }
//
//                if(!com.unisound.b.s.c(ctx.androidContext())){
//                    return false;
//                }
//
//                String word = from.getNetASRList().get(0).getRecognitionResult();
//
//                LogMgr.d("PPP HOOK", "Recognition: " + word);
//
//
//                if("".equals(word) || word.contains("returnCode")){
//                    return false;
//                }
//
//
//
//                String newResult = PhicommUtils.byNewApi(word);
//
//                Mixture<Intent, Result> newForm = this.c.from(newResult);
//                if(b(newForm)) {
//                    return a(ctx, newResult, newForm);
//                }
//
//
//            }catch (Exception e){
//                e.printStackTrace();
//            }



            return false;
        }
    }

    // ======================= v75: 腾讯云实时语音识别 =======================
    // 云知声识别照常跑(作为回退); 腾讯拿同一份音频并行识别。云知声给出「会被分发」的那条结果时扣住,
    // 等腾讯 final(最多到说完 + timeout_ms), 然后:
    //   腾讯无结果/出错/超时            → 原样用云知声结果
    //   两边文字一致                    → 用云知声结果(NLU 槽位更全)
    //   云知声是原生技能(天气/设置/闹钟…) → 用云知声结果(keep_native, 默认开)
    //   否则用腾讯文字合成一条 NLU, 走原来的分发流程, 由现有 handler 按文字本地路由:
    //     控制词(暂停/继续/下一首/音量…) → PhicommMusicSearchHandler 本地播放器
    //     点歌关键词 → 音乐搜索; 天气词 → 天气; 其他 → PhicommChatHandler(Muse)
    //   云知声判成 music 而文字不同 → 合成 music(用腾讯文字做搜索关键词)
    private static final String TX = "TencentASR";
    private static final String[] TX_WEATHER_WORDS = {
        "天气", "温度", "下雨", "晴天", "阴天", "多云", "几度", "摄氏度",
        "刮风", "下雪", "雾霾", "空气质量", "天气预报"
    };
    private static final String TX_WAKE_WORD = "小爱同学";
    /** 当前扣留代次(0 = 没有扣留) */
    private int txHoldGen = 0;
    private int txSeq = 0;

    /** 新录音/引擎取消: 作废扣留(不分发), 恢复「未处理」状态 */
    private void txDropHold(String why) {
        if (txHoldGen != 0) {
            LogMgr.d(TX, "hold dropped (" + why + "), unisound result discarded");
            txHoldGen = 0;
            this.f423a = false;
        }
    }

    /** 与原逻辑一致的「这条结果会被分发吗」(unsupported domain / 有 NLU 且不是要等 full 的过滤服务) */
    private boolean txDispatchable(Mixture<Intent, Result> m) {
        if (m == null) return true;
        if (!b(m)) return false;
        return !a(m, m.getNluList().get(0));
    }

    private boolean txTryHold(final ANTHandlerContext ctx, final String result) {
        final int sid = TencentAsr.get().activeSessionId();
        if (sid == 0) return false;
        Mixture<Intent, Result> m;
        try {
            m = this.c.from(result);
        } catch (Throwable t) {
            return false;
        }
        if (!txDispatchable(m)) return false;
        String svc = null;
        String txt = null;
        try {
            if (m != null) {
                NLU<Intent, Result> n = m.getNluList().get(0);
                svc = n.getService();
                txt = n.getText();
            } else {
                txt = String.valueOf(((JSONObject) JsonTool.parseToJSONObject(result)
                        .getJSONArray("net_nlu").get(0)).get("text"));
            }
        } catch (Throwable ignored) {
        }
        final String uniService = svc;
        final String uniText = txt;
        d(ctx);   // 原有 15s 兜底照挂(扣留最多 timeout_ms, 远小于 15s)
        this.f423a = true;
        final int gen = ++txSeq;
        txHoldGen = gen;
        final long holdAt = System.currentTimeMillis();
        LogMgr.d(TX, "hold unisound result (service=" + uniService + " text=" + uniText
                + "), wait tencent session " + sid);
        TencentAsr.get().awaitResult(sid, new TencentAsr.ResultCallback() {
            @Override
            public void onResult(String text, String reason) {
                if (txHoldGen != gen) {
                    LogMgr.d(TX, "tencent result for dropped hold ignored (" + reason + ")");
                    return;
                }
                txHoldGen = 0;
                NLUDispatcher.this.f423a = false;
                try {
                    txRoute(ctx, result, uniService, uniText, text, reason, sid,
                            System.currentTimeMillis() - holdAt);
                } catch (Throwable t) {
                    LogMgr.e(TX, "route failed (" + t + "), fallback unisound");
                    processNetResult(ctx, result);
                }
            }
        });
        return true;
    }

    private void txRoute(ANTHandlerContext ctx, String result, String uniService, String uniText,
                         String text, String reason, int sid, long waited) throws JSONException {
        if (text == null) {
            LogMgr.d(TX, "fallback reason=" + reason + " -> unisound (waited " + waited + "ms)");
            processNetResult(ctx, result);
            return;
        }
        String t = txClean(text);
        if (t.isEmpty()) {
            LogMgr.d(TX, "fallback reason=empty text after cleanup -> unisound (waited " + waited + "ms)");
            processNetResult(ctx, result);
            return;
        }
        if (txSame(t, uniText)) {
            LogMgr.d(TX, "same text as unisound -> keep unisound nlu service=" + uniService
                    + " (waited " + waited + "ms)");
            processNetResult(ctx, result);
            return;
        }
        boolean isChat = SName.CHAT.equals(uniService);
        boolean isMusic = SName.MUSIC.equals(uniService);
        boolean nativeSkill = uniService != null && !isChat && !isMusic;
        if (nativeSkill && (TencentAsr.get().keepNative() || SName.CHAT_ILLEGAL.equals(uniService))) {
            LogMgr.d(TX, "keep unisound native service=" + uniService + " (tencent text=" + t
                    + ", unisound text=" + uniText + ", waited " + waited + "ms)");
            processNetResult(ctx, result);
            return;
        }
        String svc;
        if (isMusic) {   // 控制词在 music 服务下同样由 PhicommMusicSearchHandler 本地处理
            svc = SName.MUSIC;
        } else if (txContainsAny(t, TX_WEATHER_WORDS)) {
            svc = SName.WEATHER;
        } else {
            svc = SName.CHAT;
        }
        LogMgr.d(TX, "route tencent text=" + t + " -> " + svc + " (unisound service=" + uniService
                + " text=" + uniText + ", " + reason + ", waited " + waited + "ms)");
        processNetResult(ctx, txSynth(svc, t, sid));
    }

    /** 合成一条与云知声 full 结果同结构的 JSON(net_asr full + net_nlu), 交给原 processNetResult */
    private static String txSynth(String svc, String text, int sid) throws JSONException {
        JSONObject asr = new JSONObject();
        asr.put("engine_mode", "tencent");
        asr.put("result_type", "full");
        asr.put("last_result", true);
        asr.put("recognition_result", text);
        asr.put("sessionID", "tencent-" + sid);
        JSONObject nlu = new JSONObject();
        nlu.put("rc", 0);
        nlu.put("returnCode", 0);
        if (SName.CHAT.equals(svc)) {
            nlu.put("code", "ANSWER");
            JSONObject g = new JSONObject();
            g.put("type", "T");
            g.put("text", "");
            nlu.put("general", g);
        }
        nlu.put("service", svc);
        nlu.put("history", svc);
        nlu.put("source", "tencent_asr");
        nlu.put("text", text);
        nlu.put("responseId", "tencent-" + sid);
        JSONObject root = new JSONObject();
        root.put("net_asr", new JSONArray().put(asr));
        root.put("net_nlu", new JSONArray().put(nlu));
        return root.toString();
    }

    /** 去掉开头的唤醒词(oneshot)和首尾标点/空白 */
    static String txClean(String s) {
        if (s == null) return "";
        String t = s.trim();
        t = TX_EDGE_PUNCT.matcher(t).replaceAll("");
        if (t.startsWith(TX_WAKE_WORD)) {
            t = TX_EDGE_PUNCT.matcher(t.substring(TX_WAKE_WORD.length())).replaceAll("");
        }
        return t;
    }

    private static final java.util.regex.Pattern TX_EDGE_PUNCT = java.util.regex.Pattern.compile(
            "^[\\s\\p{P}\\p{S}\\u3000]+|[\\s\\p{P}\\p{S}\\u3000]+$");

    /** 去掉所有标点/空白后比较 */
    static boolean txSame(String a, String b) {
        if (a == null || b == null) return false;
        return txLetters(a).equalsIgnoreCase(txLetters(b));
    }

    private static String txLetters(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isLetterOrDigit(ch)) sb.append(ch);
        }
        return sb.toString();
    }

    private static boolean txContainsAny(String t, String[] words) {
        for (String w : words) if (t.contains(w)) return true;
        return false;
    }

    /* access modifiers changed from: protected */
    @Override // com.unisound.vui.handler.ANTEventDispatcher
    public boolean onWakeupResult(ANTHandlerContext ctx, String result) {
        LogMgr.d("NLUDispatcher", "onWakeupResult:" + result);
        // ⭐ v82: 新一轮唤醒, 上一轮的卡死看门狗作废
        cancelRecogStallWatchdog();
        LocalASR localASR = this.c.from(result).getLocalASRList().get(0);
        String trim = localASR.getRecognitionResult().trim();
        
        LogMgr.d("NLUDispatcher", "[DEBUG] Recognized wakeup word: '" + trim + "'");

        if (a(ctx.androidContext(), trim)) {
            LogMgr.d("NLUDispatcher", trim + " is CompetitionWord, return");
            return true;
        } else if (b(ctx.androidContext(), trim) && a(localASR.getScore())) {
            LogMgr.d("NLUDispatcher", "isFunctionWakeupWord : wakeupResult:" + trim + ";success core:" + localASR.getScore());
            return a(ctx, localASR);
        } else if (!c(ctx.androidContext(), trim) || !b(localASR.getScore())) {
            LogMgr.d("NLUDispatcher", "[DEBUG] Not a main wakeup word, score=" + localASR.getScore());
            return true;
        } else {
            LogMgr.d("NLUDispatcher", "isMainWakeupWord : wakeupResult:" + trim + ";success core:" + localASR.getScore());
            
            // ⭐ 检查是否是"闭嘴"命令
            if (isShutUpCommand(trim)) {
                LogMgr.d("NLUDispatcher", "Shut-up command detected: " + trim);
                
                // 启用闭嘴模式(1小时)
                PersonaManager.enableShutUpMode();
                
                // 播放确认提示
                ctx.playTTS("好的,我闭嘴一小时");
                
                // 记录交互时间
                PersonaManager.recordInteraction();
                
                return true;  // 拦截,不进入对话
            }
            
            // 查找对应的人格配置
            LogMgr.d("NLUDispatcher", "[DEBUG] Looking up persona for wakeup word: '" + trim + "'");
            PersonaConfig personaConfig = PersonaConfig.findByWakeupWord(trim);
            
            if (personaConfig != null) {
                LogMgr.d("NLUDispatcher", "[DEBUG] Found persona: " + personaConfig.getPersonaName() + " (" + personaConfig.getPersonaId() + ")");
                // 发布人格激活事件
                ctx.pipeline().fireUserEventTriggered(
                    new PersonaActivationEvent(trim, personaConfig.getPersonaId())
                );
                LogMgr.d("NLUDispatcher", "Published PersonaActivationEvent for: " + trim);
            } else {
                LogMgr.w("NLUDispatcher", "[DEBUG] No persona found for wakeup word: '" + trim + "'");
                LogMgr.w("NLUDispatcher", "[DEBUG] Available wakeup words: 你好小迪, 小讯小讯, 交接手续, 捣蛋鬼, 英语陪练师, 成语接龙");
            }
            
            return false;  // 放行,进入语音识别
        }
    }
    
    /**
     * 检查是否是"闭嘴"命令
     */
    private boolean isShutUpCommand(String text) {
        return text.contains("给我闭嘴") || 
               text.contains("闭嘴") || 
               text.contains("别听了") || 
               text.contains("别监听了") ||
               text.contains("安静一小时");
    }

    @Override // com.unisound.vui.engine.ANTInboundHandler, com.unisound.vui.engine.ANTInboundHandlerAdapter
    public void userEventTriggered(Object evt, ANTHandlerContext ctx) throws Exception {
        ctx.fireUserEventTriggered(evt);
    }

    public static void main(String[] args) {
        MixtureProcessor mixtureProcessor = new MixtureProcessor.Builder().build();
        String hook_res = "{'net_nlu':[{'semantic':{'intent':{'tag':'相声','category':'相声评书','keyword':'相声'}},'code':'SEARCH_CATEGORY','data':{'result':{'total':'1','playlist':[{'url_m4a_high':'http://aod.cos.tx.xmcdn.com/group31/M02/2B/C3/wKgJSVmT_JSz0FGhAI6Is9Fjw-g605.m4a','episode':691,'urlm4a':'http://aod.cos.tx.xmcdn.com/group31/M02/2B/C6/wKgJSVmT_Jeim-uMADZpM86L_MY216.m4a','play_count':122077,'title':'2014新年相声喜乐会  郭德纲 于谦《学评书》','url':'http://aod.cos.tx.xmcdn.com/group31/M02/2B/BE/wKgJSVmT_IWTn7j3AEZ_fjTItf0356.mp3','tags':','cover':'http://imgopen.xmcdn.com/group31/M09/20/BD/wKgJX1mBiHCR9cbqAAApsFjA_e4605.jpg!op_type=3&columns=100&rows=100','duration':1154,'update_time':'2017-08-16 16:04:57','url_high':'http://aod.cos.tx.xmcdn.com/group31/M02/2B/BF/wKgJSVmT_IvTQy78AIz-buUQbS8364.mp3','id':47545988,'cover_large':'http://imgopen.xmcdn.com/group31/M09/20/BD/wKgJX1mBiHCR9cbqAAApsFjA_e4605.jpg!op_type=3&columns=640&rows=640'}]'originIntent':{'nluSlotInfos':[]},'history':'cn.yunzhisheng.audio','source':'nlu','uniCarRet':{'result':{},'returnCode':609,'message':'aios-home.hivoice.cn'},'rc':0,'general':{'actionAble':'true','quitDialog':'true','text':'为您播放相声:','type':'T'},'returnCode':0,'audioUrl':'http://asrv3.hivoice.cn/trafficRouter/r/OVAVOb','service':'cn.yunzhisheng.audio','nluProcessTime':'98','text':'播放相声','responseId':'2a9be6f6b605411b83149795a3591b59'}]}";
        hook_res = hook_res.replace('\'','"');

        Mixture<Intent, Result> from2  = mixtureProcessor.from(hook_res);
        System.out.println(from2);
    }
}
