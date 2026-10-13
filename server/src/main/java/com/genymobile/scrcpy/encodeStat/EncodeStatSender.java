package com.genymobile.scrcpy.encodeStat;

import com.genymobile.scrcpy.util.Ln;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.system.Os;
import android.system.OsConstants;
import android.system.ErrnoException;

public final class EncodeStatSender {

    private static final long JOIN_TIMEOUT_MS = 1000;

    private Thread thread;
    // 每 client 独立队列:send 广播到所有 client,各自拿到完整流(不分摊,
    // 否则多监控端并存时 pts 间隔翻倍、统计失真);断开由写线程自行移除
    private final List<ClientHandler> clients = new CopyOnWriteArrayList<>();
    // private static final String TERMINATE_MSG = "__TERMINATE__"; // 特殊的终止消息
    private final LocalServerSocket localServerSocket;
    private String abstractName;

    public EncodeStatSender(String name) throws IOException {
        this.abstractName = name + "_encode_stat";
        this.localServerSocket = new LocalServerSocket(abstractName);
    }

    public void send(String msg) {
        for (ClientHandler client : clients) {
            client.enqueue(msg);
        }
    }

    private void loop() throws IOException {
        Ln.i("listen localabstract:" + abstractName);
        // accept 循环:client 断开后继续等待新连接
        while (!Thread.currentThread().isInterrupted()) {
            LocalSocket socket = localServerSocket.accept();
            // 每 client 一个写线程:client 消失后写可能阻塞在已满的 socket 缓冲
            // (unix socket 无写超时),独立线程避免拖死 accept 循环;daemon 线程随进程退出回收
            ClientHandler client = new ClientHandler(socket);
            clients.add(client);
            client.start();
        }
    }

    private final class ClientHandler extends Thread {

        private final LocalSocket socket;
        // 60fps * 10s / 30fps * 20s;满则丢最旧保最新
        private final BlockingQueue<String> queue = new ArrayBlockingQueue<>(600);

        ClientHandler(LocalSocket socket) {
            super("encode-stat-client");
            this.socket = socket;
            setDaemon(true);
        }

        void enqueue(String msg) {
            if (!queue.offer(msg)) {
                String tmp = queue.poll();
                queue.offer(msg);
                Ln.d("Device message dropped: " + tmp);
            }
        }

        @Override
        public void run() {
            DataOutputStream dos = null;
            try {
                dos = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                while (!isInterrupted()) {
                    String msg = queue.take();
                    msg += "\n";
                    dos.write(msg.getBytes(StandardCharsets.UTF_8));
                    dos.flush();
                }
            } catch (IOException | InterruptedException e) {
                Ln.d("EncodeStatSender client ended: " + e);
            } finally {
                clients.remove(this);
                Ln.d("close client socket");
                try {
                    if (dos != null) {
                        dos.close();
                    }
                } catch (IOException ignored) {
                }
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }



    public void start() {
        thread = new Thread(() -> {
            try {
                loop();
            } catch (IOException e) {
                // this is expected on close
                Ln.e("EncodeStatSender loop failed: " + e);
            } finally {
                Ln.d("EncodeStatSender loop stopped");
            }
        }, "encode-stat-send");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        if (thread != null) {
            Ln.d("EncodeStatSender stop");
            thread.interrupt();
            // queue.offer(TERMINATE_MSG); // 向队列中添加终止消息

            try {
                Ln.d("shutdown LocalServerSocket");
                Os.shutdown(localServerSocket.getFileDescriptor(), OsConstants.SHUT_RDWR);
            } catch (ErrnoException e) {
                Ln.e("Failed to shutdown LocalServerSocket: " + e);
            }

            try {
                Ln.d("close LocalServerSocket");
                localServerSocket.close(); // 关闭 LocalServerSocket 以中断 accept()
            } catch (IOException e) {
                Ln.e("Failed to close LocalServerSocket: " + e);
            }
        }
    }

    public void join() throws InterruptedException {
        if (thread != null) {
            Ln.d("EncodeStatSender join start");
            thread.join(JOIN_TIMEOUT_MS);
            if (thread.isAlive()) {
                Ln.w("EncodeStatSender join timeout");
            }
            Ln.d("EncodeStatSender joined end");
        }
    }
}