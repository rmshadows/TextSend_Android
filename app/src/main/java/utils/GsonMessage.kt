package utils

import com.google.gson.GsonBuilder
import java.io.Serializable
import java.util.LinkedList

/**
 * {id,  data, notes}
 * 不允许直接使用！
 * 使用流程：
 * 加密：
 * Message->GsonMessage(除Data外明文)
 * GsonMessage->GsonMessage（加密所有参数到密文JSON）
 * 解密L：
 * GsonMessage->GsonMessage（解密所有参数到明文，包括Data）
 */
class GsonMessage(id: String?, data: LinkedList<String>?, notes: String?) :
    Serializable {
    val id: String?
    val data: LinkedList<String>?
    val notes: String?

    init {
        if (notes == null) {
            this.id = ""
        }else{
            this.id = id
        }
        if (data == null) {
            this.data = LinkedList()
        }else{
            this.data = data
        }
        if (notes == null) {
            this.notes = ""
        }else{
            this.notes = notes
        }
    }

    /**
     * 重写的方法
     */
    override fun toString(): String {
        val gson = GsonBuilder()
            .registerTypeAdapter(GsonMessage::class.java, GsonMessageTypeAdapter())
            .create()
        return gson.toJson(this)
    }

    companion object {
        private const val serialVersionUID = 6697595348360693976L
    }
}