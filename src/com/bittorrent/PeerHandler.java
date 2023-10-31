package com.bittorrent;

import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

enum EventType{
    PieceReceived,
    //Sent if disconnect
    //shutdownSignal MUST be true if disconnect was due to error
    DisconnectedFromPeer
}
record Event(EventType type, int peerId, int pieceIndex){}
public class PeerHandler extends Thread implements PeerListener{
    Socket socket;
    DataInputStream reader;
    DataOutputStream writer;

    //ClientHandler -> peer
    AtomicBoolean shouldChoke;
    //peer -> ClientHandler
    boolean peerHasChoked;
    //Used in server to determine if we should optimistically uncloke peer
    AtomicBoolean peerIsInterested = new AtomicBoolean(false);
    //piece index of piece we are currently requesting
    //if there is no piece in flight, this should be -1
    int pieceIndexInFlight = -1;
    ArrayList<Integer> piecesToRequest = new ArrayList<>();
    HashSet<Integer> piecesReceived = new HashSet<>();

    //TODO: graceful shutdown for handler and server
    AtomicBoolean handlerShutdownSignal;
    //only accounts for piece message payload size
    int bytesDownloadedSinceChokeInterval = 0;

    FilePieces filePieces;
    FilePieces.Bitfield peerBitfield;

    PeerInfoConfig hostCfg;
    PeerInfoConfig peerInfoCfg;
    int handlerId;
    int pieceSize;
    int numPieces;
    
    Thread readerThread;
    BlockingQueue<Message> messageQueue;
    //Used to notify server thread that we have received a message and it should publish it to all peerHandlers
    //Also used to notify server thread we have disconnected, so it can remove us from its list of peerHandlers
    ServerListener serverListener;
    //Queue that we receive PieceReceived events from
    ConcurrentLinkedQueue<Event> inboundEventQueue = new ConcurrentLinkedQueue<>();
    //Used to atomically add items to queue and wait on queue updates
    Object queueLock = new Object();
    //Used to update the download rate, 
    Semaphore chokeSemaphore;
    boolean shouldNotifyServerOnShutdown = true;
    



    public PeerHandler(int handlerId, Socket socket, FilePieces fileBitfield, PeerInfoConfig hostCfg, PeerInfoConfig peerInfoCfg, CommonConfig commonCfg, ServerListener listener, Semaphore chokeSemaphore) {
        shouldChoke = new AtomicBoolean(true);
        handlerShutdownSignal = new AtomicBoolean(false);
        this.peerHasChoked = true;
        //arbitrary capacity of 64, should be fine
        this.messageQueue = new ArrayBlockingQueue<>(64);
        this.peerInfoCfg = peerInfoCfg;
        this.hostCfg = hostCfg;
        this.socket = socket;
        this.filePieces = fileBitfield;
        this.pieceSize = commonCfg.pieceSize();
        this.numPieces = (int)Math.ceil((double)commonCfg.fileSize() / commonCfg.pieceSize());
        this.peerBitfield = new FilePieces.Bitfield(this.numPieces);
        this.serverListener = listener;
        this.chokeSemaphore = chokeSemaphore;
        this.handlerId = handlerId;
    }

    public void chokePeer(){
        this.shouldChoke.set(true);
        synchronized(this.queueLock){
            this.queueLock.notify();
        }
    }

    public void unchokePeer(){
        this.shouldChoke.set(false);
        synchronized(this.queueLock){
            this.queueLock.notify();
        }
    }

    public boolean isChokingPeer(){
        return this.shouldChoke.get();
    }

    //Reads and writes handhsake
    //its fine if we start handshake before connecting to other peers
    void handshake() throws IOException, BittorrentException {
        ByteBuffer buf = ByteBuffer.allocate(32);
        var header = "P2PFILESHARINGPROJ".getBytes();
        assert header.length == 18;
        buf.put(header);
        buf.putInt(28, this.hostCfg.peerId());
        this.writer.write(buf.array());
        this.writer.flush();

        byte[] clientHandshake = new byte[18];
        this.reader.readFully(clientHandshake);
        String clientHeaderStr = new String(clientHandshake, "UTF-8");
        if(!clientHeaderStr.equals("P2PFILESHARINGPROJ")){
            throw new BittorrentException("bad header: " + clientHeaderStr);
        }
        peerHandlerLog("Client handshake header received");
        byte[] peerIdBytes = new byte[4];
        this.reader.readNBytes(10);
        this.reader.readFully(peerIdBytes);
        var peerId = ByteBuffer.wrap(peerIdBytes).getInt();
        if(this.peerInfoCfg == null){
            //TODO: check if the toString method of the SocketAddress class is valid
            this.peerInfoCfg = new PeerInfoConfig(peerId, this.socket.getRemoteSocketAddress().toString(), this.socket.getPort(), false);
        }
        else{
            assert peerInfoCfg.peerId() == peerId;
        }
        peerHandlerLog("Client peer id = " + peerId);

    }

