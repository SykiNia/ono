package moe.ono.creator

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.ono.hooks.protocol.buildMessage
import moe.ono.util.Utils
import com.lxj.xpopup.XPopup
import com.lxj.xpopup.core.BasePopupView
import com.lxj.xpopup.core.BottomPopupView
import com.lxj.xpopup.util.XPopupUtils
import moe.ono.R
import moe.ono.config.CacheConfig
import moe.ono.hooks.base.util.Toasts
import moe.ono.ui.CommonContextWrapper
import moe.ono.ui.view.JsonViewer
import moe.ono.util.AppRuntimeHelper
import moe.ono.util.analytics.ActionReporter
import org.json.JSONObject

@SuppressLint("ResourceType")
class QQMessageFetcherResultDialog(context: Context) : BottomPopupView(context) {
    @SuppressLint("SetTextI18n", "ServiceCast")
    override fun onCreate() {
        super.onCreate()
        Handler(Looper.getMainLooper()).postDelayed({
            val jsonViewer = findViewById<JsonViewer>(R.id.rv_json)
            val btnCopy = findViewById<Button>(R.id.btn_copy)
            val btnElemHex = findViewById<Button>(R.id.btn_elem_hex)
            val rgType = findViewById<RadioGroup>(R.id.rg_type)
            val tvContent = findViewById<TextView>(R.id.tv_content)
            var copyText: String

            jsonViewer.setJson(content)


            val rb = arrayOf<RadioButton>(findViewById(rgType.checkedRadioButtonId))

            copyText = jsonViewer.getJSONString()

            rgType.setOnCheckedChangeListener { _: RadioGroup?, _: Int ->
                rb[0] = findViewById(rgType.checkedRadioButtonId)
                val type = rb[0].text.toString()
                when (type) {
                    "pb" -> {
                        jsonViewer.setJson(content)
                        tvContent.visibility = GONE
                        jsonViewer.visibility = VISIBLE
                        copyText = jsonViewer.getJSONString()
                    }
                    "pb (elem)" -> {
                        try {
                            val result = content?.getJSONObject("6")
                                ?.getJSONObject("3")
                                ?.getJSONObject("1")
                                ?.getJSONArray("2")

                            if (result != null) {
                                jsonViewer.setJson(result)
                            }
                        } catch (e: Exception) {
                            val result = content?.getJSONObject("6")
                                ?.getJSONObject("3")
                                ?.getJSONObject("1")
                                ?.getJSONObject("2")

                            if (result != null) {
                                jsonViewer.setJson(result)
                            }
                        }

                        tvContent.visibility = GONE
                        jsonViewer.visibility = VISIBLE
                        copyText = jsonViewer.getJSONString()
                    }
                    "MsgRecord" -> {
                        tvContent.text = formatMsgRecord(CacheConfig.getMsgRecord().toString())
                        tvContent.visibility = VISIBLE
                        jsonViewer.visibility = GONE
                        copyText = tvContent.text.toString()
                    }
                }
            }


            btnCopy.setOnClickListener {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Copied Text", copyText))
                Toasts.info(context, "已复制")
            }

            btnElemHex.setOnClickListener {
                try {
                    showElementHexDialog()
                } catch (e: Exception) {
                    Toasts.error(context, "导不出来: " + e.message)
                }
            }


        }, 100)
    }

    private fun formatMsgRecord(input: String): String {
        var indentLevel = 0
        val indentStep = "    "
        val result = StringBuilder()

        input.forEach { char ->
            when (char) {
                '{' -> {
                    result.append(char)
                    result.append("\n")
                    indentLevel++
                    result.append(indentStep.repeat(indentLevel))
                }
                '}' -> {
                    result.append("\n")
                    indentLevel = if (indentLevel > 0) indentLevel - 1 else 0
                    result.append(indentStep.repeat(indentLevel))
                    result.append(char)
                }
                ',' -> {
                    result.append(char)
                    result.append("\n")
                    result.append(indentStep.repeat(indentLevel))
                }
                else -> {
                    result.append(char)
                }
            }
        }
        return result.toString()
    }




