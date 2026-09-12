package com.actionmental.platform.shizuku;

interface IPrivilegedService {
    void destroy() = 16777114;
    String exec(String command) = 1;
    /** 注入一次按下 + 抬起。返回空串表示成功，否则是失败原因。 */
    String injectKey(int keyCode, int metaState) = 2;
    /** 只注入按下或只注入抬起。修饰键要能「按住」，所以两半必须分开发。 */
    String injectKeyState(int keyCode, int metaState, boolean down) = 3;
}