    //meant to run in separate thread

    //TODO: URGENT -> errors should stop the thread entirely, and we need a way to update the main thread that we are done serving this client
    void readMessages() {
        peerHandlerLog("Reading messages");
        while(!handlerShutdownSignal.get()){
            try{
                var message = Message.fromInputStream(this.reader, this.pieceSize + 4);
                //add message to queue and release semaphore
                onMessage(message);
            }
            catch(SocketTimeoutException e){
                peerHandlerLog("error: socket timed out, we should not be waiting this long");
                notifyThreadsToStop();
                return;
            }
            catch(IOException e){
                //TODO: add better log, some of these errors are intentional and signal successful shutdown
                peerHandlerLog("There was an error with the socket: %s".formatted(e.getMessage()));
                notifyThreadsToStop();
                return;
            }
            catch (BittorrentException e){
                peerHandlerLog("There was an error parsing the message");
                e.printStackTrace();
                notifyThreadsToStop();
                return;
            }
        }
        
        //if we broke out of the loop that means this was already called
        //onShutdown();
        peerHandlerLog("Reader for peer " + this.peerInfoCfg.peerId() + " is done");
    }

    //Add item to queue and release semaphore as one atomic operation
    //Semaphore acts as notification that there is an item in the queue
    public void onPieceReceived(Event event){
        synchronized(this.queueLock){
            Logger.log("adding piece received event to queue");
            this.inboundEventQueue.add(event);
            this.queueLock.notify();
        }
        
    }       
    //Add item to queue and release semaphore as one atomic operation
    //Semaphore acts as notification that there is an item in the queue
    public void onMessage(Message message){
        synchronized(this.queueLock){
            this.messageQueue.add(message);
            this.queueLock.notify();
        }
    }

    //Processes pieceReceived events from queue, which signals that we have received a piece from a different peer
    //This should send a HAVE message to peer and remove the piece from the list of pieces to request
    //If we have all the pieces our peer has to offer, we should send a NOT_INTERESTED message
    //If we and the peer have all the pieces, we should disconnect
    //returns true if shutdown was triggered
    boolean processPieceReceivedEvent(ArrayList<Event> events) throws IOException {
        for(var event: events){
            if(event.type() != EventType.PieceReceived){
                //TODO: remove
                throw new RuntimeException("received invalid event type in pieceReceived handler");
            }

            int pieceIndex = event.pieceIndex();
            //Ignore piece if it matches the piece we are currently requesting, we'll wait for the piece message associated with our request
            if(pieceIndex == this.pieceIndexInFlight){
                peerHandlerLog("[PIECE_RECEIVED_EV] Received piece %d from peer %d, which matches in flight request, ignoring".formatted(event.pieceIndex(), event.peerId()));
                return false;
            }
            peerHandlerLog("[PIECE_RECEIVED_EV] Received piece %d from peer %d, sending HAVE message".formatted(event.pieceIndex(), event.peerId()));
            Message.sendHaveMessage(writer, event.pieceIndex());
            
            boolean indexWasRemoved = this.piecesToRequest.removeIf((Integer j) -> j == pieceIndex);
            if(!indexWasRemoved){
                peerHandlerLog("[PIECE_RECEIVED_EV] Received piece %d from peer %d, but we were not expecting this piece".formatted(event.pieceIndex(), event.peerId()));
            }
            if(indexWasRemoved && this.piecesToRequest.isEmpty()){
                peerHandlerLog("Sending not interested message after receiving piece %d from different peer %d".formatted(event.pieceIndex(), event.peerId()));
                Message.sendNotInterestedMessage(this.writer);
            }
            boolean shutdownTriggered = bothPeersHaveCompleteFile("disconnecting after receiving piece from other peer(%d)".formatted(event.peerId()));
            if(shutdownTriggered){
                return true;
            }
        }
        return false;
    }

    boolean processMessages(ArrayList<Message> messages, boolean choked) throws BittorrentException, IOException{
        for(var message: messages){
            boolean shutdownTriggered = handleMessage(message, choked);
            if(shutdownTriggered){
                return true;
            }
        }
        return false;
    }

    boolean requestInFlight(){
        return this.pieceIndexInFlight != -1;
    }

    void cancelInFlightRequest(){
        this.pieceIndexInFlight = -1;
    }

