package com.bittorrent;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Array;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

record PeerInfoCfg(int peerId, String hostName, int port, boolean hasFile) {  }
record CommonCfg(int numberOfPreferredNeighbors, int unchokingInterval, int optimisticUnchokingInterval, String fileName, int fileSize, int pieceSize) { }

public class Server {
    CommonCfg commonCfg;
    ArrayList<PeerInfoCfg> peerInfoCfgs;
    ArrayList<ClientHandler> handlers;

    FileBitfield fileBitfield;
    PeerInfoCfg hostConfig;
    ServerSocket serverSocket;
    public Server(int hostId, CommonCfg commonCfg, ArrayList<PeerInfoCfg> peerInfoCfgs) throws BittorrentException {
        this.commonCfg = commonCfg;
        this.peerInfoCfgs = peerInfoCfgs;
        this.handlers = new ArrayList<>();
        for (var peer: this.peerInfoCfgs) {
            if(peer.peerId() == hostId){
                this.hostConfig = peer;
                break;
            }
        }
        assert this.hostConfig != null;
        Logger.log(this.hostConfig);
        this.fileBitfield = new FileBitfield(commonCfg, "peer_" + this.hostConfig.peerId(), this.hostConfig.hasFile());
    }

    public void start() throws IOException {
            this.serverSocket = new ServerSocket(this.hostConfig.port());


        for (var peerCfg: this.peerInfoCfgs) {
            if(peerCfg.peerId() == this.hostConfig.peerId()){
                break;
            }
            Socket socket = new Socket(peerCfg.hostName(), peerCfg.port());
            var handler = new ClientHandler(socket, this.fileBitfield, this.hostConfig, peerCfg, this.commonCfg);
            handlers.add(handler);
            handler.run();
        }

        //TODO: we know how many peers there are, so we can just loop that many times
        //TODO: this thread will maybe eventually be responsible for updating handler choke/unchoked status
        while(handlers.size() != peerInfoCfgs.size() - 1){
            var socket = this.serverSocket.accept();
            var handler = new ClientHandler(socket, this.fileBitfield, this.hostConfig, null, this.commonCfg);
            handlers.add(handler);
            handler.start();
        }

        Logger.log("All peers connected");

        shutdown();
    }

    void shutdown() {
        for(var handler: this.handlers) {
            try{
                handler.join();
            }
            catch(InterruptedException e){
                Logger.log("Thread interrupted on shutdown: " + e.getMessage());
            }
       }
    }
}
