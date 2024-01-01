package utils

import AES_Utils.AES_CFB
import Datetime_Utils.Datetime_Utils
import RandomNumber.RandomNumber
import cn.rmshadows.textsend.MainActivity
import java.util.LinkedList

object MessageCrypto {
    // 分隔符
    const val MSG_SPLITOR = "☯☯"

    /**
     * 加密字符串
     *
     * @param string 字符串
     */
    fun tsEncryptString(string: String?): String {
        val cfb = AES_CFB(MainActivity.AES_TOKEN, "ES", 32)
        return cfb.encrypt(string)
    }

    /**
     * 解密字符串
     *
     * @param string 字符串
     */
    fun tsDecryptString(string: String?): String {
        val cfb = AES_CFB(MainActivity.AES_TOKEN, "ES", 32)
        return cfb.decrypt(string)
    }

    /**
     * 加密明文GsonMessage (注意：不会对Data进行加密！Data加密请在Message中进行！)
     *
     * @param clearGsonMessage 明文gm
     */
    fun gsonMessageEncrypt(clearGsonMessage: GsonMessage): GsonMessage? {
        return try {
            var id = java.lang.String.format(
                "%s%s%s",
                clearGsonMessage.id,
                MSG_SPLITOR,
                randomInt()
            )
            id = tsEncryptString(id)
            val data: LinkedList<String>? = clearGsonMessage.data
            var notes = java.lang.String.format(
                "%s%s%s",
                clearGsonMessage.notes,
                MSG_SPLITOR,
                randomInt()
            )
            notes = tsEncryptString(notes)
            val encryptedGm = GsonMessage(id, data, notes)
            System.out.print(clearGsonMessage)
            print("  ->  ")
            System.out.println(encryptedGm)
            encryptedGm
        } catch (e: Exception) {
            e.printStackTrace()
            println("Gson Message JSON加密失败")
            null
        }
    }

    /**
     * 解密加密的GsonMessage到明文 (Data也会被解密成明文)
     *
     * @param encryptedGsonMessage 加密的GM
     */
    fun gsonMessageDecrypt(encryptedGsonMessage: GsonMessage): GsonMessage? {
        return try {
            val id = tsDecryptString(encryptedGsonMessage.id)
                .split(MSG_SPLITOR.toRegex()).dropLastWhile { it.isEmpty() }
                .toTypedArray()[0]
            val data = LinkedList<String>()
            // 解密Data
            for (es in encryptedGsonMessage.data!!) {
                data.add(tsDecryptString(es))
            }
            val notes =
                tsDecryptString(encryptedGsonMessage.notes)
                    .split(MSG_SPLITOR.toRegex()).dropLastWhile { it.isEmpty() }
                    .toTypedArray()[0]
            //            System.out.print(encryptedGsonMessage);
            //            System.out.print("  ->  ");
            //            System.out.println(clearGm);
            GsonMessage(id, data, notes)
        } catch (e: Exception) {
            e.printStackTrace()
            println("Gson Message JSON解密失败")
            null
        }
    }

    private val stringTimestamp: String
        /**
         * 返回时间戳
         *
         * @return 1693449156
         */
        get() = Datetime_Utils.getTimeStampNow(false).toString()

    /**
     * 返回随机数 0~5000
     */
    private fun randomInt(): Int {
        return RandomNumber.secureRandomInt(0, 5000)
    }
}