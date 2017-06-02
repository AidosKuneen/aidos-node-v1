package com.aidos.ari.service;

import com.aidos.ari.Peers;
import com.aidos.ari.conf.Configuration;
import com.aidos.ari.conf.Configuration.DefaultConfSettings;
import com.aidos.ari.conf.ipType;
import com.jayway.jsonpath.*;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.Record;
import org.xbill.DNS.SRVRecord;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;

public class PD {

	private static final Logger log = LoggerFactory.getLogger(PD.class);

	private volatile boolean shuttingDown;

	private static final PD instance = new PD();

	private static final String PD_FILE = "peerlist.store";

	// Number of nodes that should be added as peers
	private static final int PEERS_TO_FIND = 6;

	// Empty string for "false", or local ip in ipv4/6
	private static Map<ipType, String> ipMode = new HashMap<ipType, String>();
	// Seed for test has to be dualstack!
	private static String ipSeed = "seed1.aidoskuneen.com";
	private static String SRVEntry = "_seeds._tcp.aidoskuneen.com";

	private static boolean connect;
	private static boolean startLocal;

	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	private static int defaultAPIport = Configuration.integer(DefaultConfSettings.API_PORT);
	private static int defaultMeshPort = Configuration.integer(DefaultConfSettings.MESH_RECEIVER_PORT);

	public void init() throws Exception {
		// Startup

		// If local ip was set via cmd line args
		startLocal = !Configuration.string(DefaultConfSettings.LOCAL).equals("");

		// Init empty
		ipMode.put(ipType.ipv4, "");
		ipMode.put(ipType.ipv6, "");

		// Determine ip mode ipv4, ipv6 or dualstack/mixed
		if (startLocal) {
			if (InetAddress.getByName(Configuration.string(DefaultConfSettings.LOCAL)) instanceof Inet4Address) {
				ipMode.put(ipType.ipv4, Configuration.string(DefaultConfSettings.LOCAL));
			}
			if (InetAddress.getByName(Configuration.string(DefaultConfSettings.LOCAL)) instanceof Inet6Address) {
				ipMode.put(ipType.ipv6, Configuration.string(DefaultConfSettings.LOCAL));
			}
		} else {
			setIpMode();
		}

		// After here can call getIpMode()

		// Load PD file check if exists
		// First put nodes in peerlist into peers while checking its connection and getting local ip address.
		// cruel codes :( even more cruel codes :((
		if (Files.exists(Paths.get(PD_FILE)) && Files.size(Paths.get(PD_FILE)) != 0) {
			try (BufferedReader br = Files.newBufferedReader(Paths.get(PD_FILE))) {
				Arrays.stream(br.readLine().split(" ")).distinct().filter(s -> !s.isEmpty()).map(Node::uriAndType)
						.filter(u -> u.isPresent()).map(Optional::get).peek(u -> {
							if (!"tcp".equals(((URI) u.keySet().toArray()[0]).getScheme())) {
								log.warn("WARNING: {} is not a valid tcp:// uri schema.", u.keySet().toArray()[0]);
							}
						}).filter(u -> "tcp".equals(((URI) u.keySet().toArray()[0]).getScheme()))
						.map(u -> new Peers(
								new InetSocketAddress(((URI) u.keySet().toArray()[0]).getHost(),
										((URI) u.keySet().toArray()[0]).getPort()),
								u.get(((URI) u.keySet().toArray()[0]))))
						.peek(u -> {
						}).forEach(peer -> {
							if (connect == false) {
								if (isPeerOnline(
										new InetSocketAddress(peer.getAddress().getAddress(), defaultAPIport))) {
									connect = true;
								}
							}
							boolean add = Node.instance().addPeer(peer);
							log.debug("Adding {} to Node Peers. Success: {}",
									peer.getAddress().getAddress().getHostAddress(), add);
						});
			} catch (IOException e) {
				log.error("Error opening nodes file. {}", e.getMessage());
			}
		}
		// If and only if no File exists (node list is empty) or no connection possible bootstrap initial node(s) from
		// server
		if (connect == false) {
			lookup().ifPresent((dns) -> {
				isPeerRunning(new InetSocketAddress(dns.getAddress().getAddress().getHostAddress(), defaultAPIport))
						.ifPresent(ip -> {
							// This shouldn't happen to be true anymore except its the initial node
							if (!dns.getAddress().getAddress().getHostAddress().equals(ip)) {
								connect = true;
								Peers peer = new Peers(dns.getAddress(), dns.getType());
								Node.instance().addPeer(peer);
								log.debug("Adding {} to Node Peers. Success: {}",
										peer.getAddress().getAddress().getHostAddress(),
										Node.instance().getPeers().contains(peer));
							} else {
								// Inital node
								connect = true;
								log.warn("Node bootstrapped from itself. "
										+ "This message should only appear if its the initial node.");
							}
						});
			});
		}

		if (connect == false) {
			throw new Exception("Can't connect to any nodes.");
		}

		executor.submit(spawnPDThread());
		executor.shutdown();
	}

