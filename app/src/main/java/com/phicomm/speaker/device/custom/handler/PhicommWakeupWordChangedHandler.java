package com.phicomm.speaker.device.custom.handler;

import com.phicomm.speaker.device.R;
import com.unisound.vui.engine.ANTHandlerContext;
import com.unisound.vui.handler.UpdateWakeupWordHandler;
import com.unisound.vui.util.AttributeKey;
import com.unisound.vui.util.LogMgr;
import com.unisound.vui.util.SpeakerTTSUtil;
import com.unisound.vui.util.UserPerferenceUtil;
import java.util.List;

public class PhicommWakeupWordChangedHandler extends UpdateWakeupWordHandler {
    public static final AttributeKey<Boolean> UPDATE_WAKEUPWORD = AttributeKey.valueOf(UpdateWakeupWordHandler.class, "UPDATE_WAKEUPWORD");

    @Override // com.unisound.vui.handler.UpdateWakeupWordHandler, com.unisound.vui.engine.ANTDuplexHandler, com.unisound.vui.engine.ANTOutboundHandler
    public void updateWakeupWord(ANTHandlerContext ctx, List<String> wakeup) throws Exception {
        fireActiveInterrupt(ctx);
        ctx.attr(UPDATE_WAKEUPWORD).set(true);
        ctx.updateWakeupWord(wakeup);
    }

    /*
     * v81: 不再播「唤醒词修改成功 / 修改失败」。
     * 开机 PhicommInitializeHandler 每次都会 updateWakeupWord(小爱同学+人格唤醒词), 原来会播一句;
     * 而且 UPDATE_WAKEUPWORD 标记只在本 handler 收到 TTS 结束时才清, TTS 结束被别的 handler 吃掉后
     * 标记一直为 true, 之后音乐开始/结束 setWakeupWord 完成都会再播一遍(「突然说唤醒词修改成功」)。
     * 现在: 完成/失败都立刻清标记, 静默回唤醒态(和原来播完 TTS 后的动作一致, 只是不说话)。
     */
    @Override // com.unisound.vui.handler.UpdateWakeupWordHandler, com.unisound.vui.engine.ANTDuplexHandler, com.unisound.vui.handler.ANTEventDispatcher
    public boolean onWakeupEventSetWakeupwordDone(ANTHandlerContext ctx) {
        if (!ctx.hasAttr(UPDATE_WAKEUPWORD) || !Boolean.TRUE.equals(ctx.attr(UPDATE_WAKEUPWORD).getAndRemove())) {
            return super.onWakeupEventSetWakeupwordDone(ctx);
        }
        LogMgr.d("PhicommWakeupWord", "wakeup word updated (silent, no TTS)");
        ctx.enterWakeup(false);
        fireResume(ctx);
        return true;
    }

    @Override // com.unisound.vui.handler.UpdateWakeupWordHandler, com.unisound.vui.engine.ANTDuplexHandler, com.unisound.vui.handler.ANTEventDispatcher
    public boolean onWakeupEventUpdateWakeupWordFail(ANTHandlerContext ctx) {
        if (!ctx.hasAttr(UPDATE_WAKEUPWORD) || !Boolean.TRUE.equals(ctx.attr(UPDATE_WAKEUPWORD).getAndRemove())) {
            return super.onWakeupEventUpdateWakeupWordFail(ctx);
        }
        LogMgr.d("PhicommWakeupWord", "wakeup word update failed (silent, no TTS)");
        ctx.enterWakeup(false);
        fireResume(ctx);
        return true;
    }

    /* access modifiers changed from: protected */
    @Override // com.unisound.vui.handler.UpdateWakeupWordHandler, com.unisound.vui.engine.ANTDuplexHandler, com.unisound.vui.handler.ANTEventDispatcher
    public boolean onTTSEventPlayingEnd(ANTHandlerContext ctx) {
        if (!ctx.hasAttr(UPDATE_WAKEUPWORD) || !Boolean.TRUE.equals(ctx.attr(UPDATE_WAKEUPWORD).getAndRemove())) {
            return super.onTTSEventPlayingEnd(ctx);
        }
        ctx.enterWakeup(false);
        fireResume(ctx);
        return true;
    }

    /* access modifiers changed from: protected */
    @Override // com.unisound.vui.handler.UpdateWakeupWordHandler, com.unisound.vui.handler.SimpleSessionManagementHandler
    public void doInterrupt(ANTHandlerContext ctx, String interruptType) {
        if (ctx.hasAttr(UPDATE_WAKEUPWORD) && Boolean.TRUE.equals(ctx.attr(UPDATE_WAKEUPWORD).getAndRemove())) {
            ctx.cancelTTS();
        }
    }
}
