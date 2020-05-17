package unimelb.bitbox;

import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;
import unimelb.bitbox.util.Configuration;
import unimelb.bitbox.util.Document;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.net.ServerSocketFactory;
import java.io.*;
import java.math.BigInteger;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.KeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;
import java.util.logging.Logger;

public class SubServer {

	private static Logger log = Logger.getLogger(ServerMain.class.getName());

	private static ArrayList<Document> connectedClients = new ArrayList<>();

	private static Document protocolList() {
		Document newDoc = new Document();
		newDoc.append("command", "LIST_PEERS_RESPONSE");
		Document sub_doc = new Document();
		newDoc.append("peers", ServerMain.connectedPeers);
		System.out.println(ServerMain.connectedPeers.size());
		return newDoc;
	}

	private static Document protocolConnect(String HostName, Integer port, Boolean status, String message) {
		Document newDoc = new Document();
		newDoc.append("command", "CONNECT_PEER_RESPONSE");
		newDoc.append("host", HostName);
		newDoc.append("port", port);
		newDoc.append("status", status);
		newDoc.append("message", message);
		return newDoc;
	}

	private static Document protocolDisconnect(String HostName, Integer port, Boolean status, String message) {
		Document newDoc = new Document();
		newDoc.append("command", "DISCONNECT_PEER_RESPONSE");
		newDoc.append("host", HostName);
		newDoc.append("port", port);
		newDoc.append("status", status);
		newDoc.append("message", message);
		return newDoc;
	}

	private static Document protocolAuthRes(String Aes128, Boolean status, String message) {
		Document newDoc = new Document();
		newDoc.append("command", "AUTH_RESPONSE");
		if (status)
			newDoc.append("AES128", Aes128);
		newDoc.append("status", status);
		newDoc.append("message", message);
		return newDoc;
	}

	private static Document secureCommunicate(String payload) {
		Document newDoc = new Document();
		newDoc.append("payload", payload);
		return newDoc;
	}

	static void createClientServer(){

		ServerSocketFactory factory = ServerSocketFactory.getDefault();
		int clientPort = Integer.parseInt(Configuration.getConfigurationValue("clientPort"));
		log.info("Local server is ready for connecting Clients...");
		try (ServerSocket server = factory.createServerSocket(clientPort)) {
			// Wait for connections.
			while (true) {
				Socket client = server.accept();
				Thread s = new Thread(() -> clientPeer(client)) ;
				s.start();
			}
		} catch (Exception e) {
			log.warning("Socket create failed.");
		}

	}

	private static void clientPeer(Socket socket) {

		try {
			String input;
			boolean status;
			String message;
			Key AESkey = getKey(getRandomString(8));
			BufferedReader bufferIn = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
			BufferedWriter bufferOut = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

			input = ServerMain.communicateRead(bufferIn);
			if (Document.parse(input).getString("command").equals("AUTH_REQUEST")) {

				String[] keysString = Configuration.getConfigurationValue("authorized_keys").split(",");
				for (String authorized_key : keysString) {
					String[] key = authorized_key.split(" ");
					if (key[2].equals(Document.parse(input).getString("identity"))) {
						status = true;
						message = "public key found";

					} else {
						status = false;
						message = "public key not found";
					}

					String AesKey = java.util.Base64.getEncoder().encodeToString(AESkey.getEncoded());
					String AES128 = RSAencrypt(AesKey,getPublicKey(Document.parse(input).getString("identity")));

					bufferOut.write(protocolAuthRes(AES128, status, message).toJson() + "\n");
					bufferOut.flush();
				}
			}

			input = ServerMain.communicateRead(bufferIn);
			String payload = Document.parse(input).getString("payload");
			String response = decrypt(payload,AESkey);
			String host;
			int port;
			long longPort;
			String cipherBytes;
			switch (Document.parse(response).getString("command")) {
				case "LIST_PEERS_REQUEST":
					cipherBytes = encrypt(protocolList().toJson(),AESkey);

					bufferOut.write(secureCommunicate(cipherBytes).toJson() + "\n");
					bufferOut.flush();
					break;
				case "CONNECT_PEER_REQUEST":
					host = Document.parse(response).getString("host");
					longPort = Document.parse(response).getLong("port");
					port = Math.toIntExact(longPort);
					if (connectedClients.size()<=1) {
						status = true;
						message = "connected to peer";

						Document newClient = new Document();
						newClient.append("host", host);
						newClient.append("port", port);
						connectedClients.add(newClient);
						System.out.println(connectedClients.size());
					} else {
						status = false;
						message = "connection failed";
					}
					cipherBytes = encrypt(protocolConnect(host, port, status, message).toJson(), AESkey);
					bufferOut.write(secureCommunicate(cipherBytes).toJson() + "\n");
					bufferOut.flush();
					break;
				case "DISCONNECT_PEER_REQUEST":
					host = Document.parse(response).getString("host");
					longPort = Document.parse(response).getLong("port");
					port = Math.toIntExact(longPort);

					Document newClient = new Document();
					newClient.append("host", host);
					newClient.append("port", port);
					System.out.println(connectedClients.size());
					if (ServerMain.isContains(newClient, connectedClients)) {
						status = true;
						message = "disconnected from peer";
						ServerMain.removeDoc(newClient,connectedClients);
						System.out.println(connectedClients.size());
					} else {
						status = false;
						message = "connection not active";
					}
					cipherBytes = encrypt(protocolDisconnect(host, port, status, message).toJson(), AESkey);
					bufferOut.write(secureCommunicate(cipherBytes).toJson() + "\n");
					bufferOut.flush();
					break;
			}


		} catch (Exception e) {
			e.printStackTrace();
		}
	}


