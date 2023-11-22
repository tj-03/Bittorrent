package com.bittorrent;

enum EventType{
    UpdateChokeStatus,
    PeerShouldShutdown,
    ServerShouldShutdown,
    PieceReceived,
    //Sent if disconnect
    //shutdownSignal MUST be true if disconnect was due to error
    DisconnectedFromPeer
}
//TODO: rewrite peerID to be a handlerID and check if handshake failed, since if handshake fails we don't know the peerID
record Event(EventType type, int peerId, int pieceIndex){}