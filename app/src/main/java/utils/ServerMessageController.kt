package utils

import com.google.gson.Gson
import com.google.gson.JsonParser
import utils.GMToolsUtil.JSONtoGsonMessage
import utils.GMToolsUtil.MessageToEncrypptedGsonMessage
import utils.MessageCrypto.gsonMessageDecrypt
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Arrays
import java.util.function.Supplier
import java.util.stream.Stream

/**
 * 此类仅对应一个连接！
 */
class ServerMessageController( // 实例私有属性
    var socket: Socket
) : Runnable {
    var transmissionModeSet = -1
    var clientId: String

    // 连接状态 -1 未连接 0:连接 分配ID中 1:分配完ID 分配模式中 2:正常通信 -2:断开连接
    var connectionStat = -1
        set(connectionStat) {
            field = connectionStat
            if (connectionStat == 0 || connectionStat == 1 || connectionStat == -1) { // -1的情况用不到
                // 连接初始化、连接分配id中都是使用JSON
                transmissionModeSet = 1
            }
        }

    // 客户端IP
    val clientIP: String

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
            System.out.printf(
                "主动断开 用户 %s (%s) 。%n",
                clientIP,
                clientId
            )
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
        clientIP = socket.inetAddress.hostAddress
        // 生成客户端ID
        clientId = socket.hashCode().toString()
        // 状态设为连接 分配ID等事情是打开消息监听后的事
        connectionStat = 0
    }

    /**
     * 反馈核对信息到移动端，确保消息接收到 但不保证无误
     */
    fun messageFeedBack() {
        println("发送反馈信息到客户端。")
        sendMessage(Message(SERVER_ID, null, MSG_LEN, FB_MSG))
    }

    override fun run() {
        // 启动监听器
        val receiver = Thread(ServerMessageReceiver(this))
        receiver.start()
        // 发送客户端ID给客户端
        println("ID -> " + clientIP + "(" + clientId + ")")
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
        val FB_MSG: String = TextSendMain.FB_MSG
        val MSG_LEN: Int = TextSendMain.MSG_LEN
        val SERVER_ID: String = TextSendMain.SERVER_ID
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
    private var objectOutputStream: ObjectOutputStream? = null
    var serverMessageController: ServerMessageController
    private val transmitterTransmissionMode: Int

    init {
        val socket = serverMessageController.socket
        msg = m
        this.serverMessageController = serverMessageController
        transmitterTransmissionMode = serverMessageController.transmissionModeSet
        try {
            // 1:JSON 2:Object
            if (transmitterTransmissionMode == 1) {
                bufferedOutputStream = BufferedOutputStream(socket.getOutputStream())
            } else if (transmitterTransmissionMode == 2) {
                objectOutputStream = ObjectOutputStream(socket.getOutputStream())
            } else {
                throw IOException("获取流失败: 请检查模式设定以及连接状态。")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun run() {
        try {
            // 先获取加密的GSM
            val egm = MessageToEncrypptedGsonMessage(msg!!)
            if (transmitterTransmissionMode == 0 || transmitterTransmissionMode == 1) {
                // JSON传输
                println("Send to " + serverMessageController.clientIP + " (JSON)\n")
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
                println("Send to " + serverMessageController.clientIP + " (Object)\n")
                // 已经序列化GSM
                objectOutputStream!!.writeObject(egm)
                objectOutputStream!!.flush()
            } else {
                throw IOException("传输模式设置有误: Modeset error: $transmitterTransmissionMode")
            }
        } catch (e: Exception) {
            // 发送出错会断开连接
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
internal class ServerMessageReceiver(private val serverMessageController: ServerMessageController) :
    Runnable {
    private var objectInputStream: ObjectInputStream? = null
    private var bufferedInputStream: BufferedInputStream? = null
    private val receiverTransmissionMode: Int

    init {
        receiverTransmissionMode = serverMessageController.transmissionModeSet
        val socket = serverMessageController.socket
        try {
            if (receiverTransmissionMode == 1) {
                bufferedInputStream = BufferedInputStream(socket.getInputStream())
            } else if (receiverTransmissionMode == 2) {
                objectInputStream = ObjectInputStream(socket.getInputStream())
            } else {
                throw IOException("ServerMessageReceiver modeSet param error.")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // 监听出错也会关闭Socket
            serverMessageController.closeCurrentClientSocket()
        }
    }

    override fun run() {
        try {
            if (receiverTransmissionMode == 1) {
                // 接收消息
                // 如果是-1说明连接已经断了
                val readBuf = ByteArray(1024)
                var readLength: Int
                var chunk = StringBuilder()
                while (bufferedInputStream!!.read(readBuf).also { readLength = it } != -1) {
                    // 如果服务停止
                    if (serverMessageController.connectionStat == -2 && !TextSendMain.isServerRunning()) {
                        break
                    }
                    val read = kotlin.String(readBuf, 0, readLength, StandardCharsets.UTF_8)
                    chunk.append(read)
                    // 读取到JSON末尾
                    if (read.endsWith("}")) {
                        println("Received chunk: $chunk")
                        // 这里开始处理
                        val egm = JSONtoGsonMessage(chunk.toString())
                        // 解密后的信息
                        val cgm = gsonMessageDecrypt(egm)
                        if (serverMessageController.connectionStat == 0) {
                            // 获取客户端支持的模式
                            if (cgm != null && (cgm.id == serverMessageController.clientId)) { // 客户端发送的才接受
                                // Notes: {"id":"553126963","data":"","notes":"SUPPORT-{"supportMode":[1]}"}
                                val ts =
                                    cgm.notes!!.split("-".toRegex()).dropLastWhile { it.isEmpty() }
                                        .toTypedArray()
                                // 如果是SUPPORT开头
                                if ((ts.get(0) == "SUPPORT")) {
                                    // 读取客户端发送的JSON {"supportMode":[1]}
                                    // 发送决定后的传输模式 格式： "CONFIRM-" + clientMode"
                                    val selectedMode = selectClientMode(ts[1])
                                    val allocationMode = "CONFIRM-$selectedMode"
                                    if (selectedMode != null) {
                                        serverMessageController.transmissionModeSet =
                                            selectedMode.toInt()
                                    } else {
                                        throw Exception("客户端支持传输模式不支持，连接配置失败。")
                                    }
                                    // 发送决定后的传输模式
                                    serverMessageController.sendMessage(
                                        Message(
                                            TextSendMain.SERVER_ID,
                                            null,
                                            TextSendMain.MSG_LEN,
                                            allocationMode
                                        )
                                    )
                                    // 收到客户端发送的模式清单，说明客户端接受了ID请求，状态直接0变为2。 注意：直接进入接受传输模式交流
                                    serverMessageController.connectionStat = 2
                                    System.err.printf(
                                        "用户 %s (%s) 已上线。%n",
                                        serverMessageController.clientIP,
                                        serverMessageController.clientId
                                    )
                                } else {
                                    println("Drop id message (on get support mode :support mode error.) : $cgm")
                                }
                            } else {
                                println("Drop id message (on get support mode :id wrong) : $cgm")
                            }
                        } else {
                            if (cgm != null) {
                                // 客户端发送的才接受
                                if ((cgm.id == serverMessageController.clientId)) {
                                    if (cgm.notes == ServerMessageController.FB_MSG) {
                                        // 处理反馈信息
                                        println("客户端收到了消息。")
                                        TextSendMain.cleanTextArea()
                                    } else {
                                        val text = StringBuilder()
                                        for (c: String? in cgm.data!!) {
                                            text.append(c)
                                        }
                                        // 反馈客户端 注意：仅代表服务端收到信息
                                        serverMessageController.messageFeedBack()
                                        println(
                                            "Received: " + serverMessageController.clientIP
                                                    + "(" + serverMessageController.clientId + ") <- " + text
                                        )
                                        copyToClickboard(text.toString())
                                        pasteReceivedMessage()
                                    }
                                } else {
                                    // 丢弃的常规通讯信息
                                    println("Drop id message (json mode) : $cgm")
                                }
                            }
                        }
                        // reset chunk
                        chunk = StringBuilder()
                    }
                }
                println("Socket has ended.")
                ClientMessageController.connectionStat = -1
                TextSendMain.isClientConnected = false
            } else if (receiverTransmissionMode == 2) {
                // 传输对象 传输对象的时候已经进入正常通信了
                // -2 表示连接断开了 只有服务在运行、客户端没断开才会继续监听
                while (serverMessageController.connectionStat != -2 && TextSendMain.isServerRunning()) {
                    // 断开操作在TextSendMain中实现 这里已经解密成明文GM了
                    val cgm = gsonMessageDecrypt((objectInputStream!!.readObject() as GsonMessage))
                    if (cgm != null) {
                        // 判断客户端ID
                        if ((cgm.id == serverMessageController.clientId)) {
                            // 如果是客户端的反馈信息
                            if ((cgm.notes == ServerMessageController.FB_MSG)) {
                                // 处理反馈信息
                                println("客户端收到了消息。")
                                TextSendMain.cleanTextArea()
                            } else {
                                val text = StringBuilder()
                                for (c: String? in cgm.data!!) {
                                    text.append(c)
                                }
                                // 反馈服务器
                                serverMessageController.messageFeedBack()
                                println(
                                    ("Received: " + serverMessageController.clientIP
                                            + "(" + serverMessageController.clientId + ") <- " + text)
                                )
                                copyToClickboard(text.toString())
                                pasteReceivedMessage()
                            }
                        }
                    }
                }
            } else {
                throw IOException("Modeset error.")
            }
        } catch (e: Exception) {
            // 出错断开当前连接
            e.printStackTrace()
            // 直接设置状态-2 会在finally中结束当前Socket
            serverMessageController.connectionStat = -2
        } finally {
            // 状态为-2 且服务端停止运行
            if (serverMessageController.connectionStat == -2 && !TextSendMain.isServerRunning()) {
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
            System.err.println("获取到客户端支持的模式：" + Arrays.toString(strings))
            // 不使用Supplier会: stream has already been operated upon or closed
            val streamSupplier =
                Supplier {
                    Stream.of(
                        *strings
                    )
                }

            // 支持Object传输就用 2
            if (streamSupplier.get().anyMatch({ n: String -> (n == "2") })) {
                println("客户端支持Object传输，使用模式2。")
                return "2"
            } else if (streamSupplier.get().anyMatch({ n: String -> (n == "1") })) {
                println("客户端支持Object传输，使用模式1。")
                return "1"
            } else {
                throw IOException("客户端支持传输模式不支持。")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    /**
     * 模拟键盘-粘贴 粘贴收到的文字
     */
    private fun pasteReceivedMessage() {
        try {
            val robot: java.awt.Robot = java.awt.Robot()
            robot.delay(400)
            robot.keyPress(java.awt.event.KeyEvent.VK_CONTROL)
            robot.delay(100)
            robot.keyPress(java.awt.event.KeyEvent.VK_V)
            robot.delay(100)
            robot.keyRelease(java.awt.event.KeyEvent.VK_CONTROL)
            robot.delay(100)
            robot.keyRelease(java.awt.event.KeyEvent.VK_V)
            robot.delay(100)
        } catch (e: Exception) {
            e.printStackTrace()
            println("ROBOT ERROR")
        }
    }

    companion object {
        /**
         * 复制收到的消息到剪贴板
         *
         * @param text 消息
         */
        private fun copyToClickboard(text: String) {
            var ret = ""
            val sysClip: java.awt.datatransfer.Clipboard =
                java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
            // 获取剪切板中的内容
            val clipTf: java.awt.datatransfer.Transferable = sysClip.getContents(null)
            if (clipTf != null) {
                // 检查内容是否是文本类型
                if (clipTf.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.stringFlavor)) {
                    try {
                        ret = clipTf.getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            if (ret != text) {
                val clipboard: java.awt.datatransfer.Clipboard =
                    java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                // 封装文本内容
                val trans: java.awt.datatransfer.Transferable =
                    java.awt.datatransfer.StringSelection(text)
                // 把文本内容设置到系统剪贴板
                clipboard.setContents(trans, null)
            }
            println("已复制到剪辑板。")
        }
    }
}