	// Assume
	// _seeds._tcp.aidoskuneen.com. IN SRV 0 0 14265 seed1.aidoskuneen.com.
	// seed1 IN A 123.321.123.321
	// Returns all entries (not RR style) so have to select one for load balancing
	// Bootstrap with itself if its the initial node
	private Optional<Peers> lookup() {
		InetSocketAddress result = null;
		Peers initial = null;
		InetSocketAddress api;
		ipType resultType;
		// try {
		Record[] records = null;
		try {
			records = new Lookup(SRVEntry, Type.SRV).run();
		} catch (TextParseException e) {
			log.error("Couldn't retrieve record from DNS {}", e.getMessage());
		}
		if (records != null) {
			// Shuffle the records
			Collections.shuffle(Arrays.asList(records));
			// Try until record is found or until all records have been searched
			for (Record r : records) {
				SRVRecord srv = (SRVRecord) r;
				String hostname = srv.getTarget().toString().replaceFirst("\\.$", "");
				// Resolve hostname to ip, this can return ipv4 and ipv6
				InetAddress[] address = null;
				try {
					address = InetAddress.getAllByName(hostname);
				} catch (UnknownHostException e) {
					log.error("Couldn't retrieve IP from Hosts {}", e.getMessage());
				}
				if (address != null) {
					// Randomize, because otherwise will always return ipv4 first, which makes mixed always seed from
					// ipv4.
					Collections.shuffle(Arrays.asList(address));
					for (InetAddress a : address) {
						if (a instanceof Inet4Address && PD.instance().getIpMode() != ipType.ipv6
								&& PD.instance().getIpMode() != ipType.mixed) {
							result = new InetSocketAddress((Inet4Address) a, srv.getPort());
							api = new InetSocketAddress((Inet4Address) a, defaultAPIport);
							if (isPeerOnline(api)) {
								// Determine Seed Type
								resultType = ipType.ipv4;
								// If length is not two Type is instanceof
								if (address.length == 2) {
									resultType = ipType.mixed;
								}
								if (api.getAddress().getHostAddress().equals(ipMode.get(ipType.ipv4))) {
									log.debug("Seed {} is itself. (ipv4)", a.getHostAddress());
									// Found itself
									initial = new Peers(result, resultType);
								} else {
									log.debug("Seed {} is other ipv4.", a.getHostAddress());
									log.info("Retrieving Seed from DNS success. (ipv4)");
									return Optional.of(new Peers(result, resultType));
								}
							} else {
								// API not reachable
								log.debug("Seed {} is unreachable. (ipv4)", a.toString());
							}
						}
						if (a instanceof Inet6Address && PD.instance.getIpMode() != ipType.ipv4) {
							result = new InetSocketAddress((Inet6Address) a, srv.getPort());
							api = new InetSocketAddress((Inet6Address) a, defaultAPIport);
							if (isPeerOnline(api)) {
								// Determine Seed Type
								resultType = ipType.ipv6;
								// If length is not two Type is instanceof
								if (address.length == 2) {
									resultType = ipType.mixed;
								}
								if (api.getAddress().getHostAddress().equals(ipMode.get(ipType.ipv6))) {
									log.debug("Seed {} is itself. (ipv6)", a.getHostAddress());
									// Found itself
									initial = new Peers(result, resultType);
								} else {
									log.debug("Seed {} is other ipv6.", a.getHostAddress());
									log.info("Retrieving Seed from DNS success. (ipv6)");
									return Optional.of(new Peers(result, resultType));
								}
							} else {
								// API not reachable
								log.debug("Seed {} is unreachable. (ipv6)", a.toString());
							}
						}
					}
				}
			}
			// If didn't return yet has to be itself.
			if (initial != null) {
				log.debug("Retrieving Seed from DNS success. (itself)");
				return Optional.of(initial);
			}
		} else {
			log.warn("Retrieving seeds from DNS failed.");
		}
		return Optional.empty();
	}

