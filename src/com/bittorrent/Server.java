package com.bittorrent;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Array;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.PriorityQueue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;


enum TimerType{
    Unchoke,
    OptimisticUnchoke
};

enum EventType{
    PieceReceived,
    DisconnectedFromPeer
}
record Event(EventType type, int peerId, int pieceIndex){}

class TimerThread extends Thread{
    AtomicBoolean shouldStop;
    BlockingQueue<TimerType> queue;
    ConcurrentLinkedQueue<Event> eventQueue = new ConcurrentLinkedQueue<>();
    TimerType type;
    int delayInMs;
    TimerThread(BlockingQueue<TimerType> queue, AtomicBoolean shouldStop, TimerType type, int delayInMs){
        this.queue = queue;
        this.shouldStop = shouldStop;
        this.type = type;
        this.delayInMs = delayInMs;
    }
    public void run(){
        Logger.log("Starting timer thread: " + this.type);
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
        Logger.log("Stopping timer thread: " + this.type);
    }
}

public class Server {
    CommonConfig commonCfg;
    ArrayList<PeerInfoConfig> peerInfoCfgs;
    ArrayList<PeerHandler> handlers;
    AtomicBoolean stopTimers = new AtomicBoolean(false);

    FilePieces fileBitfield;
    PeerInfoConfig hostConfig;
    ServerSocket serverSocket;
    TimerThread unchokeThread;
    TimerThread optimisticUnchokeThread;
    int completePeers = 0;
    
    BlockingQueue<TimerType> timerQueue;
    ConcurrentLinkedQueue<Event> eventQueue = new ConcurrentLinkedQueue<>();
    Semaphore chokeSemaphore;
   
    public Server(int hostId, CommonConfig commonCfg, ArrayList<PeerInfoConfig> peerInfoCfgs) throws BittorrentException {
        this.commonCfg = commonCfg;
        this.peerInfoCfgs = peerInfoCfgs;
        this.handlers = new ArrayList<>();
        this.timerQueue = new ArrayBlockingQueue<>(2);
        this.unchokeThread = new TimerThread(this.timerQueue, this.stopTimers, TimerType.Unchoke, this.commonCfg.unchokingInterval() * 1000);
        this.optimisticUnchokeThread = new TimerThread(this.timerQueue, this.stopTimers, TimerType.OptimisticUnchoke, this.commonCfg.optimisticUnchokingInterval() * 1000);
        this.chokeSemaphore = new Semaphore(this.commonCfg.numberOfPreferredNeighbors(), true);
        for (var peer: this.peerInfoCfgs) {
            if(peer.peerId() == hostId){
                this.hostConfig = peer;
                break;
            }
        }
        assert this.hostConfig != null;
        Logger.log(this.hostConfig);
    }

    void handlePieceReceivedEvent(Event event, HashSet<Integer> receivedPieces){
        if(receivedPieces.contains(event.pieceIndex())){
            return;
        }
        receivedPieces.add(event.pieceIndex());
        for(var handler: this.handlers){
            if(handler.peerInfoCfg != null && handler.peerInfoCfg.peerId() != event.peerId()){
                handler.inboundEventQueue.add(event);
            }
        }
    }

    boolean handleDisconnetedFromPeerEvent(Event event){
        this.handlers.removeIf(handler -> handler.peerInfoCfg != null && handler.peerInfoCfg.peerId() == event.peerId());
        if(this.handlers.size() == 0){
            Logger.log("All peers disconnected");
            return true;
        }
        return false;
    }

