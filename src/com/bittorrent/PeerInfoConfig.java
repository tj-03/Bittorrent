package com.bittorrent;

public record PeerInfoConfig(int peerId, String hostName, int port, boolean hasFile) {  }