	// Set local ipv4/ipv6
	private void setIpMode() throws Exception {
		InetAddress[] test = InetAddress.getAllByName(ipSeed);
		for (InetAddress t : test) {
			if (t instanceof Inet6Address && isPeerOnline(new InetSocketAddress((Inet6Address) t, defaultAPIport))) {
				ipMode.put(ipType.ipv6,
						isPeerRunning(new InetSocketAddress((Inet6Address) t, defaultAPIport)).orElse(""));
				log.debug("IP set to: {} (ipv6)", ipMode.get(ipType.ipv6));
				if (ipMode.get(ipType.ipv6).equals("")) {
					log.debug("Failure setting ip mode. (ipv6)");
				}
			}
			if (t instanceof Inet4Address && isPeerOnline(new InetSocketAddress((Inet4Address) t, defaultAPIport))) {
				ipMode.put(ipType.ipv4,
						isPeerRunning(new InetSocketAddress((Inet4Address) t, defaultAPIport)).orElse(""));
				log.debug("IP set to: {} (ipv4)", ipMode.get(ipType.ipv4));
				if (ipMode.get(ipType.ipv4).equals("")) {
					log.debug("Failure setting ip mode. (ipv4)");
				}
			}
		}
		if (ipMode.get(ipType.ipv4).equals("") && ipMode.get(ipType.ipv6).equals("")) {
			throw new Exception("Couldn't set ip mode. Maybe " + ipSeed + " isn't reachable.");
		}
	}

	// <Peeraddress, searched his Peers already?>
	private Map<InetSocketAddress, Boolean> peerSearch = new HashMap<InetSocketAddress, Boolean>();
	// Copy peers here because it can contain nodes that are not added to peers for peerSearch
	private List<Peers> peersIterate = new ArrayList<Peers>();
	// Keeps offline peers and re-checks them if server got disconnected completely because then peers is empty
	private List<Peers> peersIterateDC = new ArrayList<Peers>();

