package com.bittorrent;

import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.BlockingQueue;
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
    FileChunks filePieces;
    FileChunks.Bitfield peerBitfield;

    PeerInfoCfg peerInfoCfg;
    
    

    //only accounts for piece message payload size
    AtomicLong bytesDownloadedSinceChokeInterval = new AtomicLong(0);
    PeerInfoCfg hostCfg;
    DataInputStream reader;
    DataOutputStream writer;
    Thread readerThread;
    BlockingQueue<Message> messageQueue;
    int pieceSize;
    int numPieces;
    boolean waitingForPiece = false;
    ArrayList<Integer> piecesToRequest = new ArrayList<>();


    public PeerHandler(Socket socket, FileChunks fileBitfield, PeerInfoCfg hostCfg, PeerInfoCfg peerInfoCfg, CommonCfg commonCfg) throws IOException {
        shouldChoke = new AtomicBoolean(true);
        shouldStop = new AtomicBoolean(false);
        //arbitrary capacity of 64, should be fine
        this.messageQueue = new ArrayBlockingQueue<>(64);
        this.peerInfoCfg = peerInfoCfg;
        this.hostCfg = hostCfg;
        this.socket = socket;
        this.filePieces = fileBitfield;
        this.pieceSize = commonCfg.pieceSize();
        this.numPieces = (int)Math.ceil((double)commonCfg.fileSize() / commonCfg.pieceSize());
        this.peerBitfield = new FileChunks.Bitfield(this.numPieces);

        reader = new DataInputStream(this.socket.getInputStream());
        writer = new DataOutputStream(this.socket.getOutputStream());
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
        Logger.log("Client handshake header received");
        byte[] peerIdBytes = new byte[4];
        this.reader.readNBytes(10);
        this.reader.readFully(peerIdBytes);
        var peerId = ByteBuffer.wrap(peerIdBytes).getInt();
        if(this.peerInfoCfg == null){
            //TODO: check if the toString method of the SocketAddress class is valid
            this.peerInfoCfg = new PeerInfoCfg(peerId, this.socket.getRemoteSocketAddress().toString(), this.socket.getPort(), false);
        }
        else{
            assert peerInfoCfg.peerId() == peerId;
        }
        Logger.log("Client peer id = " + peerId);

    }

    void shutdown()  {
        try{
            this.socket.close();
        }
        catch (IOException e){
            Logger.log("There was an error closing the socket for peer");
        }
    }

    //meant to run in separate thread

    //TODO: URGENT -> errors should stop the thread entirely, and we need a way to update the main thread that we are done serving this client
    void readMessages() {
        Logger.log("Reading messages");
        while(!shouldStop.get()){
            try{
                var message = Message.fromInputStream(this.reader, this.pieceSize);

                this.messageQueue.put(message);
            }
            catch(Exception e){
                //TODO: end this thread and notify main thread that we have finished
            }
        }
        Logger.log("Reader for peer " + this.peerInfoCfg.peerId() + " is done");
    }

    //TODO: URGENT -> errors should stop the thread entirely, and we need a way to update the main thread that we are done serving this client
    void processMessageQueue() throws Exception{
        Logger.log("Processing messages");
        boolean prevChokeStatus = true;
        while(!shouldStop.get()){
            boolean shouldChoke = this.shouldChoke.get();
            if(shouldChoke && !prevChokeStatus){
                Message.sendChokeMessage(this.writer);
                prevChokeStatus = true;
            }
            else if(!shouldChoke && prevChokeStatus){
                Message.sendUnchokeMessage(this.writer);
                prevChokeStatus = false;
            }
            if(!this.waitingForPiece && !this.peerHasChoked){
                requestPiece();
            }
            Message message;
            try{
                message = this.messageQueue.take();
                handleMessage(message);
            }
            catch(Exception e){
                //TODO: end this thread and notify main thread that we have finished
                throw e;
            }
        }
        Logger.log("Handler for peer " + this.peerInfoCfg.peerId() + " is done");
    }


    void handleMessage(Message message) throws BittorrentException, IOException{
      
        switch (message.messageType){
            //Interested, NotInterested
            case 2:
            case 3:{
              Logger.log("Received " + (message.messageType == 2 ? "interested" : "not interested") + " message");
               break;
            }
            //Have
            case 4:{
                if(message.payload.length != 4){
                    throw new BittorrentException("Have message payload is not 4 bytes");
                }
                var pieceIndex = ByteBuffer.wrap(message.payload).getInt();
                this.peerBitfield.set(pieceIndex);
                if(!this.filePieces.bitfield.havePiece(pieceIndex)){
                    this.piecesToRequest.add(pieceIndex);
                    Message.sendInterestedMessage(this.writer);
                }
            }
            //Bitfield message
            case 5:{
                Logger.log("Received bitfield message");
                this.peerBitfield = new FileChunks.Bitfield(message.payload, this.numPieces);
                this.piecesToRequest = filePieces.bitfield.getInterestedIndices(this.peerBitfield); 
                Logger.log("Sending interest messages");
                if(this.piecesToRequest.size() != 0){
                Message.sendInterestedMessage(this.writer);
                }
                else{
                    Message.sendNotInterestedMessage(this.writer);
                }
                break;
            }
            //request message
            case 6:{
                Logger.log("Received request message");
                if(this.shouldChoke.get()){
                    Logger.log("warning: received request message when choked");
                    return;
                }
                int pieceIndex = ByteBuffer.wrap(message.payload).getInt();

                byte[] piece = this.filePieces.getPiece(pieceIndex);
                if(piece == null){
                    Logger.log("warning: either dont have piece or piece index is out of bounds - index = " + pieceIndex);
                    return;
                }
                Logger.log("sending piece message");
                Message.sendPieceMessage(this.writer, pieceIndex, piece);
                break;
            }
            //piece message
            case 7:{
                Logger.log("Received piece message");
                if(!this.waitingForPiece){
                    Logger.log("warning: received piece message when not waiting for piece");
                    return;
                }
                var messageBuf = ByteBuffer.wrap(message.payload);
                var pieceIndex = messageBuf.getInt();
                var piece = messageBuf.slice(4, messageBuf.remaining());
                this.filePieces.updatePiece(piece.array(), pieceIndex);
                this.waitingForPiece = false;
                //the requestPiece method moves the requested index to end of the list
                this.piecesToRequest.remove(this.piecesToRequest.size() - 1);
                if(this.filePieces.isComplete()){
                    Logger.log("File is complete");
                    this.shouldStop.set(true);
                    return;
                }
            }
            

        }
    }

    void requestPiece() throws IOException{
        if(this.piecesToRequest.size() == 0){
            return;
        }
        int randomIndex = (int)(Math.random() * this.piecesToRequest.size());        
        int pieceIndex = this.piecesToRequest.get(randomIndex);
        this.piecesToRequest.set(pieceIndex, this.piecesToRequest.get(this.piecesToRequest.size() - 1));
        this.piecesToRequest.set(this.piecesToRequest.size() - 1, pieceIndex);
        Logger.log("Requesting piece " + pieceIndex);
        Message.sendRequestMessage(writer, pieceIndex);
        this.waitingForPiece = true;
    }

    void sendBitfield(){
        try{
            var bitfieldBytes = this.filePieces.bitfield.getBytes();
            Message message = new Message((byte)Message.MessageType.Bitfield.ordinal(), bitfieldBytes);
            Message.toOutputStream(this.writer, message);
        }
        catch (IOException e){
            Logger.log("Error sending bitfield");
        }
    }
    
    public void run(){
        Logger.log("Client handler running");
        try{
            Logger.log("Beginning handshake");
            handshake();
        }
        catch (IOException e){
            Logger.log(e.getMessage());
        }
        catch(BittorrentException e){
            Logger.log(e.getMessage());
        }
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
        try{
            processMessageQueue();
        }
        catch (Exception e){

        }
        shutdown();
        return;
    }
}
