package unimelb.bitbox;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import javax.net.ServerSocketFactory;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.ArrayList;
import java.util.Base64;
import java.util.logging.Logger;
import unimelb.bitbox.util.*;
import unimelb.bitbox.util.FileSystemManager.FileSystemEvent;


public class ServerMain implements FileSystemObserver {
    private static Logger log = Logger.getLogger(ServerMain.class.getName());
    private static FileSystemManager fileSystemManager;
    static ArrayList<Document> connectedPeers = new ArrayList<>();
    static ArrayList<Document> incommingPeers = new ArrayList<>();
    private static ArrayList<Document> deadPeers = new ArrayList<>();

    private static ArrayList<Triplet> sendTask = new ArrayList<>();

    static final ArrayList<Document> PEERS = getPeers();

    ServerMain() throws NumberFormatException, IOException, NoSuchAlgorithmException {
        fileSystemManager = new FileSystemManager(Configuration.getConfigurationValue("path"),
                this);
    }

    @Override
    public void processFileSystemEvent(FileSystemEvent fileSystemEvent) {
        //check remote file system to ensure they has same contents by using generateSyncEvents().
        //   then using fileSystemObserver.processFileSystemEvent(event) to send the event, in a for loop.
        for (Document i : connectedPeers) {
            // Add a new task to task list
            if (i != null) {
                sendingEvent(fileSystemEvent, i.getString("host"), i.getInteger("port"));
            } else {
                connectedPeers.remove(i);
            }
        }
    }

    static void initialConnections() {
        for (Document i : PEERS) {
            //create new Thread for each connection
            log.info("trying to connect with " + i.getString("host") + ":" + i.getInteger("port"));
            Thread s = new Thread(() -> clientThread(i.getString("host"), i.getInteger("port")));
            s.start();
        }

    }


    private static void clientThread(String host, int port) {
        try {
            String input;
            Socket socket = new Socket(host, port);
            BufferedReader bufferIn = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter bufferOut = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));


            Document newPeer;
            log.info("successful connected with " + host + ":" + port);

            //Respond from server
            bufferOut.write(protocolHandShake("HANDSHAKE_REQUEST", socket.getLocalAddress().getHostAddress()).toJson() + "\n");
            bufferOut.flush();