	private Runnable spawnPDThread() {
		return () -> {

			log.info("Spawning PD Thread");
			while (!shuttingDown) {

				// Local address
				InetSocketAddress local = new InetSocketAddress(
						ipMode.get(getIpMode() == ipType.mixed ? ipType.ipv6 : getIpMode()), defaultMeshPort);

				try {
					List<Peers> peers = Node.instance().getPeers();
					// Reset because its an arraylist, otherwise peers get added multiple times.
					// peersIterateDC is not cleared.
					peersIterate.clear();
					// This should be cleared also.
					peerSearch.clear();
					if (peers.size() > 0) {
						// Multiple threads should make this alot faster because of TIMEOUT.
						ExecutorService statusExecutor = Executors.newFixedThreadPool(peers.size());
						// online check
						for (final Peers peer : peers) {
							// Can start with initial list.
							statusExecutor.execute(() -> {
								if (!isPeerOnline(
										new InetSocketAddress(peer.getAddress().getAddress(), defaultAPIport))) {
									Node.instance().removePeer(peer);
								} else {
									// Check all online peers push status, also needed for bootstrap from peerlist.store
									// because otherwise added peers are never pushed to
									InetSocketAddress tempLocal = local;
									if (peer.getType() == ipType.ipv4 && getIpMode() == ipType.mixed) {
										tempLocal = new InetSocketAddress(ipMode.get(ipType.ipv4), defaultMeshPort);
									}
									int pushStatus = pushPeer(
											new InetSocketAddress(peer.getAddress().getAddress().getHostAddress(),
													defaultAPIport),
											tempLocal);
									if (pushStatus >= 0) {
										log.debug("Push peer: {} Status: {}",
												peer.getAddress().getAddress().getHostAddress(), pushStatus);
									} else {
										// Couldn't push to, so can remove
										Node.instance().removePeer(peer);
									}
									// Add to search this peers peers anyway
									peersIterate.add(peer);
									// No double-entries because DC is not cleared
									if (!peersIterateDC.contains(peer)) {
										peersIterateDC.add(peer);
									}
								}
							});
						}
						statusExecutor.shutdown();
						// Give 1 sec buffer
						if (!statusExecutor.awaitTermination(Configuration.CONNECTION_TIMEOUT + 1, TimeUnit.SECONDS)) {
							log.warn("Threads didn't finish in {} msec.", Configuration.CONNECTION_TIMEOUT + 1);
						}
					} else {
						// After node completely disconnected from net it loses all its peers.
						// Check all nodes here after so reconnect is possible.
						if (peersIterateDC.size() > 0) {
							ExecutorService statusExecutor = Executors.newFixedThreadPool(peersIterateDC.size());
							for (final Peers peer : peersIterateDC) {
								statusExecutor.execute(() -> {
									InetSocketAddress tempLocal = local;
									if (isPeerOnline(
											new InetSocketAddress(peer.getAddress().getAddress(), defaultAPIport))) {
										// Have to push also because other nodes also lost connection.
										// Determine ipType to push right local in case of mixed ipMode
										if (peer.getType() == ipType.ipv4 && getIpMode() == ipType.mixed) {
											tempLocal = new InetSocketAddress(ipMode.get(ipType.ipv4), defaultMeshPort);
										}
										if (pushPeer(
												new InetSocketAddress(peer.getAddress().getAddress().getHostAddress(),
														defaultAPIport),
												tempLocal) >= 0) {
											Node.instance().addPeer(new Peers(peer.getAddress(), peer.getType()));
											log.debug("Adding peer after disconnect {}",
													peer.getAddress().getAddress().getHostAddress());
										}
									}
								});
							}
							statusExecutor.shutdown();
							// Give 1 sec buffer
							if (!statusExecutor.awaitTermination(Configuration.CONNECTION_TIMEOUT + 1,
									TimeUnit.SECONDS)) {
								log.warn("Threads didn't finish in {} msec.", Configuration.CONNECTION_TIMEOUT + 1);
							}
						}
					}
					// iterate while not enough peers found
					// make it random
					Collections.shuffle(peersIterate);
					int i = -1;
					while (peers.size() < PEERS_TO_FIND && i + 1 < peersIterate.size()) {
						// Choose a node, this will also add peers of peers first searched to be searched since it
						// iterates through all.
						final Peers startSearch = peersIterate.get(++i);
						InetSocketAddress startSearchAPI = new InetSocketAddress(
								startSearch.getAddress().getAddress().getHostAddress(), defaultAPIport);
						// At this point shouldn't be a check needed for mixed, because only don't search further on
						// ipv4, which happens further below.
						if (compatibleIpTypes(getIpMode(), startSearch.getType())) {
							log.debug("Iteration Peer: {}", startSearch.getAddress().getAddress().getHostAddress());
							// Check if this nodes peers already have been searched
							if (!peerSearch.containsKey(startSearch.getAddress())
									|| !peerSearch.get(startSearch.getAddress())) {
								// Returns peer of peers.
								Map<InetSocketAddress, ipType> searchList = getPeersAndType(startSearchAPI);
								// Mark this peers peers as searched.
								peerSearch.put(startSearch.getAddress(), true);
								// Try to add these.
								for (InetSocketAddress a : searchList.keySet()) {
									// Contains means this peer has been added (was tried to be added) already.
									// Don't add node to itself!
									// Have to check with getIpType because they can be stored as mixed.
									ipType tempA = getIpTypeForAddress(a.getAddress());
									if (compatibleIpTypes(getIpMode(), tempA)) {
										// Need to check ipv4 here for mixed
										InetSocketAddress tempLocal2 = local;
										if (getIpMode() == ipType.mixed) {
											// Update local, need to determine right ipType to push for mixed
											tempLocal2 = (tempA == ipType.ipv4)
													? new InetSocketAddress(ipMode.get(ipType.ipv4), defaultMeshPort)
													: local;

										}
										if (!peersIterate.contains(new Peers(a, searchList.get(a)))
												&& !a.equals(tempLocal2)) {
											// For mixed nodes don't add mixed peers from ipv4, but can still search
											// them.
											if (a.getAddress() != null && compatibleIpTypesForSearch(
													startSearch.getType(), searchList.get(a))) {
												InetSocketAddress aAPI = new InetSocketAddress(
														a.getAddress().getHostAddress(), defaultAPIport);
												// Add local peer to remote, only if able to add local to remote
												// or if not added already
												if (pushPeer(aAPI, tempLocal2) >= 0) {
													// Add remote peer to local, test for duplicate.
													boolean add = Node.instance()
															.addPeer(new Peers(a, searchList.get(a)));
													log.debug("Adding peer {}. Added: {}",
															a.getAddress().getHostAddress(), add);
													// check here because could reach threshold while for-iterations
													if (peers.size() >= PEERS_TO_FIND) {
														break;
													}
												}
												// Even if the peer fails to be added, still search his peers.
												peersIterate.add(new Peers(a, searchList.get(a)));
												// Could only cause unnecessary overwrite
												// peerSearch.put(a, false);
											}
										}
									}
								}
							}
						}
					}
					Thread.sleep(1000 * 60 * 6); // Every 6 mins
				} catch (final Exception e) {
					log.error("PD Thread Exception: ", e);
				}
			}
			log.info("Shutting down PD Thread");
		};

	}

