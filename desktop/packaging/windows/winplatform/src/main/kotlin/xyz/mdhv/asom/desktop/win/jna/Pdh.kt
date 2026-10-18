package xyz.mdhv.asom.desktop.win.jna

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import xyz.mdhv.asom.desktop.win.api.CounterValue
import xyz.mdhv.asom.desktop.win.api.PdhApi
import xyz.mdhv.asom.desktop.win.api.PdhCounterSet

internal interface PdhApi32 : StdCallLibrary {
    fun PdhOpenQueryW(dataSource: String?, userData: Pointer?, query: PointerByReference): Int
    fun PdhAddEnglishCounterW(query: Pointer, counterPath: String, userData: Pointer?, counter: PointerByReference): Int
    fun PdhCollectQueryData(query: Pointer): Int
    fun PdhGetFormattedCounterArrayW(counter: Pointer, format: Int, bufferSize: IntByReference, itemCount: IntByReference, itemBuffer: Pointer?): Int
    fun PdhCloseQuery(query: Pointer): Int
}

/**
 * The layout of `PDH_FMT_COUNTERVALUE_ITEM_W` on a 64-bit system: `LPWSTR szName` at 0, `PDH_FMT_COUNTERVALUE` at 8 (a
 * `DWORD CStatus`, four bytes of padding, then the union whose `double` is at 16); 24 bytes per item. A test cross-checks
 * these constants against a JNA `Structure` with the same fields, so the numbers are computed by the same alignment rules
 * the DLL follows and not typed twice by hand.
 */
object PdhItemLayout {
    const val NAME_OFFSET = 0L
    const val STATUS_OFFSET = 8L
    const val VALUE_OFFSET = 16L
    const val ITEM_SIZE = 24
}

/**
 * Wildcard PDH counters read as an array of (instance, value) (windows.md 2.1, 6; AW05/AW06 unverified). PDH rate counters
 * need two collections: the first call after opening yields no valid data and returns null, and so does any collection whose
 * items are not `PDH_CSTATUS_VALID_DATA`/`NEW_DATA`. Callers must never read null as zero.
 */
class JnaPdh : PdhApi {
    override fun open(englishCounterPath: String): PdhCounterSet? {
        val lib = Libs.pdh
        val q = PointerByReference()
        if (lib.PdhOpenQueryW(null, null, q) != 0) return null
        val c = PointerByReference()
        if (lib.PdhAddEnglishCounterW(q.value, englishCounterPath, null, c) != 0) {
            lib.PdhCloseQuery(q.value)
            return null
        }
        return JnaPdhCounterSet(lib, q.value, c.value)
    }
}

private class JnaPdhCounterSet(private val lib: PdhApi32, private val query: Pointer, private val counter: Pointer) : PdhCounterSet {
    override fun collect(): List<CounterValue>? {
        if (lib.PdhCollectQueryData(query) != 0) return null
        val size = IntByReference(0)
        val count = IntByReference(0)
        val first = lib.PdhGetFormattedCounterArrayW(counter, PDH_FMT_DOUBLE, size, count, null)
        if (first != PDH_MORE_DATA || size.value <= 0) return null
        val buf = Memory(size.value.toLong())
        if (lib.PdhGetFormattedCounterArrayW(counter, PDH_FMT_DOUBLE, size, count, buf) != 0) return null
        val out = ArrayList<CounterValue>(count.value)
        for (i in 0 until count.value) {
            val base = i.toLong() * PdhItemLayout.ITEM_SIZE
            val status = buf.getInt(base + PdhItemLayout.STATUS_OFFSET)
            if (status != PDH_CSTATUS_VALID_DATA && status != PDH_CSTATUS_NEW_DATA) continue
            val namePtr = buf.getPointer(base + PdhItemLayout.NAME_OFFSET) ?: continue
            out += CounterValue(namePtr.getWideString(0), buf.getDouble(base + PdhItemLayout.VALUE_OFFSET))
        }
        return out
    }

    override fun close() {
        lib.PdhCloseQuery(query)
    }

    private companion object {
        const val PDH_FMT_DOUBLE = 0x200
        const val PDH_MORE_DATA = 0x800007D2.toInt()
        const val PDH_CSTATUS_VALID_DATA = 0
        const val PDH_CSTATUS_NEW_DATA = 1
    }
}
