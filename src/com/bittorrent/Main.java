package com.bittorrent;
import java.io.*;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
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
        catch (NumberFormatException e) {
            System.out.println("Please enter a valid port number");
            return;
        }

        var peerCfgs = readPeerInfoCfgFile();
        var commonCfg = readCommonCfgFile(peerCfgs.size() - 1);


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

        Logger.init(args[0], "log", "log%s.txt".formatted(args[0]));
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
        var path = Path.of("peer_" + hostId);
        Server server;
        if(!Files.exists(path)){
            Files.createDirectory(path);
        }
        try{
             server = new Server(hostId, commonCfg, peerCfgs);
             server.start();
        }
        catch(BittorrentException | IOException e){
            Logger.log("Finished with error: " + e.getMessage());
            return;
        }
        Logger.log("finished successfully, writing to file for host %d".formatted(hostId));
        server.fileBitfield.writeToFile(path.toString());
        Logger.log("Finished");
    }

    public static void updatePeerInfoCfgs(ArrayList<PeerInfoConfig> cfgs){
        for(int i = 0; i < cfgs.size(); i++){
            PeerInfoConfig cfg = cfgs.get(i);
            PeerInfoConfig newCfg = new PeerInfoConfig(cfg.peerId(), "127.0.0.1", 9000 + i, cfg.hasFile());
            cfgs.set(i, newCfg);
        }
    }
    public static CommonConfig readCommonCfgFile(int numPeers) throws IOException {
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
        return new CommonConfig(Math.min(numPeers, propValues[0]), propValues[1], propValues[2], properties.getProperty("FileName"), propValues[4], propValues[5]);
    }

    public static ArrayList<PeerInfoConfig> readPeerInfoCfgFile() throws IOException {
        FileReader reader = new FileReader("PeerInfo.cfg");
        BufferedReader br = new BufferedReader(reader);
        String line;
        ArrayList<PeerInfoConfig> peerInfoCfgs = new ArrayList<>();
        while ((line = br.readLine()) != null) {
            String[] splitLine = line.split(" ");
            assert splitLine.length == 4;
            var cfg = new PeerInfoConfig(Integer.parseInt(splitLine[0]), splitLine[1], Integer.parseInt(splitLine[2]), Integer.parseInt(splitLine[3]) != 0);
            peerInfoCfgs.add(cfg);
        }
        br.close();
        return peerInfoCfgs;
    }
}