    public void start() throws IOException, BittorrentException {
        this.serverSocket = new ServerSocket(this.hostConfig.port());
        this.fileBitfield = new FilePieces(this.commonCfg, "peer_" + this.hostConfig.peerId(), this.hostConfig.hasFile());
        for (var peerCfg: this.peerInfoCfgs) {
            if(peerCfg.peerId() == this.hostConfig.peerId()){
                break;
            }
            Socket socket = new Socket(peerCfg.hostName(), peerCfg.port());
            var handler = new PeerHandler(socket, this.fileBitfield, this.hostConfig, peerCfg, this.commonCfg, this.eventQueue, this.chokeSemaphore);
            handlers.add(handler);
            handler.start();
        }

        //TODO?: not sure if we should wait for all peers before starting
        while(handlers.size() != peerInfoCfgs.size() - 1){
            var socket = this.serverSocket.accept();
            var handler = new PeerHandler(socket, this.fileBitfield, this.hostConfig, null, this.commonCfg, this.eventQueue, this.chokeSemaphore);
            handlers.add(handler);
            handler.start();
        }

        //start choke process 
        Logger.log("All peers connected");
        startTimerThreads();
        HashSet<Integer> receivedPieces = new HashSet<>();

        //TODO: HIGH-PRIORITY - find a way to wait on the timer queue and the event queue at the same time
        while(true){
            var numEvents = this.eventQueue.size();
            for(int i = 0; i < numEvents; i++){
                var event = this.eventQueue.poll();
                if(event.peerId() == -1){
                    Logger.log("Event with peer id of -1, shutting down");
                    break;
                }
                if(event.type() == EventType.PieceReceived){
                    Logger.log("Handling piece received event - peerId = " + event.peerId() + ", pieceIndex = " + event.pieceIndex());
                    handlePieceReceivedEvent(event, receivedPieces);
                }
                else if(event.type() == EventType.DisconnectedFromPeer){
                    Logger.log("Handling disconnected from peer event - peerId = " + event.peerId());
                    boolean allPeersDisconnected = handleDisconnetedFromPeerEvent(event);
                    if(allPeersDisconnected){
                        shutdown();
                        return;
                    }
                }
            }
            
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
                shutdown();
                break;
            }
            catch (BittorrentException e) {
                Logger.log("Error: " + e.getMessage());
                shutdown();
                break;
            }

        }
    }

    void startTimerThreads(){
        this.unchokeThread.start();
        this.optimisticUnchokeThread.start();
    }

    void chokePeers() throws BittorrentException{
        if(this.handlers.isEmpty()){
            return;
        }
        try{
            this.chokeSemaphore.acquire(this.commonCfg.numberOfPreferredNeighbors());
            PriorityQueue<PeerHandler> queue = new PriorityQueue<>((h1, h2) -> h2.bytesDownloadedSinceChokeInterval - h1.bytesDownloadedSinceChokeInterval);
            queue.addAll(this.handlers);
            int numberOfPreferredNeighbors = Math.min(commonCfg.numberOfPreferredNeighbors(), this.handlers.size());
            int[] unchokedPeerIds = new int[numberOfPreferredNeighbors];
            for(int i = 0; i < numberOfPreferredNeighbors; i++){
                var handler = queue.poll();
                handler.shouldChoke.set(false);
                handler.bytesDownloadedSinceChokeInterval = 0;
                unchokedPeerIds[i] = handler.peerInfoCfg != null ? handler.peerInfoCfg.peerId() : -1;
            }
            Logger.log("Unchoked peers: " + Arrays.toString(unchokedPeerIds));
            synchronized(this.fileBitfield){
                Logger.log("Remaining pieces: " + this.fileBitfield.remainingPieces);
            }
            for(var handler: queue){
                handler.shouldChoke.set(true);
                handler.bytesDownloadedSinceChokeInterval = 0;
            }
            this.chokeSemaphore.release(this.commonCfg.numberOfPreferredNeighbors());
        }
        catch(InterruptedException e){
            Logger.log("Thread interrupted: " + e.getMessage());
            throw new BittorrentException(e.getMessage());
        }

    }

    void optimisticUnchoke(){
        if(this.handlers.isEmpty()){
            return;
        }
        ArrayList<PeerHandler> chokedAndInterested = new ArrayList<>();
        for(var handler: this.handlers){
            if(handler.shouldChoke.get() && handler.peerIsInterested.get()){
                chokedAndInterested.add(handler);
            }
        }
        if(chokedAndInterested.isEmpty()){
            return;
        }
        int randomIndex = (int)(Math.random() * chokedAndInterested.size());
        var handler = chokedAndInterested.get(randomIndex);
        handler.shouldChoke.set(false);
        Logger.log("Optimistically unchoked peer: " + handler.peerInfoCfg != null ? handler.peerInfoCfg.peerId() : -1);
    }

    void shutdown() {
        Logger.log("Shutting down server");
        this.stopTimers.set(true);
        for(var handler: this.handlers) {
            try{
                handler.shouldStop.set(true);
                handler.join();
            }
            catch(InterruptedException e){
                Logger.log("Thread interrupted on shutdown: " + e.getMessage());
            }
       }
       Logger.log("Server shutdown complete");
       Logger.log("Writing file to disk");
       this.fileBitfield.writeToFile("peer_" + this.hostConfig.peerId());
    }

}
