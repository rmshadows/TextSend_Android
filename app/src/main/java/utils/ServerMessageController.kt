package utils

import android.util.Log
import cn.rmshadows.textsend.viewmodels.ServerFragmentViewModel
import cn.rmshadows.textsend.viewmodels.TextsendViewModel
import com.google.gson.Gson
import com.google.gson.JsonParser
import utils.Constant.Companion.TAG
import utils.GMToolsUtil.JSONtoGsonMessage
import utils.GMToolsUtil.MessageToEncrypptedGsonMessage
import utils.GMToolsUtil.bendsWith
import utils.GMToolsUtil.bytes2GsonMessage
import utils.GMToolsUtil.gsonMessage2bytes
import utils.GMToolsUtil.mergeArrays
import utils.MessageCrypto.gsonMessageDecrypt
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Arrays
import java.util.function.Supplier
import java.util.stream.Stream


/**
 * 此类仅对应一个连接！
 */
class ServerMessageController(
    var socket: Socket,
    private val tsviewModel: TextsendViewModel,
    private val viewModel: ServerFragmentViewModel
) : Runnable {

    var transmissionModeSet = -1
    var clientId: String = socket.hashCode().toString()

    // 客户端IP
    val clientIP: String = socket.inetAddress.hostAddress as String

    // 连接状态 -1 未连接 0:连接 分配ID中 1:分配完ID 分配模式中 2:正常通信 -2:断开连接
    var connectionStat = -1
        set(connectionStat) {
            field = connectionStat
            if (connectionStat == 0 || connectionStat == 1 || connectionStat == -1) { // -1的情况用不到
                // 连接初始化、连接分配id中都是使用JSON
                transmissionModeSet = 1
            }
        }

    /**
     * 断开当前客户端
     */
    fun closeCurrentClientSocket() {
        try {
            // 设置断开 会结束消息监听
            connectionStat = -2
            // 断开Socket
            socket.close()
            // 移除列表
            SocketDeliver.socketList.remove(this)
            Log.i(TAG, "closeCurrentClientSocket: 主动断开 用户 $clientIP (${clientId}) 。%n")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 仅向当前客户端发送信息 (实例方法)
     *
     * @param message 信息
     */
    fun sendMessage(message: Message?) {
        Thread(ServerMessageTransmitter(this, message)).start()
    }

    // 构造方法
    init {
        // 客户端IP
        // 生成客户端ID
        // 状态设为连接 分配ID等事情是打开消息监听后的事
        connectionStat = 0
    }

    /**
     * 反馈核对信息到移动端，确保消息接收到 但不保证无误
     */
    fun messageFeedBack() {
        Log.i(TAG, "发送反馈信息到客户端。")
        sendMessage(Message(SERVER_ID, null, MSG_LEN, FB_MSG))
    }

    override fun run() {
        // 启动监听器
        val receiver = Thread(ServerMessageReceiver(this, tsviewModel, viewModel))
        receiver.start()
        // 发送客户端ID给客户端
        Log.i(TAG, "ID -> $clientIP (${clientId})")
        sendMessage(
            Message(
                SERVER_ID, null, MSG_LEN,
                clientId
            )
        )
        try {
            // 等待监听器结束
            receiver.join()
            // 关闭客户端连接
            closeCurrentClientSocket()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    companion object {
        const val FB_MSG: String = Constant.FB_MSG
        const val MSG_LEN: Int = Constant.MSG_LEN
        const val SERVER_ID: String = Constant.SERVER_ID
    }
}

/**
 * 服务端发送Msg到客户端
 *
 * @author jessie
 */
internal class ServerMessageTransmitter(
    serverMessageController: ServerMessageController,
    m: Message?
) :
    Runnable {
    private val msg: Message?
    private var bufferedOutputStream: BufferedOutputStream? = null
    private var serverMessageController: ServerMessageController
    private val transmitterTransmissionMode: Int

    init {
        val socket = serverMessageController.socket
        msg = m
        this.serverMessageController = serverMessageController
        transmitterTransmissionMode = serverMessageController.transmissionModeSet
        try {
            // 1:JSON 2:Object
            bufferedOutputStream = BufferedOutputStream(socket.getOutputStream())
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
        }
    }


    override fun run() {
        try {
            // 先获取加密的GSM
            val egm = MessageToEncrypptedGsonMessage(msg!!)
            if (transmitterTransmissionMode == 0 || transmitterTransmissionMode == 1) {
                // JSON传输
                if (msg.notes == ServerMessageController.FB_MSG) {
                    Log.i(TAG, "Log: 【发送反馈】JSON => " + serverMessageController.clientIP + ": " + egm)
                } else {
                    Log.i(TAG, "Log: 【发送】JSON => " + serverMessageController.clientIP + ": " + egm)
                }
                // 将GSM对象读取成文字传输
                var read: Int
                val buf = ByteArray(1024)
                val bufferedInputStream = BufferedInputStream(
                    ByteArrayInputStream(
                        egm.toString().toByteArray(
                            StandardCharsets.UTF_8
                        )
                    )
                )
                while (bufferedInputStream.read(buf).also { read = it } != -1) {
                    bufferedOutputStream!!.write(buf, 0, read)
                }
                bufferedOutputStream!!.flush()
                // 会关闭输入流（GSM对象读取完了就关闭），不会关闭输出流(会关闭Socket)
                bufferedInputStream.close()
            } else if (transmitterTransmissionMode == 2) {
                // OBJECT传输
                if (msg.notes == ServerMessageController.FB_MSG) {
                    Log.i(TAG, "Log: 【发送反馈】OBJECT => " + serverMessageController.clientIP + ": " + egm)
                } else {
                    Log.i(TAG, "Log: 【发送】OBJECT => " + serverMessageController.clientIP + ": " + egm)
                }
                // 将对象序列化为字节数组并分块发送
                val begm = gsonMessage2bytes(egm)
                if (begm != null) {
                    // 将GSM对象读取成byte传输
                    bufferedOutputStream!!.write(begm)
                    bufferedOutputStream!!.flush()
                }
            } else {
                throw IOException("传输模式设置有误: Mode set error: $transmitterTransmissionMode")
            }
        } catch (e: java.lang.Exception) {
            // 发送出错会断开连接
            Log.i(TAG, "ServerMessageTransmitterError: ")
            e.printStackTrace()
            serverMessageController.closeCurrentClientSocket()
        }
    }
}

/**
 * 服务端接收客户端信息
 *
 * @author jessie
 */
internal class ServerMessageReceiver(
    private val serverMessageController: ServerMessageController,
    private val tsviewModel: TextsendViewModel,
    private val viewModel: ServerFragmentViewModel
) :
    Runnable {
    private var bufferedInputStream: BufferedInputStream? = null
    private var receiverTransmissionMode: Int
    private var count = 0

    init {
        receiverTransmissionMode = serverMessageController.transmissionModeSet
        val socket = serverMessageController.socket
        try {
            bufferedInputStream = if (receiverTransmissionMode == 1) {
                BufferedInputStream(socket.getInputStream())
            } else {
                throw IOException("ServerMessageReceiver modeSet param error.")
            }
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
            // 监听出错也会关闭Socket
            serverMessageController.closeCurrentClientSocket()
        }
    }


    override fun run() {
        try {
            while (serverMessageController.connectionStat != -2 && viewModel.uiState.value.serverRunning) {
                if (receiverTransmissionMode == 1) {
                    // 接收消息
                    // 如果是-1说明连接已经断了
                    val readBuf = ByteArray(1024)
                    var readLength: Int
                    var chunk = StringBuilder()
                    while ((bufferedInputStream!!.read(readBuf).also { readLength = it } != -1) && receiverTransmissionMode == 1) {
                        // 如果服务停止
                        if (serverMessageController.connectionStat == -2 && !viewModel.uiState.value.serverRunning) {
                            break
                        }
                        val read = String(readBuf, 0, readLength, StandardCharsets.UTF_8)
                        chunk.append(read)
                        // 读取到JSON末尾
                        if (read.endsWith("}")) {
                            Log.i(TAG, "Log: 【接收】1: <== : $chunk")
                            // 这里开始处理
                            val egm = JSONtoGsonMessage(chunk.toString())
                            // 解密后的信息
                            val cgm = gsonMessageDecrypt(egm)
                            if (serverMessageController.connectionStat == 0) {
                                // 获取客户端支持的模式
                                if (cgm != null && cgm.id == serverMessageController.clientId) { // 客户端发送的才接受
                                    // Notes: {"id":"553126963","data":"","notes":"SUPPORT-{"supportMode":[1]}"}
                                    val ts = cgm.notes!!.split("-".toRegex())
                                        .dropLastWhile { it.isEmpty() }
                                        .toTypedArray()
                                    // 如果是SUPPORT开头
                                    if (ts[0] == "SUPPORT") {
                                        // 读取客户端发送的JSON {"supportMode":[1]}
                                        // 发送决定后的传输模式 格式： "CONFIRM-" + clientMode"
                                        val selectedMode = selectClientMode(ts[1])
                                        val allocationMode = "CONFIRM-$selectedMode"
                                        if (selectedMode != null) {
                                            // 发送决定后的传输模式(先发送再修改传输模式)
                                            serverMessageController.sendMessage(
                                                Message(
                                                    Constant.SERVER_ID,
                                                    null,
                                                    Constant.MSG_LEN,
                                                    allocationMode
                                                )
                                            )
                                            serverMessageController.transmissionModeSet =
                                                selectedMode.toInt()
                                            // 设置接收器模式
                                            receiverTransmissionMode =
                                                serverMessageController.transmissionModeSet
                                        } else {
                                            throw java.lang.Exception("客户端支持传输模式不支持，连接配置失败。")
                                        }
                                        // 收到客户端发送的模式清单，说明客户端接受了ID请求，状态直接0变为2。 注意：直接进入接受传输模式交流
                                        serverMessageController.connectionStat = 2
                                        System.err.printf(
                                            "Log: 用户 %s (%s) 已上线(模式:%s)。%n",
                                            serverMessageController.clientIP,
                                            serverMessageController.clientId,
                                            serverMessageController.transmissionModeSet
                                        )
                                        if(receiverTransmissionMode == 2){
                                            break
                                        }
                                    } else {
                                        Log.i(TAG, "Log: 【丢弃】1:Drop id message (on get support mode :support mode error.) : $cgm")
                                    }
                                } else {
                                    Log.i(TAG, "Log: 【丢弃】1:Drop id message (on get support mode :id wrong) : $cgm")
                                }
                            } else {
                                if (cgm != null) {
                                    // 客户端发送的才接受
                                    if (cgm.id == serverMessageController.clientId) {
                                        if (cgm.notes == ServerMessageController.FB_MSG) {
                                            // 处理反馈信息
                                            Log.i(TAG, "Log: 【接收反馈】1:客户端收到了消息。")
                                            tsviewModel.cleanEditText()
                                        } else {
                                            val text = StringBuilder()
                                            for (c in cgm.data!!) {
                                                text.append(c)
                                            }
                                            // 反馈客户端 注意：仅代表服务端收到信息
                                            serverMessageController.messageFeedBack()
                                            Log.i(TAG, 
                                                "Log: 【接收】JSON <== " + serverMessageController.clientIP
                                                        + "(" + serverMessageController.clientId + ") <- " + text
                                            )
                                            tsviewModel.update(
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                text.toString(),
                                                null
                                            )
                                        }
                                    } else {
                                        // 丢弃的常规通讯信息
                                        Log.i(TAG, "Log: 【丢弃】1:Drop id message (json mode) : $cgm")
                                    }
                                }
                            }
                            // reset chunk
                            chunk = StringBuilder()
                        }
                    }
                } else if (receiverTransmissionMode == 2) {
                    Log.i(TAG, "Log: 服务端进入Object传输模式")
                    // 传输对象 传输对象的时候已经进入正常通信了
                    // -2 表示连接断开了 只有服务在运行、客户端没断开才会继续监听
                    // 断开操作在TextSendMain中实现 这里已经解密成明文GM了
                    val readBuf = ByteArray(1024)
                    // 用于记录上次的值
                    var chunk: ByteArray? = null
                    var readLength: Int
                    // 读取对象字节数组并反序列化
                    while ((bufferedInputStream!!.read(readBuf).also { readLength = it } != -1) && receiverTransmissionMode == 2) {
                        // 如果服务停止
                        if (serverMessageController.connectionStat == -2 && !viewModel.uiState.value.serverRunning) {
                            break
                        }
                        chunk = if (chunk == null) {
                            readBuf.copyOfRange(0, readLength)
                        } else {
                            mergeArrays(chunk, readBuf.copyOfRange(0, readLength))
                        }
                        // 和上一次的值合并,检查是否到达了结束标记
                        if (bendsWith(chunk, Constant.endMarker)) {
                            val egm = bytes2GsonMessage(chunk)
                            // 解密后的信息
                            val cgm = gsonMessageDecrypt(egm!!)
                            if (cgm != null) {
                                // 客户端发送的才接受
                                if (cgm.id == serverMessageController.clientId) {
                                    if (cgm.notes == ServerMessageController.FB_MSG) {
                                        // 处理反馈信息
                                        Log.i(TAG, "Log: 【接收反馈】2:客户端收到了消息。")
                                        tsviewModel.cleanEditText()
                                    } else {
                                        val text = StringBuilder()
                                        for (c in cgm.data!!) {
                                            text.append(c)
                                        }
                                        // 反馈客户端 注意：仅代表服务端收到信息
                                        serverMessageController.messageFeedBack()
                                        Log.i(TAG, 
                                            "Log: 【接收】OBJECT <== : " + serverMessageController.clientIP
                                                    + "(" + serverMessageController.clientId + ") <- " + text
                                        )
                                        tsviewModel.update(
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                text.toString(),
                                                null
                                            )
                                    }
                                } else {
                                    // 丢弃的常规通讯信息
                                    Log.i(TAG, "Log: 【丢弃】2:Drop id message (object mode) : $cgm")
                                }
                                chunk = null
                            }
                        }
                    }
                } else {
                    throw IOException("Mode set error.")
                }
                count++
                if (count > 10) {
                    Log.i(TAG, "Log: Count 10 次，结束Socket。")
                    break
                }
            }
            Log.i(TAG, "Log: Socket has ended.")
            serverMessageController.connectionStat = -1
            tsviewModel.update(null, null, false, null, null, null, null)
        } catch (e: java.lang.Exception) {
            // 出错断开当前连接
            Log.i(TAG, "ServerMessageReceiverError: ")
            e.printStackTrace()
            // 直接设置状态-2 会在finally中结束当前Socket
            serverMessageController.connectionStat = -2
        } finally {
            // 状态为-2 且服务端停止运行
            if (serverMessageController.connectionStat == -2 && !viewModel.uiState.value.serverRunning) {
                serverMessageController.closeCurrentClientSocket()
            }
        }
    }

    /**
     * 选择客户端模式
     *
     * @param supportModejson JSON {"supportMode":[1, 2, 3]}
     */
    private fun selectClientMode(supportModejson: String): String? {
        try {
            val jsonElement = JsonParser.parseString(supportModejson)
            val jsonObject = jsonElement.asJsonObject
            // [1, 2]
            val strings = Gson().fromJson(
                jsonObject["supportMode"],
                Array<String>::class.java
            )
            Log.i(TAG, "selectClientMode: 获取到客户端支持的模式：${Arrays.toString(strings)}")
            // 不使用Supplier会: stream has already been operated upon or closed
            val streamSupplier =
                Supplier {
                    Stream.of(
                        *strings
                    )
                }

            // 支持Object传输就用 2
            return if (streamSupplier.get().anyMatch { n: String -> (n == "2") }) {
                Log.i(TAG, "selectClientMode: 客户端支持Object传输，使用模式2。")
                "2"
            } else if (streamSupplier.get().anyMatch { n: String -> (n == "1") }) {
                Log.i(TAG, "selectClientMode: 客户端支持Object传输，使用模式1。")
                "1"
            } else {
                throw IOException("客户端支持传输模式不支持。")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
}