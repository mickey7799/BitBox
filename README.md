# BitBox
### A distributed point-to-point file sharing system

The File System Manager monitors a given directory in the file system on a local machine for changes to files, and a BitBox Peer can relay these changes to another BitBox Peer on a remote machine. 

When there are changes in the file system, File System Manager will create the following events:
- FILE_CREATE 
- FILE_DELETE
- FILE_MODIFY
- DIRECTORY_CREATE
- DIRECTORY_DELETE

The BitBox Peer is an observer of these events. The File System Manager provides APIs for the BitBox Peer to safely interact with files and directories inside the share directory.

All communication will be via persistent TCP connections between the peers. All messages will be in JSON format.

Public and private key technique, along with symmetric key encryption in general is used to distribute symmetric key to connected peers in order to provide read/write permission. 
