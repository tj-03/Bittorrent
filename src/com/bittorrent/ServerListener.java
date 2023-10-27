package com.bittorrent;

public interface ServerListener{
    void onTimeout(TimerType t) throws BittorrentException;
    void onPieceReceived(int pieceIndex, int fromPeerId) throws BittorrentException;
    void onPeerDisconnected(int peerId);
    void onError();
}