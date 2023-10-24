package com.bittorrent;

import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

public class ClientHandler extends Thread{
    Socket socket;
    AtomicBoolean shouldChoke;
    FileBitfield fileBitfield;

    PeerInfoCfg peerInfoCfg;

    //only accounts for piece message payload size
    long bytesDownloadedSinceChokeInterval = 0;
    PeerInfoCfg hostCfg;
    DataInputStream reader;
    DataOutputStream writer;

    public ClientHandler(Socket socket, PeerInfoCfg hostCfg, PeerInfoCfg peerInfoCfg) throws IOException {
        shouldChoke = new AtomicBoolean();
        shouldChoke.set(true);
        this.peerInfoCfg = peerInfoCfg;
        this.hostCfg = hostCfg;
        this.socket = socket;
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
        shutdown();
        return;
    }
}
