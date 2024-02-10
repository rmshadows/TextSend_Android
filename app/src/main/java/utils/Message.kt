package utils

import android.util.Log
import utils.MessageCrypto.tsEncryptString
import java.io.Serializable
import java.util.LinkedList

/**
 * 消息类(从3.1.3之后开始，Message仅作保留，不做传输对象了。传输请使用GsonMessage)
 * 加密的Msg类 解密请在App中实现，此类不包含解密的任何功能
 * @author ryan
 */
class Message(id: String?, text: String?, length: Int, notes: String?) :
    Serializable {
    // 储存数据
    var data = LinkedList<String>()

    // ID
    val id: String?

    // 留言
    var notes: String?

    private fun addData(s: String) {
        data.add(s)
    }

    init {
        // 去除null
        var mtext = text
        if (mtext == null || mtext == "") {
            mtext = ""
        } else {
            Log.i(Constant.TAG, "【封装】Message：$mtext")
        }
        this.id = id
        if (notes != null) {
            this.notes = notes
        } else {
            this.notes = ""
        }
        // 需要截取的长度
        var t_len = mtext.length
        var start = 0
        var end = 0
        // 000 000 000 0 3 10 0,3 3,6 6,9
        while (t_len > length) {
            end += length
            addData(tsEncryptString(mtext.substring(start, end)))
            start += length
            t_len -= length
        }
        val e = mtext.substring(start)
        if (e != "") {
            addData(tsEncryptString(e))
        }
    }

    fun printData() {
        for (str in data) {
            println(str)
        }
    }

    companion object {
        private const val serialVersionUID = 6697595348360693967L
    }
}