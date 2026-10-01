package com.actionmental.platform

import android.icu.text.Transliterator
import com.actionmental.core.clip.Polyphones
import java.util.concurrent.ConcurrentHashMap

/**
 * 汉字 → 拼音，用系统自带的 ICU（Android 10 起公开），不引入第三方库、不带字典文件。
 *
 * 每个字只查一次 ICU，结果按码点缓存：常用汉字就几千个，缓存很快就满，之后全是查表。
 * 设备实测：热缓存下 2000 字的文本建键约 0.06ms。
 *
 * ü 同时给 `v` 和 `u` 两种写法 —— 输入法习惯打 lv，也有人打 lu，两种都该找得到。
 */
class IcuPinyin {

    private val hanLatin by lazy { Transliterator.getInstance("Han-Latin") }
    private val ascii by lazy { Transliterator.getInstance("Latin-ASCII; Lower") }
    private val cache = ConcurrentHashMap<Int, List<String>>()

    fun readings(cp: Int): List<String> {
        if (Character.UnicodeScript.of(cp) != Character.UnicodeScript.HAN) return emptyList()
        return cache.getOrPut(cp) { Polyphones.merge(cp, lookup(cp)) }
    }

    /** ICU 的 Transliterator 不保证线程安全，查表在 IO 线程池里并发，所以加锁。 */
    @Synchronized
    private fun lookup(cp: Int): List<String> {
        val latin = runCatching { hanLatin.transliterate(String(Character.toChars(cp))).trim() }.getOrNull()
        // 没有读音的字 ICU 原样返回
        if (latin.isNullOrEmpty() || latin.codePointAt(0) == cp) return emptyList()
        val plain = ascii.transliterate(latin).filter { it in 'a'..'z' }
        if (plain.isEmpty()) return emptyList()
        if (latin.none { it in UMLAUT }) return listOf(plain)
        val v = ascii.transliterate(latin.map { if (it in UMLAUT) 'v' else it }.joinToString("")).filter { it in 'a'..'z' }
        return listOf(v, plain).distinct()
    }

    private companion object {
        const val UMLAUT = "üǖǘǚǜ"
    }
}
