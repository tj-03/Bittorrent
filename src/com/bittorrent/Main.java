package com.bittorrent;
import java.io.*;
import java.net.*;
import java.util.Arrays;
import java.util.Properties;


public class Main {
    public static void main(String[] args) throws IOException {
        int portNumber;
        if(args.length < 1){
            System.out.println("Please enter a valid port number");
            return;
        }
        try {
            portNumber = Integer.parseInt(args[0]);
        }
        catch (Exception e) {
            System.out.println("Please enter a valid port number");
            return;
        }
        System.out.println(readCommonCfgFile());


    }

    record CommonCfg(int numberOfPreferredNeighbors, int unchokingInterval, int optimisticUnchokingInterval, String fileName, int fileSize, int pieceSize) {
    }
    public static CommonCfg readCommonCfgFile() throws IOException {
        Properties properties;
        try (InputStream inputStream = new FileInputStream("Common.cfg")) {
            properties = new Properties();
            properties.load(inputStream);
        }
        //convert to record
        String[] propNames = {"NumberOfPreferredNeighbors", "UnchokingInterval", "OptimisticUnchokingInterval", "FileName", "FileSize", "PieceSize"};
        int[] propValues = new int[propNames.length];
        for (int i = 0; i < propNames.length; i++) {
            if(i == 3)
                continue;
            propValues[i] = Integer.parseInt(properties.getProperty(propNames[i]));
        }
        return new CommonCfg(propValues[0], propValues[1], propValues[2], properties.getProperty("FileName"), propValues[4], propValues[5]);
    }

    public Properties readPeerInfoCfgFile(){
        Properties prop = new Properties();
        try {
            prop.load(new FileInputStream("PeerInfo.cfg"));
        } catch (IOException e) {
            e.printStackTrace();
        }
        return prop;
    }
}
