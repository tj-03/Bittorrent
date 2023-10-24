package com.bittorrent;

import java.util.ArrayList;

public class FileBitfield {
    ArrayList<ArrayList<Byte>> pieces;
    boolean isBitfieldCached = false;
    ArrayList<Integer> bitfield;

    CommonCfg commonCfg;
    int numPieces;
    int lastPieceSize;
    boolean haveFile;
    public FileBitfield(CommonCfg commonCfg, boolean haveFile) throws BittorrentException{
        String fileName = commonCfg.fileName();
        int fileSize = commonCfg.fileSize();
        int pieceSize = commonCfg.pieceSize();
        this.numPieces = (int) Math.ceil(fileSize / pieceSize);
        this.lastPieceSize = fileSize % pieceSize;
        this.haveFile = haveFile;
    }


    public ArrayList<Integer> calculateBitfield(){
        //TODO: implement
        //TODO: maybe cache result of this
        return null;
    }

}
