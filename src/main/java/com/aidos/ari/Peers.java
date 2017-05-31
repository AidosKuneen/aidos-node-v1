package com.aidos.ari;

import com.aidos.ari.conf.ipType;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Peers {
	private static final Logger log = LoggerFactory.getLogger(Peers.class);
	private final InetSocketAddress address;
	private final ipType type;

	private int numberOfAllTransactions;
	private int numberOfNewTransactions;
	private int numberOfInvalidTransactions;
	private ExecutorService exec = Executors.newFixedThreadPool(10);

//	public Peers(final InetSocketAddress address) {
//		this.address = address;
//		this.type = ipType.no_init;
//	}

	public Peers(final InetSocketAddress address, final ipType type) {
		this.address = address;
		this.type = type;
	}

	public boolean isLocal() {
		InetAddress a = address.getAddress();
		if (a.isLinkLocalAddress() || a.isLoopbackAddress() || a.isSiteLocalAddress()) {
			log.error("loopback adress {}", a);
			return true;
		}
		return false;
	}

	public void send(final byte[] packet) {
		exec.submit(() -> {
			DataOutputStream dos = null;
			try (Socket s = new Socket();) {
				s.connect(address, 2000);
				dos = new DataOutputStream(s.getOutputStream());
				dos.write(packet);
			} catch (final IOException e) {
				log.error("cannot send to {},{}", address, e.getMessage());
			} finally {
				try {
					if (dos != null) {
						dos.close();
					}
				} catch (final IOException ee) {
				}
			}
		});
	}

	@Override
	public boolean equals(final Object obj) {
		if (this == obj) {
			return true;
		}
		if ((obj == null) || (obj.getClass() != this.getClass())) {
			return false;
		}
		return address.equals(((Peers) obj).address);
	}

	@Override
	public int hashCode() {
		return address.hashCode();
	}

	public InetSocketAddress getAddress() {
		return address;
	}

	public ipType getType() {
		return type;
	}

	public void incAllTransactions() {
		numberOfAllTransactions++;
	}

	public void incNewTransactions() {
		numberOfNewTransactions++;
	}

	public void incInvalidTransactions() {
		numberOfInvalidTransactions++;
	}

	public int getNumberOfAllTransactions() {
		return numberOfAllTransactions;
	}

	public int getNumberOfInvalidTransactions() {
		return numberOfInvalidTransactions;
	}

	public int getNumberOfNewTransactions() {
		return numberOfNewTransactions;
	}
}
