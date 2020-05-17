package unimelb.bitbox;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Calendar;
import java.util.logging.Logger;

import unimelb.bitbox.util.*;
import unimelb.bitbox.util.FileSystemManager.FileSystemEvent;


public class UdpServerMain implements FileSystemObserver {
	private static DatagramSocket ds;
	private static Logger log = Logger.getLogger(ServerMain.class.getName());
	private static FileSystemManager fileSystemManager;
	private static ArrayList<Document> connectedPeers = new ArrayList<>();
	private static ArrayList<Document> rememberedPeers = new ArrayList<>();
	private static ArrayList<Document> deadPeers = new ArrayList<>();

	private static ArrayList<Fivelet> sendTask = new ArrayList<>();

	private final static ArrayList<Document> PEERS = getPeers();
	private static ArrayList<Document> handshakePeers = new ArrayList<>();

	private static ArrayList<Triplet> fileLoaderPathName = new ArrayList<>();

	UdpServerMain() throws NumberFormatException, IOException, NoSuchAlgorithmException {
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
			// host port of peers
			String host = toIP(i.getString("host"));
			int port = i.getInteger("port");
			String localAddress = Configuration.getConfigurationValue("advertisedName");
			log.info("trying to handshake with " + host + ":" + port);
			Document content = protocolHandShake("HANDSHAKE_REQUEST", localAddress);

			Fivelet<String, Integer, Document, Integer, Long> newSending = new Fivelet<>(host, port, content, 0, (long) 0);
			sendTask.add(newSending);
			Document newPeer = new Document();
			newPeer.append("host", host);
			newPeer.append("port", port);
			handshakePeers.add(newPeer);
		}

		// start to listen pockets
		Thread listen = new Thread(() -> listen());
		listen.start();

		// start to count time to sync all files
		Thread sycFile = new Thread(() -> sycnFiles(Integer.parseInt(Configuration.getConfigurationValue("syncInterval"))));
		sycFile.start();

