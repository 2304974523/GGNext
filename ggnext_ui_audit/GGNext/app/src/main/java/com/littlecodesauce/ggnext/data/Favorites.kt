package com.littlecodesauce.ggnext.data


/**
 * 输入历史（对应原版 History / MemoryHistory）：
 * 存在于内存 + DataStore，限制条数由设置 history_limit 控制。
 */
class SearchHistory(private val limit: Int = 500) {

    private val entries = ArrayDeque<String>()
    private val pins = HashSet<String>()

    fun add(value: String): Unit {
        if (value.isBlank()) return
        entries.remove(value)
        entries.addFirst(value)
        while (entries.size > limit) {
            val last = entries.lastOrNull() ?: break
            if (pins.contains(last)) break
            entries.removeLast()
        }
    }

    fun list(): List<String> = entries.toList()

    fun pin(value: String, pinned: Boolean = true) {
        if (pinned) pins.add(value) else pins.remove(value)
    }

    fun isPinned(value: String) = pins.contains(value)

    fun clear() {
        entries.clear()
        pins.clear()
    }

    fun remove(value: String) {
        entries.remove(value)
    }
}
