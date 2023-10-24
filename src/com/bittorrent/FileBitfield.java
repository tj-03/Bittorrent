package com.bittorrent;

import java.util.ArrayList;

public class FileBitfield {
    ArrayList<ArrayList<Byte>> pieces;
    boolean isBitfieldCached = false;
    ArrayList<Integer> bitfield;

    CommonCfg commonCfg;
    int numPieces;
    int lastPieceSize;
    public FileBitfield(CommonCfg commonCfg) {
        String fileName = commonCfg.fileName();
        int fileSize = commonCfg.fileSize();
        int pieceSize = commonCfg.pieceSize();
        this.numPieces = (int) Math.ceil(fileSize / pieceSize);
        this.lastPieceSize = fileSize % pieceSize;
    }

    public ArrayList<Integer> calculateBitfield(){
        //TODO: implement
        //TODO: maybe cache result of this
        return null;
    }

}