	// Simple connection check with timeout (faster than API check)
	public static boolean isPeerOnline(InetSocketAddress address) {
		try (Socket soc = new Socket()) {
			soc.connect(address, Configuration.CONNECTION_TIMEOUT);
		} catch (IOException ex) {
			log.debug("Peer {} not reachable.", address.getAddress().getHostAddress());
			return false;
		}
		return true;
	}

	// Checks peer online status via API ping command (more specific than just checkPeerConnect as it checks the API)
	// Returns only "ip" if it returns the expected JSON
	private Optional<String> isPeerRunning(InetSocketAddress address) {
		// Checks peer online status via API ping command
		try {
			String urlString = getHostURL(address);
			URL url = new URL("http://" + urlString);
			URLConnection con = url.openConnection();
			HttpURLConnection http = (HttpURLConnection) con;
			http.setRequestMethod("POST");
			http.setDoOutput(true);
			byte[] out = "{\"command\": \"ping\"}".getBytes(StandardCharsets.UTF_8);
			int length = out.length;

			http.setFixedLengthStreamingMode(length);
			http.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
			// Here if connection refused
			http.connect();

			try (OutputStream os = http.getOutputStream()) {
				os.write(out);
			}
			ByteArrayOutputStream result = new ByteArrayOutputStream();
			byte[] buffer = new byte[1024];
			int length2;
			while ((length2 = http.getInputStream().read(buffer)) != -1) {
				result.write(buffer, 0, length2);
			}
			// StandardCharsets.UTF_8.name() > JDK 7
			http.getInputStream().close();
			// Json
			String json = result.toString(StandardCharsets.UTF_8.name());
			String ip = JsonPath.parse(json).read("$.ip");

			return Optional.of(ip);
			// if (result.toString(StandardCharsets.UTF_8.name()).matches("\\{\"duration\":[\\d]+\\}")) {
			// return true;
			// }
			// receive answer duration:x then online, if not offline. could also return round time = ping.
		} catch (PathNotFoundException e) {
			log.warn("JSON Path failure.");
		} catch (IOException e) {
			log.debug("Can't connect to API on address: {}", address.getAddress().getHostAddress());
		}
		return Optional.empty();
	}