    //Select a random piece from the list of pieces to request and send a request message
    //Move the requested index to the back of the list to be popped once we receive the piece (more efficient than remove())
    //Set the pieceIndexInFlifght to the requested piece
    void requestPiece() throws IOException{
        if(this.piecesToRequest.size() == 0){
            peerHandlerLog("No pieces to request");
            return;
        }
        int randomIndex = (int)(Math.random() * this.piecesToRequest.size());        
        int pieceIndex = this.piecesToRequest.get(randomIndex);
        this.piecesToRequest.set(randomIndex, this.piecesToRequest.get(this.piecesToRequest.size() - 1));
        this.piecesToRequest.set(this.piecesToRequest.size() - 1, pieceIndex);
        peerHandlerLog("Requesting piece " + pieceIndex);
        Message.sendRequestMessage(writer, pieceIndex);
        this.pieceIndexInFlight = pieceIndex;
    }

    boolean handleHaveMessage(Message message) throws IOException, BittorrentException{
        var pieceIndex = ByteBuffer.wrap(message.payload).getInt();
        peerHandlerLog("Received have message for piece " + pieceIndex);
        if(pieceIndex < 0 || pieceIndex >= this.numPieces){
            throw new BittorrentException("invalid piece index: " + pieceIndex);
        }
        this.peerBitfield.set(pieceIndex);
        if(!this.filePieces.bitfield.havePiece(pieceIndex)){
            if(this.piecesToRequest.isEmpty()){
                peerHandlerLog("Sending interested message after receiving have message for piece " + pieceIndex + " while having empty request queue");
                Message.sendInterestedMessage(this.writer);
            }
            if(requestInFlight()){
                this.piecesToRequest.set(this.piecesToRequest.size() -1, pieceIndex);
                this.piecesToRequest.add(this.pieceIndexInFlight);
            }
            else{
                this.piecesToRequest.add(pieceIndex);
            }
        }
        return bothPeersHaveCompleteFile("disconnecting after receiving have message from peer");
    }
    boolean handleRequestMessage(Message message, boolean choked) throws BittorrentException, IOException{
        int pieceIndex = ByteBuffer.wrap(message.payload).getInt();
        peerHandlerLog("Received request message for piece " + pieceIndex);
        if(choked){
            peerHandlerLog("[WARNING]: Received request message while choked");
            return false;
        }
        if(pieceIndex < 0 || pieceIndex >= this.numPieces){
            throw new BittorrentException("invalid piece index: " + pieceIndex);
        }
        if(!this.peerIsInterested.get()){
            throw new BittorrentException("peer requested piece while not interested");
        }
        byte[] piece = this.filePieces.getPiece(pieceIndex);
        if(piece == null){
            throw new BittorrentException("peer requested piece index %d which we do not have".formatted(pieceIndex));
        }
        peerHandlerLog("Sending piece message for piece " + pieceIndex);
        Message.sendPieceMessage(this.writer, pieceIndex, piece);
        this.peerBitfield.set(pieceIndex);
        return bothPeersHaveCompleteFile("disconnecting after sending piece %d to peer".formatted(pieceIndex));
    }

    boolean handlePieceMessage(Message message) throws BittorrentException{
        ByteBuffer buf = ByteBuffer.wrap(message.payload);
        int pieceIndex = buf.getInt();
        peerHandlerLog("Received piece message for piece " + pieceIndex);
        if(pieceIndex < 0 || pieceIndex >= this.numPieces){
            throw new BittorrentException("invalid piece index: " + pieceIndex);
        }
        if(pieceIndex != this.pieceIndexInFlight){
            throw new BittorrentException("received piece message for piece index %d while expecting piece index %d".formatted(pieceIndex, this.pieceIndexInFlight));
        }
        //If this happens we messed up, since the requestInFlight check should have caught this
        if(this.filePieces.bitfield.havePiece(pieceIndex)){
            //throw new BittorrentException("received piece message for piece index %d which we already have (this is an error on our side)".formatted(pieceIndex));
        }
        byte[] byteArray = buf.array();
        var piece = Arrays.copyOfRange(byteArray, 4, byteArray.length);
        this.filePieces.updatePiece(piece, pieceIndex);
        //this.chokeSemaphore.acquireUninterruptibly();
        this.bytesDownloadedSinceChokeInterval += piece.length;
        //this.chokeSemaphore.release();
        if(this.piecesReceived.contains(pieceIndex)){
            throw new BittorrentException("received piece index %d twice (this is an error on our side)".formatted(pieceIndex));
        }
        this.piecesReceived.add(pieceIndex);
        this.cancelInFlightRequest();
        this.piecesToRequest.remove(this.piecesToRequest.size() - 1);
        int peerId = this.peerInfoCfg != null ? this.peerInfoCfg.peerId() : -1;
        this.serverListener.onPieceReceived(pieceIndex, peerId);
        return bothPeersHaveCompleteFile("disconnecting after receiving piece %d from peer".formatted(pieceIndex));
    }

