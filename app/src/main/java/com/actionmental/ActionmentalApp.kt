package com.actionmental

import android.app.Application
import com.actionmental.core.diag.CrashSink

class ActionmentalApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 先装崩溃处理器，再建对象图 —— 否则图自己在构造时崩掉，就没有任何人记得下来。
        // 此刻 EventLog 还不存在，所以先写进一个只认文件的落地点，图建好后再接上。
        CrashSink.install(filesDir)
        AppGraph.init(this)
    }

    /**
     * 界面退到后台、或系统内存吃紧时，把只有界面用得上的东西放掉。
     *
     * 进程要以无障碍服务的身份长期活着，所以「活着的时候占多少」直接决定了
     * 它在厂商后台限制名单上的位置。
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        AppGraph.get(this).onTrimMemory(level)
    }
}