	private static String RSAencrypt(String rawText, PublicKey publicKey) throws IOException, GeneralSecurityException {
		Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
		cipher.init(Cipher.ENCRYPT_MODE, publicKey);
		byte [] output = cipher.doFinal(rawText.getBytes("UTF-8"));
		return new String(org.apache.commons.codec.binary.Base64.encodeBase64(output));
	}

	private static int decodeUInt32(byte[] key, int start_index){
		byte[] test = Arrays.copyOfRange(key, start_index, start_index + 4);
		return new BigInteger(test).intValue();

	}

	private static void writePEM(String fileName, String header, byte[] content) throws IOException{
		File f = new File(fileName);
		FileWriter fw = new FileWriter(f);
		PemObject pemObject = new PemObject(header, content);
		PemWriter pemWriter = new PemWriter(fw);
		pemWriter.writeObject(pemObject);
		pemWriter.close();
	}

	private static PublicKey getPublicKey(String identity) throws Exception{
		String pk = "";
		String[] keyString = Configuration.getConfigurationValue("authorized_keys").split(",");
		for (String pubKeyPEM : keyString) {
			String[] one_pubkey = pubKeyPEM.split(" ");
			if(one_pubkey[2].equals(identity)){
				pk = one_pubkey[1];
			}
		}
		byte[] key = java.util.Base64.getDecoder().decode(pk.getBytes());
		byte[] sshrsa = new byte[] { 0, 0, 0, 7, 's', 's', 'h', '-', 'r', 's',
				'a' };
		int start_index = sshrsa.length;
		/* Decode the public exponent */
		int len = decodeUInt32(key, start_index);
		start_index += 4;
		byte[] pe_b = new byte[len];
		for(int i= 0 ; i < len; i++){
			pe_b[i] = key[start_index++];
		}
		BigInteger pe = new BigInteger(pe_b);
		/* Decode the modulus */
		len = decodeUInt32(key, start_index);
		start_index += 4;
		byte[] md_b = new byte[len];
		for(int i = 0 ; i < len; i++){
			md_b[i] = key[start_index++];
		}
		BigInteger md = new BigInteger(md_b);
		KeyFactory keyFactory = KeyFactory.getInstance("RSA");
		KeySpec ks = new RSAPublicKeySpec(md, pe);
		byte[] pubBytes = keyFactory.generatePublic(ks).getEncoded();
		writePEM("pubPkcs8.pem", "PUBLIC KEY", pubBytes );
		return keyFactory.generatePublic(ks);

	}


	private static String getRandomString(int length){
		String str="abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
		Random random=new Random();
		StringBuffer sb=new StringBuffer();
		for(int i=0;i<length;i++){
			int number=random.nextInt(62);
			sb.append(str.charAt(number));
		}
		return sb.toString();
	}
	private static Key getKey(String keySeed) {
		try {
			SecureRandom secureRandom = SecureRandom.getInstance("SHA1PRNG");
			secureRandom.setSeed(keySeed.getBytes());
			KeyGenerator generator = KeyGenerator.getInstance("AES");
			generator.init(secureRandom);
			return generator.generateKey();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}


	private static String decrypt(String cipherText, Key secretKey) {

		try {
			Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
			cipher.init(Cipher.DECRYPT_MODE, secretKey);
			byte[] c = java.util.Base64.getDecoder().decode(cipherText);
			byte[] result = cipher.doFinal(c);
			String plainText = new String(result, "UTF-8");
			return plainText;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}


	private static String encrypt(String plainText,Key secretKey) {

		try {
			Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
			cipher.init(Cipher.ENCRYPT_MODE, secretKey);
			byte[] p = plainText.getBytes("UTF-8");
			byte[] result = cipher.doFinal(p);
			String encoded = java.util.Base64.getEncoder().encodeToString(result);
			return encoded;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
}
