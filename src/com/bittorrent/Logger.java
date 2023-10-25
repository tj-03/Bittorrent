package com.bittorrent;

import java.sql.Timestamp;

public class Logger {
    static public String hostId = "";
    public static void log(Object message){
        System.out.println("[%s]: Peer [%s] %s".formatted(new Timestamp(System.currentTimeMillis()), hostId, message));
    }
}
