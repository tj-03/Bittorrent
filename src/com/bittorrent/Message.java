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
    public byte[] messagePayload;

    Message(byte type, byte[] payload){
        this.messageType = type;
        this.messagePayload = payload;
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
        var buf = ByteBuffer.allocate(1 + 4 + message.messagePayload.length);
        buf.putInt(message.messagePayload.length);
        buf.put(message.messageType);
        if(message.messagePayload.length != 0){
            buf.put(message.messagePayload);
        }
        stream.write(buf.array());
        stream.flush();
    }

    @Override
    public String toString(){
        return "Message{type: %d, payload len: %d}".formatted(this.messageType, this.messagePayload.length);
    }

}