            input = communicateRead(bufferIn);
            log.info(host + ":" + port + " response:\t" + Document.parse(input).getString("command"));
            switch (Document.parse(input).getString("command")) {
                case "INVALID_POTOCOL":
                    // Add the peer to dead peers list
                    newPeer = new Document();
                    newPeer.append("host", host);
                    newPeer.append("port", port);
                    deadPeers.add(newPeer);
                    log.warning("failed to connect with" + host + ":" + port);
                    socket.close();
                    break;
                case "CONNECTION_REFUSED":
                    log.info(Document.parse(input).getString("message"));
                    Document dPeer = new Document();
                    dPeer.append("host", host);
                    dPeer.append("port", port);
                    deadPeers.add(dPeer);
                    ArrayList<Document> newPeers = (ArrayList<Document>) Document.parse(input).get("peers");

                    for (Document i : newPeers) {
                        long portLong = i.getLong("port");
                        int portInt = Math.toIntExact(portLong);
                        Document realnewPeer = new Document();
                        realnewPeer.append("host", i.getString("host"));
                        realnewPeer.append("port", portInt);
                        if (!isContains(realnewPeer, deadPeers)) {
                            log.info("trying to connect with " + i.getString("host") + ":" + portInt);
                            Thread s = new Thread(() -> clientThread(i.getString("host"), portInt));
                            s.start();
                        }
                    }
                    socket.close();
                    break;
                case "HANDSHAKE_RESPONSE":
                    // Add the peer to connected peers list
                    newPeer = new Document();
                    newPeer.append("host", host);
                    newPeer.append("port", port);
                    connectedPeers.add(newPeer);
                    log.info("successful connected with" + host + ":" + port);
                    // start listening in same socket as well as same thread
                    // Now, you are the client
                    serverClient(host, port, bufferIn, bufferOut, socket);
                    break;
            }

        } catch (IOException e) {
            log.warning("failed to connect with " + host + ":" + port);
            Document newPeer = new Document();
            newPeer.append("host", host);
            newPeer.append("port", port);
            deadPeers.add(newPeer);
        }
    }


    /**
     * To create a Server to keep waiting for peers's connection
     *
     * @param port the local port of the server itself
     */
    static void createServerSocket(int port) {
        ServerSocketFactory factory = ServerSocketFactory.getDefault();
        try (ServerSocket server = factory.createServerSocket(port)) {
            log.info("Waiting for other peers connection.");

            // Wait for connections.
            while (true) {
                Socket client = server.accept();
                BufferedReader bufferIn = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                BufferedWriter bufferOut = new BufferedWriter(new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8));
                // Start a new thread for a connection
                // Now you are the server
                log.info("Peer number " + (incommingPeers.size() + 1) + " is applying for connection.");
                Thread s = new Thread(() -> serverThread(client, bufferIn, bufferOut));
                s.start();

            }
        } catch (IOException e) {
            log.warning(e.getMessage());
        }
    }

    private static void serverThread(Socket socket, BufferedReader bufferIn, BufferedWriter bufferOut) {
        try {
            String input = communicateRead(bufferIn);
            if (input == null) {
                log.info("received an invalid protocol, thread closed");
                socket.close();
            } else {
                Document hostport = (Document) Document.parse(input).get("hostPort");
                log.info("connected with " + socket.getInetAddress().getHostName());
                long longPort = hostport.getLong("port");
                int intPort = Math.toIntExact(longPort);
                Document dPeer = new Document();
                dPeer.append("host", hostport.getString("host"));
                dPeer.append("port", intPort);
                log.info(hostport.getString("host") + ":" + intPort + "\thandshaked to you.");

                if (!Document.parse(input).getString("command").equals("HANDSHAKE_REQUEST")) {
                    log.info("received an invalid protocol, thread closed");
                    bufferOut.write(protocolInvalid().toJson() + "\n");
                    bufferOut.flush();
                    socket.close();
                } else if (incommingPeers.size() >= Integer.parseInt(Configuration.getConfigurationValue("maximumIncommingConnections"))) {
                    bufferOut.write(protocolConnectionRefused().toJson() + "\n");
                    bufferOut.flush();
                    log.info("reach maximum number of connections, thread closed");
                    socket.close();
                } else {
                    bufferOut.write(protocolHandShake("HANDSHAKE_RESPONSE", socket.getLocalAddress().getHostAddress()).toJson() + "\n");
                    bufferOut.flush();
                    removeDoc(dPeer, deadPeers);
                    connectedPeers.add(dPeer);
                    incommingPeers.add(dPeer);

                    //start the communication
                    serverClient(hostport.getString("host"), intPort, bufferIn, bufferOut, socket);
                }
            }
        } catch (IOException e) {
            log.warning(e.getMessage());
        }
    }


    /**
     * The individual thread created by the server for communicating with a client
     */
    private static void serverClient(String host, int port, BufferedReader bufferIn, BufferedWriter bufferOut, Socket socket) {
        ArrayList<String> fileLoaderPathName = new ArrayList<>();

        // let the new thread to send contents
        Thread se = new Thread(() -> sendThread(socket, bufferOut, host, port));
        se.start();

        //Add sycnEvent to task list
        ArrayList<FileSystemEvent> syncEvents = fileSystemManager.generateSyncEvents();
        for (FileSystemEvent j : syncEvents) {
            sendingEvent(j, host, port);
        }


        try {
            String in;
            String pathName;
            Document input;
            Document fileDescriptor;
            boolean status;
            String content;
            long fileSize;
            long blockSize = Long.parseLong(Configuration.getConfigurationValue("blockSize"));

            while (true) {

                in = communicateRead(bufferIn);
                input = Document.parse(in);
                String command = input.getString("command");
                switch (command) {
                    case "FILE_CREATE_REQUEST":
                        pathName = input.getString("pathName");
                        log.info(host + ":" + port + " require to create file:\t" + pathName);
                        if (fileSystemManager.isSafePathName(pathName)) {
                            fileDescriptor = (Document) input.get("fileDescriptor");
                            //check the existing of file firstly.
                            try {
                                if (!fileSystemManager.fileNameExists(pathName)) {

                                    status = fileSystemManager.createFileLoader(
                                            pathName,
                                            fileDescriptor.getString("md5"),
                                            fileDescriptor.getLong("fileSize"),
                                            fileDescriptor.getLong("lastModified")
                                    );
                                    if (status) {
                                        if (fileSystemManager.checkShortcut(input.getString("pathName"))) {
                                            communicateSend(
                                                    host, port,
                                                    protocolFileDirResponse(input,
                                                            "FILE_CREATE_RESPONSE",
                                                            "shortcut was used",
                                                            false));
                                            log.info("shortcut was used:\t" + pathName);
                                        } else {
                                            communicateSend(
                                                    host, port,
                                                    protocolFileDirResponse(input,
                                                            "FILE_CREATE_RESPONSE",
                                                            "file loader ready",
                                                            true));
                                            fileLoaderPathName.add(pathName);
                                            log.info("file loader ready:\t" + pathName);
                                            fileSize = fileDescriptor.getLong("fileSize");
                                            if (fileSize <= blockSize) {
                                                communicateSend(
                                                        host, port,
                                                        protocolBytesRequest(input,
                                                                0, fileSize));
                                            } else {
                                                communicateSend(
                                                        host, port,
                                                        protocolBytesRequest(input,
                                                                0, blockSize));
                                            }
                                            log.info(pathName + "\tBytesRequest protocol has been send to:\t" + host + ":" + port);
                                        }

                                    } else {
                                        communicateSend(
                                                host, port,
                                                protocolFileDirResponse(
                                                        input,
                                                        "FILE_CREATE_RESPONSE",
                                                        "there was a problem creating the file",
                                                        false)
                                        );
                                        log.warning("there was a problem creating the file:\t" + pathName);
                                    }
                                } else {
                                    communicateSend(
                                            host, port,
                                            protocolFileDirResponse(
                                                    input,
                                                    "FILE_CREATE_RESPONSE",
                                                    "pathname already exists",
                                                    false)
                                    );
                                    log.warning("pathname already exists:\t" + pathName);
                                }
                            } catch (IOException e) {
                                log.warning(e.getMessage());
                                communicateSend(
                                        host, port,
                                        protocolFileDirResponse(
                                                input,
                                                "FILE_CREATE_RESPONSE",
                                                "there was a problem creating the file",
                                                false)
                                );
                                log.warning("there was a problem creating the file:\t" + pathName);
                            }
                        } else {
                            String potocolTamp = input.getString("command").replace("REQUEST", "RESPONSE");
                            communicateSend(
                                    host, port,
                                    protocolFileDirResponse(input,
                                            potocolTamp,
                                            "unsafe pathname given",
                                            false)
                            );
                            log.warning("unsafe pathname given:\t" + pathName);
                        }
                        break;
                    case "FILE_CREATE_RESPONSE":
                        log.info(host + ":" + port + " response: " + input.getString("message"));
                        break;
                    case "FILE_BYTES_REQUEST":
                        try {
                            fileDescriptor = (Document) input.get("fileDescriptor");
                            ByteBuffer fileBytes = fileSystemManager.readFile(fileDescriptor.getString("md5"),
                                    input.getLong("position"),
                                    input.getLong("length"));
                            content = new String(Base64.getEncoder().encode(fileBytes.array()),
                                    StandardCharsets.UTF_8);
                            communicateSend(
                                    host, port,
                                    protocolBytesResponse(input,
                                            content,
                                            "successful read",
                                            true));
                            log.info(input.getString("pathName") + "\tBytesResponse protocol has been send to:\t" + host + ":" + port);
                        } catch (Exception e) {
                            log.warning(e.getMessage());
                            communicateSend(
                                    host, port,
                                    protocolBytesResponse(input,
                                            " ",
                                            "unsuccessful read",
                                            false));
                            log.warning("unsuccessful read:\t" + input.getString("pathName"));
                        }
                        break;
                    case "FILE_BYTES_RESPONSE":
                        fileDescriptor = (Document) input.get("fileDescriptor");
                        long position = input.getLong("position");
                        content = input.getString("content");
                        pathName = input.getString("pathName");
                        long length = input.getLong("length");
                        fileSize = fileDescriptor.getLong("fileSize");
                        status = input.getBoolean("status");
                        try {
                            if (status) {
                                ByteBuffer fileBuffer = Base64.getDecoder().decode(ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8)));
                                fileSystemManager.writeFile(pathName, fileBuffer, position);
                                if (fileSystemManager.checkWriteComplete(pathName)) {
                                    log.info("Receive " + pathName + " successful.");
                                    fileSystemManager.cancelFileLoader(pathName);
                                    fileLoaderPathName.remove(pathName);
                                } else {
                                    if (fileSize - (position + length) <= blockSize) {
                                        communicateSend(
                                                host, port,
                                                protocolBytesRequest(input,
                                                        position + blockSize, fileSize - (position + length)));
                                    } else {
                                        communicateSend(
                                                host, port,
                                                protocolBytesRequest(input,
                                                        position + blockSize, blockSize));
                                    }
                                }
                                log.info(host + ":" + port + " : " + input.getString("message"));
                                log.info(host + ":" + port + " : " + pathName + " is loading :\t" + String.format("%.2f", ((double) (position + length) / (double) fileSize) * 100) + " %");
                            } else {
                                log.warning("failed to write byte to file:\t" + pathName);
                                fileSystemManager.cancelFileLoader(pathName);
                                fileLoaderPathName.remove(pathName);
                            }
                        } catch (Exception e) {
                            log.warning("failed to write byte to file:\t" + pathName);
                            fileSystemManager.cancelFileLoader(pathName);
                            fileLoaderPathName.remove(pathName);
                        }
                        break;
                    case "FILE_DELETE_REQUEST":
                        pathName = input.getString("pathName");
                        log.info(host + ":" + port + " require to delete file: " + pathName);
                        try {
                            if (fileSystemManager.isSafePathName(pathName)) {
                                fileDescriptor = (Document) input.get("fileDescriptor");
                                //check the existing of file firstly.
                                if (fileSystemManager.fileNameExists(input.getString("pathName"),
                                        fileDescriptor.getString("md5"))) {
                                    status = fileSystemManager.deleteFile(
                                            input.getString("pathName"),
                                            fileDescriptor.getLong("lastModified"),
                                            fileDescriptor.getString("md5"));
                                    if (status) {
                                        communicateSend(
                                                host, port,
                                                protocolFileDirResponse(input,
                                                        "FILE_DELETE_RESPONSE",
                                                        "file deleted",
                                                        true)
                                        );
                                        log.info("successful deleted:\t" + pathName);
                                    } else {
                                        communicateSend(
                                                host, port,
                                                protocolFileDirResponse(
                                                        input,
                                                        "FILE_DELETE_RESPONSE",
                                                        "there was a problem deleting the file",
                                                        false)
                                        );
                                        log.warning("there was a problem deleting the file:\t" + pathName);
                                    }
                                } else {
                                    communicateSend(
                                            host, port,
                                            protocolFileDirResponse(
                                                    input,
                                                    "FILE_DELETE_RESPONSE",
                                                    "pathname does not exist",
                                                    false)
                                    );
                                    log.warning("failed to delete, pathname does not exist:\t" + pathName);
                                }
                            } else {
                                String potocolTamp = input.getString("command").replace("REQUEST", "RESPONSE");
                                communicateSend(
                                        host, port,
                                        protocolFileDirResponse(input,
                                                potocolTamp,
                                                "unsafe pathname given",
                                                false)
                                );
                                log.warning("unsafe pathname given:\t" + pathName);
                            }
                        } catch (Exception e) {
                            log.warning(e.getMessage());
                            log.warning("there was a problem deleting the file:\t" + pathName);
                        }
                        break;
                    case "FILE_DELETE_RESPONSE":
                        log.info(host + ":" + port + " response: " + input.getString("message"));
                        break;
                    case "FILE_MODIFY_REQUEST":
                        pathName = input.getString("pathName");
                        log.info(host + ":" + port + " require to modify file: " + pathName);
                        try {
                            if (fileSystemManager.isSafePathName(pathName)) {
                                fileDescriptor = (Document) input.get("fileDescriptor");
                                //check the existing of file firstly.
                                if (!fileSystemManager.fileNameExists(input.getString("pathName"),
                                        fileDescriptor.getString("md5"))) {
                                    status = fileSystemManager.modifyFileLoader(
                                            input.getString("pathName"),
                                            fileDescriptor.getString("md5"),
                                            fileDescriptor.getLong("lastModified")
                                    );
                                    if (status) {
                                        communicateSend(
                                                host, port,
                                                protocolFileDirResponse(input,
                                                        "FILE_MODIFY_RESPONSE",
                                                        "file loader ready",
                                                        true));
                                        log.info("file loader ready:\t" + pathName);
                                        fileSize = fileDescriptor.getLong("fileSize");
                                        if (fileSize <= blockSize) {
                                            communicateSend(
                                                    host, port,
                                                    protocolBytesRequest(input,
                                                            0, fileSize));
                                        } else {
                                            communicateSend(
                                                    host, port,
                                                    protocolBytesRequest(input,
                                                            0, blockSize));
                                        }
                                        log.info(pathName + "\tBytesRequest protocol has been send to:\t" + host + ":" + port);
                                    } else {
                                        communicateSend(
                                                host, port,
                                                protocolFileDirResponse(
                                                        input,
                                                        "FILE_CREATE_RESPONSE",
                                                        "there was a problem modifying the file",
                                                        false)
                                        );
                                        log.warning("there was a problem modifying the file:\t" + pathName);
                                    }
                                } else {
                                    communicateSend(
                                            host, port,
                                            protocolFileDirResponse(
                                                    input,
                                                    "FILE_CREATE_RESPONSE",
                                                    "same file already exciting",
                                                    false)
                                    );
                                    log.warning("pathname already exists:\t" + pathName);
                                }
                            } else {
                                String potocolTamp = input.getString("command").replace("REQUEST", "RESPONSE");
                                communicateSend(
                                        host, port,
                                        protocolFileDirResponse(input,
                                                potocolTamp,
                                                "unsafe pathname given",
                                                false)
                                );
                                log.warning("unsafe pathname given:\t" + pathName);
                            }
                        } catch (Exception e) {
                            log.warning(e.getMessage());
                            String potocolTamp = input.getString("command").replace("REQUEST", "RESPONSE");
                            communicateSend(
                                    host, port,
                                    protocolFileDirResponse(input,
                                            potocolTamp,
                                            "unsafe pathname given",
                                            false)
                            );
                            log.warning("unsafe pathname given:\t" + pathName);
                        }
                        break;
                    case "FILE_MODIFY_RESPONSE":
                        log.info(host + ":" + port + " response: " + input.getString("message"));
                        break;
                    case "DIRECTORY_CREATE_REQUEST":
                        pathName = input.getString("pathName");
                        log.info(host + ":" + port + " require to create directory: " + pathName);
                        if (fileSystemManager.isSafePathName(pathName)) {
                            if (!fileSystemManager.dirNameExists(input.getString("pathName"))) {
                                status = fileSystemManager.makeDirectory(input.getString("pathName"));
                                if (status) {
                                    communicateSend(
                                            host, port,
                                            protocolFileDirResponse(input,
                                                    "DIRECTORY_CREATE_RESPONSE",
                                                    "directory created",
                                                    true)
                                    );
                                    log.info("directory created:\t" + pathName);
                                } else {
                                    communicateSend(
                                            host, port,
                                            protocolFileDirResponse(input,
                                                    "DIRECTORY_CREATE_RESPONSE",
                                                    "there was a problem creating the directory",
                                                    false)
                                    );
                                    log.warning("there was a problem creating the directory:\t" + pathName);
                                }
                            } else {
                                communicateSend(
                                        host, port,
                                        protocolFileDirResponse(input,
                                                "DIRECTORY_CREATE_RESPONSE",
                                                "pathname already exists",
                                                false)
                                );
                                log.warning("pathname already exists:\t" + pathName);
                            }
                        } else {
                            String potocolTamp = input.getString("command").replace("REQUEST", "RESPONSE");
                            communicateSend(
                                    host, port,
                                    protocolFileDirResponse(input,
                                            potocolTamp,
                                            "unsafe pathname given",
                                            false)
                            );
                            log.warning("unsafe pathname given:\t" + pathName);
                        }
                        break;
                    case "DIRECTORY_CREATE_RESPONSE":
                        log.info(host + ":" + port + " response: " + input.getString("message"));
                        break;
                    case "DIRECTORY_DELETE_REQUEST":
                        pathName = input.getString("pathName");
                        log.info(host + ":" + port + " require to delete directory: " + pathName);
                        try {
                            if (fileSystemManager.isSafePathName(pathName)) {
                                if (fileSystemManager.dirNameExists(input.getString("pathName"))) {
                                    status = fileSystemManager.deleteDirectory(input.getString("pathName"));
                                    if (status) {
                                        communicateSend(
                                                host, port,
                                                protocolFileDirResponse(input,
                                                        "DIRECTORY_DELETE_RESPONSE",
                                                        "directory deleted",
                                                        true)
                                        );
                                        log.info("directory deleted:\t" + pathName);
                                    } else {
                                        communicateSend(
                                                host, port,
                                                protocolFileDirResponse(input,
                                                        "DIRECTORY_DELETE_RESPONSE",
                                                        "there was a problem deleting the directory",
                                                        false)
                                        );
                                        log.warning("there was a problem deleting the directory:\t" + pathName);
                                    }
                                } else {
                                    communicateSend(
                                            host, port,
                                            protocolFileDirResponse(input,
                                                    "DIRECTORY_DELETE_RESPONSE",
                                                    "pathname does not exist",
                                                    false)
                                    );
                                    log.warning("pathname does not exist:\t" + pathName);
                                }
                            } else {
                                String potocolTamp = input.getString("command").replace("REQUEST", "RESPONSE");
                                communicateSend(
                                        host, port,
                                        protocolFileDirResponse(input,
                                                potocolTamp,
                                                "unsafe pathname given",
                                                false)
                                );
                                log.warning("unsafe pathname given:\t" + pathName);
                            }
                        } catch (Exception e) {
                            String potocolTamp = input.getString("command").replace("REQUEST", "RESPONSE");
                            communicateSend(
                                    host, port,
                                    protocolFileDirResponse(input,
                                            potocolTamp,
                                            "there was a problem deleting the directory",
                                            false)
                            );
                            log.warning("there was a problem deleting the directory:\t" + pathName);
                        }
                        break;
                    case "DIRECTORY_DELETE_RESPONSE":
                        log.info(host + ":" + port + " response: " + input.getString("message"));
                        break;
                    default:
                        communicateSend(host, port, protocolInvalid());
                        break;

                }

            }
        } catch (Exception e) {
            log.warning(e.getMessage());
            Document newPeer = new Document();
            newPeer.append("host", host);
            newPeer.append("port", port);
            removeDoc(newPeer, connectedPeers);
            removeDoc(newPeer, incommingPeers);
        } finally {
            Document newPeer = new Document();
            newPeer.append("host", host);
            newPeer.append("port", port);
            removeDoc(newPeer, connectedPeers);
            removeDoc(newPeer, incommingPeers);
            if (fileLoaderPathName.size() > 0) {
                for (String i : fileLoaderPathName) {
                    try {
                        fileSystemManager.cancelFileLoader(i);
                    } catch (IOException ex) {
                        log.warning(ex.getMessage());
                    }
                }
            }
            log.info("connection closed:\t" + host + ":" + port);
            try {
                socket.close();
            } catch (Exception e) {
                log.warning(e.getMessage());
            }
        }
    }


    private static void sendingEvent(FileSystemEvent fileSystemEvent, String host, int port) {
        switch (fileSystemEvent.event) {
            case FILE_CREATE:
                communicateSend(host, port, protocolFileDirRequest(fileSystemEvent, "FILE_CREATE_REQUEST"));
                break;
            case FILE_DELETE:
                communicateSend(host, port, protocolFileDirRequest(fileSystemEvent, "FILE_DELETE_REQUEST"));
                break;
            case FILE_MODIFY:
                communicateSend(host, port, protocolFileDirRequest(fileSystemEvent, "FILE_MODIFY_REQUEST"));
                break;
            case DIRECTORY_CREATE:
                communicateSend(host, port, protocolFileDirRequest(fileSystemEvent, "DIRECTORY_CREATE_REQUEST"));
                break;
            case DIRECTORY_DELETE:
                communicateSend(host, port, protocolFileDirRequest(fileSystemEvent, "DIRECTORY_DELETE_REQUEST"));
                break;
        }

    }

    static void sycnFiles(int time) {
        while (true) {
            try {
                Thread.sleep(time * 1000);
            } catch (InterruptedException e) {
                log.info(e.getMessage());
                Thread.currentThread().interrupt();
                break;
            }
            ArrayList<FileSystemEvent> syncEvents = new ArrayList<>();
            syncEvents = fileSystemManager.generateSyncEvents();
            for (Document i : connectedPeers) {
                if (i != null) {
                    for (FileSystemEvent j : syncEvents) {
                        sendingEvent(j, i.getString("host"), i.getInteger("port"));
                    }
                } else {
                    connectedPeers.remove(i);
                }
            }
        }
    }

    private static void sendThread(Socket socket, BufferedWriter bufferOut, String host, int port) {
        ArrayList<Triplet> newSendTask = new ArrayList<>();
        //send fileEvents if applicable
        Document peer = new Document();
        peer.append("host", host);
        peer.append("port", port);
        while (isContains(peer, connectedPeers)) {

            // avoid DDos
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                log.info(e.getMessage());
                Thread.currentThread().interrupt();
                break;
            }
            try {
                newSendTask.addAll(sendTask);
                //int sendLength =sendTask.size();
                for (int i = 0; i < newSendTask.size(); ++i) {
                    Triplet<String, Integer, Document> aTask = newSendTask.get(i);
                    if (aTask == null) {
                        sendTask.remove(aTask);
                        continue;
                    }
                    if (aTask.getHost().equals(host) && aTask.getPort() == port) {
                        //send content
                        bufferOut.write(aTask.getContent().toJson() + "\n");
                        bufferOut.flush();

                        log.info("send " + aTask.getContent().getString("command") + " to: " + host + ":" + port);

                        //remove task from list
                        sendTask.remove(aTask);
                        //int sendLength =sendTask.size();
                    }
                }
                newSendTask.clear();
            } catch (Exception e) {
                log.warning(e.getMessage());
                log.warning(host + ":" + port + " disconnected.");
                Document newPeer = new Document();
                newPeer.append("host", host);
                newPeer.append("port", port);
                removeDoc(newPeer, connectedPeers);
                removeDoc(newPeer, incommingPeers);

                //remove all duplicates
                try {
                    //remove all duplicates
                    newSendTask.clear();
                    newSendTask.addAll(sendTask);
                    for (int i = 0; i < newSendTask.size(); ++i) {
                        Triplet<String, Integer, Document> aTask = newSendTask.get(i);
                        if (aTask.getHost().equals(host) && aTask.getPort() == port) {
                            sendTask.remove(aTask);
                        }
                    }
                } catch (Exception a) {
                    log.warning(a.getMessage());
                }


                try {
                    socket.close();
                } catch (Exception o) {
                    log.warning(o.getMessage());
                }
            }
        }
    }

    /**
     * To get all peers' addresses and ports. Save into a ArrayList with Documents
     *
     * @return a list of peers.
     */
    private static ArrayList<Document> getPeers() {
        //Use Document to store each peer to easy to get.
        ArrayList<Document> peers = new ArrayList<>();
        Document peer;
        try {
            String[] peersString = Configuration.getConfigurationValue("peers").split(",");
            for (String p : peersString) {
                peer = new Document();
                String[] one_peer = p.split(":");
                peer.append("host", one_peer[0]);
                peer.append("port", Integer.parseInt(one_peer[1]));
                peers.add(peer);
            }
        } catch (Exception e) {
            log.info("incorrect peers in config, program will exit");
            System.exit(-1);
        }
        return peers;
    }

    /**
     * To send a request and get a response.
     *
     * @return a response from other peer
     */
    static String communicateRead(BufferedReader in) throws IOException {
        String output = "";
        try {
            while (null == (output = in.readLine())) {

            }
            //output = in.readLine();
        } catch (IOException | ClassCastException e) {
            log.info(e.getMessage());
            throw e;
        }
        return output;
    }

    /**
     * To send a request and get a response.
     *
     * @param content one peer which you want to send
     * @author Yiqi Wang
     */
    private static void communicateSend(String host, int port, Document content) {
        Triplet<String, Integer, Document> newSending = new Triplet<>(host, port, content);
        sendTask.add(newSending);
    }

    /**
     * To generate a REQUEST json document of file and directory protocol
     *
     * @param fileSystemEvent Only accept File or Directory protocol
     * @param protocol        such like "FILE_CREATE_REQUEST"
     * @return Document
     */
    private static Document protocolFileDirRequest(FileSystemEvent fileSystemEvent, String protocol) {
        Document doc = new Document();
        doc.append("command", protocol);
        switch (protocol) {
            case "FILE_CREATE_REQUEST":
            case "FILE_DELETE_REQUEST":
            case "FILE_MODIFY_REQUEST":
                Document sub_doc = new Document();
                sub_doc.append("md5", fileSystemEvent.fileDescriptor.md5);
                sub_doc.append("lastModified", fileSystemEvent.fileDescriptor.lastModified);
                sub_doc.append("fileSize", fileSystemEvent.fileDescriptor.fileSize);
                doc.append("fileDescriptor", sub_doc);
                break;
        }
        doc.append("pathName", fileSystemEvent.pathName);
        return doc;
    }

    /**
     * To generate a RESPONSE json document of file and directory protocol
     *
     * @param doc      REQUEST document
     * @param protocol such like "FILE_CREATE_RESPONSE"
     * @param message  debug message
     * @param status   boolean
     * @return Document
     */
    private static Document protocolFileDirResponse(Document doc, String protocol, String message, boolean status) {
        Document newDoc = new Document();
        newDoc.append("command", protocol);
        switch (protocol) {
            case "FILE_CREATE_RESPONSE":
            case "FILE_DELETE_RESPONSE":
            case "FILE_MODIFY_RESPONSE":
                Document sub_doc = (Document) doc.get("fileDescriptor");
                newDoc.append("fileDescriptor", sub_doc);
                break;
        }
        newDoc.append("pathName", doc.getString("pathName"));
        newDoc.append("message", message);
        newDoc.append("status", status);
        return newDoc;
    }

    /**
     * Return a Document which can be convert to JSON is used to transmit to peers.
     *
     * @param protocol      To identify the protocol is request or responds."HANDSHAKE_REQUEST" or
     *                      "HANDSHAKE_RESPONSE"
     * @param localHostName let the host name to be the IP address when it is "localhost"
     * @return json document
     */
    private static Document protocolHandShake(String protocol, String localHostName) {
        Document doc = new Document();
        Document sub_doc = new Document();
        String host = Configuration.getConfigurationValue("advertisedName");
        if (host.toLowerCase().equals("localhost")) {
            host = localHostName;
        }
        doc.append("command", protocol);
        sub_doc.append("host", host);
        sub_doc.append("port", Integer.parseInt(Configuration.getConfigurationValue("port")));
        doc.append("hostPort", sub_doc);
        //log.info(host);
        return doc;
    }

    /**
     * To generate a json document of "CONNECTION_REFUSED" protocol
     *
     * @return json document
     */
    private static Document protocolConnectionRefused() {
        Document doc = new Document();
        doc.append("command", "CONNECTION_REFUSED");
        doc.append("message", "connection limit reached");
        doc.append("peers", connectedPeers);
        return doc;
    }

    /**
     * To generate a json document of "INVALID_PROTOCOL" protocol
     *
     * @return json document
     */
    private static Document protocolInvalid() {
        Document doc = new Document();
        doc.append("command", "INVALID_PROTOCOL");
        doc.append("message", "message must contain a command field as string");
        return doc;
    }

    /**
     * To generate a REQUEST json document of FILE_BYTES_REQUEST
     *
     * @param doc      file document
     * @param position file position
     * @param length   file length
     * @return Document
     */
    private static Document protocolBytesRequest(Document doc, long position, long length) {
        Document newDoc = new Document();
        newDoc.append("command", "FILE_BYTES_REQUEST");
        Document sub_doc = (Document) doc.get("fileDescriptor");
        newDoc.append("fileDescriptor", sub_doc);
        newDoc.append("pathName", doc.getString("pathName"));
        newDoc.append("position", position);
        newDoc.append("length", length);
        return newDoc;
    }

    /**
     * To generate a RESPONSE json document of FILE_BYTES_RESPONSE
     *
     * @param doc     file document from FILE_BYTES_REQUEST
     * @param content bytes encoded in Base64 format
     * @param message debug message
     * @param status  boolean
     * @return Document
     */
    private static Document protocolBytesResponse(Document doc, String content, String message, boolean status) {
        Document newDoc = new Document();
        newDoc.append("command", "FILE_BYTES_RESPONSE");
        Document sub_doc = (Document) doc.get("fileDescriptor");
        newDoc.append("fileDescriptor", sub_doc);
        newDoc.append("pathName", doc.getString("pathName"));
        newDoc.append("position", doc.getLong("position"));
        newDoc.append("length", doc.getLong("length"));
        newDoc.append("content", content);
        newDoc.append("message", message);
        newDoc.append("status", status);
        return newDoc;
    }

    static boolean isContains(Document doc1, ArrayList<Document> list) {
        for (int i = 0; i < list.size(); ++i) {
            if (list.get(i).toJson().equals(doc1.toJson()))
                return true;
        }
        return false;
    }

    static void removeDoc(Document doc1, ArrayList<Document> list) {
        for (int i = 0; i < list.size(); ++i) {
            Document temp = list.get(i);
            if (temp.toJson().equals(doc1.toJson()))
                list.remove(temp);
        }
    }
}


