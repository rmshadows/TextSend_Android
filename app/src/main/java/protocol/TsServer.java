package protocol;

import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * v1 服务端：accept + 多会话
 */
public final class TsServer implements Runnable {
    private static volatile TsServer instance;

    private final int port;
    private final int maxConnections;
    private final PairingMaterial material;
    private final IntConsumer onClientCount;
    private final Consumer<String> onText;
    /** 空串表示正常结束；非空是启动/监听失败原因 */
    private final Consumer<String> onListenEnded;
    private volatile FileIoCallback fileIo;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final List<TsPeer> peers = new ArrayList<>();
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final AuthLimiter authLimiter = new AuthLimiter();
    private ServerSocket serverSocket;

    public TsServer(int port, int maxConnections, PairingMaterial material, IntConsumer onClientCount,
                    Consumer<String> onText, Consumer<String> onListenEnded) {
        this.port = port;
        this.maxConnections = maxConnections;
        this.material = material;
        this.onClientCount = onClientCount;
        this.onText = onText;
        this.onListenEnded = onListenEnded;
        instance = this;
    }

    public PairingMaterial getMaterial() {
        return material;
    }

    public static void stopCurrent() {
        TsServer s = instance;
        if (s != null) {
            s.stop();
        }
    }

    /** @return 实际发出的会话数；服务未运行返回 -1 */
    public static int sendToAllCurrent(String text) {
        TsServer s = instance;
        if (s == null) {
            return -1;
        }
        return s.sendToAll(text);
    }

    public static int aliveCountCurrent() {
        TsServer s = instance;
        return s == null ? -1 : s.aliveCount();
    }

    public static boolean isRunningCurrent() {
        TsServer s = instance;
        return s != null && s.running.get();
    }

    public static void setFileIoCurrent(FileIoCallback cb) {
        TsServer s = instance;
        if (s == null) {
            return;
        }
        s.fileIo = cb;
        synchronized (s.peers) {
            for (TsPeer p : s.peers) {
                p.setFileIo(cb);
            }
        }
    }

    public static int sendFileToAllCurrent(Path path, BooleanSupplier cancelled) throws Exception {
        return sendFileToAllCurrent(path, cancelled, null, null, 0, 0);
    }

    public static int sendFileToAllCurrent(Path path, BooleanSupplier cancelled,
                                           String treeName, String relPath, int seq, int of)
            throws Exception {
        TsServer s = instance;
        if (s == null) {
            return -1;
        }
        return s.sendFileToAll(path, cancelled, treeName, relPath, seq, of);
    }

    public static int sendFileToAllCurrent(String name, long size, TsPeer.StreamOpener opener,
                                           BooleanSupplier cancelled, int seq, int of)
            throws Exception {
        return sendFileToAllCurrent(name, size, opener, cancelled, null, null, seq, of);
    }

    public static int sendFileToAllCurrent(String name, long size, TsPeer.StreamOpener opener,
                                           BooleanSupplier cancelled, String treeName, String relPath,
                                           int seq, int of)
            throws Exception {
        TsServer s = instance;
        if (s == null) {
            return -1;
        }
        return s.sendFileToAll(name, size, opener, cancelled, treeName, relPath, seq, of);
    }

    public static void cancelFilesCurrent() {
        TsServer s = instance;
        if (s != null) {
            s.cancelFiles();
        }
    }

    public static void prepareOutgoingCurrent() {
        TsServer s = instance;
        if (s != null) {
            s.prepareOutgoing();
        }
    }

    public void prepareOutgoing() {
        List<TsPeer> snap;
        synchronized (peers) {
            snap = new ArrayList<>(peers);
        }
        for (TsPeer p : snap) {
            p.prepareOutgoingSend();
        }
    }

    public void stop() {
        running.set(false);
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
        List<TsPeer> toClose;
        synchronized (peers) {
            toClose = new ArrayList<>(peers);
            peers.clear();
        }
        for (TsPeer p : toClose) {
            p.close();
        }
        pool.shutdownNow();
        if (instance == this) {
            instance = null;
        }
        notifyCount();
    }

