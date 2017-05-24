package com.aidos.ari.service;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.aidos.ari.Milestone;
import com.aidos.ari.Peers;
import com.aidos.ari.conf.Configuration;
import com.aidos.ari.conf.Configuration.DefaultConfSettings;
import com.aidos.ari.conf.ipType;
import com.aidos.ari.hash.Curl;
import com.aidos.ari.model.Hash;
import com.aidos.ari.model.Transaction;
import com.aidos.ari.service.storage.Storage;
import com.aidos.ari.service.storage.StorageScratchpad;
import com.aidos.ari.service.storage.StorageTransactions;
import java.io.DataInputStream;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * The class node is responsible for managing Thread's connection.
 */
public class Node {

	private static final Logger log = LoggerFactory.getLogger(Node.class);

	private static final Node instance = new Node();

	private static final int TRANSACTION_PACKET_SIZE = 1650;
	private static final int QUEUE_SIZE = 1000;
	private static final int PAUSE_BETWEEN_TRANSACTIONS = 100;

	private ServerSocket socket;

	private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

	private final List<Peers> peers = new CopyOnWriteArrayList<>();
	private final ConcurrentSkipListSet<Transaction> queuedTransactions = weightQueue();

	private final byte[] receivingPacket = new byte[TRANSACTION_PACKET_SIZE];
	private final byte[] sendingPacket = new byte[TRANSACTION_PACKET_SIZE];
	private final byte[] tipRequestingPacket = new byte[TRANSACTION_PACKET_SIZE];

	private final ExecutorService executor = Executors.newFixedThreadPool(4);

	public void init() throws Exception {

		socket = new ServerSocket(Configuration.integer(DefaultConfSettings.MESH_RECEIVER_PORT));

		executor.submit(spawnReceiverThread());
		executor.submit(spawnBroadcasterThread());
		executor.submit(spawnTipRequesterThread());

		executor.shutdown();
	}

	public static Optional<String> checkIp(final String dnsName) {

		if (StringUtils.isEmpty(dnsName)) {
			return Optional.empty();
		}

		InetAddress inetAddress;
		try {
			inetAddress = java.net.InetAddress.getByName(dnsName);
		} catch (UnknownHostException e) {
			return Optional.empty();
		}

		final String hostAddress = inetAddress.getHostAddress();

		if (StringUtils.equals(dnsName, hostAddress)) { // not a DNS...
			return Optional.empty();
		}

		return Optional.of(hostAddress);
	}

	private Runnable spawnReceiverThread() {
		return () -> {

			final Curl curl = new Curl();
			final int[] receivedTransactionTrits = new int[Transaction.TRINARY_SIZE];
			final byte[] requestedTransaction = new byte[Transaction.HASH_SIZE];

			log.info("Spawning Receiver Thread");

			final SecureRandom rnd = new SecureRandom();
			long randomTipBroadcastCounter = 0;

			while (!shuttingDown.get()) {
				try (Socket s = socket.accept(); DataInputStream in = new DataInputStream(s.getInputStream());) {
					int psize = 0;
					for (psize = 0; psize < TRANSACTION_PACKET_SIZE;) {
						int si = in.read(receivingPacket, psize, TRANSACTION_PACKET_SIZE - psize);
						if (si < 0) {
							break;
						}
						psize += si;
					}

					if (psize == TRANSACTION_PACKET_SIZE) {

						for (final Peers peer : peers) {
							if (peer.getAddress().getAddress().equals(s.getInetAddress())) {
								try {
									peer.incAllTransactions();
									final Transaction receivedTransaction = new Transaction(receivingPacket,
											receivedTransactionTrits, curl);
									if (StorageTransactions.instance().storeTransaction(receivedTransaction.hash,
											receivedTransaction, false) != 0) {
										peer.incNewTransactions();
										broadcast(receivedTransaction);
									}

									final long transactionPointer;
									System.arraycopy(receivingPacket, Transaction.SIZE, requestedTransaction, 0,
											Transaction.HASH_SIZE);
									if (Arrays.equals(requestedTransaction, receivedTransaction.hash)) {
										if (Configuration.booling(DefaultConfSettings.EXPERIMENTAL)
												&& ++randomTipBroadcastCounter % 3 == 0) {
											log.info("Experimental: Random Tip Broadcaster.");

											final String[] tips = StorageTransactions.instance().tips().stream()
													.map(Hash::toString).toArray(size -> new String[size]);
											final String rndTipHash = tips[rnd.nextInt(tips.length)];

											transactionPointer = StorageTransactions.instance()
													.transactionPointer(rndTipHash.getBytes());
										} else {
											transactionPointer = StorageTransactions.instance()
													.transactionPointer(Milestone.latestMilestone.bytes());
										}
									} else {
										transactionPointer = StorageTransactions.instance()
												.transactionPointer(requestedTransaction);
									}
									if (transactionPointer > Storage.CELLS_OFFSET - Storage.SUPER_GROUPS_OFFSET) {
										synchronized (sendingPacket) {
											System.arraycopy(
													StorageTransactions.instance()
															.loadTransaction(transactionPointer).bytes,
													0, sendingPacket, 0, Transaction.SIZE);
											StorageScratchpad.instance().transactionToRequest(sendingPacket,
													Transaction.SIZE);
											peer.send(sendingPacket);
										}
									}
								} catch (final RuntimeException e) {
									log.error("Received an Invalid Transaction. Dropping it...");
									peer.incInvalidTransactions();
								}
								break;
							}
						}
					} else {
						log.error("illegal packet size={},from={}", psize, s.getInetAddress());
					}
				} catch (final Exception e) {
					log.error("Receiver Thread Exception:", e);

				}
			}
			log.info("Shutting down spawning Receiver Thread");
		};
	}

