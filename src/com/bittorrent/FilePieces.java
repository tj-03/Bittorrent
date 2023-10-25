package com.bittorrent;

import java.io.*;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;

public class FilePieces {
    //TODO: get rid of this and use BitSet instead
    public static class Bitfield{
        int numBits;
        int totalBits;
        byte[] bits;
        
        public Bitfield(int numBits){
            this.numBits = numBits;
            if(numBits % 8 != 0){
                numBits += 8 - (numBits % 8);
            }
            this.totalBits = numBits;
            this.bits = new byte[this.totalBits / 8];
        }

        public Bitfield(byte[] bytes, int numBits){
            this.numBits = numBits;
            this.totalBits = bytes.length * 8;
            this.bits = bytes;
        }

        public void set(int index){
            this.bits[index / 8] |= 1 << ((index % 8));
        }

        public boolean get(int index){
            return (this.bits[index / 8] & (1 << (index % 8))) != 0;
        }

        void setAll(){
            for(int i = 0; i < this.bits.length; i++){
                this.bits[i] = (byte) 0xFF;
            }
            this.bits[this.bits.length - 1] = (byte) (0xFF >>> (this.totalBits - this.numBits));
        }

        public synchronized byte[] getBytes(){
            return this.bits.clone();
        }

        public synchronized ArrayList<Integer> getInterestedIndices(Bitfield other){
            var interested = new ArrayList<Integer>();
            for(int i = 0; i < this.numBits; i++){
                if(!this.get(i) && other.get(i)){
                    interested.add(i);
                }
            }
            return interested;
        }

        public synchronized boolean havePiece(int index){
            return this.get(index);
        }

        public synchronized void setPiece(int index){
            this.set(index);
        }


    }
    ArrayList<byte[]> pieces;
    CommonConfig commonCfg;
    int numPieces;
    int fileSize;
    int pieceSize;
    int lastPieceSize;
    int numBits;
    int remainingPieces;
    public Bitfield bitfield;

    boolean haveFile;
    public FilePieces(CommonConfig commonCfg, String fileDirectory, boolean haveFile) throws BittorrentException{
        this.fileSize = commonCfg.fileSize();
        this.pieceSize = commonCfg.pieceSize();
        this.numPieces = (int) Math.ceil((double)this.fileSize / this.pieceSize);
        int remainder = this.fileSize % this.pieceSize;
        this.lastPieceSize = remainder == 0 ? this.pieceSize : remainder;
        this.pieces = new ArrayList<>(Collections.nCopies(this.numPieces, null));
        this.bitfield = new Bitfield(this.numPieces);
        this.remainingPieces = this.numPieces;
        this.commonCfg = commonCfg;
        if(haveFile){
            loadFile(fileDirectory + "/" + commonCfg.fileName());
            this.bitfield.setAll();
            this.remainingPieces = 0;
        }
        this.haveFile = haveFile;
    }

    public void writeToFile(String fileDirectory){
        if(!isComplete()){
            Logger.log("warning: attempting to write incomplete file");
            return;
        }
        File file = new File(fileDirectory + "/" + this.commonCfg.fileName());
        try(OutputStream outputStream = new FileOutputStream(file);){
            for(int i = 0; i < this.numPieces; i++){
                int n = i == this.numPieces - 1 ? this.lastPieceSize : this.pieceSize;
                outputStream.write(this.pieces.get(i), 0, n);
            }
        }
        catch(IOException e){
            Logger.log("error: " + e.getMessage());
            return;
        }
        Logger.log(fileDirectory + "/" + this.commonCfg.fileName() + " successfully written");
    }
    public synchronized void updatePiece(byte[] piece, int index) throws BittorrentException{
        if(index >= this.numPieces){
            throw new BittorrentException("index out of bounds");
        }
        if((index == this.numPieces - 1) && (piece.length != this.lastPieceSize)){
            throw new BittorrentException("piece size does not match expected piece size (expected: %d, actual: %d, index: %d"
                    .formatted(this.lastPieceSize, piece.length, index));
        }
        if(index < this.numPieces - 1 && piece.length != this.pieceSize){
            throw new BittorrentException("piece size does not match expected piece size (expected: %d, actual: %d. index: %d)"
                    .formatted(this.pieceSize, piece.length, index));
        }
        if(this.bitfield.get(index)){
            return;
        }
        this.remainingPieces--;
        this.pieces.set(index, piece);
        this.bitfield.set(index);
    }

    public byte[] getPiece(int index){
        synchronized(this.bitfield){
            if(index < 0 || index >= this.numPieces){
                return null;
            }
            if(!this.bitfield.get(index)){
                return null;
            }
        }
        return this.pieces.get(index);

    }

    public synchronized boolean isComplete(){
        return this.remainingPieces == 0;
    }

    void loadFile(String fileName) throws BittorrentException {
        File file = new File(fileName);
        try(InputStream inputStream = new FileInputStream(file);){
            if(file.length() != this.fileSize){
                throw new BittorrentException("File size does not match expected file size");
            }
            for(int i = 0; i < this.numPieces; i++){
                int n = i == this.numPieces - 1 ? this.lastPieceSize : this.pieceSize;
                var bytes = inputStream.readNBytes(n);
                this.pieces.set(i, bytes);
            }
        }
        catch(IOException e){
            throw new BittorrentException(e.getMessage());
        }  
    }


    //Need to think about how threads are accessing this object and if I should lock individual fields or the whole object
    //TODO: need tests for this
    




}
