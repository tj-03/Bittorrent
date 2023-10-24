package com.bittorrent;

import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class ClientHandler extends Thread{
    Socket socket;
    AtomicBoolean shouldChoke;

    //TODO: change this to something better
    AtomicBoolean shouldStop;
    FileBitfield fileBitfield;

    PeerInfoCfg peerInfoCfg;

    //only accounts for piece message payload size
    long bytesDownloadedSinceChokeInterval = 0;
    PeerInfoCfg hostCfg;
    DataInputStream reader;
    DataOutputStream writer;
    Thread readerThread;
    BlockingQueue<Message> messageQueue;
    int pieceSize;

    public ClientHandler(Socket socket, FileBitfield fileBitfield, PeerInfoCfg hostCfg, PeerInfoCfg peerInfoCfg, CommonCfg commonCfg) throws IOException {
        shouldChoke = new AtomicBoolean(true);
        shouldStop = new AtomicBoolean(false);
        //arbitrary capacity of 64, should be fine
        this.messageQueue = new ArrayBlockingQueue<>(64);
        this.peerInfoCfg = peerInfoCfg;
        this.hostCfg = hostCfg;
        this.socket = socket;
        this.fileBitfield = fileBitfield;
        this.pieceSize = commonCfg.pieceSize();
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
    }

    //TODO: URGENT -> errors should stop the thread entirely, and we need a way to update the main thread that we are done serving this client

    void processMessageQueue(){
        Logger.log("Processing messages");
        while(!shouldStop.get()){
            Message message;
            try{
                message = this.messageQueue.take();
                Logger.log("Received message from queue: " + message);
            }
            catch(Exception e){
                //TODO: end this thread and notify main thread that we have finished
            }

        }
    }

    void handleMessage(Message message){
        Logger.log("Message received: "+ message);
    }

    void sendBitfield(){
        try{
            var bitfieldBytes = this.fileBitfield.getBitfieldBytes();
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
            ClientHandler handler;
            Thread init(ClientHandler handler){
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
