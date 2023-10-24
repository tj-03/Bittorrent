package com.bittorrent;
import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Properties;


public class Main {
    public static void main(String[] args) throws IOException, BittorrentException {

        int hostId;
        if(args.length < 1){
            System.out.println("Please enter a valid port number");
            return;
        }
        try {
            hostId = Integer.parseInt(args[0]);
        }
        catch (Exception e) {
            System.out.println("Please enter a valid port number");
            return;
        }

        var commonCfg = readCommonCfgFile();
        var peerCfgs = readPeerInfoCfgFile();

        boolean hasHostId = false;
        for(var peerCfg: peerCfgs){
            if(peerCfg.peerId() == hostId){
                hasHostId = true;
                break;
            }
        }
        if(!hasHostId){
            System.out.println("Please provide a peer id that matches one of the peers in PeerInfo.cfg");
        }

        Logger.hostId = args[0];
        Logger.log("Started");
        if(args.length >= 2){
            try{
                boolean runningLocally = Boolean.parseBoolean(args[1]);
                if(runningLocally){
                    Logger.log("Running locally");
                    updatePeerInfoCfgs(peerCfgs);
                }
            }
            catch (Exception e){
                Logger.log("Please enter a valid boolean for running locally");
                return;
            }
        }

        //Logger.log(commonCfg);
        //Logger.log(peerCfgs);
        Server server = new Server(hostId, commonCfg, peerCfgs);
        try{
            server.start();
        }
        catch (Exception e){
            Logger.log("Error starting server: " + e.getMessage());
            e.printStackTrace();
        }
        Logger.log("Finished");
    }

    public static void updatePeerInfoCfgs(ArrayList<PeerInfoCfg> cfgs){
        for(int i = 0; i < cfgs.size(); i++){
            PeerInfoCfg cfg = cfgs.get(i);
            PeerInfoCfg newCfg = new PeerInfoCfg(cfg.peerId(), "127.0.0.1", 9000 + i, cfg.hasFile());
            cfgs.set(i, newCfg);
        }
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

    public static ArrayList<PeerInfoCfg> readPeerInfoCfgFile() throws IOException {
        FileReader reader = new FileReader("PeerInfo.cfg");
        Properties prop = new Properties();
        BufferedReader br = new BufferedReader(reader);
        String line;
        ArrayList<PeerInfoCfg> peerInfoCfgs = new ArrayList<>();
        while ((line = br.readLine()) != null) {
            String[] splitLine = line.split(" ");
            assert splitLine.length == 4;
            var cfg = new PeerInfoCfg(Integer.parseInt(splitLine[0]), splitLine[1], Integer.parseInt(splitLine[2]), Boolean.parseBoolean(splitLine[3]));
            peerInfoCfgs.add(cfg);
        }
        return peerInfoCfgs;
    }
}
