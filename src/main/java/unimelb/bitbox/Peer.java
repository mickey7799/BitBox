package unimelb.bitbox;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.logging.Logger;
import unimelb.bitbox.util.Configuration;

public class Peer
{

	private static Logger log = Logger.getLogger(Peer.class.getName());
    public static void main( String[] args ) throws IOException, NumberFormatException, NoSuchAlgorithmException
    {
    	System.setProperty("java.util.logging.SimpleFormatter.format",
                "[%1$tc] %2$s %4$s: %5$s%n");
        log.info("BitBox Peer starting...");
        Configuration.getConfiguration();

        String mode = Configuration.getConfigurationValue("mode");
        if (mode.equals("tcp")) {
            new ServerMain();
            //try to connect with each peer in config
            ServerMain.initialConnections();
            Thread server = new Thread(() -> ServerMain.createServerSocket(Integer.parseInt(Configuration.getConfigurationValue("port"))));
            server.start();
            Thread sycFile = new Thread(() -> ServerMain.sycnFiles(Integer.parseInt(Configuration.getConfigurationValue("syncInterval"))));
            sycFile.start();
            Thread client = new Thread(() -> SubServer.createClientServer());
            client.start();
        }else if(mode.equals("udp")){
            new UdpServerMain();
            UdpServerMain.initialConnections();
        }
    }
}


