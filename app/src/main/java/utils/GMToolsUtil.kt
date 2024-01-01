package utils

import com.google.gson.GsonBuilder
import utils.MessageCrypto.gsonMessageEncrypt

/*
* JSON传输模式的类 可以将gson msg对象转为json
*/   object GMToolsUtil {
    /**
     * 返回Gson对象 注意 没有加密！（除了Message自己加密的data）
     *
     * @param m Message
     */
    fun MessageToGsonMessage(m: Message): GsonMessage {
        return GsonMessage(m.id.toString(), m.data, m.notes)
    }

    /**
     * 转成加密的Gson对象，可以直接用于发送
     * @param m Message
     * @return GsonMessage
     */
    fun MessageToEncrypptedGsonMessage(m: Message): GsonMessage? {
        return gsonMessageEncrypt(GsonMessage(m.id.toString(), m.data, m.notes))
    }

    /**
     * JSON转GsonMessage对象 (未解密)
     * @param json json
     */
    fun JSONtoGsonMessage(json: String?): GsonMessage {
        val gson =
            GsonBuilder().registerTypeAdapter(GsonMessage::class.java, GsonMessageTypeAdapter())
                .create()
        return gson.fromJson(json, GsonMessage::class.java)
    }
}