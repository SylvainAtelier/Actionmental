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
    /**
     * 以 shell 身份让系统开一次二维码式的无线调试配对（IAdbManager.enablePairingByQrCode），
     * 名称与配对码由调用方定。返回空串表示成功，否则是失败原因。
     */
    String startAdbPairing(String serviceName, String password) = 8;
    void stopAdbPairing() = 9;
    /**
     * 依次注入一串按键（三个数组一一对应，down 为 1 表示按下）。组合键发给输入法时要整串发。
     * deviceId 为触发它的那把实体键盘：有的输入法只认实体键盘来的快捷键。返回空串表示成功。
     */
    String injectSequence(int deviceId, in int[] keyCodes, in int[] downs, in int[] metaStates) = 10;
}
