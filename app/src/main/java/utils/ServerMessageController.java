package utils;

import android.os.Build;

import cn.rmshadows.textsend.MainActivity;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * 此类仅对应一个连接！
 */
public class ServerMessageController implements Runnable {
    final static String FB_MSG = MainActivity.FB_MSG;
    final static int MSG_LEN = MainActivity.MSG_LEN;
    final static String SERVER_ID = MainActivity.SERVER_ID;

    // 实例私有属性
    private Socket socket;
    private int transmissionModeSet = -1;
    private String clientId;
    // 连接状态 -1 未连接 0:连接 分配ID中 1:分配完ID 分配模式中 2:正常通信 -2:断开连接
    private int connectionStat = -1;
    // 客户端IP
    public final String clientIP;

    public Socket getSocket() {
        return socket;
    }

    public void setSocket(Socket socket) {
        this.socket = socket;
    }

    public int getTransmissionModeSet() {
        return transmissionModeSet;
    }

    public void setTransmissionModeSet(int transmissionModeSet) {
        this.transmissionModeSet = transmissionModeSet;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public int getConnectionStat() {
        return connectionStat;
    }

    public void setConnectionStat(int connectionStat) {
        this.connectionStat = connectionStat;
        if (connectionStat == 0 || connectionStat == 1 || connectionStat == -1) { // -1的情况用不到
            // 连接初始化、连接分配id中都是使用JSON
            setTransmissionModeSet(1);
        }
    }

    /**
     * 断开当前客户端
     */
    public void closeCurrentClientSocket() {
        try {
            // 设置断开 会结束消息监听
            setConnectionStat(-2);
            // 断开Socket
            socket.close();
            // 移除列表
            SocketDeliver.socketList.remove(this);
            System.out.printf("主动断开 用户 %s (%s) 。%n",
                    clientIP,
                    getClientId());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 仅向当前客户端发送信息 (实例方法)
     *
     * @param message 信息
     */
    public void sendMessage(Message message) {
        new Thread(new ServerMessageTransmitter(this, message)).start();
    }

    // 构造方法
    public ServerMessageController(Socket clientSocket) {
        this.socket = clientSocket;
        // 客户端IP
        clientIP = socket.getInetAddress().getHostAddress();
        // 生成客户端ID
        setClientId(String.valueOf(clientSocket.hashCode()));
        // 状态设为连接 分配ID等事情是打开消息监听后的事
        setConnectionStat(0);
    }

    /**
     * 反馈核对信息到移动端，确保消息接收到 但不保证无误
     */
    public void messageFeedBack() {
        System.out.println("发送反馈信息到客户端。");
        sendMessage(new Message(ServerMessageController.SERVER_ID, null, ServerMessageController.MSG_LEN, ServerMessageController.FB_MSG));
    }

    @Override
    public void run() {
        // 启动监听器
        Thread receiver = new Thread(new ServerMessageReceiver(this));
        receiver.start();
        // 发送客户端ID给客户端
        System.out.println("ID -> " + clientIP + "(" + getClientId() + ")");
        sendMessage(new Message(SERVER_ID, null, MSG_LEN, getClientId()));
        try {
            // 等待监听器结束
            receiver.join();
            // 关闭客户端连接
            closeCurrentClientSocket();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

/**
 * 服务端发送Msg到客户端
 *
 * @author jessie
 */
class ServerMessageTransmitter implements Runnable {
    private final Message msg;
    private BufferedOutputStream bufferedOutputStream = null;
    private ObjectOutputStream objectOutputStream = null;
    ServerMessageController serverMessageController;
    private final int transmitterTransmissionMode;

    public ServerMessageTransmitter(ServerMessageController serverMessageController, Message m) {
        Socket socket = serverMessageController.getSocket();
        this.msg = m;
        this.serverMessageController = serverMessageController;
        this.transmitterTransmissionMode = serverMessageController.getTransmissionModeSet();
        try {
            // 1:JSON 2:Object
            if (transmitterTransmissionMode == 1) {
                bufferedOutputStream = new BufferedOutputStream(socket.getOutputStream());
            } else if (transmitterTransmissionMode == 2) {
                objectOutputStream = new ObjectOutputStream(socket.getOutputStream());
            } else {
                throw new IOException("获取流失败: 请检查模式设定以及连接状态。");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void run() {
        try {
            // 先获取加密的GSM
            GsonMessage egm = GMToolsUtil.MessageToEncrypptedGsonMessage(msg);
            if (transmitterTransmissionMode == 0 || transmitterTransmissionMode == 1) {
                // JSON传输
                System.out.println("Send to " + serverMessageController.clientIP + " (JSON)\n");
                // 将GSM对象读取成文字传输
                int read;
                byte[] buf = new byte[1024];
                BufferedInputStream bufferedInputStream = new BufferedInputStream(new ByteArrayInputStream(egm.toString().getBytes(StandardCharsets.UTF_8)));
                while ((read = bufferedInputStream.read(buf)) != -1) {
                    bufferedOutputStream.write(buf, 0, read);
                }
                bufferedOutputStream.flush();
                // 会关闭输入流（GSM对象读取完了就关闭），不会关闭输出流(会关闭Socket)
                bufferedInputStream.close();
            } else if (transmitterTransmissionMode == 2) {
                System.out.println("Send to " + serverMessageController.clientIP + " (Object)\n");
                // 已经序列化GSM
                objectOutputStream.writeObject(egm);
                objectOutputStream.flush();
            } else {
                throw new IOException("传输模式设置有误: Modeset error: " + transmitterTransmissionMode);
            }
        } catch (Exception e) {
            // 发送出错会断开连接
            e.printStackTrace();
            serverMessageController.closeCurrentClientSocket();
        }
    }
}

/**
 * 服务端接收客户端信息
 *
 * @author jessie
 */
class ServerMessageReceiver implements Runnable {
    private final ServerMessageController serverMessageController;
    private ObjectInputStream objectInputStream = null;
    private BufferedInputStream bufferedInputStream = null;
    private final int receiverTransmissionMode;

    public ServerMessageReceiver(ServerMessageController serverMessageController) {
        this.serverMessageController = serverMessageController;
        receiverTransmissionMode = serverMessageController.getTransmissionModeSet();
        Socket socket = serverMessageController.getSocket();
        try {
            if (receiverTransmissionMode == 1) {
                bufferedInputStream = new BufferedInputStream(socket.getInputStream());
            } else if (receiverTransmissionMode == 2) {
                objectInputStream = new ObjectInputStream(socket.getInputStream());
            } else {
                throw new IOException("ServerMessageReceiver modeSet param error.");
            }
        } catch (Exception e) {
            e.printStackTrace();
            // 监听出错也会关闭Socket
            serverMessageController.closeCurrentClientSocket();
        }
    }

    @Override
    public void run() {
        try {
            if (receiverTransmissionMode == 1) {
                // 接收消息
                // 如果是-1说明连接已经断了
                byte[] readBuf = new byte[1024];
                int readLength;
                StringBuilder chunk = new StringBuilder();
                while ((readLength = bufferedInputStream.read(readBuf)) != -1) {
                    // 如果服务停止
                    if (serverMessageController.getConnectionStat() == -2 && !MainActivity.isServerRunning()) {
                        break;
                    }
                    String read = new String(readBuf, 0, readLength, StandardCharsets.UTF_8);
                    chunk.append(read);
                    // 读取到JSON末尾
                    if (read.endsWith("}")) {
                        System.out.println("Received chunk: " + chunk);
                        // 这里开始处理
                        GsonMessage egm = GMToolsUtil.JSONtoGsonMessage(String.valueOf(chunk));
                        // 解密后的信息
                        GsonMessage cgm = MessageCrypto.gsonMessageDecrypt(egm);
                        if (serverMessageController.getConnectionStat() == 0) {
                            // 获取客户端支持的模式
                            if (cgm != null && Objects.equals(cgm.id(), serverMessageController.getClientId())) {// 客户端发送的才接受
                                // Notes: {"id":"553126963","data":"","notes":"SUPPORT-{"supportMode":[1]}"}
                                String[] ts = cgm.notes().split("-");
                                // 如果是SUPPORT开头
                                if (Objects.equals(ts[0], "SUPPORT")) {
                                    // 读取客户端发送的JSON {"supportMode":[1]}
                                    // 发送决定后的传输模式 格式： "CONFIRM-" + clientMode"
                                    String selectedMode = selectClientMode(ts[1]);
                                    String allocationMode = "CONFIRM-" + selectedMode;
                                    if (selectedMode != null) {
                                        serverMessageController.setTransmissionModeSet(Integer.parseInt(selectedMode));
                                    } else {
                                        throw new Exception("客户端支持传输模式不支持，连接配置失败。");
                                    }
                                    // 发送决定后的传输模式
                                    serverMessageController.sendMessage(new Message(MainActivity.SERVER_ID, null, MainActivity.MSG_LEN, allocationMode));
                                    // 收到客户端发送的模式清单，说明客户端接受了ID请求，状态直接0变为2。 注意：直接进入接受传输模式交流
                                    serverMessageController.setConnectionStat(2);
                                    System.err.printf("用户 %s (%s) 已上线。%n",
                                            serverMessageController.clientIP,
                                            serverMessageController.getClientId());
                                } else {
                                    System.out.println("Drop id message (on get support mode :support mode error.) : " + cgm);
                                }
                            } else {
                                System.out.println("Drop id message (on get support mode :id wrong) : " + cgm);
                            }
                        } else {
                            if (cgm != null) {
                                // 客户端发送的才接受
                                if (Objects.equals(cgm.id(), serverMessageController.getClientId())) {
                                    if (cgm.notes().equals(ServerMessageController.FB_MSG)) {
                                        // 处理反馈信息
                                        System.out.println("客户端收到了消息。");
                                        MainActivity.cleanTextArea();
                                    } else {
                                        StringBuilder text = new StringBuilder();
                                        for (String c : cgm.data()) {
                                            text.append(c);
                                        }
                                        // 反馈客户端 注意：仅代表服务端收到信息
                                        serverMessageController.messageFeedBack();
                                        System.out.println("Received: " + serverMessageController.clientIP
                                                +"("+ serverMessageController.getClientId() + ") <- " + text);
                                        copyToClickboard(text.toString());
                                    }
                                } else {
                                    // 丢弃的常规通讯信息
                                    System.out.println("Drop id message (json mode) : " + cgm);
                                }
                            }
                        }
                        // reset chunk
                        chunk = new StringBuilder();
                    }
                }
                System.out.println("Socket has ended.");
                ClientMessageController.connectionStat = -1;
                MainActivity.isClientConnected = false;
            } else if (receiverTransmissionMode == 2) {
                // 传输对象 传输对象的时候已经进入正常通信了
                // -2 表示连接断开了 只有服务在运行、客户端没断开才会继续监听
                while (serverMessageController.getConnectionStat() != -2 && MainActivity.isServerRunning()) {
                    // 断开操作在MainActivity中实现 这里已经解密成明文GM了
                    GsonMessage cgm = MessageCrypto.gsonMessageDecrypt((GsonMessage) objectInputStream.readObject());
                    if (cgm != null) {
                        // 判断客户端ID
                        if (Objects.equals(cgm.id(), serverMessageController.getClientId())) {
                            // 如果是客户端的反馈信息
                            if (cgm.notes().equals(ServerMessageController.FB_MSG)) {
                                // 处理反馈信息
                                System.out.println("客户端收到了消息。");
                                MainActivity.cleanTextArea();
                            } else {
                                StringBuilder text = new StringBuilder();
                                for (String c : cgm.data()) {
                                    text.append(c);
                                }
                                // 反馈服务器
                                serverMessageController.messageFeedBack();
                                System.out.println("Received: " + serverMessageController.clientIP
                                        +"("+ serverMessageController.getClientId() + ") <- " + text);
                                copyToClickboard(text.toString());
                            }
                        }
                    }
                }
            } else {
                throw new IOException("Modeset error.");
            }
        } catch (Exception e) {
            // 出错断开当前连接
            e.printStackTrace();
            // 直接设置状态-2 会在finally中结束当前Socket
            serverMessageController.setConnectionStat(-2);
        } finally {
            // 状态为-2 且服务端停止运行
            if (serverMessageController.getConnectionStat() == -2 && !MainActivity.isServerRunning()) {
                serverMessageController.closeCurrentClientSocket();
            }
        }
    }

    /**
     * 选择客户端模式 Android
     *
     * @param supportModejson JSON {"supportMode":[1, 2, 3]}
     */
    private String selectClientMode(String supportModejson) {
        try {
            JsonElement jsonElement = JsonParser.parseString(supportModejson);
            JsonObject jsonObject = jsonElement.getAsJsonObject();
            // [1, 2]
            String[] strings = new Gson().fromJson(jsonObject.get("supportMode"), String[].class);
            System.err.println("获取到客户端支持的模式：" + Arrays.toString(strings));
            // 支持Object传输就用 2
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // android 7
                // 不使用Supplier会: stream has already been operated upon or closed
                Supplier<Stream<String>> streamSupplier = () -> Stream.of(strings);
                if (streamSupplier.get().anyMatch(n -> n.equals("2"))) {
                    System.out.println("客户端支持Object传输，使用模式2。");
                    return "2";
                } else if (streamSupplier.get().anyMatch(n -> n.equals("1"))) {
                    System.out.println("客户端支持Object传输，使用模式1。");
                    return "1";
                } else {
                    throw new IOException("客户端支持传输模式不支持。");
                }
            }else {
                if (arrayFind(strings, "2")) {
                    System.out.println("客户端支持Object传输，使用模式2。");
                    return "2";
                } else if (arrayFind(strings, "1")) {
                    System.out.println("客户端支持Object传输，使用模式1。");
                    return "1";
                } else {
                    throw new IOException("客户端支持传输模式不支持。");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Android < 24
     * @param strings 数组
     * @param s 匹配
     */
    private boolean arrayFind(String[] strings, String s){
        for (String string : strings) {
            if (string.equals(s)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 复制收到的消息到剪贴板
     *
     * @param text 消息
     */
    private static void copyToClickboard(String text) {
        MainActivity.copyToClickboardAndroid(text);
        System.out.println("已复制到剪辑板。");
    }
}
