package com.actionmental.ui

import com.actionmental.core.translate.TranslationException
import com.actionmental.core.translate.TranslationFailure

/** Present only known, sanitized failures; never display server bodies or credential errors. */
fun translationFailureMessage(error: Throwable): String = when ((error as? TranslationException)?.kind) {
    TranslationFailure.MISSING_TOKEN -> "请先保存 MiniMax Token"
    TranslationFailure.AUTH -> "Token 无效或已失效，请重新配置"
    TranslationFailure.RATE_LIMIT -> "请求过于频繁，请稍后重试"
    TranslationFailure.TIMEOUT -> "连接超时，请重试"
    TranslationFailure.NETWORK -> "网络连接失败，请检查网络"
    TranslationFailure.INCOMPLETE -> "模型返回不完整，请重试"
    TranslationFailure.EMPTY_RESPONSE -> "模型未返回译文，请重试"
    TranslationFailure.INVALID_REQUEST -> "请求参数无效，请检查模型与语言预设"
    else -> "翻译服务暂不可用，请稍后重试"
}
