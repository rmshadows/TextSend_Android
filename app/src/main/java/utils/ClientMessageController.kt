package utils

import android.util.Log
import cn.rmshadows.textsend.viewmodels.TextsendViewModel
import utils.Constant.Companion.TAG
import utils.GMToolsUtil.JSONtoGsonMessage
import utils.GMToolsUtil.MessageToEncrypptedGsonMessage
import utils.GMToolsUtil.bendsWith
import utils.GMToolsUtil.bytes2GsonMessage
import utils.GMToolsUtil.mergeArrays
import utils.MessageCrypto.gsonMessageDecrypt
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.Socket
import java.nio.charset.StandardCharsets


class ClientMessageController(
    private val socket: Socket,
    private val tsViewModel: TextsendViewModel
) : Runnable {

    override fun run() {
        // 初始化成功必定连接成功
        connectionStat = 0
        // 分配socket
        clientSocket = socket
        Thread(ClientMessageReceiver(socket, tsViewModel)).start()
    }

    companion object {
        // 支持1 JSON(文本) 2 Object(直接传输GsonMessage)  SUPPORT-{"supportMode":[1, 2]}
        const val SUPPORT_MODE = "{\"supportMode\":[1, 2]}"

        // 连接状态 -1:初始化 0:连接成功准备接受ID 1:ID接受成功，准备接受模式（已经将支持的模式发出）2:收到服务器返回的模式 进入正常通信
        var connectionStat = -1

        // 发送器传输模式（服务器传回来的）传输模式 1:JSON 2:Java Class Object(默认)
        var transmissionModeSet = -1

        // 服务器分配的ID
        var clientId: String? = null

        // Socket
        var clientSocket: Socket? = null

        /**
         * PC端主动发送信息到移动端的方法
         */
        fun sendMessageToServer(m: Message?, tsViewModel: TextsendViewModel) {
            // 初始化就用JSON发送
            if (connectionStat == 0 || connectionStat == 1) {
                Thread(clientSocket?.let {
                    ClientMessageTransmitter(
                        it,
                        m,
                        1,
                        tsViewModel
                    )
                }).start()
            } else {
                // 根据模式来选择
                Thread(
                    clientSocket?.let {
                        ClientMessageTransmitter(
                            it,
                            m,
                            transmissionModeSet,
                            tsViewModel
                        )
                    }
                ).start()
            }
        }

        fun closeClientSocket(tsViewModel: TextsendViewModel) {
            try {
                clientSocket?.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
            tsViewModel.update(null, null, false, null, null, null, -1)
            clientSocket = null
        }

        /**
         * 复制收到的消息到剪贴板
         */
        fun copyToClipboard(text: String, tsViewModel: TextsendViewModel) {
            tsViewModel.update(null, null, null, null, null, text, null)
        }

        // 反馈消息到服务端
        fun messageFeedBack(tsViewModel: TextsendViewModel) {
            Log.i(TAG, "messageFeedBack: 客户端发送反馈信息")
            sendMessageToServer(
                Message(
                    clientId,
                    null,
                    Constant.MSG_LEN,
                    Constant.FB_MSG
                ),
                tsViewModel
            )
        }
    }
}

/**
 * 客户端发送Msg到服务端
 *
 * @author jessie
 */
internal class ClientMessageTransmitter(
    socket: Socket,
    private val msg: Message?,
    private val transmitterTransmissionMode: Int,
    private val tsViewModel: TextsendViewModel
) :
    Runnable {
    private var bufferedOutputStream: BufferedOutputStream? = null

    init {
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
            msg.printData()
            if (transmitterTransmissionMode == 0 || transmitterTransmissionMode == 1) {
                // JSON传输
                if (msg.notes == ServerMessageController.FB_MSG) {
                    Log.i(TAG, "Log: 【发送反馈】JSON ==> ：$egm")
                } else {
                    Log.i(TAG, "Log: 【发送】JSON ==> ：$egm")
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
                    Log.i(TAG, "Log: 【发送反馈】OBJECT ==> ：$egm")
                } else {
                    Log.i(TAG, "Log: 【发送】OBJECT ==> ：$egm")
                }
                // 将对象序列化为字节数组并分块发送
                val begm: ByteArray? = GMToolsUtil.gsonMessage2bytes(egm)
                if (begm != null) {
                    // 将GSM对象读取成byte传输
                    bufferedOutputStream!!.write(begm)
                    bufferedOutputStream!!.flush()
                }
            } else {
                throw IOException("传输模式设置有误: Mode set error: $transmitterTransmissionMode")
            }
        } catch (e: java.lang.Exception) {
            Log.i(TAG, "ClientMessageTransmitterError: ")
            e.printStackTrace()
            tsViewModel.update(null, null, false, null, null, null, null)
        }
    }
}

