package unimelb.bitbox;

import org.apache.commons.codec.binary.Base64;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;
import org.kohsuke.args4j.CmdLineParser;
import unimelb.bitbox.util.Document;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.logging.Logger;

public class Client {
    private static Logger log = Logger.getLogger(ServerMain.class.getName());

    public static void main( String[] args )throws Exception {
        CmdArgs argsBean = new CmdArgs();
        CmdLineParser parser = new CmdLineParser(argsBean);
        parser.parseArgument(args);
        String[] argsclient = argsBean.getPeer().split(":");
        clientThread(argsclient[0],Integer.parseInt(argsclient[1]),argsBean.getIdentity(),argsBean.getCommand());
    }

	private static PrivateKey getPrivateKey(String filename) throws IOException, GeneralSecurityException {
        String strKeyPEM = "";
        BufferedReader br = new BufferedReader(new FileReader(filename));
        String line;
        while ((line = br.readLine()) != null) {
            strKeyPEM += line + "\n";
        }
        br.close();
        String privateKeyPEM = strKeyPEM;
        privateKeyPEM = privateKeyPEM.replace("-----BEGIN RSA PRIVATE KEY-----\n", "");
        privateKeyPEM = privateKeyPEM.replace("-----END RSA PRIVATE KEY-----\n", "");
        java.security.Security.addProvider(
                new org.bouncycastle.jce.provider.BouncyCastleProvider()
        );

        byte[] encoded = Base64.decodeBase64(privateKeyPEM.getBytes());
        KeyFactory privatekf = KeyFactory.getInstance("RSA");
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(encoded);
        RSAPrivateKey privKey = (RSAPrivateKey) privatekf.generatePrivate(keySpec);
        byte[] pubBytes = privKey.getEncoded();
        writePEM("priPkcs8.pem", "PRIVATE KEY", pubBytes );
        return privatekf.generatePrivate(keySpec);
    }
    private static void writePEM(String fileName, String header, byte[] content) throws IOException{
        File f = new File(fileName);
        FileWriter fw = new FileWriter(f);
        PemObject pemObject = new PemObject(header, content);
        PemWriter pemWriter = new PemWriter(fw);
        pemWriter.writeObject(pemObject);
        pemWriter.close();
    }

    private static String RSAdecrypt(String cipherText, PrivateKey privateKey) throws IOException, GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.DECRYPT_MODE, privateKey);
        return new String(cipher.doFinal(Base64.decodeBase64(cipherText.getBytes())), "UTF-8");
    }

    private static void clientThread(String host, int port, String identity, String command){
        try {
            String input;
            Socket socket = new Socket(host, port);
            BufferedReader bufferIn = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter bufferOut = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

            log.info("successful connected with " + host + ":" + port);

            //Respond from server
            bufferOut.write(protocolAuthReq(identity).toJson()+ "\n");
            bufferOut.flush();

            input = communicateRead(bufferIn);
            System.out.println();
            String cipherBytes;

            log.info(host + ":" + port + " response:\t" + Document.parse(input).getString("command"));
            if (Document.parse(input).getString("command").equals("AUTH_RESPONSE")) {

                String key =  Document.parse(input).getString("AES128");
                String aeskeySt = RSAdecrypt(key,getPrivateKey("bitboxclient_rsa"));

                byte[] decodeKey = java.util.Base64.getDecoder().decode(aeskeySt);

                SecretKey AESkey = new SecretKeySpec(decodeKey,0,decodeKey.length,"AES");
                switch (command){
                    case "list_peers":

                        cipherBytes = encrypt(protocolList().toJson(), AESkey);
                        bufferOut.write(secureCommunicate(cipherBytes).toJson() + "\n");

                        bufferOut.flush();
                        break;
                    case "connect_peer":
                        cipherBytes = encrypt(protocolConnect(host,port).toJson(),AESkey);
                        bufferOut.write(secureCommunicate(cipherBytes).toJson() + "\n");
                        bufferOut.flush();
                        break;
                    case "disconnect_peer":
                        cipherBytes = encrypt(protocolDisconnect(host,port).toJson(),AESkey);
                        bufferOut.write(secureCommunicate(cipherBytes).toJson() + "\n");
                        bufferOut.flush();
                        break;
                }

                input = communicateRead(bufferIn);
                String payload = Document.parse(input).getString("payload");
                String response = decrypt(payload,AESkey);
                String message = Document.parse(response).getString("message");
                switch (Document.parse(response).getString("command")) {
                    case "LIST_PEERS_RESPONSE":
                        Document res = Document.parse(response);
                        ArrayList<Document> arr = (ArrayList<Document>) res.get("peers");
                        log.info("response success");
                        for(int i = 0; i < arr.size();i++)
                            log.info(arr.get(i).toJson());
                        socket.close();
                        break;
                    case "CONNECT_PEER_RESPONSE":
                        log.info(message);
                        socket.close();
                        break;
                    case "DISCONNECT_PEER_RESPONSE":
                        log.info(message);
                        socket.close();
                        break;
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static String communicateRead(BufferedReader in) throws IOException {
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

    private static String decrypt(String cipherText, Key secretKey) {

        try {
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.DECRYPT_MODE, secretKey);
            byte[] c = java.util.Base64.getDecoder().decode(cipherText);
            byte[] result = cipher.doFinal(c);
            return new String(result, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String encrypt(String plainText,Key secretKey) {

        try {
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
            byte[] p = plainText.getBytes(StandardCharsets.UTF_8);
            byte[] result = cipher.doFinal(p);
            return java.util.Base64.getEncoder().encodeToString(result);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Document protocolList() {
        Document newDoc = new Document();
        newDoc.append("command", "LIST_PEERS_REQUEST");
        return newDoc;
    }

    private static Document protocolConnect(String host, int port) {
        Document newDoc = new Document();
        newDoc.append("command", "CONNECT_PEER_REQUEST");
        newDoc.append("host", host);
        newDoc.append("port", port);
        return newDoc;
    }

    private static Document protocolDisconnect(String host, int port) {
        Document newDoc = new Document();
        newDoc.append("command", "DISCONNECT_PEER_REQUEST");
        newDoc.append("host", host);
        newDoc.append("port", port);
        return newDoc;
    }

    private static Document protocolAuthReq(String identity) {
        Document newDoc = new Document();
        newDoc.append("command", "AUTH_REQUEST");
        newDoc.append("identity", identity);
        return newDoc;
    }

    private static Document secureCommunicate(String payload) {
        Document newDoc = new Document();
        newDoc.append("payload", payload);
        return newDoc;
    }
}
