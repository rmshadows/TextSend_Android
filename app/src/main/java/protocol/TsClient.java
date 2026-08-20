package protocol;

import java.io.IOException;
import java.net.Socket;

/** 当前客户端会话（Android 单连接） */
public final class TsClient {
    private TsClient() {
    }

    public static volatile TsPeer peer;
    public static volatile Socket socket;
    public static volatile boolean abort;

    public static void close() {
        abort = true;
        TsPeer p = peer;
        peer = null;
        if (p != null) {
            p.close();
        }
        Socket s = socket;
        socket = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }
}
