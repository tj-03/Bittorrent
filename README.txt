Project Members: Tristan Joseph (tristanjoseph@ufl.edu), Aaron Upchurch, Blake Grey

Group Number: 48

All the work was done by Tristan Joseph (me), my teaammates did not contribute to the project and did not communicate with me for entirety of the semester.

All functionality for the client was implemented, but unforutunately I was not able to get the program to 
run on the remote linux machines. 

This program implements a simple version of the bittorrent

The program was built using a JDK on version 19.0.2, and the remote machines only had version 11. I tried to rewrite the program to be compatible with version 11, but I was not able to get it to work. 

To run the project locally, unzip, and use the following commands to build:


```
./build.bat
```

Or if you are on a linux machine:

```
./build.sh
```

Alternatively, you can manually use ```javac -cp bin -d bin {src_files} ``` to compile.
Then to run the program locally:

```
java -classpath bin com.bittorrent.Main {peerNum} true
```
Where peerNum is the number of the peer you want to run, and the last argument is whether or not you want to run the program locally.

If you want to try running on a remote machine, simply omit the last argument or set it to "false".

Make sure that the PeerInfo.cfg and Common.cfg files are in the root directory.
Also make sure that the direcotires peer_1001, peer_1002, etc. are in the root directory, and that the file to download is in the correct peer_* directory.