/**
 * 客户端接收服务端信息
 * 流程：
 * 1.首先接受服务端给的ID
 * 2.发送自己支持的服务
 * 3.接受服务端分配模式
 * 4.证常通讯
 *
 * @author jessie
 */
internal class ClientMessageReceiver(
    socket: Socket,
    private val tsViewModel: TextsendViewModel
) : Runnable {
    private var bufferedInputStream: BufferedInputStream? = null

    // 接收器传输模式
    private var receiverTransmissionMode = -1
    private var count = 0

    init {
        try {
            // 开始都是用JSON
            if (ClientMessageController.transmissionModeSet == 1 || connectionStat == 0 || connectionStat == 1 || connectionStat == -1) {
                bufferedInputStream = BufferedInputStream(socket.getInputStream())
                receiverTransmissionMode = 1
            } else {
                throw IOException("Mode Set Error.")
            }
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
        }
    }

    private val connectionStat: Int
        // 获取连接状态
        get() = ClientMessageController.connectionStat


    override fun run() {
        try {
            while (tsViewModel.uiState.value.isClientConnected) {
                if (receiverTransmissionMode == 1) {
                    // 重复赋值(暂未处理)
                    ClientMessageController.connectionStat = 0
                    ClientMessageController.transmissionModeSet = 1
                    // 接收消息
                    // 如果是-1说明连接已经断了
                    val readBuf = ByteArray(1024)
                    var readLength: Int
                    var chunk = StringBuilder()
                    while ((bufferedInputStream!!.read(readBuf)
                            .also {
                                readLength = it
                            } != -1
                                ) && receiverTransmissionMode == 1
                    ) {
                        if (!tsViewModel.uiState.value.isClientConnected) {
                            break
                        }
                        val read = String(readBuf, 0, readLength, StandardCharsets.UTF_8)
                        chunk.append(read)
                        // 读取到JSON末尾
                        if (read.endsWith("}")) {
                            Log.i(TAG, "Log: 【接收】JSON <== : $chunk")
                            // 这里开始处理
                            val egm = JSONtoGsonMessage(chunk.toString())
                            // 解密后的信息
                            val cgm = gsonMessageDecrypt(egm)
                            if (connectionStat == 0) {
                                // 获取ID
                                // 服务器发送的才接受
                                if (cgm != null && cgm.id == Constant.SERVER_ID) {
                                    ClientMessageController.clientId = cgm.notes
                                    Log.i(TAG, "Log: 【接收】获取到服务器分配的ID：" + ClientMessageController.clientId)
                                    // 发送支持的模式 格式：SUPPORT-{"supportMode":[1]}
                                    val supportMode =
                                        "SUPPORT-" + ClientMessageController.SUPPORT_MODE
                                    ClientMessageController.sendMessageToServer(
                                        Message(
                                            ClientMessageController.clientId,
                                            "",
                                            Constant.MSG_LEN,
                                            supportMode
                                        ),
                                        tsViewModel
                                    )
                                    // 进入接受传输模式
                                    ClientMessageController.connectionStat = 1
                                } else {
                                    Log.i(TAG, "Log: 【丢弃】Drop id message (on get id) : $cgm")
                                }
                            } else if (connectionStat == 1) {
                                // 开始接受服务器发过来的传输模式
                                var tsp: Array<String>
                                if (cgm != null) {
                                    tsp = cgm.notes!!.split("-".toRegex())
                                        .dropLastWhile { it.isEmpty() }.toTypedArray()
                                    // 服务器发送的才接受 {"id":"-200","data":"","notes":"CONFIRM-1"}
                                    // 判断服务器ID 且CONFIRM开头
                                    if (cgm.id == Constant.SERVER_ID && tsp[0] == "CONFIRM") {
                                        // 设置客户端传输模式
                                        ClientMessageController.transmissionModeSet = tsp[1].toInt()
                                        // 设置客户端接收模式
                                        receiverTransmissionMode =
                                            ClientMessageController.transmissionModeSet
                                        Log.i(TAG, "Log: 【接收】获取到服务器传输模式：" + ClientMessageController.transmissionModeSet)
                                        // 进入通讯模式
                                        ClientMessageController.connectionStat = 2
                                        // 不仅要设置，还要主动断开，要不会马上进入下个JSON读取循环
                                        if(receiverTransmissionMode == 2){
                                            break
                                        }
                                    } else {
                                        // 丢弃的信息
                                        Log.i(TAG, "Log: 【丢弃】Drop id message (on get modeSet) : $cgm")
                                    }
                                }
                            } else {
                                if (cgm != null) {
                                    // 服务器发送的才接受
                                    if (cgm.id == Constant.SERVER_ID) {
                                        if (cgm.notes == Constant.FB_MSG) {
                                            // 处理反馈信息
                                            Log.i(TAG, "Log: 【接收反馈】服务器收到了消息。")
                                            tsViewModel.cleanEditText()
                                        } else {
                                            val text = StringBuilder()
                                            for (c in cgm.data!!) {
                                                text.append(c)
                                            }
                                            // 反馈服务器 注意：仅代表客户端收到信息
                                            ClientMessageController.messageFeedBack(tsViewModel)
                                            Log.i(TAG, "Log: 【接收】JSON <== 服务器的消息：$text")
                                            ClientMessageController.copyToClipboard(
                                                text.toString(),
                                                tsViewModel
                                            )
                                        }
                                    } else {
                                        // 丢弃的常规通讯信息
                                        Log.i(TAG, "Log: 【丢弃】Drop id message (json mode) : $cgm")
                                    }
                                }
                            }
                            // reset chunk
                            chunk = StringBuilder()
                        }
                    }
                } else if (receiverTransmissionMode == 2) {
                    // 传输对象 传输对象的时候已经进入正常通信了
                    // -2 表示连接断开了 只有服务在运行、客户端没断开才会继续监听
                    // 断开操作在TextSendMain中实现 这里已经解密成明文GM了
                    // 读取对象字节数组并反序列化
                    var chunk: ByteArray? = null
                    val readBuf = ByteArray(1024)
                    var readLength: Int
                    while ((bufferedInputStream!!.read(readBuf).also { readLength = it } != -1)  && receiverTransmissionMode == 2 ) {
                        // 如果服务停止
                        if (!tsViewModel.uiState.value.isClientConnected) {
                            break
                        }
                        // 如果是第一次，就赋值
                        chunk = if (chunk == null) {
                            readBuf.copyOfRange(0, readLength)
                        } else {
                            mergeArrays(chunk, readBuf.copyOfRange(0, readLength))
                        }
                        // 检查是否到达了结束标记
                        if (bendsWith(chunk, Constant.endMarker)) {
                            // 到达了结束标记，停止读取
                            val egm = bytes2GsonMessage(chunk)
                            // 解密后的信息
                            val cgm = gsonMessageDecrypt(egm!!)
                            if (cgm != null) {
                                // 客户端发送的才接受
                                if (cgm.id == Constant.SERVER_ID) {
                                    if (cgm.notes == ServerMessageController.FB_MSG) {
                                        // 处理反馈信息
                                        Log.i(TAG, "Log: 【接收反馈】2:服务器收到了消息。")
                                        tsViewModel.cleanEditText()
                                    } else {
                                        val text = StringBuilder()
                                        for (c in cgm.data!!) {
                                            text.append(c)
                                        }
                                        // 反馈客户端 注意：仅代表服务端收到信息
                                        ClientMessageController.messageFeedBack(tsViewModel)
                                        Log.i(TAG, "Log: 【接收】OBJECT <== 服务器的消息：$text")
                                        ClientMessageController.copyToClipboard(
                                            text.toString(),
                                            tsViewModel
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
            ClientMessageController.connectionStat = -1
            tsViewModel.update(null, null, false, null, null, null, null)
        } catch (e: Exception) {
            Log.i(TAG, "ClientMessageReceiverError: ")
            e.printStackTrace()
            tsViewModel.update(null, null, false, null, null, null, null)
        }
    }

}