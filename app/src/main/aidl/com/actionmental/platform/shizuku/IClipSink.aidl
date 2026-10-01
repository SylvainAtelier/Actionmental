package com.actionmental.platform.shizuku;

/** 特权进程把剪贴板变化推回主进程。oneway：特权进程不等主进程处理完。 */
oneway interface IClipSink {
    /** sensitive 来自 ClipDescription 的 EXTRA_IS_SENSITIVE，主进程据此决定不记录。 */
    void onClip(String text, boolean sensitive);
}
