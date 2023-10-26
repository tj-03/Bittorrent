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
        Piece;

        public static MessageType fromByte(byte b){
            for(var t: MessageType.values()){
                if(t.ordinal() == b){
                    return t;
                }
            }
            return null;
        }
    }

    public MessageType messageType;
    public byte[] payload;

    Message(MessageType type, byte[] payload){
        this.messageType = type;
        this.payload = payload;
    }

    public static Message fromInputStream(DataInputStream stream, int maxPayloadSize) throws IOException, BittorrentException {
        var messageLength = stream.readInt();
        if(messageLength > maxPayloadSize){
            throw new BittorrentException("message length to big: " + messageLength);
        }
        var messageType = MessageType.fromByte(stream.readByte());
        if(messageType == null){
            throw new BittorrentException("invalid message type byte: " + messageType);
        }
        return new Message(messageType, stream.readNBytes(messageLength));
    }

    public static void toOutputStream(DataOutputStream stream, Message message) throws IOException {
        var buf = ByteBuffer.allocate(1 + 4 + (message.payload != null ? message.payload.length : 0));
        buf.putInt(message.payload != null ? message.payload.length : 0);
        buf.put((byte)message.messageType.ordinal());
        if(message.payload != null && message.payload.length != 0){
            buf.put(message.payload);
        }
        stream.write(buf.array());
        stream.flush();
    }

    public static void sendInterestedMessage(DataOutputStream stream) throws IOException {
        var message = new Message(MessageType.Interested, null);
        Message.toOutputStream(stream, message);
    }

    public static void sendNotInterestedMessage(DataOutputStream stream) throws IOException {
        var message = new Message(MessageType.NotInterested, null);
        Message.toOutputStream(stream, message);
    }

    public static void sendChokeMessage(DataOutputStream stream) throws IOException {
        var message = new Message(MessageType.Choke, null);
        Message.toOutputStream(stream, message);
    }

    public static void sendUnchokeMessage(DataOutputStream stream) throws IOException {
        var message = new Message(MessageType.Unchoke, null);
        Message.toOutputStream(stream, message);
    }

    public static void sendHaveMessage(DataOutputStream stream, int pieceIndex) throws IOException {
        var buf = ByteBuffer.allocate(4);
        buf.putInt(pieceIndex);
        var message = new Message(MessageType.Have, buf.array());
        Message.toOutputStream(stream, message);
    }

    public static void sendRequestMessage(DataOutputStream stream, int pieceIndex) throws IOException {
        var buf = ByteBuffer.allocate(4);
        buf.putInt(pieceIndex);
        var message = new Message(MessageType.Request, buf.array());
        Message.toOutputStream(stream, message);
    }

    public static void sendPieceMessage(DataOutputStream stream, int pieceIndex, byte[] pieceData) throws IOException {
        var buf = ByteBuffer.allocate(4 + pieceData.length);
        buf.putInt(pieceIndex);
        buf.put(pieceData);
        var message = new Message(MessageType.Piece, buf.array());
        Message.toOutputStream(stream, message);
    }

    @Override
    public String toString(){
        return "Message{type: %d, payload len: %d}".formatted(this.messageType, this.payload != null ? this.payload.length : 0);
    }
}