    @Override
    public void run() {
        String listenError = "";
        try {
            ServerSocket ss = new ServerSocket();
            serverSocket = ss;
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(port), 50);
            System.out.println("Log: v1 server listen " + port + " PIN=" + material.pin
                    + "  (psk+pin 两种连接都接受)");
            while (running.get()) {
                Socket sock = ss.accept();
                String ip = sock.getInetAddress() == null ? "?" : sock.getInetAddress().getHostAddress();
                System.out.println("Log: 【接入】TCP " + ip + ":" + sock.getPort());
                if (!authLimiter.allow(ip)) {
                    System.out.println("Log: 【拒绝】" + ip + " PIN 失败次数过多，已锁定");
                    sock.close();
                    continue;
                }
                synchronized (peers) {
                    peers.removeIf(p -> !p.occupiesSlot());
                    if (peers.size() >= maxConnections) {
                        System.out.println("Log: 【拒绝】" + ip + " 已达最大连接 " + maxConnections);
                        sock.close();
                        continue;
                    }
                    TsPeer[] box = new TsPeer[1];
                    TsPeer peer = new TsPeer(sock, material, msg -> {
                    }, () -> {
                        authLimiter.ok(ip);
                        notifyCount();
                    }, () -> {
                        TsPeer self = box[0];
                        if (self != null && self.authRejected() && running.get()) {
                            authLimiter.fail(ip);
                        }
                        synchronized (peers) {
                            peers.removeIf(p -> !p.occupiesSlot());
                        }
                        notifyCount();
                    }, onText);
                    peer.setFileIo(fileIo);
                    box[0] = peer;
                    peers.add(peer);
                    pool.execute(peer);
                }
                notifyCount();
            }
        } catch (IOException e) {
            if (running.get()) {
                e.printStackTrace();
                if (e instanceof BindException) {
                    listenError = "端口 " + port + " 已被占用";
                } else {
                    listenError = "服务启动失败";
                }
            }
        } finally {
            stop();
            if (onListenEnded != null) {
                onListenEnded.accept(listenError);
            }
        }
    }

    public int sendToAll(String text) {
        List<TsPeer> snap;
        synchronized (peers) {
            snap = new ArrayList<>(peers);
        }
        int sent = 0;
        for (TsPeer p : snap) {
            if (!p.isAlive()) {
                continue;
            }
            try {
                p.sendText(text);
                sent++;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return sent;
    }

    public int aliveCount() {
        synchronized (peers) {
            return (int) peers.stream().filter(TsPeer::isAlive).count();
        }
    }

    public int sendFileToAll(Path path, BooleanSupplier cancelled, String treeName, String relPath,
                             int seq, int of) throws Exception {
        long size = path == null ? 0 : java.nio.file.Files.size(path);
        return sendToTargets(size, cancelled, (p, c) -> p.sendFile(path, c, treeName, relPath, seq, of));
    }

    public int sendFileToAll(String name, long size, TsPeer.StreamOpener opener,
                             BooleanSupplier cancelled, int seq, int of) throws Exception {
        return sendFileToAll(name, size, opener, cancelled, null, null, seq, of);
    }

    public int sendFileToAll(String name, long size, TsPeer.StreamOpener opener,
                             BooleanSupplier cancelled, String treeName, String relPath,
                             int seq, int of) throws Exception {
        return sendToTargets(size, cancelled, (p, c) -> p.sendFile(name, size, opener, c, treeName, relPath, seq, of));
    }

    @FunctionalInterface
    private interface PeerSend {
        void send(TsPeer peer, BooleanSupplier cancelled) throws Exception;
    }

    private int sendToTargets(long fileSize, BooleanSupplier cancelled, PeerSend send) throws Exception {
        List<TsPeer> snap;
        synchronized (peers) {
            snap = new ArrayList<>(peers);
        }
        List<TsPeer> targets = new ArrayList<>();
        int unsupported = 0;
        for (TsPeer p : snap) {
            if (cancelled != null && cancelled.getAsBoolean()) {
                throw new IOException("cancelled");
            }
            if (!p.isAlive()) {
                continue;
            }
            if (!p.supportsFile()) {
                unsupported++;
                continue;
            }
            targets.add(p);
        }
        if (targets.isEmpty()) {
            if (unsupported > 0) {
                throw new IOException("对端还不支持文件传输");
            }
            return 0;
        }
        int n = targets.size();
        if (n == 1) {
            send.send(targets.get(0), cancelled);
            return 1;
        }
        final long[] peerBytes = new long[n];
        final Object progressLock = new Object();
        FileIoCallback[] prev = new FileIoCallback[n];
        for (int i = 0; i < n; i++) {
            TsPeer p = targets.get(i);
            prev[i] = p.getFileIo();
            final int idx = i;
            FileIoCallback orig = prev[i];
            p.setFileIo(new FileIoCallback() {
                @Override
                public void onProgress(boolean incoming, String name, long done, long total, int s, int o) {
                    if (incoming) {
                        if (orig != null) {
                            orig.onProgress(true, name, done, total, s, o);
                        }
                        return;
                    }
                    long sum;
                    synchronized (progressLock) {
                        peerBytes[idx] = done;
                        sum = 0;
                        for (long b : peerBytes) {
                            sum += b;
                        }
                    }
                    if (orig != null) {
                        orig.onProgress(false, name, sum, Math.max(fileSize, 1) * n, s, o);
                    }
                }

                @Override
                public void onReceived(String name, String where) {
                    if (orig != null) {
                        orig.onReceived(name, where);
                    }
                }

                @Override
                public void onReceiveFailed(String name, String err) {
                    if (orig != null) {
                        orig.onReceiveFailed(name, err);
                    }
                }
            });
        }
        ExecutorService ex = Executors.newFixedThreadPool(n);
        int sent = 0;
        Exception last = null;
        try {
            List<Future<?>> futs = new ArrayList<>();
            for (TsPeer p : targets) {
                futs.add(ex.submit(() -> {
                    send.send(p, cancelled);
                    return null;
                }));
            }
            for (Future<?> f : futs) {
                try {
                    f.get();
                    sent++;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("cancelled");
                } catch (ExecutionException e) {
                    Throwable c = e.getCause() == null ? e : e.getCause();
                    if (c.getMessage() != null && c.getMessage().contains("cancelled")) {
                        cancelFiles();
                        throw (c instanceof Exception ex0) ? ex0 : new IOException("cancelled", c);
                    }
                    last = c instanceof Exception ex1 ? ex1 : new IOException(c);
                    c.printStackTrace();
                }
            }
        } finally {
            ex.shutdownNow();
            try {
                ex.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            for (int i = 0; i < n; i++) {
                targets.get(i).setFileIo(prev[i]);
            }
        }
        if (sent == 0 && last != null) {
            throw last;
        }
        return sent;
    }

    public void cancelFiles() {
        List<TsPeer> snap;
        synchronized (peers) {
            snap = new ArrayList<>(peers);
        }
        for (TsPeer p : snap) {
            p.cancelFile();
        }
    }

    private void notifyCount() {
        int n;
        synchronized (peers) {
            n = (int) peers.stream().filter(TsPeer::isAlive).count();
        }
        if (onClientCount != null) {
            onClientCount.accept(n);
        }
    }
}
