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

import javax.management.RuntimeErrorException;


enum TimerType{
    Unchoke,
    OptimisticUnchoke
};
class TimerThread extends Thread{
    AtomicBoolean serverShutdownSignal;
    BlockingQueue<TimerType> queue;
    ServerListener listener;
    TimerType type;
    int delayInMs;
    TimerThread(ServerListener listener, AtomicBoolean shutdownSignal, TimerType type, int delayInMs){
        this.listener = listener;
        this.type = type;
        this.delayInMs = delayInMs;
        this.serverShutdownSignal = shutdownSignal;
    }
    public void run(){
        Logger.log("Starting timer thread: " + this.type);
        while(!serverShutdownSignal.get()){
            try{
                listener.onTimeout(this.type);
                Thread.sleep(this.delayInMs);
            }
            catch(InterruptedException e){
                Logger.log("Thread interrupted: %s : shutting down".formatted(e.getMessage()));
                listener.onError();
                break;
            }
            catch(BittorrentException e){
                Logger.log("Exception thrown when dispatching timout event: %s : shutting down".formatted(e.getMessage()));
                listener.onError();
                break;
            }
        }
        Logger.log("Stopping timer thread: " + this.type);
    }
}

public class Server implements ServerListener{
    CommonConfig commonCfg;
    ArrayList<PeerInfoConfig> peerInfoCfgs;
    PeerInfoConfig hostConfig;

    ArrayList<PeerHandler> handlers;
    ServerSocket serverSocket;
    int completePeers = 0;
    Semaphore chokeSemaphore;

    TimerThread unchokeThread;
    TimerThread optimisticUnchokeThread;

   

    FilePieces fileBitfield;
    
    BlockingQueue<TimerType> timerQueue;
    ConcurrentLinkedQueue<Event> eventQueue = new ConcurrentLinkedQueue<>();
    //we use this semaphore to wait on both the timer queue and event queue
    Semaphore queueSemaphore = new Semaphore(0);
    //we use this lock to make sure threads that call listener methods do not update queues while we are processing

    final Object queueLock = new Object();
    //Used from separate threads when an error occurs
     //Can be set to true by Timer threads
     //Might only use for timer, disconnect event should 
     AtomicBoolean shutdownSignal = new AtomicBoolean(false);
   
    public Server(int hostId, CommonConfig commonCfg, ArrayList<PeerInfoConfig> peerInfoCfgs) throws BittorrentException {
        this.commonCfg = commonCfg;
        this.peerInfoCfgs = peerInfoCfgs;
        this.handlers = new ArrayList<>();
        this.timerQueue = new ArrayBlockingQueue<TimerType>(2);
        this.unchokeThread = new TimerThread(this, this.shutdownSignal, TimerType.Unchoke, this.commonCfg.unchokingInterval() * 1000);
        this.optimisticUnchokeThread = new TimerThread(this, this.shutdownSignal, TimerType.OptimisticUnchoke, this.commonCfg.optimisticUnchokingInterval() * 1000);
        this.chokeSemaphore = new Semaphore(this.commonCfg.numberOfPreferredNeighbors(), true);
        for (var peer: this.peerInfoCfgs) {
            if(peer.peerId() == hostId){
                this.hostConfig = peer;
                break;
            }
        }
        if(this.hostConfig == null){
            throw new BittorrentException("Host id not found in peer info cfgs");
        }
        Logger.log(this.hostConfig);
    }

    void handlePieceReceivedEvent(Event event, HashSet<Integer> receivedPieces){
        if(receivedPieces.contains(event.pieceIndex())){
            return;
        }
        receivedPieces.add(event.pieceIndex());
        for(var handler: this.handlers){
            if(handler.peerInfoCfg != null && handler.peerInfoCfg.peerId() != event.peerId()){
                handler.onPieceReceived(event);
            }
        }
    }

    boolean handleDisconnectedFromPeerEvent(Event event){
        Logger.log("Removing handler for peer: " + event.peerId() + " from list of handlers");
        this.handlers.removeIf(handler -> handler.peerInfoCfg != null && handler.peerInfoCfg.peerId() == event.peerId());
        if(this.handlers.size() == 0){
            Logger.log("All peers disconnected");
            return true;
        }
        return false;
    }