    /**
     * 把当前 pb / MsgRecord 里的元素全部摊成 hex + 字段号
     * 任何 elementType 都走同一条路，不做类型判断
     */
    private fun buildElementHexText(): String {
        val sb = StringBuilder()
        val pbJson = content

        // 1. pb 里能重新编码的，直接给真实字节
        val elemArr = try {
            pbJson?.optJSONObject("6")?.optJSONObject("3")?.optJSONObject("1")?.optJSONArray("2")
        } catch (e: Exception) {
            null
        }

        if (elemArr != null && elemArr.length() > 0) {
            sb.append("## 从 pb 重新编码\n")
            for (i in 0 until elemArr.length()) {
                val el = elemArr.optJSONObject(i) ?: continue
                val field = el.keys().asSequence().firstOrNull() ?: "?"
                val bytes = try {
                    buildMessage(el.toString())
                } catch (e: Exception) {
                    null
                }
                sb.append("\n--- element[$i] 字段=").append(field)
                if (bytes != null) {
                    sb.append(" 长度=").append(bytes.size).append('\n')
                    sb.append("HEX: ").append(bytesToHex(bytes)).append('\n')
                    dumpProto(bytes, sb)
                } else {
                    sb.append(" (重新编码失败)\n").append(el.toString()).append('\n')
                }
            }
            sb.append('\n')
        }

        // 2. MsgRecord：对象没有序列化方法，改从本地库取真实 blob
        //    所有 getter 都走反射，不同 QQ 版本删方法也不会崩
        val rec: Any? = try {
            CacheConfig.getMsgRecord()
        } catch (e: Throwable) {
            null
        }

        if (rec != null) {
            val records = ArrayList<Any?>()
            records.add(rec)
            callList(rec, "getRecords").forEach { records.add(it) }

            records.forEachIndexed { ri, r ->
                val msgId = callLong(r, "getMsgId")
                sb.append("\n## MsgRecord[").append(ri).append("] msgId=").append(msgId)
                    .append(" msgType=").append(callInt(r, "getMsgType"))
                    .append('/').append(callInt(r, "getSubMsgType"))
                    .append(" chatType=").append(callInt(r, "getChatType"))
                    .append(" seq=").append(callLong(r, "getMsgSeq")).append('\n')

                val hits = queryDbBlobs(msgId)
                if (hits.isEmpty()) {
                    sb.append("(本地库没查到这条的 blob)\n")
                } else {
                    hits.forEach { (name, bytes) ->
                        sb.append("\n--- ").append(name).append(" 长度=").append(bytes.size).append('\n')
                        sb.append("HEX: ").append(bytesToHex(bytes)).append('\n')
                        dumpProto(bytes, sb)
                    }
                }

                callList(r, "getElements").forEachIndexed { i, e ->
                    sb.append("\n--- element[").append(i).append("] elementType=")
                        .append(callInt(e, "getElementType")).append('\n')

                    val grayTip = callObj(e, "getGrayTipElement")
                    if (grayTip != null) {
                        sb.append("  grayTip.subElementType=")
                            .append(callInt(grayTip, "getSubElementType")).append('\n')
                    }
                    sb.append("  extBufForUI=").append(hexOf(callBytes(e, "getExtBufForUI"))).append('\n')
                }
            }
        }

        if (sb.isEmpty()) sb.append("(没有可用的数据)")
        return sb.toString()
    }

