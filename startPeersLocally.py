
import subprocess
f = open("PeerInfo.cfg", "r")
lines = f.readlines()

peerNums = []
for line in lines:

    peerNum = line.split(" ")[0]
    peerNums.append(peerNum)

print(peerNums)
for peerNum in peerNums:
    cmd =  f"C:/Users/josep/.jdks/jdk-19.0.2/bin/java.exe -classpath C:/Users/josep/Desktop/java_stuff/bittorrent/out/production/bittorrent com.bittorrent.Main {peerNum} true"
    subprocess.Popen(cmd, shell=True)
