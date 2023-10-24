package com.bittorrent;

import java.io.*;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;

public class FileBitfield {
    ArrayList<byte[]> pieces;
    CommonCfg commonCfg;
    int numPieces;
    int fileSize;
    int pieceSize;
    int lastPieceSize;
    int numBits;

    boolean haveFile;
    public FileBitfield(CommonCfg commonCfg, String fileDirectory, boolean haveFile) throws BittorrentException{
        String fileName = commonCfg.fileName();
        this.fileSize = commonCfg.fileSize();
        this.pieceSize = commonCfg.pieceSize();
        this.numPieces = (int) Math.ceil(this.fileSize / this.pieceSize);
        this.lastPieceSize = this.fileSize % this.pieceSize;
        this.pieces = new ArrayList<>(Collections.nCopies(this.numPieces, null));
        int numBits = this.numPieces;

        if(numBits % 8 != 0){
            numBits += 8 - (numBits % 8);
        }
        this.numBits = numBits;
        if(haveFile){
            loadFile(fileDirectory + "/" + commonCfg.fileName());
        }
        this.haveFile = haveFile;
    }

    void loadFile(String fileName) throws BittorrentException {
        File file = new File(fileName);
        InputStream inputStream;
        try{
            inputStream = new FileInputStream(file);
        }
        catch(FileNotFoundException e){
            throw new BittorrentException(e.getMessage());
        }
        if(file.length() != this.fileSize){
            throw new BittorrentException("File size does not match expected file size");
        }
        for(int i = 0; i < this.numPieces; i++){
            int n = i == this.numPieces - 1 ? this.lastPieceSize : this.pieceSize;
            try{
                var bytes = inputStream.readNBytes(n);
                this.pieces.set(i, bytes);
            }
            catch(IOException e){
                throw new BittorrentException(e.getMessage());
            }
        }
    }


    //Need to think about how threads are accessing this object and if I should lock individual fields or the whole object
    //TODO: need tests for this
    public byte[] getBitfieldBytes(){
        synchronized (this.pieces){
            byte[] bytes = new byte[this.numBits / 8];
            for(int i = 0; i < this.pieces.size(); i++){
                if(this.pieces.get(i) != null){
                    bytes[i / 8] |= 1 << ((i % 8));
                }
            }
            return bytes;
        }
    }




}
