package lo.naui.book

import java.io.ByteArrayOutputStream

/**
 * 把分角色生成的几段音频拼成一段。
 *
 * WAV 是能精确拼的：RIFF 头里 `fmt ` 那段描述格式，`data` 那段是采样。
 * 只要采样率一样，把 data 接起来、重算两个长度字段就行了。
 * 其它格式（mp3 之类）没有可靠的拼接方式，就直接首尾相接 —— 播放器一般认。
 */
object AudioMerge {

    /** 是不是 WAV */
    fun isWav(b: ByteArray): Boolean =
        b.size > 44 && b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() &&
            b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte()

    /**
     * 合并。全是 WAV 就精确拼，只要有一段不是就走朴素拼接。
     * 返回 null 表示没东西可拼。
     */
    fun merge(parts: List<ByteArray>): ByteArray? {
        val list = parts.filter { it.isNotEmpty() }
        if (list.isEmpty()) return null
        if (list.size == 1) return list[0]

        return if (list.all { isWav(it) }) mergeWav(list) else concat(list)
    }

    private fun concat(list: List<ByteArray>): ByteArray {
        val total = list.sumOf { it.size }
        val out = ByteArrayOutputStream(total)
        list.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun mergeWav(list: List<ByteArray>): ByteArray? {
        val first = parse(list[0]) ?: return concat(list)
        val datas = mutableListOf<ByteArray>()
        list.forEach { b ->
            val p = parse(b) ?: return concat(list)
            datas += p.data
        }
        return buildWav(first.fmt, datas)
    }

    private class Parsed(val fmt: ByteArray, val data: ByteArray)

    /** 扫 chunk，把 fmt 和 data 抠出来 */
    private fun parse(b: ByteArray): Parsed? {
        if (!isWav(b)) return null
        var pos = 12                      // 跳过 RIFF....WAVE
        var fmt: ByteArray? = null
        var data: ByteArray? = null

        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4, Charsets.US_ASCII)
            val size = readInt(b, pos + 4)
            if (size < 0 || pos + 8 + size > b.size) break
            val body = b.copyOfRange(pos + 8, pos + 8 + size)

            when (id) {
                "fmt " -> fmt = body
                "data" -> {
                    data = body
                    break                     // data 之后就没别的了
                }
            }
            pos += 8 + size + (size and 1)    // chunk 是偶数字节对齐
        }
        val f = fmt ?: return null
        val d = data ?: return null
        return Parsed(f, d)
    }

    private fun buildWav(fmt: ByteArray, datas: List<ByteArray>): ByteArray {
        val dataLen = datas.sumOf { it.size }
        val out = ByteArrayOutputStream(12 + 8 + fmt.size + 8 + dataLen)

        fun writeAscii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun writeInt(v: Int) {
            out.write(v and 0xFF)
            out.write((v ushr 8) and 0xFF)
            out.write((v ushr 16) and 0xFF)
            out.write((v ushr 24) and 0xFF)
        }

        writeAscii("RIFF")
        writeInt(4 + 8 + fmt.size + 8 + dataLen)
        writeAscii("WAVE")
        writeAscii("fmt ")
        writeInt(fmt.size)
        out.write(fmt)
        if (fmt.size and 1 == 1) out.write(0)
        writeAscii("data")
        writeInt(dataLen)
        datas.forEach { out.write(it) }

        return out.toByteArray()
    }

    private fun readInt(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or
            ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or
            ((b[at + 3].toInt() and 0xFF) shl 24)
}
