package com.genymobile.scrcpy.encodeStat;

import com.genymobile.scrcpy.util.Ln;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.system.Os;
import android.system.OsConstants;
import android.system.ErrnoException;

public final class EncodeStatSender {

    private Thread thread;
    private final BlockingQueue<String> queue = new ArrayBlockingQueue<>(600); // 60fps * 10s   // 30fps * 20s
    // private static final String TERMINATE_MSG = "__TERMINATE__"; // 特殊的终止消息
    private LocalServerSocket localServerSocket;
    private String abstractName;

    public EncodeStatSender(String name) {
        // this.abstractName = name + "_debug";
        this.abstractName = "scrcpy_encode_stat";
    }

    public void send(String msg) {
        if (!queue.offer(msg)) {
            // 修改代码，当消息队列满时，丢弃最早的消息，并打印它，然后保留最新的消息
            String tmp = queue.poll();
            queue.offer(msg);
            Ln.d("Device message dropped: " + tmp);
        }
    }

    private void loop() throws IOException, InterruptedException {
        Ln.i("listen localabstract:" + abstractName);
        LocalSocket socket = null;
        DataOutputStream dos = null;

        try {
            localServerSocket = new LocalServerSocket(abstractName);
            socket = localServerSocket.accept();
            dos = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

            while (!Thread.currentThread().isInterrupted()) {
                String msg = queue.take();
                // Ln.d("queue take msg: " + msg);
                // if (TERMINATE_MSG.equals(msg)) {
                //     break; // 收到终止消息，退出循环
                // }
                msg += "\n";
                byte[] bytes = msg.getBytes(StandardCharsets.UTF_8);
                dos.write(bytes);
                dos.flush();
            }
        } catch (IOException | InterruptedException e) {
            Ln.d("EncodeStatSender loopInner failed: " + e);
        } finally {
            if (dos != null) {
                Ln.d("close DataOutputStream");
                dos.close();
            }
            if (socket != null) {
                Ln.d("close LocalSocket");
                socket.close();
            }
            if (localServerSocket != null) {
                Ln.d("close LocalServerSocket");
                localServerSocket.close();
                localServerSocket = null;
            }
        }
        Ln.d("EncodeStatSender loop finished");
    }

    public void start() {
        thread = new Thread(() -> {
            try {
                loop();
            } catch (IOException | InterruptedException e) {
                // this is expected on close
                Ln.e("EncodeStatSender loop failed: " + e);
            } finally {
                Ln.d("EncodeStatSender loop stopped");
            }
        }, "encode-stat-send");
        thread.start();
    }

    public void stop() {
        if (thread != null) {
            Ln.d("EncodeStatSender stop");
            thread.interrupt();
            // queue.offer(TERMINATE_MSG); // 向队列中添加终止消息

            if (localServerSocket != null) {
                try {
                    Ln.d("shutdown LocalServerSocket");
                    Os.shutdown(localServerSocket.getFileDescriptor(), OsConstants.SHUT_RDWR);
                } catch (ErrnoException e) {
                    e.printStackTrace();
                }

                try {
                    Ln.d("close LocalServerSocket");
                    localServerSocket.close(); // 关闭 LocalServerSocket 以中断 accept()
                } catch (IOException e) {
                    Ln.e("Failed to close LocalServerSocket: " + e);
                }
            }
        }
    }

    public void join() throws InterruptedException {
        if (thread != null) {
            Ln.d("EncodeStatSender join start");
            thread.join();
            Ln.d("EncodeStatSender joined end");
        }
    }
}