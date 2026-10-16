package com.genymobile.scrcpy.monitor;

import android.net.LocalSocket;

import java.io.IOException;
import java.io.BufferedOutputStream;
import java.io.BufferedInputStream;

import java.io.DataOutputStream;
import java.io.DataInputStream;
import java.io.OutputStream;
import java.io.InputStream;

public final class EncodeInfoChannel {

    // private final DeviceMessageSender sender;

    public class MessageWriter {
        private final DataOutputStream dos;
    
        public MessageWriter(OutputStream rawOutputStream) {
            dos = new DataOutputStream(new BufferedOutputStream(rawOutputStream));
        }
    
        public void write(String msg) throws IOException {
            dos.writeUTF(msg);
            dos.flush();
        }
    }

    public class MessageReader {
        private final DataInputStream dis;
    
        public MessageReader(InputStream rawInputStream) {
            dis = new DataInputStream(new BufferedInputStream(rawInputStream));
        }
    
        public String read() throws IOException {
            // return dis.readFully();
            return null;
        }
    }

    private final MessageReader reader;
    private final MessageWriter writer;

    public EncodeInfoChannel(LocalSocket socket) throws IOException {
        reader = new MessageReader(socket.getInputStream());
        writer = new MessageWriter(socket.getOutputStream());
    }

    public String recv() throws IOException {
        return reader.read();
    }

    public void send(String msg) throws IOException {
        writer.write(msg);
    }
}