    void initAndStartHandlers() throws IOException, BittorrentException{
        this.serverSocket = new ServerSocket(this.hostConfig.port());
        this.fileBitfield = new FilePieces(this.commonCfg, "peer_" + this.hostConfig.peerId(), this.hostConfig.hasFile());
        for (var peerCfg: this.peerInfoCfgs) {
            if(peerCfg.peerId() == this.hostConfig.peerId()){
                break;
            }
            Socket socket = new Socket(peerCfg.hostName(), peerCfg.port());
            var handler = new PeerHandler(socket, this.fileBitfield, this.hostConfig, peerCfg, this.commonCfg, this, this.chokeSemaphore);
            handlers.add(handler);
            handler.start();
        }
        //wait for all peers to connect
        //TODO: Review doc to make sure this is valid, otherwise we may have to create a separate thread for accepting connections
        while(handlers.size() != peerInfoCfgs.size() - 1){
            var socket = this.serverSocket.accept();
            var handler = new PeerHandler(socket, this.fileBitfield, this.hostConfig, null, this.commonCfg, this, this.chokeSemaphore);
            handlers.add(handler);
            handler.start();
        }
        Logger.log("All peers connected");

    }
    public void start() throws IOException, BittorrentException {
        try{
            initAndStartHandlers();
        }
        catch(IOException e){
            Logger.log("Error starting server: " + e.getMessage());
            shutdown(e);
        }
        catch(BittorrentException e){
            Logger.log("Error starting server: " + e.getMessage());
            shutdown(e);
        }
        //start choke process 
        startTimerThreads();
        runProtocol();
        shutdown();
    }

