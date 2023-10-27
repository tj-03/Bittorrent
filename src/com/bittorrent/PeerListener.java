package com.bittorrent;

public interface PeerListener {
    void onPieceReceived(Event event);
    void onMessage(Message message);
    void onServerShutdown();
}