    boolean handleMessage(Message message, Boolean choked) throws BittorrentException, IOException{
        switch(message.messageType){
            case Choke:
                peerHandlerLog("Received choke message");
                this.peerHasChoked = true;
                //cancel any outstanding requests
                this.cancelInFlightRequest();
                break;
            case Unchoke:
                peerHandlerLog("Received unchoke message");
                this.peerHasChoked = false;
                break;
            case Interested:
                peerHandlerLog("Received interested message");
                //Used in server to determine if we should optimistically unchoke peer
                this.peerIsInterested.set(true);
                break;
            case NotInterested:
                peerHandlerLog("Received not interested message");
                //Used in server to determine if we should optimistically uncloke peer
                this.peerIsInterested.set(false);
                //We can't blindly shutdown if our bitfield is complete, if we completed our bitfield from another peer and sent a have message,
                //our peer may not have received that yet and could be sending a Not Interested message for a previous state
                //We should not rely on this message to shutdown and instead keep track of our peer's bitfield accurately
                if(this.filePieces.isComplete()){
                    //onShutdown();
                }
                break;
            case Bitfield:
                peerHandlerLog("Received bitfield message");
                this.peerBitfield = new FilePieces.Bitfield(message.payload, this.numPieces);
                this.piecesToRequest = filePieces.bitfield.getInterestedIndices(this.peerBitfield); 
                if(this.piecesToRequest.size() != 0){
                    peerHandlerLog("Sending interest messages");    
                    Message.sendInterestedMessage(this.writer);
                }
                else{
                    peerHandlerLog("Sending not interest messages");    
                    Message.sendNotInterestedMessage(this.writer);
                }
                return bothPeersHaveCompleteFile("disconnecting after receiving bitfield message from peer");
            case Have:
                return handleHaveMessage(message);
            case Request:
                return handleRequestMessage(message, choked);
            case Piece:
                return handlePieceMessage(message);
            default:
                throw new BittorrentException("invalid message type: " + message.messageType);
        }
        return false;
    
    }

    ArrayList<Message> getMessagesFromQueue(){
        ArrayList<Message> messages = new ArrayList<>();
        Message message;
        while((message = this.messageQueue.poll()) != null){
            messages.add(message);
        }
        return messages;
    }

    ArrayList<Event> getEventsFromQueue(){
        ArrayList<Event> events = new ArrayList<>();
        Event event;
        while((event = this.inboundEventQueue.poll()) != null){
            events.add(event);
        }
        return events;
    }

    //TODO: URGENT -> errors should stop the thread entirely, and we need a way to update the main thread that we are done serving this client
    //TODO: rename method
    void communicateWithPeer() throws IOException, BittorrentException {
        peerHandlerLog("Processing messages");
        //Maybe move this to an event queue
        boolean prevChokeStatus = true;
        while(!handlerShutdownSignal.get()){

            boolean shouldChoke = this.shouldChoke.get();
            //TODO: refactor this log
            if(this.piecesToRequest.size() < 20){
                peerHandlerLog("%d pieces left to request: %s".formatted(this.piecesToRequest.size(), Arrays.toString(this.piecesToRequest.toArray())));
            }
            else{
                peerHandlerLog("%d pieces left to request ...".formatted(this.piecesToRequest.size()));
            }
            peerHandlerLog("Current choking peer = %b".formatted(shouldChoke));
            peerHandlerLog("Peer currently choking us = %b".formatted(this.peerHasChoked));
            peerHandlerLog("Current piece in flight = %d".formatted(this.pieceIndexInFlight));
            if(shouldChoke && !prevChokeStatus){
                peerHandlerLog("Choking peer, sending choke message");
                Message.sendChokeMessage(this.writer);
                prevChokeStatus = true;
            }
            else if(!shouldChoke && prevChokeStatus){
                peerHandlerLog("Unchoking peer, sending unchoke message");
                Message.sendUnchokeMessage(this.writer);
                prevChokeStatus = false;
            }

            boolean shutdownTriggered = false;
            ArrayList<Message> messages;
            ArrayList<Event> events;
            synchronized(this.queueLock){
                try{
                    this.queueLock.wait();
                }
                catch(InterruptedException e){
                    throw new BittorrentException(e);
                }
               messages = getMessagesFromQueue();
               events = getEventsFromQueue();
            }
            shutdownTriggered = processMessages(messages, shouldChoke) || processPieceReceivedEvent(events);

            if(shutdownTriggered){
                break;
            }
            
            if(!this.requestInFlight() && !this.peerHasChoked){
                requestPiece();
            }
        }
        peerHandlerLog("Handler for peer " + this.peerInfoCfg.peerId() + " is done");
    }

