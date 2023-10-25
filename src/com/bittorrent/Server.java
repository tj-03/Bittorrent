package com.bittorrent;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Array;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

record PeerInfoCfg(int peerId, String hostName, int port, boolean hasFile) {  }
record CommonCfg(int numberOfPreferredNeighbors, int unchokingInterval, int optimisticUnchokingInterval, String fileName, int fileSize, int pieceSize) { }

enum TimerType{
    Unchoke,
    OptimisticUnchoke
};

class TimerThread extends Thread{
    AtomicBoolean shouldStop;
    BlockingQueue<TimerType> queue;
    TimerType type;
    int delayInMs;
    TimerThread(BlockingQueue<TimerType> queue, AtomicBoolean shouldStop, TimerType type, int delayInMs){
        this.queue = queue;
        this.shouldStop = shouldStop;
        this.type = type;
        this.delayInMs = delayInMs;
    }
    public void run(){
        while(!shouldStop.get()){
            try{
                this.queue.put(this.type);
                Thread.sleep(this.delayInMs);
            }
            catch(InterruptedException e){
                Logger.log("Thread interrupted: " + e.getMessage());
                return;
            }
        }
    }
}

public class Server {
    CommonCfg commonCfg;
    ArrayList<PeerInfoCfg> peerInfoCfgs;
    ArrayList<PeerHandler> handlers;
    AtomicBoolean stopTimers = new AtomicBoolean(false);

    FileChunks fileBitfield;
    PeerInfoCfg hostConfig;
    ServerSocket serverSocket;
    TimerThread unchokeThread;
    TimerThread optimisticUnchokeThread;
    
    BlockingQueue<TimerType> timerQueue;
   
    public Server(int hostId, CommonCfg commonCfg, ArrayList<PeerInfoCfg> peerInfoCfgs) throws BittorrentException {
        this.commonCfg = commonCfg;
        this.peerInfoCfgs = peerInfoCfgs;
        this.handlers = new ArrayList<>();
        this.timerQueue = new ArrayBlockingQueue<>(2);
        this.unchokeThread = new TimerThread(this.timerQueue, this.stopTimers, TimerType.Unchoke, this.commonCfg.unchokingInterval() * 1000);
        this.optimisticUnchokeThread = new TimerThread(this.timerQueue, this.stopTimers, TimerType.OptimisticUnchoke, this.commonCfg.optimisticUnchokingInterval() * 1000);

        for (var peer: this.peerInfoCfgs) {
            if(peer.peerId() == hostId){
                this.hostConfig = peer;
                break;
            }
        }
        assert this.hostConfig != null;
        Logger.log(this.hostConfig);
        this.fileBitfield = new FileChunks(commonCfg, "peer_" + this.hostConfig.peerId(), this.hostConfig.hasFile());
    }

    public void start() throws IOException {
            this.serverSocket = new ServerSocket(this.hostConfig.port());


        for (var peerCfg: this.peerInfoCfgs) {
            if(peerCfg.peerId() == this.hostConfig.peerId()){
                break;
            }
            Socket socket = new Socket(peerCfg.hostName(), peerCfg.port());
            var handler = new PeerHandler(socket, this.fileBitfield, this.hostConfig, peerCfg, this.commonCfg);
            handlers.add(handler);
            handler.start();
        }

        //TODO?: not sure if we should wait for all peers before starting
        while(handlers.size() != peerInfoCfgs.size() - 1){
            var socket = this.serverSocket.accept();
            var handler = new PeerHandler(socket, this.fileBitfield, this.hostConfig, null, this.commonCfg);
            handlers.add(handler);
            handler.start();
        }

        //start choke process 
        

        Logger.log("All peers connected");
        startTimerThreads();
        while(true){
            try{
                var type = this.timerQueue.take();
                if(type == TimerType.Unchoke){
                    chokePeers();
                }
                else if(type == TimerType.OptimisticUnchoke){
                    optimisticUnchoke();
                }
            }
            catch(InterruptedException e){
                Logger.log("Thread interrupted: " + e.getMessage());
                break;
            }
        }

        shutdown();
    }

    void startTimerThreads(){
        this.unchokeThread.start();
        this.optimisticUnchokeThread.start();
    }

    void chokePeers(){
        Logger.log("Choking peers ...");
    }

    void optimisticUnchoke(){
        Logger.log("Optimistic unchoke peer ...");
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
