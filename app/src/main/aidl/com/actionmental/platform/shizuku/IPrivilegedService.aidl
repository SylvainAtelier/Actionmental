package com.actionmental.platform.shizuku;

import com.actionmental.platform.shizuku.IClipSink;

interface IPrivilegedService {
    void destroy() = 16777114;
    String exec(String command) = 1;
    /** 注入一次按下 + 抬起。返回空串表示成功，否则是失败原因。 */
    String injectKey(int keyCode, int metaState) = 2;
    /** 只注入按下或只注入抬起。修饰键要能「按住」，所以两半必须分开发。 */
    String injectKeyState(int keyCode, int metaState, boolean down) = 3;
    /** 以 shell 身份监听剪贴板，变化时推给 sink。返回空串表示成功，否则是失败原因。 */
    String watchClipboard(IClipSink sink, int userId) = 4;
    void unwatchClipboard() = 5;
    /** 读当前剪贴板的文本。读不到（锁屏、为空、非文本、被标成敏感）时返回 null。 */
    String readClipboard(int userId) = 6;
    /** 以 shell 身份写剪贴板：shell 的 WRITE_CLIPBOARD 不受「仅前台」限制。返回空串表示成功。 */
    String writeClipboard(String text, int userId) = 7;
}
