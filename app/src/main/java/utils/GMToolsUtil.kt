package utils

import com.google.gson.GsonBuilder
import utils.MessageCrypto.gsonMessageEncrypt
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.Arrays


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

    /**
     * GsonMessage转字节
     * @param gm GsonMessage
     * @return byte[]
     */
    fun gsonMessage2bytes(gm: GsonMessage?): ByteArray? {
        try {
            ByteArrayOutputStream().use { byteArrayOutputStream ->
                ObjectOutputStream(byteArrayOutputStream).use { objectOutputStream ->
                    objectOutputStream.writeObject(gm)
                    objectOutputStream.flush()
                    objectOutputStream.close()
                    return mergeArrays(byteArrayOutputStream.toByteArray(), Constant.endMarker)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    /**
     * 合并数组
     * @param array1 数组1
     * @param array2 数组2
     * @return 数组1+数组2
     */
    fun mergeArrays(array1: ByteArray, array2: ByteArray): ByteArray {
        val mergedArray = ByteArray(array1.size + array2.size)
        System.arraycopy(array1, 0, mergedArray, 0, array1.size)
        System.arraycopy(array2, 0, mergedArray, array1.size, array2.size)
        return mergedArray
    }

    /**
     * 去除末尾的结束符号
     * @param c 字节数组
     * @param b 结束符号字节数组
     * @return 去除末尾的结束符号de字节数组
     */
    fun removeArray(c: ByteArray, b: ByteArray): ByteArray {
        val index = indexOfSubArray(c, b)
        return if (index != -1) {
            val result = ByteArray(c.size - b.size)
            System.arraycopy(c, 0, result, 0, index)
            System.arraycopy(c, index + b.size, result, index, c.size - index - b.size)
            result
        } else {
            c
        }
    }

    /**
     * 查找子数组在数组中的起始索引
     * @param array 数组
     * @param subArray 子数组
     * @return 索引
     */
    fun indexOfSubArray(array: ByteArray, subArray: ByteArray): Int {
        for (i in 0..array.size - subArray.size) {
            var found = true
            for (j in subArray.indices) {
                if (array[i + j] != subArray[j]) {
                    found = false
                    break
                }
            }
            if (found) {
                return i
            }
        }
        return -1
    }

    /**
     * 去掉字节数组末尾的0
     * @param array 字节数组
     * @return 去尾0字节数组
     */
    fun removeTrailingZeros(array: ByteArray): ByteArray {
        var lastIndex = array.size - 1
        while (lastIndex >= 0 && array[lastIndex].toInt() == 0) {
            lastIndex--
        }
        return Arrays.copyOf(array, lastIndex + 1)
    }


    /**
     * 字节转GM
     * @param bytes 字节数组
     * @return GsonMessage
     */
    fun bytes2GsonMessage(bytes: ByteArray): GsonMessage? {
        // 去零
        var b = bytes
        b = removeTrailingZeros(b)
        // 去头
        b = removeArray(b, Constant.endMarker)
        try {
            ByteArrayInputStream(b).use { byteArrayInputStream ->
                ObjectInputStream(
                    byteArrayInputStream
                ).use { objectInputStream -> return objectInputStream.readObject() as GsonMessage }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    /**
     * 判断字节数组是否以指定的字节数组结尾
     * @param data 字节数组
     * @param endMarker 指定的字节数组
     * @return 是否
     */
    fun bendsWith(data: ByteArray, endMarker: ByteArray): Boolean {
        var d = data
        d = removeTrailingZeros(d)
        if (d.size < endMarker.size) {
            return false
        }
        for (i in endMarker.indices) {
            if (d[d.size - endMarker.size + i] != endMarker[i]) {
                return false
            }
        }
        return true
    }
}