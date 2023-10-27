package com.bittorrent;

public class BittorrentException extends Exception{
    public BittorrentException() {
    }
    public BittorrentException(String message) {
        super(message);
    }
    public BittorrentException(Exception e) {
        super(e);
    }

}