	// Get Peerlist from a node
	private Map<InetSocketAddress, ipType> getPeersAndType(InetSocketAddress remote) {
		Map<InetSocketAddress, ipType> addresses = new HashMap<InetSocketAddress, ipType>();
		try {
			String urlString = getHostURL(remote);
			URL url = new URL("http://" + urlString);
			URLConnection con = url.openConnection();
			HttpURLConnection http = (HttpURLConnection) con;
			http.setRequestMethod("POST");
			http.setDoOutput(true);

			byte[] out = "{\"command\": \"getPeerAddresses\"}".getBytes(StandardCharsets.UTF_8);
			int length = out.length;

			http.setFixedLengthStreamingMode(length);
			http.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
			// Here if connection refused
			http.connect();

			try (OutputStream os = http.getOutputStream()) {
				os.write(out);
			}
			ByteArrayOutputStream result = new ByteArrayOutputStream();
			byte[] buffer = new byte[1024];
			int length2;
			while ((length2 = http.getInputStream().read(buffer)) != -1) {
				result.write(buffer, 0, length2);
			}
			// StandardCharsets.UTF_8.name() > JDK 7
			http.getInputStream().close();
			// Json
			String json = result.toString(StandardCharsets.UTF_8.name());
			List<String> peerlist = JsonPath.parse(json).read("$.peerlist");

			for (String p : peerlist) {
				// ipv6 possible
				getAddressAndType(p).ifPresent(map -> {
					addresses.putAll(map);
				});
			}
		} catch (IOException e) {
			log.debug("Can't connect to API on address: {}", remote.getAddress().getHostAddress());
		}
		return addresses;
	}

	// 0 already added, 1 added, -1 maxed not added.
	private Integer pushPeer(InetSocketAddress remote, InetSocketAddress local) {
		try {
			String urlString = getHostURL(remote);
			URL url = new URL("http://" + urlString);
			URLConnection con = url.openConnection();
			HttpURLConnection http = (HttpURLConnection) con;
			http.setRequestMethod("POST");
			http.setDoOutput(true);

			byte[] out = ("{\"command\": \"addPeer\", \"uri\": \"tcp://" + getHostURL(local) + "\", \"type\": \""
					+ getIpMode().name() + "\"}").getBytes(StandardCharsets.UTF_8);
			int length = out.length;

			http.setFixedLengthStreamingMode(length);
			http.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
			// Here if connection refused
			http.connect();

			try (OutputStream os = http.getOutputStream()) {
				os.write(out);
			}
			ByteArrayOutputStream result = new ByteArrayOutputStream();
			byte[] buffer = new byte[1024];
			int length2;
			while ((length2 = http.getInputStream().read(buffer)) != -1) {
				result.write(buffer, 0, length2);
			}
			// StandardCharsets.UTF_8.name() > JDK 7
			http.getInputStream().close();
			// Json
			String json = result.toString(StandardCharsets.UTF_8.name());
			Integer addedPeer = JsonPath.parse(json).read("$.addedPeer");
			// 0 already added, 1 added, -1 maxed not added.
			return addedPeer;
		} catch (IOException e) {
			log.debug("Failure pushing Peer address to: {} {}", remote.getAddress().getHostAddress(), e.getMessage());
			// in case of an error, treat as not added.
			return -1;
		}
	}

	public ipType getIpMode() {
		if (ipMode.get(ipType.ipv4) != "" && ipMode.get(ipType.ipv6) != "") {
			return ipType.mixed;
		} else if (ipMode.get(ipType.ipv4) != "") {
			return ipType.ipv4;
		} else {
			return ipType.ipv6;
		}
	}

	// tests if the pushed ip is the right claimed type and also if its compatible with the node type.
	public static boolean pushTest(ipType given, ipType claimed) {
		if (compatibleIpTypes(given, claimed)) {
			if (PD.instance().getIpMode() == ipType.mixed && given == ipType.ipv4 && claimed == ipType.mixed) {
				return false;
			}
			return true;
		}
		return false;
	}

