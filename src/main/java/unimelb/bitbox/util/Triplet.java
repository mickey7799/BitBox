package unimelb.bitbox.util;

public class Triplet<T, U, V> {

	private final T host;
	private final U port;
	private final V content;

	public Triplet(T host, U port, V fileEvent) {
		this.host = host;
		this.port = port;
		this.content = fileEvent;
	}

	public T getHost() { return host; }
	public U getPort() { return port; }
	public V getContent() { return content; }
}
