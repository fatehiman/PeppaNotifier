package ir.kimiasoft.peppanotifier

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TimeFmt {
    private val full = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US)

    fun fmt(unix: Long): String {
        if (unix <= 0) return ""
        return full.format(Date(unix * 1000))
    }

    /** Formats remaining seconds as HH:MM (hours may exceed 23). */
    fun remaining(secs: Long): String {
        if (secs <= 0) return ""
        val totalMin = secs / 60
        val h = totalMin / 60
        val m = totalMin % 60
        return "%02d:%02d".format(h, m)
    }
}
