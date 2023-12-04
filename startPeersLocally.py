
import subprocess
from time import sleep
f = open("PeerInfo.cfg", "r")
lines = f.readlines()

peerNums = []
for line in lines:

    peerNum = line.split(" ")[0]
    peerNums.append(peerNum)

print(peerNums)
#peerNums = peerNums[1:]
for peerNum in peerNums:
    cmd =  f"java -classpath bin com.bittorrent.Main {peerNum} true"
   # print("Starting peer " + peerNum + " locally")
    subprocess.Popen(cmd, shell=True)
    sleep(.2)
