package com.bittorrent;

import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

public class ClientHandler extends Thread{
    Socket socket;
    AtomicBoolean choked;
    FileBitfield fileBitfield;

    //only accounts for piece message payload size
    long bytesDownloadedSinceChokeInterval = 0;
    public ClientHandler(String host, int port) {
        choked.set(true);
        try {
            this.socket = new Socket(host, port);
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    public ClientHandler(Socket socket) {
        choked.set(true);
        this.socket = socket;
    }

    //Reads and writes handhsake
    //its fine if we start handshake before connecting to other peers
    void handshake(){
        //TODO: implement
    }
}
