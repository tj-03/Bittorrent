package com.bittorrent;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.sql.Timestamp;

public class Logger {
    static public String hostId = "";
    String directory;
    static File file;
    static OutputStream out;
    public static void init(String hostId, String directory, String logFile){
        Logger.hostId = hostId;
        Logger.file = new File(directory + "/" + logFile);
        try{
            Logger.out = new FileOutputStream(Logger.file);
            System.out.println("Logging to %s".formatted(Logger.file.getAbsolutePath()));
        }
        catch(FileNotFoundException e){
            System.out.println("File not found");
            Logger.out = null;
        }
    }
    public static void log(Object message){
        String log = "[%s]: Peer [%s] %s".formatted(new Timestamp(System.currentTimeMillis()), hostId, message);
        System.out.println(log);
        if(Logger.out != null){
            try {
                Logger.out.write((log + "\n").getBytes());
            } catch (IOException e) {
                System.out.println("Error writing to log file");
            };
        }
    }

    public static void logErr(Object message){
        String log = "[%s]: Peer [%s] [ERROR]: %s".formatted(new Timestamp(System.currentTimeMillis()), hostId, message);
        System.out.println(log);
        if(Logger.out != null){
            try {
                Logger.out.write((log + "\n").getBytes());
            } catch (IOException e) {
                System.out.println("Error writing to log file");
            };
        }
    }

    public static void log(Object message, int peerId){
        System.out.println("[%s]: Peer [%s] %s".formatted(new Timestamp(System.currentTimeMillis()), hostId, message));
        
    }
}