    /**
     * 从 nt_msg.db 里按 msgId 抓 msgContent 之类的大 blob
     */
    private fun queryDbBlobs(msgId: Long): List<Pair<String, ByteArray>> {
        val out = ArrayList<Pair<String, ByteArray>>()
        val file = context.getDatabasePath("nt_msg.db")
        if (!file.exists()) return out

        val db: SQLiteDatabase = try {
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        } catch (e: Exception) {
            return out
        }

        try {
            for (table in arrayOf("c2c_msg_table", "group_msg_table", "guild_msg_table")) {
                try {
                    db.rawQuery("SELECT * FROM $table WHERE 40001 = ? LIMIT 1", arrayOf(msgId.toString())).use { c ->
                        if (c.moveToFirst()) {
                            for (i in 0 until c.columnCount) {
                                if (c.getType(i) == Cursor.FIELD_TYPE_BLOB) {
                                    val b = c.getBlob(i)
                                    if (b != null && b.size > 16) {
                                        out.add("$table.${c.getColumnName(i)}" to b)
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
        } finally {
            try {
                db.close()
            } catch (_: Exception) {
            }
        }
        return out
    }

    private fun showElementHexDialog() {
        val text = buildElementHexText()

        val tv = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            setText(text)
            setPadding(24, 24, 24, 24)
        }

        val sv = ScrollView(context)
        sv.addView(tv, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val container = FrameLayout(context)
        container.addView(sv, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("元素字节 / 字段号")
            .setView(container)
            .setPositiveButton("复制 HEX", null)
            .setNeutralButton("复制解析", null)
            .setNegativeButton("关闭", null)
            .create()

        dialog.setOnShowListener {
            val h = (context.resources.displayMetrics.heightPixels * 0.65).toInt()
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, h)
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                copyToClipboard(extractHexLines(text), "已复制 HEX")
            }
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                copyToClipboard(text, "已复制解析")
            }
        }
        dialog.show()
    }

    private fun extractHexLines(text: String): String {
        val sb = StringBuilder()
        text.lineSequence().forEach { line ->
            if (line.startsWith("HEX: ")) sb.append(line.removePrefix("HEX: ")).append('\n')
        }
        return if (sb.isEmpty()) text else sb.toString()
    }

    private fun copyToClipboard(text: String, toast: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ono", text))
        Toasts.info(context, toast)
    }

    /* ---- 反射取值：QQ 各版本 getter 增删都不影响 ---- */

    private fun callObj(obj: Any?, name: String): Any? = try {
        if (obj == null) null else obj.javaClass.getMethod(name).invoke(obj)
    } catch (e: Throwable) {
        null
    }

    private fun callInt(obj: Any?, name: String): Int =
        (callObj(obj, name) as? Int) ?: 0

    private fun callLong(obj: Any?, name: String): Long = when (val v = callObj(obj, name)) {
        is Long -> v
        is Int -> v.toLong()
        is Number -> v.toLong()
        else -> 0L
    }

    private fun callBytes(obj: Any?, name: String): ByteArray? =
        callObj(obj, name) as? ByteArray

    private fun callList(obj: Any?, name: String): List<Any?> =
        (callObj(obj, name) as? List<*>)?.map { it } ?: emptyList()

    private fun hexOf(b: ByteArray?): String =
        if (b == null) "null" else if (b.size <= 32) bytesToHex(b) else bytesToHex(b.copyOfRange(0, 32)) + "..."

    private fun bytesToHex(b: ByteArray): String =
        Utils.bytesToHex(b)

    /**
     * protobuf 无 schema 解析，只按 tag 编号打印，正好用来读字段号
     */
    private fun dumpProto(b: ByteArray, sb: StringBuilder, depth: Int = 0, path: String = ""): Boolean {
        var i = 0
        var fields = 0
        val pad = "    ".repeat(depth)
        while (i < b.size) {
            val t = readVarint(b, i) ?: return false
            val key = t.first
            i = t.second
            val field = (key ushr 3).toInt()
            val wire = (key and 7L).toInt()
            if (field <= 0) return false

            when (wire) {
                0 -> {
                    val v = readVarint(b, i) ?: return false
                    i = v.second
                    sb.append(pad).append(path).append(field).append(": ").append(v.first).append('\n')
                }
                1 -> {
                    if (i + 8 > b.size) return false
                    sb.append(pad).append(path).append(field).append(": fixed64 ")
                        .append(bytesToHex(b.copyOfRange(i, i + 8))).append('\n')
                    i += 8
                }
                5 -> {
                    if (i + 4 > b.size) return false
                    sb.append(pad).append(path).append(field).append(": fixed32 ")
                        .append(bytesToHex(b.copyOfRange(i, i + 4))).append('\n')
                    i += 4
                }
                2 -> {
                    val l = readVarint(b, i) ?: return false
                    i = l.second
                    val len = l.first.toInt()
                    if (len < 0 || i + len > b.size) return false
                    val v = b.copyOfRange(i, i + len)
                    i += len

                    val sub = StringBuilder()
                    var parsed = false
                    if (len > 0) {
                        parsed = try {
                            dumpProto(v, sub, depth + 1, "$path$field.")
                        } catch (e: Exception) {
                            false
                        }
                    }

                    if (parsed) {
                        sb.append(pad).append(path).append(field).append(" { ").append(len).append("B\n")
                        sb.append(sub).append(pad).append("}\n")
                    } else {
                        val str = String(v, Charsets.UTF_8)
                        val printable = v.isNotEmpty() && str.all { c ->
                            c == '\n' || c == '\r' || c == '\t' || c.code in 0x20..0x7E || c.code > 0x7F
                        }
                        if (printable) {
                            sb.append(pad).append(path).append(field).append(": \"")
                                .append(str.replace("\"", "\\\"")).append("\"\n")
                        } else {
                            sb.append(pad).append(path).append(field).append(": ")
                                .append(if (v.size <= 48) bytesToHex(v) else bytesToHex(v.copyOfRange(0, 48)) + "...")
                                .append('\n')
                        }
                    }
                }
                else -> return false
            }
            fields++
        }
        return fields > 0
    }

    private fun readVarint(b: ByteArray, start: Int): Pair<Long, Int>? {
        var res = 0L
        var shift = 0
        var i = start
        while (i < b.size) {
            val v = b[i].toInt() and 0xFF
            res = res or ((v and 0x7F).toLong() shl shift)
            i++
            if (v and 0x80 == 0) return res to i
            shift += 7
            if (shift > 63) return null
        }
        return null
    }

    override fun getImplLayoutId(): Int {
        return R.layout.layout_qq_message_fetcher_result
    }

    companion object {
        private var popupView: BasePopupView? = null
        private var content: JSONObject? = null

        fun createView(context: Context, content: JSONObject) {
            val fixContext = CommonContextWrapper.createAppCompatContext(context)
            val newPop = XPopup.Builder(fixContext).moveUpToKeyboard(true).isDestroyOnDismiss(true)
            newPop.maxHeight((XPopupUtils.getScreenHeight(context) * .90f).toInt())
            newPop.popupHeight((XPopupUtils.getScreenHeight(context) * .90f).toInt())
            Companion.content = content


            ActionReporter.reportVisitor(
                AppRuntimeHelper.getAccount(),
                "CreateView-QQMessageFetcherResultDialog"
            )

            popupView = newPop.asCustom(QQMessageFetcherResultDialog(fixContext))
            popupView?.show()
        }
    }
}



