package com.actionmental.core.sync

/**
 * 文件夹选择器给的是 SAF 树地址（`content://…/tree/primary%3ADocuments%2Fnotes`），
 * 而同步走的是普通文件路径。两者之间只差一个固定的映射：文档 ID 的卷名换成挂载点。
 */
object StoragePaths {

    /**
     * @param documentId `DocumentsContract.getTreeDocumentId` 的结果，形如 `primary:Documents/notes`
     * @param primaryRoot 主存储的挂载点，通常是 `/storage/emulated/0`
     * @return 本地路径；认不出的卷（云盘、下载提供者等）返回 null
     */
    fun fromDocumentId(documentId: String, primaryRoot: String): String? {
        val colon = documentId.indexOf(':')
        if (colon <= 0) return null
        val volume = documentId.substring(0, colon)
        val relative = documentId.substring(colon + 1).trim('/')
        val base = when {
            volume == "primary" -> primaryRoot.trimEnd('/')
            // 外置卡 / U 盘的卷 ID 是 XXXX-XXXX 这种形式
            volume.matches(Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")) -> "/storage/$volume"
            else -> return null
        }
        return if (relative.isEmpty()) base else "$base/$relative"
    }
}
