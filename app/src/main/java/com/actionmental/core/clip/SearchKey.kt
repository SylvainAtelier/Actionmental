package com.actionmental.core.clip

/**
 * 搜索键：入库时算一次，查的时候只做字节级的子串查找（SQLite 的 instr）。
 *
 * 一条键由几段拼成，段间用换行隔开（键盘打不出换行，跨段的误命中不会发生）：
 * - 原文开头 [KEY_CHARS] 个字符的小写；
 * - 有汉字时：全拼、首字母，各一段。「中文设置」→ `zhongwenshezhi`、`zwsz`；
 * - 有多音字时：每个多音字换成它的另一个读音，再出一份全拼和首字母。
 *   「银行」→ 主读 `yinxing`，另读 `yinhang`。这不是逐词的正确读音，但不用词库、
 *   不让键随多音字个数指数膨胀，又覆盖了「一个词里只有一个多音字」这种最常见的情况。
 *
 * 只取开头一段是测出来的取舍：键越长，全表扫描越慢；剪贴板里要找的东西几乎总在开头。
 * 拼音由调用方给（平台层用系统自带的 ICU），这里不碰 Android，单元测试可以直接跑。
 */
object SearchKey {

    /** 参与搜索的开头长度。10000 条时最坏的一次全表扫描在 20ms 内（设备实测）。 */
    const val KEY_CHARS = 512

    private const val SEPARATOR = '\n'

    /**
     * @param readings 一个码点的全部读音，小写无声调，第一个是主读；不是汉字或查不到就给空列表。
     */
    fun build(text: String, readings: (Int) -> List<String>): String {
        val head = if (text.length <= KEY_CHARS) text else text.substring(0, safeEnd(text, KEY_CHARS))
        val key = StringBuilder(head.lowercase())

        val full = StringBuilder()
        val initials = StringBuilder()
        val altFull = StringBuilder()
        val altInitials = StringBuilder()
        var han = false
        var polyphone = false

        var i = 0
        while (i < head.length) {
            val cp = head.codePointAt(i)
            i += Character.charCount(cp)
            val options = readings(cp)
            if (options.isNotEmpty()) {
                han = true
                val alt = options.getOrNull(1)
                if (alt != null) polyphone = true
                full.append(options[0])
                initials.append(options[0][0])
                altFull.append(alt ?: options[0])
                altInitials.append((alt ?: options[0])[0])
            } else if (Character.isLetterOrDigit(cp)) {
                // 夹在中文里的英文和数字照样进拼音段：「打开Chrome」用 dkchrome 也找得到
                val lower = Character.toLowerCase(cp)
                full.appendCodePoint(lower)
                initials.appendCodePoint(lower)
                altFull.appendCodePoint(lower)
                altInitials.appendCodePoint(lower)
            }
        }
        if (han) key.append(SEPARATOR).append(full).append(SEPARATOR).append(initials)
        // 首字母多半相同（xing / hang 不同，zhong / chong 不同，le / yue 不同），不同才多存一份
        if (polyphone) {
            key.append(SEPARATOR).append(altFull)
            if (altInitials.toString() != initials.toString()) key.append(SEPARATOR).append(altInitials)
        }
        return key.toString()
    }

    /** 搜索词：按空白拆开、转小写，每一段都要出现在键里。 */
    fun terms(query: String): List<String> =
        query.trim().lowercase().split(WHITESPACE).filter { it.isNotEmpty() }

    private fun safeEnd(text: String, end: Int): Int =
        if (Character.isHighSurrogate(text[end - 1])) end - 1 else end

    private val WHITESPACE = Regex("\\s+")
}

/**
 * 常用多音字的补充读音。
 *
 * 系统的 ICU 只给每个字一个读音，而且未必是最常用的那个（「长」给 zhang，「行」给 xing）。
 * 这里把两个读音都列出来，由 [SearchKey] 生成主读、另读两份键。只收日常文字里真会碰到的。
 */
object Polyphones {
    private val table: Map<Int, List<String>> = mapOf(
        '行' to "xing hang", '长' to "chang zhang", '重' to "zhong chong", '乐' to "le yue",
        '朝' to "chao zhao", '还' to "hai huan", '了' to "le liao", '都' to "dou du",
        '得' to "de dei", '便' to "bian pian", '传' to "chuan zhuan", '调' to "diao tiao",
        '藏' to "cang zang", '和' to "he huo", '解' to "jie xie", '角' to "jiao jue",
        '差' to "cha chai", '单' to "dan shan", '校' to "xiao jiao", '曾' to "ceng zeng",
        '降' to "jiang xiang", '弹' to "dan tan", '给' to "gei ji", '系' to "xi ji",
        '薄' to "bao bo", '着' to "zhe zhao", '觉' to "jue jiao", '会' to "hui kuai",
        '落' to "luo la", '区' to "qu ou", '沈' to "shen chen", '仇' to "chou qiu",
        '盛' to "sheng cheng", '参' to "can shen", '大' to "da dai", '地' to "di de",
        '的' to "de di", '省' to "sheng xing", '识' to "shi zhi", '宿' to "su xiu",
        '率' to "lv shuai", '露' to "lu lou", '模' to "mo mu", '色' to "se shai",
        '厦' to "sha xia", '什' to "shen shi", '属' to "shu zhu", '说' to "shuo shui",
        '似' to "si shi", '提' to "ti di", '吓' to "xia he", '血' to "xue xie",
        '剥' to "bao bo", '秘' to "mi bi", '恶' to "e wu", '圈' to "quan juan",
        '卡' to "ka qia", '壳' to "ke qiao", '车' to "che ju", '柏' to "bai bo",
        '奇' to "qi ji",
    ).mapKeys { it.key.code }.mapValues { it.value.split(' ') }

    /** 把补充读音并进 ICU 给的主读。补充表里有的，以补充表的顺序为准。 */
    fun merge(cp: Int, primary: List<String>): List<String> {
        val extra = table[cp] ?: return primary
        return (extra + primary).distinct()
    }
}