		// start to send pockets
		sendThread();
	}

	private static String toIP(String address) {
		InetAddress theaddress = null;
		String newaddress;
		try {
			theaddress = InetAddress.getByName(address);
			newaddress = theaddress.getHostAddress();
		} catch (UnknownHostException e) {
			log.warning("Failed to get IP address of " + address);
			newaddress = address;
		}

		return newaddress;
	}

	/**
	 * The individual thread created by the server for communicating with a client
	 */
	private static void listen() {
		String pathName;
		Document input;
		Document fileDescriptor;
		boolean status;
		String content;
		long fileSize;
		String host;
		int port;
		long blockSize = Long.parseLong(Configuration.getConfigurationValue("blockSize"));
		if (blockSize < 10000)
			blockSize = 10000;
		if (blockSize > 40000)
			blockSize = 40000;

		int udpPort = Integer.parseInt(Configuration.getConfigurationValue("udpPort"));
		try {
			ds = new DatagramSocket(udpPort);

			byte[] buf = new byte[65000];
			DatagramPacket ds_receive = new DatagramPacket(buf, 65000);

			while (true) {

				String str_receive;
				do {
					ds.receive(ds_receive);
					str_receive = new String(ds_receive.getData(), 0, ds_receive.getLength());
				} while (str_receive == null);
				try {
					input = Document.parse(str_receive);
					String command = input.getString("command");

					if (command == null)
						continue;
					host = ds_receive.getAddress().getHostAddress();
					port = ds_receive.getPort();

					Document newPeer = new Document();
					newPeer.append("host", host);
					newPeer.append("port", port);

					if (!isContains(newPeer, connectedPeers)) {
						if (isContains(newPeer, handshakePeers)) {
							switch (command) {
								case "INVALID_POTOCOL":
									// Add the peer to dead peers list
									newPeer = new Document();
									newPeer.append("host", host);
									newPeer.append("port", port);
									deadPeers.add(newPeer);
									log.warning("failed to connect with" + host + ":" + port);
									removeDoc(newPeer, handshakePeers);
									removeTask(input, host, port);
									break;
								case "CONNECTION_REFUSED":
									log.info(input.getString("message"));
									Document dPeer = new Document();
									dPeer.append("host", host);
									dPeer.append("port", port);
									deadPeers.add(dPeer);
									removeDoc(newPeer, handshakePeers);
									removeTask(input, host, port);
									ArrayList<Document> newPeers = (ArrayList<Document>) input.get("peers");

									for (Document i : newPeers) {
										long portLong = i.getLong("port");
										int portInt = Math.toIntExact(portLong);
										Document realnewPeer = new Document();
										realnewPeer.append("host", i.getString("host"));
										realnewPeer.append("port", portInt);
										if (!isContains(realnewPeer, deadPeers)) {
											String localAddress;
											String remoteAddress;
											try {
												Socket socket = new Socket(i.getString("host"), portInt);
												localAddress = socket.getLocalAddress().getHostAddress();
												remoteAddress = socket.getInetAddress().getHostAddress();
												socket.close();
											} catch (IOException e) {
												log.warning(e.getMessage());
												localAddress = "localhost";
												remoteAddress = i.getString("host");
											}
											log.info("trying to handshake with " + remoteAddress + ":" + portInt);
											Document newcontent = protocolHandShake("HANDSHAKE_REQUEST", localAddress);
											Fivelet<String, Integer, Document, Integer, Long> newSending = new Fivelet<>(remoteAddress, portInt, newcontent, 0, (long) 0);
											sendTask.add(newSending);
											handshakePeers.add(i);
										}
									}
									break;
								case "HANDSHAKE_RESPONSE":
									// Add the peer to connected peers list
									newPeer = new Document();
									newPeer.append("host", host);
									newPeer.append("port", port);
									connectedPeers.add(newPeer);
									log.info("successful connected with" + host + ":" + port);
									removeDoc(newPeer, handshakePeers);
									removeTask(input, host, port);

									ArrayList<FileSystemEvent> syncEvents = fileSystemManager.generateSyncEvents();
									for (FileSystemEvent j : syncEvents) {
										sendingEvent(j, host, port);
									}
									break;
							}
						} else {
							if (command.equals("HANDSHAKE_REQUEST")) {
								log.info(host + ":" + port + "\thandshaked to you.");
								if (rememberedPeers.size() >= Integer.parseInt(Configuration.getConfigurationValue("maximumIncommingConnections"))) {
									log.info("reach maximum number of connections, thread closed");
									communicateSend(host, port, protocolConnectionRefused());
								} else {
									String localAddress = Configuration.getConfigurationValue("advertisedName");
									communicateSend(host, port, protocolHandShake("HANDSHAKE_RESPONSE", localAddress));
									removeDoc(newPeer, deadPeers);
									rememberedPeers.add(newPeer);
									connectedPeers.add(newPeer);

									ArrayList<FileSystemEvent> syncEvents = fileSystemManager.generateSyncEvents();
									for (FileSystemEvent j : syncEvents) {
										sendingEvent(j, host, port);
									}

								}
							} else {
								log.info("received an invalid protocol");
								communicateSend(host, port, protocolInvalid());
							}
						}
					} else {
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
													Triplet<String, Integer, String> newPath = new Triplet<>(host, port, pathName);
													fileLoaderPathName.add(newPath);
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
								removeTask(input, host, port);
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
								removeTask(input, host, port);
								try {
									if (status) {
										ByteBuffer fileBuffer = Base64.getDecoder().decode(ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8)));
										fileSystemManager.writeFile(pathName, fileBuffer, position);
										if (fileSystemManager.checkWriteComplete(pathName)) {
											log.info("Receive " + pathName + " successful.");
											fileSystemManager.cancelFileLoader(pathName);
											Triplet<String, Integer, String> newPath = new Triplet<>(host, port, pathName);
											fileLoaderPathName.remove(newPath);
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
										Triplet<String, Integer, String> newPath = new Triplet<>(host, port, pathName);
										fileLoaderPathName.remove(newPath);
									}
								} catch (Exception e) {
									log.warning("failed to write byte to file:\t" + pathName);
									fileSystemManager.cancelFileLoader(pathName);
									Triplet<String, Integer, String> newPath = new Triplet<>(host, port, pathName);
									fileLoaderPathName.remove(newPath);
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
								removeTask(input, host, port);
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
								removeTask(input, host, port);
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
								removeTask(input, host, port);
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
								removeTask(input, host, port);
								break;
							case "INVALID_PROTOCOL":
								String localAddress = Configuration.getConfigurationValue("advertisedName");
								log.info("trying to handshake with " + host + ":" + port);
								Document newcontent = protocolHandShake("HANDSHAKE_REQUEST", localAddress);
								Fivelet<String, Integer, Document, Integer, Long> newSending = new Fivelet<>(host, port, newcontent, 0, (long) 0);
								sendTask.add(newSending);
								removeDoc(newPeer, connectedPeers);
								handshakePeers.add(newPeer);
								break;
							default:
								communicateSend(host, port, protocolInvalid());
								break;
						}

					}
					//ds_receive.setLength(65000);

				} catch (Exception e) {
					log.warning(e.getMessage());
					log.info("received an invalid protocol");
					communicateSend(ds_receive.getAddress().getHostAddress(), ds_receive.getPort(), protocolInvalid());
				}
			}
		} catch (SocketException e) {
			log.warning(e.getMessage());
		} catch (IOException e) {
			log.warning(e.getMessage());
		} catch (Exception e) {
			log.warning(e.getMessage());
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

	private static void sycnFiles(int time) {
		while (true) {
			try {
				Thread.sleep(time * 1000);
			} catch (InterruptedException e) {
				log.info(e.getMessage());
				Thread.currentThread().interrupt();
				break;
			}
			ArrayList<FileSystemEvent> syncEvents;
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

	private static void sendThread() {
		ArrayList<Fivelet> newSendTask = new ArrayList<>();
		try {
			DatagramPacket ds_send;

			while (true) {
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

					for (int i = 0; i < newSendTask.size(); ++i) {
						Fivelet<String, Integer, Document, Integer, Long> aTask = newSendTask.get(i);
						if (aTask.getHost() == null) {
							System.out.println("sdsfdsfsdfsdfsdfdsfsdfsdfsdfsdfs");
						}
						Document dPeer = new Document();
						dPeer.append("host", aTask.getHost());
						dPeer.append("port", aTask.getPort());

						if (aTask == null || aTask.getHost() == null || aTask.getPort() == null || aTask.getContent() == null
								|| aTask.getCount() == null || aTask.getTime() == null) {
							sendTask.remove(aTask);
							continue;
						}

						if (isContains(dPeer, deadPeers) && (!aTask.getContent().getString("command").equals("INVALID_PROTOCOL"))) {
							sendTask.remove(aTask);
							continue;
						}

						if (aTask.getCount() < Integer.parseInt(Configuration.getConfigurationValue("udpRetries"))
								&& (Calendar.getInstance().getTimeInMillis() - aTask.getTime() >
								(Long.parseLong(Configuration.getConfigurationValue("udpTimeout")) * 1000))
								&& sendTask.contains(aTask)) {
							//send content
							InetAddress host = InetAddress.getByName(aTask.getHost());

							ds_send = new DatagramPacket(aTask.getContent().toJson().getBytes(), aTask.getContent().toJson().getBytes().length, host, aTask.getPort());
							ds.send(ds_send);
							log.info("send " + aTask.getContent().getString("command") + " to: " + aTask.getHost() + ":" + aTask.getPort());

							//remove task from list
							sendTask.remove(aTask);
							Fivelet<String, Integer, Document, Integer, Long> newTask = new Fivelet<>(aTask.getHost(), aTask.getPort(),
									aTask.getContent(), aTask.getCount() + 1, Calendar.getInstance().getTimeInMillis());
							sendTask.add(newTask);
						} else if (aTask.getCount() == Integer.parseInt(Configuration.getConfigurationValue("udpRetries"))) {
							sendTask.remove(aTask);

							deadPeers.add(dPeer);
							removeDoc(dPeer, connectedPeers);
							removeDoc(dPeer, rememberedPeers);

							ArrayList<Triplet> toRemove = new ArrayList<>();
							toRemove.addAll(fileLoaderPathName);
							for (Triplet<String, Integer, String> aPath : toRemove) {
								if (aPath.getPort() == dPeer.getInteger("port") && aPath.getHost().equals(dPeer.getString("host"))) {
									fileLoaderPathName.remove(aPath);
									fileSystemManager.cancelFileLoader(aPath.getContent());
								}
							}


						} else if (aTask.getCount() > Integer.parseInt(Configuration.getConfigurationValue("udpRetries"))) {
							InetAddress host = InetAddress.getByName(aTask.getHost());
							ds_send = new DatagramPacket(aTask.getContent().toJson().getBytes(), aTask.getContent().toJson().getBytes().length, host, aTask.getPort());
							ds.send(ds_send);
							log.info("send " + aTask.getContent().getString("command") + " to: " + aTask.getHost() + ":" + aTask.getPort());
							//remove task from list
							sendTask.remove(aTask);
						}
					}
					newSendTask.clear();
				} catch (Exception e) {
					log.warning(e.getMessage());
					e.printStackTrace();
				}
			}
		} catch (Exception e) {
			log.warning(e.getMessage());
			e.printStackTrace();
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
	 * @param content one peer which you want to send
	 * @author Yiqi Wang
	 */
	private static void communicateSend(String host, int port, Document content) {
		Fivelet<String, Integer, Document, Integer, Long> newSending;
		if (content.getString("command").equals("INVALID_PROTOCOL") || content.getString("command").contains("RESPONSE")) {
			newSending = new Fivelet<>(host, port, content, Integer.parseInt(Configuration.getConfigurationValue("udpRetries")) + 1, (long) 0);
		} else {
			newSending = new Fivelet<>(host, port, content, 0, (long) 0);
		}
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
		sub_doc.append("port", Integer.parseInt(Configuration.getConfigurationValue("udpPort")));
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

	private static boolean isContains(Document doc1, ArrayList<Document> list) {
		for (int i = 0; i < list.size(); ++i) {
			if (list.get(i).toJson().equals(doc1.toJson()))
				return true;
		}
		return false;
	}

	private static void removeDoc(Document doc1, ArrayList<Document> list) {
		for (int i = 0; i < list.size(); ++i) {
			Document temp = list.get(i);
			if (temp.toJson().equals(doc1.toJson()))
				list.remove(temp);
		}
	}

	private static void removeTask(Document doc, String host, int port) {
		Document content = new Document();
		Document subDoc = new Document();
		switch (doc.getString("command")) {
			case "CONNECTION_REFUSED":
				content.append("command", "HANDSHAKE_REQUEST");
				subDoc.append("host", Configuration.getConfigurationValue("advertisedName"));
				subDoc.append("port", Integer.parseInt(Configuration.getConfigurationValue("udpPort")));
				content.append("hostPort", subDoc);
				break;
			case "HANDSHAKE_RESPONSE":
				content.append("command", "HANDSHAKE_REQUEST");
				subDoc.append("host", Configuration.getConfigurationValue("advertisedName"));
				subDoc.append("port", Integer.parseInt(Configuration.getConfigurationValue("udpPort")));
				content.append("hostPort", subDoc);
				break;
			case "FILE_CREATE_RESPONSE":
				content.append("command", "FILE_CREATE_REQUEST");
				content.append("fileDescriptor", (Document) doc.get("fileDescriptor"));
				content.append("pathName", doc.getString("pathName"));
				break;
			case "FILE_BYTES_RESPONSE":
				content.append("command", "FILE_BYTES_REQUEST");
				content.append("fileDescriptor", (Document) doc.get("fileDescriptor"));
				content.append("pathName", doc.getString("pathName"));
				content.append("position", doc.getLong("position"));
				content.append("length", doc.getLong("length"));
				break;
			case "FILE_DELETE_RESPONSE":
				content.append("command", "FILE_DELETE_REQUEST");
				content.append("fileDescriptor", (Document) doc.get("fileDescriptor"));
				content.append("pathName", doc.getString("pathName"));
				break;
			case "FILE_MODIFY_RESPONSE":
				content.append("command", "FILE_MODIFY_REQUEST");
				content.append("fileDescriptor", (Document) doc.get("fileDescriptor"));
				content.append("pathName", doc.getString("pathName"));
				break;
			case "DIRECTORY_CREATE_RESPONSE":
				content.append("command", "DIRECTORY_CREATE_REQUEST");
				content.append("pathName", doc.getString("pathName"));
				break;
			case "DIRECTORY_DELETE_RESPONSE":
				content.append("command", "DIRECTORY_DELETE_REQUEST");
				content.append("pathName", doc.getString("pathName"));
				break;
		}

		ArrayList<Fivelet> newTasks = new ArrayList<>();
		newTasks.addAll(sendTask);
		for (Fivelet<String, Integer, Document, Integer, Long> aTask : newTasks) {
			if (aTask.getHost().equals(host) && aTask.getPort() == port && aTask.getContent().toJson().equals(content.toJson())) {
				sendTask.remove(aTask);
				break;
			}
		}
	}
}