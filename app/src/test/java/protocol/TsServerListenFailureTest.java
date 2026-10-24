package protocol;

import org.junit.Test;

import java.net.BindException;
import java.net.SocketException;

import static org.junit.Assert.assertEquals;

public class TsServerListenFailureTest {
    @Test
    public void portInUse() {
        assertEquals("端口 54300 已被占用",
                TsServer.describeListenFailure(54300, new BindException("Address already in use")));
    }

    @Test
    public void errnoNotWrappedAsIoException() {
        assertEquals("端口 54300 已被占用",
                TsServer.describeListenFailure(54300,
                        new ErrnoException("bind failed: EADDRINUSE (Address already in use)")));
    }

    @Test
    public void permissionDenied() {
        assertEquals("没有权限监听端口 80",
                TsServer.describeListenFailure(80,
                        new BindException("bind failed: EACCES (Permission denied)")));
    }

    @Test
    public void portOutOfRange() {
        assertEquals("端口 70000 无效",
                TsServer.describeListenFailure(70000, new IllegalArgumentException("port out of range:70000")));
    }

    @Test
    public void otherIoFailure() {
        assertEquals("服务启动失败",
                TsServer.describeListenFailure(54300, new SocketException("Protocol family not supported")));
    }

    /** 类名以 ErrnoException 结尾，模拟系统没把它包成 IOException 的情况。 */
    static final class ErrnoException extends Exception {
        ErrnoException(String message) {
            super(message);
        }
    }
}