	public static ipType getIpTypeForAddress(InetAddress address) {
		if (address instanceof Inet4Address) {
			return ipType.ipv4;
		}
		if (address instanceof Inet6Address) {
			return ipType.ipv6;
		}
		log.warn("Couldn't identify ipType for address.");
		return null;
	}

	public static ipType getIpTypeForAddress(URI uri) {
		InetAddress address;
		try {
			address = InetAddress.getByName(uri.getHost());

			if (address instanceof Inet4Address) {
				return ipType.ipv4;
			}
			if (address instanceof Inet6Address) {
				return ipType.ipv6;
			}
		} catch (UnknownHostException e) {
			log.warn("getIpTypeForAddress {}", e.getMessage());
		}
		log.warn("Couldn't identify ipType for address.");
		return null;
	}

	// Returns if compatible ipTypes for peer discovery:
	// ipv4 searches ipv4 only
	// ipv6 searches ipv6 only
	// mixed searches both
	private static boolean compatibleIpTypes(ipType ipMode, ipType remote) {
		if ((ipMode == ipType.ipv4 && remote == ipType.ipv6) || (ipMode == ipType.ipv6 && remote == ipType.ipv4)) {
			return false;
		}
		return true;
	}

	// For Search we don't want to add mixed nodes from ipv4 nodes.
	// Only if this is a mixed node!
	private boolean compatibleIpTypesForSearch(ipType startSearch, ipType searchList) {
		if (getIpMode() == ipType.mixed) {
			if (startSearch == ipType.ipv4 && searchList == ipType.mixed) {
				return false;
			}
		}
		return true;
	}

	public void shutdown() throws InterruptedException {
		// Write peers into new empty file
		if (Node.instance().getPeers().size() > 0) {
			try (BufferedWriter br = Files.newBufferedWriter(Paths.get(PD_FILE), StandardOpenOption.CREATE,
					StandardOpenOption.TRUNCATE_EXISTING)) {
				for (Peers n : Node.instance().getPeers()) {
					// ipv6 format
					String uri = getHostURL(n.getAddress());
					br.append("tcp://" + uri + "|" + n.getType().name() + StringUtils.SPACE);
				}
				br.close();
			} catch (IOException e) {
				log.error("Error writing Peers file.");
			}
			log.info("Peerlist succesfully saved.");
		} else {
			log.info("No Peerlist created since Peers were empty.");
		}
		shuttingDown = true;
		executor.awaitTermination(6, TimeUnit.SECONDS);
	}

	// Utilizes ipv6
	public static String getHostURL(InetSocketAddress address) {
		String host = address.getAddress().getHostAddress();
		// code from
		// http://grepcode.com/file/repository.grepcode.com/java/root/jdk/openjdk/6-b14/java/net/URL.java#383
		if (host.indexOf(':') >= 0 && !host.startsWith("[")) {
			return "[" + host + "]:" + address.getPort();
		}
		return host + ":" + address.getPort();
	}

	public static Optional<InetSocketAddress> getAddress(String address) {
		// URI automatically validates schemes and returns ipv4 and ipv6 host and port in the right way
		try {
			String[] values = address.split("|");
			URI uri = new URI("tcp://" + values[0]);
			String host = uri.getHost();
			int port = uri.getPort();
			return Optional.of(new InetSocketAddress(host, port));
		} catch (URISyntaxException e) {
			log.debug("Failed to retrieve InetSocketAddress from String: {}", address);
		}
		return Optional.empty();
	}

	public static Optional<Map<InetSocketAddress, ipType>> getAddressAndType(String address) {
		try {
			// Always size 2
			String[] values = address.split("\\|");
			URI uri = new URI("tcp://" + values[0]);
			String host = uri.getHost();
			int port = uri.getPort();
			Map<InetSocketAddress, ipType> map = new HashMap<InetSocketAddress, ipType>();
			map.put(new InetSocketAddress(host, port), ipType.valueOf(values[1]));
			return Optional.of(map);
		} catch (URISyntaxException e) {
			log.debug("Failed to retrieve InetSocketAddress from String: {}", address);
		}
		return Optional.empty();
	}

	public static PD instance() {
		return instance;
	}

	private PD() {
	}

}