    public void run(){
        try {
            this.socket.setSoTimeout(10000 * 25);
            reader = new DataInputStream(this.socket.getInputStream());
            writer = new DataOutputStream(this.socket.getOutputStream());
            peerHandlerLog("Beginning handshake");
            handshake();
            peerHandlerLog("Client handler running");
            if(this.hostCfg.hasFile()){
                sendBitfield();
            }
            this.readerThread = new Thread(){
                PeerHandler handler;
                Thread init(PeerHandler handler){
                    this.handler = handler;
                    return this;
                }
                public void run(){
                    this.handler.readMessages();
                }
            }.init(this);
    
            this.readerThread.start();
            communicateWithPeer();
        } 
        catch (IOException e) {
            peerHandlerLogErr("IO Error communicating with peer: " + e.getMessage());
            peerHandlerLog("Triggering server down due to IO error");
            //serverListener.onError();
            //this.shouldNotifyServerOnShutdown = false;
        }
        catch(BittorrentException e){
            peerHandlerLogErr(e.getMessage());
            peerHandlerLog("Triggering server down due to IO error");
            //serverListener.onError();
            //this.shouldNotifyServerOnShutdown = false;
        }
        finally{
            peerHandlerLog("Disconnecting");
            notifyThreadsToStop();
            if(this.shouldNotifyServerOnShutdown){
                //int peerId = this.peerInfoCfg != null ? this.peerInfoCfg.peerId() : -1;
                this.serverListener.onPeerDisconnected(this.handlerId);
            }
            try{
                if(this.readerThread != null && this.readerThread.isAlive()){
                    this.readerThread.join();
                }
            }
            catch(InterruptedException e){
                peerHandlerLogErr("Error joining reader thread");
            }
        }
        peerHandlerLog("Returning from run()");
        return;
    }

    //TODO: update to use BitSet. We can also keep track of peer completion better by using a bool and keeping track of the peer's remaining pieces
    boolean bothPeersHaveCompleteFile(){
        boolean peerHasCompleteFile = true;
        for(int i = 0; i < this.numPieces; i++){
            if(!this.peerBitfield.get(i)){
                peerHasCompleteFile = false;
                break;
            }
        }
        peerHandlerLog("Host file status = %b, peer file status = %b".formatted(this.filePieces.isComplete(), peerHasCompleteFile));
        return this.filePieces.isComplete() && peerHasCompleteFile;
    }
    boolean bothPeersHaveCompleteFile(Object message){
        boolean res = bothPeersHaveCompleteFile();
        if(res)
        peerHandlerLog(message);
        return res;
    }

    void sendBitfield(){
        try{
            var bitfieldBytes = this.filePieces.bitfield.getBytes();
            Message message = new Message(Message.MessageType.Bitfield, bitfieldBytes);
            Message.toOutputStream(this.writer, message);
            peerHandlerLog("Sent bitfield");
        }
        catch (IOException e){
            peerHandlerLogErr("Error sending bitfield");
        }
    }

    //Releases semaphore and closed socket to prevent infinite blocking
    //Sets shutdown signal to true to break any loops
    void notifyThreadsToStop(){
        this.handlerShutdownSignal.set(true);
        if(!this.socket.isClosed()){
            try{
                this.socket.close();
            }
            catch(IOException e){
                peerHandlerLogErr("Error closing socket");
            }
        }
        synchronized(this.queueLock){
            this.queueLock.notify();
        }
        peerHandlerLog("Notied threads to stop");
    }

    //Used by server to trigger shutdown to all handlers
    public void onServerShutdown(){
        this.shouldNotifyServerOnShutdown = true;
        notifyThreadsToStop();
    }

    void peerHandlerLog(Object message){
        int peerId = this.peerInfoCfg != null ? this.peerInfoCfg.peerId() : -1;
        Logger.log("-> Peer [%d]: %s".formatted(peerId, message));
    }
    void peerHandlerLogErr(Object message){
        int peerId = this.peerInfoCfg != null ? this.peerInfoCfg.peerId() : -1;
        Logger.logErr("-> Peer [%d]: %s".formatted(peerId, message));
    }
}