    public boolean processEvents(ArrayList<Event> events, HashSet<Integer> receivedPieces){
        for(var event: events){
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
                boolean allPeersDisconnected = handleDisconnectedFromPeerEvent(event);
                if(allPeersDisconnected){
                    return true;
                }
            }
        }
        return false;
    }

    void processTimerEvents(ArrayList<TimerType> timerEvents){
        for(var type: timerEvents){
            if(type == TimerType.Unchoke){
                determinePreferredPeers();
            }
            else if(type == TimerType.OptimisticUnchoke){
                optimisticUnchoke();
            }
        }
    }

    ArrayList<TimerType> getTimerEvents(){
        ArrayList<TimerType> events = new ArrayList<>();
        for(int i = 0; i < 2; i++){
            TimerType type = this.timerQueue.poll();
            if(type == null){
                break;
            }
            events.add(type);
        }
        return events;
    }
    ArrayList<Event> getEvents(){
        ArrayList<Event> events = new ArrayList<>();
        int numEvents = this.eventQueue.size();
        for(int i = 0; i < numEvents; i++){
            var event = this.eventQueue.poll();
            events.add(event);
        }
        return events;
    }

    public void runProtocol(){
        //We don't want to update handlers about pieces they already know we've received
        HashSet<Integer> receivedPieces = new HashSet<>();

        while(!this.shutdownSignal.get()){
            //TODO: review logic again and make sure that drainPermits will NEVER wait forever
            this.queueSemaphore.drainPermits();

            ArrayList<TimerType> timerEvents = new ArrayList<>();
            ArrayList<Event> handlerEvents = new ArrayList<>();

            synchronized(this.queueLock){
                timerEvents = getTimerEvents();
                handlerEvents = getEvents();
            }
            boolean allPeersDisconnected = processEvents(handlerEvents, receivedPieces);
            if(allPeersDisconnected){
                break;
            }
            processTimerEvents(timerEvents);
        }

    }
    void startTimerThreads(){
        this.unchokeThread.start();
        this.optimisticUnchokeThread.start();
    }

    //Unchoke the peers with the highest download rate
    void determinePreferredPeers(){
        if(this.handlers.isEmpty()){
            return;
        }
        //Stop all peers from updating their download rate (may refactor this or just not synchronize, as it's not a big deal)
        int permits = this.chokeSemaphore.drainPermits();
        PriorityQueue<PeerHandler> queue = new PriorityQueue<>((h1, h2) -> h2.bytesDownloadedSinceChokeInterval - h1.bytesDownloadedSinceChokeInterval);
        queue.addAll(this.handlers);
        int numberOfPreferredNeighbors = Math.min(commonCfg.numberOfPreferredNeighbors(), this.handlers.size());
        int[] unchokedPeerIds = new int[numberOfPreferredNeighbors];
        for(int i = 0; i < numberOfPreferredNeighbors; i++){
            var handler = queue.poll();
            handler.unchokePeer();
            handler.bytesDownloadedSinceChokeInterval = 0;
            unchokedPeerIds[i] = handler.peerInfoCfg != null ? handler.peerInfoCfg.peerId() : -1;
        }
        Logger.log("Unchoked peers: " + Arrays.toString(unchokedPeerIds));
        synchronized(this.fileBitfield){
            Logger.log("Remaining pieces: " + this.fileBitfield.remainingPieces);
        }
        for(var handler: queue){
            handler.chokePeer();
            handler.bytesDownloadedSinceChokeInterval = 0;
        }
        this.chokeSemaphore.release(permits);
    }

    //According to the spec, a neighbor can be both preferred and optimistically unchoked
    //Currently we are not doing this, as we only choose from choked+interested peers, rather than just interested
    void optimisticUnchoke(){
        if(this.handlers.isEmpty()){
            return;
        }
        ArrayList<PeerHandler> chokedAndInterested = new ArrayList<>();
        for(var handler: this.handlers){
            //if we follow the spec, this should be: if(handler.interested())
            if(handler.isChokingPeer() && handler.peerIsInterested.get()){
                chokedAndInterested.add(handler);
            }
        }
        if(chokedAndInterested.isEmpty()){
            return;
        }
        int randomIndex = (int)(Math.random() * chokedAndInterested.size());
        var handler = chokedAndInterested.get(randomIndex);
        handler.unchokePeer();
        Logger.log("Optimistically unchoked peer: " + handler.peerInfoCfg != null ? handler.peerInfoCfg.peerId() : -1);
    }

    //Listener implementation
    public void onTimeout(TimerType type) throws BittorrentException {
        synchronized(this.queueLock){
            this.timerQueue.add(type);
            this.queueSemaphore.release();
        }
    }

    public void onPeerDisconnected(int peerId) {
        Logger.log("[REMOVE]: called onPeerDisconnected for peer: " + peerId);
        synchronized(this.queueLock){
            var event = new Event(EventType.DisconnectedFromPeer, peerId, -1);
            this.eventQueue.add(event);
            this.queueSemaphore.release();
        }
    }

    public void onPieceReceived(int pieceIndex, int peerId) {
        synchronized(this.queueLock){
            var event = new Event(EventType.PieceReceived, peerId, pieceIndex);
            this.eventQueue.add(event);
            this.queueSemaphore.release();
        }
    }

    public void onError() {
        this.shutdownSignal.set(true);
    }

    void shutdown() {
        this.timerQueue.poll();
        this.shutdownSignal.set(true);
        if(this.serverSocket != null){
            try{
                Logger.log("Closing server socket");
                this.serverSocket.close();
            }
            catch(IOException e){
                Logger.log("Error closing server socket: " + e.getMessage());
            }
        }
        Logger.log("Shutting down server");
        for(var handler: this.handlers) {
            try{
                handler.onServerShutdown();
                handler.join();
            }
            catch(InterruptedException e){
                Logger.log("Thread interrupted on shutdown: " + e.getMessage());
            }
       }
       Logger.log("Server shutdown complete");
    }

    void shutdown(BittorrentException e) throws BittorrentException { 
        Logger.log("Shutting down server with exception");
        this.shutdownSignal.set(true);
        shutdown();
        throw e;
    }

    void shutdown(IOException e) throws IOException { 
        Logger.log("Shutting down server with exception");
        this.shutdownSignal.set(true);
        shutdown();
        throw e;
    } 
}
