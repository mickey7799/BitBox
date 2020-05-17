package unimelb.bitbox;

//Remember to add the args4j jar to your project's build path
import org.kohsuke.args4j.Option;

public class CmdArgs {

    @Option(required = true, name = "-c", aliases = {"--command"}, usage = "Command")
    private String command;

    @Option(required = false, name = "-s", usage = "Client")
    private String client = "localhost:8111";

    @Option(required = false, name = "-p", usage = "Peers")
    private String peers;

    @Option(required = false, name = "-i", usage = "Identity")
    private String identity="mickey@Mickeys-MacBook.local";



    public String getCommand() {
        return command;
    }

    public String getClient() {
        return client;
    }

    public String getPeer() {
        return peers;
    }
    public String getIdentity() {
        return identity;
    }

}
