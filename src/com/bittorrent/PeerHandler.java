package com.bittorrent;

import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;


public class PeerHandler extends Thread{
    Socket socket;
    //ClientHandler -> peer
    AtomicBoolean shouldChoke;
    //TODO: graceful shutdown for handler and server
    AtomicBoolean shouldStop;
    //peer -> ClientHandler
    boolean peerHasChoked;
    FilePieces filePieces;
    FilePieces.Bitfield peerBitfield;

    PeerInfoConfig peerInfoCfg;
    
    

    //only accounts for piece message payload size
    int bytesDownloadedSinceChokeInterval = 0;
    PeerInfoConfig hostCfg;
    DataInputStream reader;
    DataOutputStream writer;
    Thread readerThread;
    BlockingQueue<Message> messageQueue;
    int pieceSize;
    int numPieces;
    boolean waitingForPiece = false;
    ArrayList<Integer> piecesToRequest = new ArrayList<>();
    Semaphore chokeSemaphore;
    AtomicBoolean peerIsInterested = new AtomicBoolean(false);
    ConcurrentLinkedQueue<Event> outboundEventQueue;
    ConcurrentLinkedQueue<Event> inboundEventQueue = new ConcurrentLinkedQueue<>();


    public PeerHandler(Socket socket, FilePieces fileBitfield, PeerInfoConfig hostCfg, PeerInfoConfig peerInfoCfg, CommonConfig commonCfg, ConcurrentLinkedQueue<Event> eventQueue, Semaphore chokeSemaphore) {
        shouldChoke = new AtomicBoolean(true);
        shouldStop = new AtomicBoolean(false);
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
        this.outboundEventQueue = eventQueue;
        this.chokeSemaphore = chokeSemaphore;
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

    void shutdown()  {
        peerHandlerLog("Shutting down ...");
        try{
            this.socket.close();
            if(this.readerThread != null && this.readerThread.isAlive()){
                this.readerThread.join();
            }
        }
        catch (IOException e){
            peerHandlerLog("There was an error closing the socket for peer");
        }
        catch (InterruptedException e){
            peerHandlerLog("There was an error joining the reader thread");
        }
        finally{
            int peerId = this.peerInfoCfg != null ? this.peerInfoCfg.peerId() : -1;
            this.outboundEventQueue.add(new Event(EventType.DisconnectedFromPeer, peerId, -1));
            peerHandlerLog("Peer handler for peer " + peerId + " successfully shut down");
        }

    }

    //meant to run in separate thread

    //TODO: URGENT -> errors should stop the thread entirely, and we need a way to update the main thread that we are done serving this client
    void readMessages() {
        peerHandlerLog("Reading messages");
        while(!shouldStop.get()){
            try{
                var message = Message.fromInputStream(this.reader, this.pieceSize + 4);

                this.messageQueue.put(message);
            }
            catch(SocketTimeoutException e){
                peerHandlerLog("error: socket timed out, we should not be waiting this long");
                break;
            }
            catch(SocketException e){
                peerHandlerLog("There was an error with the socket " + e.getMessage());
                break;
            }
            catch (IOException e){
                peerHandlerLog("There was an error reading from the socket");
                e.printStackTrace();
                break;
            }
            catch (BittorrentException e){
                peerHandlerLog("There was an error parsing the message");
                e.printStackTrace();
                break;
            }
            catch (InterruptedException e){
                peerHandlerLog("There was an error putting the message in the queue");
                e.printStackTrace();
                break;
            }

        }
        shouldStop.set(true);
        //send one more message to queue to unblock the processMessageQueue method
        this.messageQueue.add(new Message(Message.MessageType.Choke, new byte[0]));
        peerHandlerLog("Reader for peer " + this.peerInfoCfg.peerId() + " is done");
    }

    //TODO: URGENT -> errors should stop the thread entirely, and we need a way to update the main thread that we are done serving this client
    void processMessageQueue() throws IOException, BittorrentException, InterruptedException {
        peerHandlerLog("Processing messages");
        boolean prevChokeStatus = true;
        while(!shouldStop.get()){
            boolean shouldChoke = this.shouldChoke.get();
            var event = this.inboundEventQueue.poll();
            if(event != null && event.type() == EventType.PieceReceived){
                peerHandlerLog("Received %d piece from peer %d, sending HAVE message".formatted(event.pieceIndex(), event.peerId()));
                Message.sendHaveMessage(writer, event.pieceIndex());
                this.piecesToRequest.removeIf((Integer i) -> i == event.pieceIndex());
                if(this.piecesToRequest.isEmpty()){
                    peerHandlerLog("Sending not interested message after receiving piece %d from different peer %d".formatted(event.pieceIndex(), event.peerId()));
                    Message.sendNotInterestedMessage(this.writer);
                }
                checkStatusAndStopIfComplete("disconnecting after receiving piece from other peer(%d)".formatted(event.peerId()));
            }
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
            if(!this.waitingForPiece && !this.peerHasChoked){
                requestPiece();
            }
            Message message;
            message = this.messageQueue.poll();
            if(message != null)
            handleMessage(message);

        }
        peerHandlerLog("Handler for peer " + this.peerInfoCfg.peerId() + " is done");
    }


    void handleMessage(Message message) throws BittorrentException, IOException{
      
        switch (message.messageType){
            //Choke
            case Choke:{
                peerHandlerLog("Received choke message");
                this.peerHasChoked = true;
                break;
            }
            //Unchoke
            case Unchoke:{
                peerHandlerLog("Received unchoke message");
                this.peerHasChoked = false;
                break;
            }
            //Interested, NotInterested
            case Interested:{
                peerHandlerLog("Received interested message");
                this.peerIsInterested.set(true);
                break;
            }
            case NotInterested:{
                peerHandlerLog("Received uninterested message");
                this.peerIsInterested.set(false);
                if(filePieces.isComplete()){
                    peerHandlerLog("disconnecting after receiving uninterested message from peer (we both have the file)");
                    disconnect();
                }
                break;
            }
            //Have
            case Have:{
                if(message.payload.length != 4){
                    Logger.log("error: have message payload is not 4 bytes");
                    throw new BittorrentException("Have message payload is not 4 bytes");
                }

                var pieceIndex = ByteBuffer.wrap(message.payload).getInt();
                peerHandlerLog("Received have message for piece " + pieceIndex);
                if(this.peerBitfield.get(pieceIndex)){
                    peerHandlerLog("warning: received have message for piece that peer already has");
                }
                else{
                    this.peerBitfield.set(pieceIndex);
                }
                int remainingPieces = 0;
                for(int i = 0; i < this.numPieces; i++){
                    if(!this.peerBitfield.get(i)){
                        remainingPieces++;
                    }
                }
                peerHandlerLog("Peers remaining pieces: " + remainingPieces);
                if(!this.filePieces.bitfield.havePiece(pieceIndex)){
                    this.piecesToRequest.add(pieceIndex);
                    Message.sendInterestedMessage(this.writer);
                }
                checkStatusAndStopIfComplete("disconnecting after receiving have message (my file is complete and just learned peers is now complete)");
                break;
            }
            //Bitfield message
            case Bitfield:{
                peerHandlerLog("Received bitfield message");
                this.peerBitfield = new FilePieces.Bitfield(message.payload, this.numPieces);
                this.piecesToRequest = filePieces.bitfield.getInterestedIndices(this.peerBitfield); 
                peerHandlerLog("Sending interest messages");
                if(this.piecesToRequest.size() != 0){
                Message.sendInterestedMessage(this.writer);
                }
                else{
                    Message.sendNotInterestedMessage(this.writer);
                }
                break;
            }
            //request message
            case Request:{
                peerHandlerLog("Received request message");
                if(this.shouldChoke.get()){
                    peerHandlerLog("warning: received request message when choked");
                    return;
                }
                int pieceIndex = ByteBuffer.wrap(message.payload).getInt();

                byte[] piece = this.filePieces.getPiece(pieceIndex);
                if(piece == null){
                    peerHandlerLog("warning: either dont have piece or piece index is out of bounds - index = " + pieceIndex);
                    return;
                }
                peerHandlerLog("Sending piece message");
                Message.sendPieceMessage(this.writer, pieceIndex, piece);
                break;
            }
            //piece message
            case Piece:{
                
                if(!this.waitingForPiece){
                    peerHandlerLog("warning: received piece message when not waiting for piece");
                    return;
                }
                var messageBuf = ByteBuffer.wrap(message.payload);
                var pieceIndex = messageBuf.getInt();
                peerHandlerLog("Received piece message for index " + pieceIndex);
                var piece = Arrays.copyOfRange(messageBuf.array(), 4, messageBuf.array().length);
                this.filePieces.updatePiece(piece, pieceIndex);
                this.outboundEventQueue.add(new Event(EventType.PieceReceived, this.peerInfoCfg != null ? this.peerInfoCfg.peerId() : -1, pieceIndex));
                try{
                    this.chokeSemaphore.acquire();
                    this.bytesDownloadedSinceChokeInterval += piece.length;
                }
                catch(InterruptedException e){
                    peerHandlerLog("Error acquiring semaphore");
                    throw new BittorrentException(e.getMessage());
                }
                finally{
                    this.chokeSemaphore.release();
                }
                this.waitingForPiece = false;
                //the requestPiece method moves the requested index to end of the list
                this.piecesToRequest.remove(this.piecesToRequest.size() - 1);
                if(this.piecesToRequest.isEmpty()){
                    peerHandlerLog("Sending not interested message after receiving piece");
                    Message.sendNotInterestedMessage(this.writer);
                }
                if(this.filePieces.isComplete()){
                    peerHandlerLog("File is complete");
                }
                checkStatusAndStopIfComplete("disconnecting after receiving piece (we both have the file)");
                break;
            }
            

        }
    }

    void disconnect(){
        this.shouldStop.set(true);
        try{
            if(!socket.isClosed()){
                socket.close();
            }
        }
        catch(IOException e){
            peerHandlerLog("Error closing socket");
        }
    }
    void checkStatusAndStopIfComplete(){
        if(shouldStop()){
            disconnect();
        }
    }
    void checkStatusAndStopIfComplete(Object message){
        if(shouldStop()){
            peerHandlerLog(message);
            disconnect();
        }
    }

    //TODO: update to use BitSet. We can also keep track of peer completion better by using a bool and keeping track of the peer's remaining pieces
    boolean shouldStop(){
        if(!this.filePieces.isComplete()){
            return false;
        }
        for(int i = 0; i < this.numPieces; i++){
            if(!this.peerBitfield.get(i)){
                return false;
            }
        }
        return true;
    }
    void requestPiece() throws IOException{
        if(this.piecesToRequest.size() == 0){
            return;
        }
        int randomIndex = (int)(Math.random() * this.piecesToRequest.size());        
        int pieceIndex = this.piecesToRequest.get(randomIndex);
        this.piecesToRequest.set(randomIndex, this.piecesToRequest.get(this.piecesToRequest.size() - 1));
        this.piecesToRequest.set(this.piecesToRequest.size() - 1, randomIndex);
        peerHandlerLog("Requesting piece " + pieceIndex);
        Message.sendRequestMessage(writer, pieceIndex);
        this.waitingForPiece = true;
    }

    void sendBitfield(){
        try{
            var bitfieldBytes = this.filePieces.bitfield.getBytes();
            Message message = new Message(Message.MessageType.Bitfield, bitfieldBytes);
            Message.toOutputStream(this.writer, message);
        }
        catch (IOException e){
            peerHandlerLog("Error sending bitfield");
        }
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
            processMessageQueue();
        } 
        catch (IOException e) {
            e.printStackTrace();
        }
        catch(BittorrentException e){
            peerHandlerLog(e.getMessage());
        }
        catch(InterruptedException e){
            peerHandlerLog("Thread interrupted: " + e.getMessage());
        }
        finally{
            shutdown();
        }
        
    

        return;
    }

    void peerHandlerLog(Object message){
        int peerId = this.peerInfoCfg != null ? this.peerInfoCfg.peerId() : -1;
        Logger.log("-> Peer [%d]: %s".formatted(peerId, message));
    }
}
