package com.bittorrent;

import java.io.IOException;
import java.lang.reflect.Array;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

record PeerInfoCfg(int peerId, String hostName, int port, boolean hasFile) { }
record CommonCfg(int numberOfPreferredNeighbors, int unchokingInterval, int optimisticUnchokingInterval, String fileName, int fileSize, int pieceSize) { }

public class Server {
    CommonCfg commonCfg;
    ArrayList<PeerInfoCfg> peerInfoCfgs;
    ArrayList<ClientHandler> handlers;
    int portNumber;
    ServerSocket serverSocket;
    public Server(int port, CommonCfg commonCfg, ArrayList<PeerInfoCfg> peerInfoCfgs) {
        this.commonCfg = commonCfg;
        this.peerInfoCfgs = peerInfoCfgs;
    }

    public void start() throws IOException {
        try {
            this.serverSocket = new ServerSocket(this.portNumber);
        }
        catch (Exception e) {
            System.out.println("Error creating server socket");
            e.printStackTrace();
        }
        for (var peer: this.peerInfoCfgs) {
            if(peer.port() == this.portNumber){
                break;
            }
            var handler = new ClientHandler(peer.hostName(), peer.port());
            handlers.add(handler);
            handler.run();
        }

        //TODO: we know how many peers there are, so we can just loop that many times
        //TODO: this thread will maybe eventually be responsible for updating handler choke/unchoked status
        while(true){
            var socket = this.serverSocket.accept();
            var handler = new ClientHandler(socket);
            handlers.add(handler);
            handler.run();
        }
    }
}