	private Runnable spawnBroadcasterThread() {
		return () -> {

			log.info("Spawning Broadcaster Thread");

			while (!shuttingDown.get()) {

				try {
					final Transaction transaction = queuedTransactions.pollFirst();
					if (transaction != null) {

						for (final Peers peer : peers) {
							try {
								synchronized (sendingPacket) {
									System.arraycopy(transaction.bytes, 0, sendingPacket, 0, Transaction.SIZE);
									StorageScratchpad.instance().transactionToRequest(sendingPacket, Transaction.SIZE);
									peer.send(sendingPacket);
								}
							} catch (final Exception e) {
								// ignore
							}
						}
					}
					Thread.sleep(PAUSE_BETWEEN_TRANSACTIONS);
				} catch (final Exception e) {
					log.error("Broadcaster Thread Exception:", e);
				}
			}
			log.info("Shutting down Broadcaster Thread");
		};
	}

	private Runnable spawnTipRequesterThread() {
		return () -> {

			log.info("Spawning Tips Requester Thread");

			while (!shuttingDown.get()) {

				try {
					final Transaction transaction = StorageTransactions.instance()
							.loadMilestone(Milestone.latestMilestone);
					System.arraycopy(transaction.bytes, 0, tipRequestingPacket, 0, Transaction.SIZE);
					System.arraycopy(transaction.hash, 0, tipRequestingPacket, Transaction.SIZE, Transaction.HASH_SIZE);

					peers.forEach(n -> n.send(tipRequestingPacket));

					Thread.sleep(5000);
				} catch (final Exception e) {
					log.error("Tips Requester Thread Exception:", e);
				}
			}
			log.info("Shutting down Requester Thread");
		};
	}

	private static ConcurrentSkipListSet<Transaction> weightQueue() {
		return new ConcurrentSkipListSet<>((transaction1, transaction2) -> {
			if (transaction1.weightMagnitude == transaction2.weightMagnitude) {
				for (int i = 0; i < Transaction.HASH_SIZE; i++) {
					if (transaction1.hash[i] != transaction2.hash[i]) {
						return transaction2.hash[i] - transaction1.hash[i];
					}
				}
				return 0;
			}
			return transaction2.weightMagnitude - transaction1.weightMagnitude;
		});
	}

	public void broadcast(final Transaction transaction) {
		queuedTransactions.add(transaction);
		if (queuedTransactions.size() > QUEUE_SIZE) {
			queuedTransactions.pollLast();
		}
	}

	public void shutdown() throws InterruptedException {
		shuttingDown.set(true);
		executor.awaitTermination(6, TimeUnit.SECONDS);
	}

	// helpers methods
	public boolean removePeer(final URI uri) {
		return peers
				.remove(new Peers(new InetSocketAddress(uri.getHost(), uri.getPort()), PD.getIpTypeForAddress(uri)));
	}

	public boolean removePeer(final InetSocketAddress is) {
		return peers.remove(new Peers(is, PD.getIpTypeForAddress(is.getAddress())));
	}
	
	public boolean removePeer(final Peers peer) {
		return Node.instance().getPeers().remove(peer);
	}

	public boolean addPeer(final URI uri) {
		final Peers peer = new Peers(new InetSocketAddress(uri.getHost(), uri.getPort()), PD.getIpTypeForAddress(uri));
		if (!Node.instance().getPeers().contains(peer)) {
			return Node.instance().getPeers().add(peer);
		}
		return false;
	}

	public boolean addPeer(final Peers peer) {
		if (!Node.instance().getPeers().contains(peer)) {
			return Node.instance().getPeers().add(peer);
		}
		return false;
	}

	public static Optional<Map<URI, ipType>> uriAndType(final String uri) {
		try {
			// always size 2
			String[] values = uri.split("\\|");
			Map<URI, ipType> map = new HashMap<URI, ipType>();
			map.put(new URI(values[0]), ipType.valueOf(values[1]));
			return Optional.of(map);
		} catch (URISyntaxException e) {
		}
		return Optional.empty();
	}

	public static Optional<URI> uri(final String uri) {
		try {
			return Optional.of(new URI(uri));
		} catch (URISyntaxException e) {
			log.error("Uri {} raised URI Syntax Exception", uri);
		}
		return Optional.empty();
	}

	public static Node instance() {
		return instance;
	}

	public int queuedTransactionsSize() {
		return queuedTransactions.size();
	}

	public int howManyPeers() {
		return peers.size();
	}

	public List<Peers> getPeers() {
		return peers;
	}

	// mask
	public List<Peers> getPeersWithoutMilestone() {
		List<Peers> temp = new ArrayList<Peers>(peers);
		if (Milestone.milestoneIp != null) {
			for (InetSocketAddress milestone : Milestone.milestoneIp) {
				temp.remove(new Peers(milestone, ipType.mixed));
			}
		}
		return temp;
	}

	private Node() {
	}
}
