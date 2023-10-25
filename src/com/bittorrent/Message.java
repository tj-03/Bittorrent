package com.bittorrent;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public class Message {
    enum MessageType{
        Choke,
        Unchoke,
        Interested,
        NotInterested,
        Have,
        Bitfield,
        Request,
        Piece
    }

    public byte messageType;
    public byte[] payload;

    Message(byte type, byte[] payload){
        this.messageType = type;
        this.payload = payload;
    }

    public static Message fromInputStream(DataInputStream stream, int maxPayloadSize) throws IOException, BittorrentException {
        var messageLength = stream.readInt();
        if(messageLength > maxPayloadSize){
            throw new BittorrentException("message length to big: " + messageLength);
        }
        var messageType = stream.readByte();
        if(messageType > 7){
            throw new BittorrentException("invalid message type: " + messageType);
        }

        //choke, unchoke, interested, not interested
        if(messageType <=  3){
            if(messageLength != 0)
                throw new BittorrentException("invalid payload size %d for message type %b".formatted(messageLength, messageType));
            return new Message(messageType, null);
        }
        return new Message(messageType, stream.readNBytes(messageLength));
    }

    public static void toOutputStream(DataOutputStream stream, Message message) throws IOException {
        var buf = ByteBuffer.allocate(1 + 4 + (message.payload != null ? message.payload.length : 0));
        buf.putInt(message.payload != null ? message.payload.length : 0);
        buf.put(message.messageType);
        if(message.payload != null && message.payload.length != 0){
            buf.put(message.payload);
        }
        stream.write(buf.array());
        stream.flush();
    }

    public static void sendInterestedMessage(DataOutputStream stream) throws IOException {
        var message = new Message((byte)MessageType.Interested.ordinal(), null);
        Message.toOutputStream(stream, message);
    }

    public static void sendNotInterestedMessage(DataOutputStream stream) throws IOException {
        var message = new Message((byte)MessageType.NotInterested.ordinal(), null);
        Message.toOutputStream(stream, message);
    }

    public static void sendChokeMessage(DataOutputStream stream) throws IOException {
        var message = new Message((byte)MessageType.Choke.ordinal(), null);
        Message.toOutputStream(stream, message);
    }

    public static void sendUnchokeMessage(DataOutputStream stream) throws IOException {
        var message = new Message((byte)MessageType.Unchoke.ordinal(), null);
        Message.toOutputStream(stream, message);
    }

    public static void sendHaveMessage(DataOutputStream stream, int pieceIndex) throws IOException {
        var buf = ByteBuffer.allocate(4);
        buf.putInt(pieceIndex);
        var message = new Message((byte)MessageType.Have.ordinal(), buf.array());
        Message.toOutputStream(stream, message);
    }

    public static void sendRequestMessage(DataOutputStream stream, int pieceIndex) throws IOException {
        var buf = ByteBuffer.allocate(4);
        buf.putInt(pieceIndex);
        var message = new Message((byte)MessageType.Request.ordinal(), buf.array());
        Message.toOutputStream(stream, message);
    }

    public static void sendPieceMessage(DataOutputStream stream, int pieceIndex, byte[] pieceData) throws IOException {
        var buf = ByteBuffer.allocate(4 + pieceData.length);
        buf.putInt(pieceIndex);
        buf.put(pieceData);
        var message = new Message((byte)MessageType.Piece.ordinal(), buf.array());
        Message.toOutputStream(stream, message);
    }

    @Override
    public String toString(){
        return "Message{type: %d, payload len: %d}".formatted(this.messageType, this.payload != null ? this.payload.length : 0);
    }

}
