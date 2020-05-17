package unimelb.bitbox.util;

public class Fivelet<T,U,V,N,M> {

    private final T host;
    private final U port;
    private final V content;
    private final N count;
    private final M time;

    public Fivelet(T host, U port, V fileEvent, N count, M time) {
        this.host = host;
        this.port = port;
        this.content = fileEvent;
        this.count = count;
        this.time = time;
    }

    public T getHost() { return host; }
    public U getPort() { return port; }
    public V getContent() { return content; }
    public N getCount() { return  count;}
    public M getTime() { return  time;}
}
