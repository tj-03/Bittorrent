package com.bittorrent;

public class Logger {
    static public String hostId = "";
    public static void log(Object message){
        System.out.println("Host [" + hostId  + "]: " + message);
    }
}
