# BitBox
## A distributed point-to-point file sharing system

The File System Manager monitors a given directory in the file system on a local machine for changes to files, and a BitBox Peer can relay these changes to another BitBox Peer on a remote machine. 


## System Events
When there are changes in the file system, File System Manager will create the following events:
- FILE_CREATE 
- FILE_DELETE
- FILE_MODIFY
- DIRECTORY_CREATE
- DIRECTORY_DELETE


## APIs
The BitBox Peer is an observer of these events. The File System Manager provides APIs for the BitBox Peer to safely interact with files and directories inside the share directory. Example APIs are listed below: 

- cancelFileLoader(String)
- checkShortcut(String)
- checkWriteComplete(String) 
- deleteFile(String, long)
- fileNameExists(String)
- isSafePathName(String)


## TCP connections & Message passing
All communication will be via persistent TCP connections between the peers. All messages will be in JSON format. For a HANDSHAKE_REQUEST, the message is constructed as the following:

```
{
"command": "HANDSHAKE_REQUEST",
"hostPort" : {
    "host" : "sunrise.cis.unimelb.edu.au",
    "port" : 8111
 }
}
```

## Public and private key technique
Public and private key technique, along with symmetric key encryption in general, is used to distribute symmetric key to connected peers in order to provide read/write permission.

The process of authenticating peers is described as the following:

1. First, each peer creates its own public and private key and sends the public key to all its connected peers.

2.Second, when a peer (peer A) initially connects to peers in its peer list. Peer A will send SYNMETRIC_KEY_DISTRIBUTE protocol to all the connected peers. The SYNMETRIC_KEY_DISTRIBUTE message contains the owner of the key and the symmetric key which is generated once peer A starts the connection. 

3. That symmetric key is used to encrypt and decrypt FILE_CREATE_REQUEST and FILE_BYTES_REQUEST, which is followed by the FILE_MODIFY_RESPONSE in order to constrain that only peers with permission can receive the file and can modify the file.

4. Peers who receive the symmetric key will store the key and mapped it to the sender according to the owner field 
of SYNMETRIC_KEY_DISTRIBUTE.
