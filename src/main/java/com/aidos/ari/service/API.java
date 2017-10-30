package com.aidos.ari.service;

import static io.undertow.Handlers.path;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnio.channels.StreamSinkChannel;
import org.xnio.streams.ChannelInputStream;
import com.aidos.ari.Main;
import com.aidos.ari.Milestone;
import com.aidos.ari.Peers;
import com.aidos.ari.Snapshot;
import com.aidos.ari.conf.Configuration;
import com.aidos.ari.conf.ipType;
import com.aidos.ari.conf.Configuration.DefaultConfSettings;
import com.aidos.ari.hash.Curl;
import com.aidos.ari.hash.PearlDiver;
import com.aidos.ari.model.Hash;
import com.aidos.ari.model.Transaction;
import com.aidos.ari.service.dto.AbstractResponse;
import com.aidos.ari.service.dto.AccessLimitedResponse;
import com.aidos.ari.service.dto.AddedPeersResponse;
import com.aidos.ari.service.dto.AttachToMeshResponse;
import com.aidos.ari.service.dto.ErrorResponse;
import com.aidos.ari.service.dto.ExceptionResponse;
import com.aidos.ari.service.dto.FindTransactionsResponse;
import com.aidos.ari.service.dto.GetBalancesResponse;
import com.aidos.ari.service.dto.GetInclusionStatesResponse;
import com.aidos.ari.service.dto.GetNodeInfoResponse;
import com.aidos.ari.service.dto.GetPeersResponse;
import com.aidos.ari.service.dto.GetTipsResponse;
import com.aidos.ari.service.dto.GetTransactionsToApproveResponse;
import com.aidos.ari.service.dto.GetTrytesResponse;
import com.aidos.ari.service.dto.RetrieveIpResponse;
import com.aidos.ari.service.storage.Storage;
import com.aidos.ari.service.storage.StorageAddresses;
import com.aidos.ari.service.storage.StorageApprovers;
import com.aidos.ari.service.storage.StorageBundle;
import com.aidos.ari.service.storage.StorageScratchpad;
import com.aidos.ari.service.storage.StorageTags;
import com.aidos.ari.service.storage.StorageTransactions;
import com.aidos.ari.utils.Converter;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import io.undertow.util.Methods;
import io.undertow.util.MimeMappings;
import io.undertow.util.StatusCodes;

@SuppressWarnings("unchecked")
public class API {

	private static final Logger log = LoggerFactory.getLogger(API.class);

	// Max amount of peers that a node will accept.
	private static final int MAX_PEERS = 12;

	private final static int HASH_SIZE = 81;
	private final static int TRYTES_SIZE = 2673;

	private final static char ZERO_LENGTH_ALLOWED = 'Y';
	private final static char ZERO_LENGTH_NOT_ALLOWED = 'N';

	private Pattern trytesPattern = Pattern.compile("[9A-Z]*");

	private Undertow server;

	private final Gson gson = new GsonBuilder().create();
	private final PearlDiver pearlDiver = new PearlDiver();

	private final AtomicInteger counter = new AtomicInteger(0);

	public void init() throws IOException {
		final int apiPort = Configuration.integer(DefaultConfSettings.API_PORT);
		final String apiHost = Configuration.string(DefaultConfSettings.API_HOST);

		log.debug("Binding JSON-REST API Undertown server on {}:{}", apiHost, apiPort);

		server = Undertow.builder().addHttpListener(apiPort, apiHost)
				.setHandler(path().addPrefixPath("/", new HttpHandler() {
					@Override
					public void handleRequest(final HttpServerExchange exchange) throws Exception {
						HttpString requestMethod = exchange.getRequestMethod();
						if (Methods.OPTIONS.equals(requestMethod)) {
							String allowedMethods = "GET,HEAD,POST,PUT,DELETE,TRACE,OPTIONS,CONNECT,PATCH";
							// return list of allowed methods in response headers
							exchange.setStatusCode(StatusCodes.OK);
							exchange.getResponseHeaders().put(Headers.CONTENT_TYPE,
									MimeMappings.DEFAULT_MIME_MAPPINGS.get("txt"));
							exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, 0);
							exchange.getResponseHeaders().put(Headers.ALLOW, allowedMethods);
							exchange.getResponseHeaders().put(new HttpString("Access-Control-Allow-Origin"), "*");
							exchange.getResponseHeaders().put(new HttpString("Access-Control-Allow-Headers"),
									"Origin, X-Requested-With, Content-Type, Accept");
							exchange.getResponseSender().close();
							return;
						}

						if (exchange.isInIoThread()) {
							exchange.dispatch(this);
							return;
						}
						processRequest(exchange);
					}
				})).build();
		server.start();
	}

	private void processRequest(final HttpServerExchange exchange) throws IOException {
		final ChannelInputStream cis = new ChannelInputStream(exchange.getRequestChannel());
		exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");

		final long beginningTime = System.currentTimeMillis();
		final String body = IOUtils.toString(cis, StandardCharsets.UTF_8);
		final AbstractResponse response = process(body, exchange.getSourceAddress());
		sendResponse(exchange, response, beginningTime);
	}

	private AbstractResponse process(final String requestString, InetSocketAddress sourceAddress)
			throws UnsupportedEncodingException {

		try {

			final Map<String, Object> request = gson.fromJson(requestString, Map.class);
			if (request == null) {
				return ExceptionResponse.create("Invalid request payload: '" + requestString + "'");
			}

			final String command = (String) request.get("command");
			if (command == null) {
				return ErrorResponse.create("COMMAND parameter has not been specified in the request.");
			}

			boolean addressIsLoopBack = sourceAddress.getAddress().isLoopbackAddress();

			// remote access limited to: ping, addPeer and getPeerAddresses.
			if ((!Configuration.string(DefaultConfSettings.REMOTEAPI).contains(command)
					&& Configuration.string(DefaultConfSettings.REMOTEWALLET).contains(command))
					&& !addressIsLoopBack) {
				return AccessLimitedResponse.create("COMMAND " + command + " is not available on this node");
			}

			log.info("# {} -> Requesting command '{}'", counter.incrementAndGet(), command);

			switch (command) {

			case "ping": {
				// returns "duration":x and retrieves caller ip
				log.debug("Invoking 'ping' with {}", sourceAddress.getAddress().getHostAddress());
				return RetrieveIpResponse.create(sourceAddress.getAddress().getHostAddress());
			}
			case "addPeer": {
				if (!request.containsKey("uri") && !request.containsKey("type")) {
					return ErrorResponse.create("Invalid parameters.");
				}
				final String uri = (String) request.get("uri");
				final ipType type = ipType.valueOf((String) request.get("type"));
				// returns true if valid request, means uri has same ip than sourceAddress
				// also don't want to add localhost
				// also only add if remote API is reachable
				if (checkInvokerAddress(uri, sourceAddress) && !addressIsLoopBack
						&& PD.isPeerOnline(new InetSocketAddress(sourceAddress.getAddress().getHostAddress(),
								Configuration.integer(DefaultConfSettings.API_PORT)))
						&& PD.pushTest(PD.getIpTypeForAddress(sourceAddress.getAddress()), type)) {
					log.debug("Invoking 'addPeer' with {} from address {} (type {})", uri,
							sourceAddress.getAddress().getHostAddress(), type.name());
					// can refuse if maxPeers is reached
					// returns either 1 if added or 0 if not, -1 if maxed
					return addPeerStatement(uri, type);
				} else {
					// creates HTTP-Error 400 when called
					return ErrorResponse.create("Failure adding peer.");
				}
			}
			case "getPeerAddresses": {
				log.debug("Invoking 'getPeerAddresses' with from address {}",
						sourceAddress.getAddress().getHostAddress());
				// Milestone server masked
				return getPeerAddressStatement();
			}
			case "attachToMesh": {
				if (!request.containsKey("trunkTransaction") || !request.containsKey("branchTransaction")
						|| !request.containsKey("minWeightMagnitude") || !request.containsKey("trytes")) {
					return ErrorResponse.create("Invalid parameters.");
				}
				if (!validTrytes((String) request.get("trunkTransaction"), HASH_SIZE, ZERO_LENGTH_NOT_ALLOWED)) {
					return ErrorResponse.create("Invalid trunkTransaction hash.");
				}
				if (!validTrytes((String) request.get("branchTransaction"), HASH_SIZE, ZERO_LENGTH_NOT_ALLOWED)) {
					return ErrorResponse.create("Invalid branchTransaction hash.");
				}
				final Hash trunkTransaction = new Hash((String) request.get("trunkTransaction"));
				final Hash branchTransaction = new Hash((String) request.get("branchTransaction"));
				final int minWeightMagnitude = ((Double) request.get("minWeightMagnitude")).intValue();
				final List<String> trytes = (List<String>) request.get("trytes");
				for (final String tryt : trytes) {
					if (!validTrytes(tryt, TRYTES_SIZE, ZERO_LENGTH_NOT_ALLOWED)) {
						return ErrorResponse.create("Invalid trytes input.");
					}
				}
				return attachToMeshStatement(trunkTransaction, branchTransaction, minWeightMagnitude, trytes);
			}
			case "broadcastTransactions": {
				if (!request.containsKey("trytes")) {
					return ErrorResponse.create("Invalid parameters.");
				}
				final List<String> trytes = (List<String>) request.get("trytes");
				for (final String tryt : trytes) {
					if (!validTrytes(tryt, TRYTES_SIZE, ZERO_LENGTH_NOT_ALLOWED)) {
						return ErrorResponse.create("Invalid trytes input.");
					}
				}
				log.debug("Invoking 'broadcastTransactions' with {}", trytes);
				return broadcastTransactionStatement(trytes);
			}
			case "findTransactions": {
				if (!request.containsKey("bundles") && !request.containsKey("addresses") && !request.containsKey("tags")
						&& !request.containsKey("approvees")) {
					return ErrorResponse.create("Invalid parameters.");
				}
				return findTransactionStatement(request);
			}
			case "getBalances": {
				if (!request.containsKey("addresses") || !request.containsKey("threshold")) {
					return ErrorResponse.create("Invalid parameters.");
				}
				final List<String> addresses = (List<String>) request.get("addresses");
				for (final String address : addresses) {
					if (!validTrytes(address, HASH_SIZE, ZERO_LENGTH_NOT_ALLOWED)) {
						return ErrorResponse.create("Invalid addresses input.");
					}
				}
				final int threshold = ((Double) request.get("threshold")).intValue();
				return getBalancesStatement(addresses, threshold);
			}
			case "getInclusionStates": {
				if (!request.containsKey("transactions") || !request.containsKey("tips")) {
					return ErrorResponse.create("Invalid parameters.");
				}
				final List<String> trans = (List<String>) request.get("transactions");
				final List<String> tps = (List<String>) request.get("tips");

				for (final String tx : trans) {
					if (!validTrytes(tx, HASH_SIZE, ZERO_LENGTH_NOT_ALLOWED)) {
						return ErrorResponse.create("Invalid transactions input.");
					}
				}
				for (final String ti : tps) {
					if (!validTrytes(ti, HASH_SIZE, ZERO_LENGTH_NOT_ALLOWED)) {
						return ErrorResponse.create("Invalid tips input.");
					}
				}

				if (invalidSubmeshStatus()) {
					return ErrorResponse
							.create("This operations cannot be executed: The Submesh has not been updated yet.");
				}
				return getInclusionStateStatement(trans, tps);
			}
			case "getNodeInfo": {
				return GetNodeInfoResponse.create(Main.NAME, Main.VERSION, Runtime.getRuntime().availableProcessors(),
						Runtime.getRuntime().freeMemory(), System.getProperty("java.version"),
						Runtime.getRuntime().maxMemory(), Runtime.getRuntime().totalMemory(), Milestone.latestMilestone,
						Milestone.latestMilestoneIndex, Milestone.latestSolidSubmeshMilestone,
						Milestone.latestSolidSubmeshMilestoneIndex, Node.instance().howManyPeers(),
						Node.instance().queuedTransactionsSize(), System.currentTimeMillis(),
						StorageTransactions.instance().tips().size(),
						StorageScratchpad.instance().getNumberOfTransactionsToRequest());
			}
			case "getTips": {
				return getTipsStatement();
			}
			case "getTransactionsToApprove": {
				final int depth = ((Double) request.get("depth")).intValue();
				if (invalidSubmeshStatus()) {
					return ErrorResponse
							.create("This operations cannot be executed: The Submesh has not been updated yet.");
				}
				return getTransactionToApproveStatement(depth);
			}
			case "getTrytes": {
				if (!request.containsKey("hashes")) {
					return ErrorResponse.create("Invalid parameters.");
				}
				final List<String> hashes = (List<String>) request.get("hashes");
				if (hashes == null) {
					return ErrorResponse.create("Wrong arguments.");
				}
				for (final String hash : hashes) {
					if (!validTrytes(hash, HASH_SIZE, ZERO_LENGTH_ALLOWED)) {
						return ErrorResponse.create("Invalid hash input.");
					}
				}
				log.debug("Executing getTrytesStatement: {}", hashes);
				return getTrytesStatement(hashes);
			}
			case "interruptAttachingToMesh": {
				pearlDiver.cancel();
				return AbstractResponse.createEmptyResponse();
			}
			case "storeTransactions": {
				if (!request.containsKey("trytes")) {
					return ErrorResponse.create("Invalid parameters.");
				}
				List<String> trytes = (List<String>) request.get("trytes");
				log.debug("Invoking 'storeTransactions' with {}", trytes);
				return storeTransactionStatement(trytes);
			}
			default:
				return ErrorResponse.create("Command [" + command + "] is unknown");
			}

		} catch (final Exception e) {
			log.error("API Exception: ", e);
			return ExceptionResponse.create(e.getLocalizedMessage());
		}
	}

	public static boolean invalidSubmeshStatus() {
		log.info(Milestone.latestSolidSubmeshMilestoneIndex + "," + Milestone.MILESTONE_START_INDEX);
		return (Milestone.latestSolidSubmeshMilestoneIndex == Milestone.MILESTONE_START_INDEX);
	}

	private AbstractResponse getTrytesStatement(List<String> hashes) {
		final List<String> elements = new LinkedList<>();
		for (final String hash : hashes) {
			final Transaction transaction = StorageTransactions.instance().loadTransaction((new Hash(hash)).bytes());
			if (transaction != null) {
				elements.add(Converter.trytes(transaction.trits()));
			}
		}
		return GetTrytesResponse.create(elements);
	}

	private synchronized AbstractResponse getTransactionToApproveStatement(final int depth) {
		final Hash trunkTransactionToApprove = TipsManager.transactionToApprove(null, depth);
		if (trunkTransactionToApprove == null) {
			return ErrorResponse.create("The Submesh is not solid.");
		}
		final Hash branchTransactionToApprove = TipsManager.transactionToApprove(trunkTransactionToApprove, depth);
		if (branchTransactionToApprove == null) {
			return ErrorResponse.create("The Submesh is not solid.");
		}
		return GetTransactionsToApproveResponse.create(trunkTransactionToApprove, branchTransactionToApprove);
	}

	private AbstractResponse getTipsStatement() {
		return GetTipsResponse.create(
				StorageTransactions.instance().tips().stream().map(Hash::toString).collect(Collectors.toList()));
	}

	private AbstractResponse storeTransactionStatement(final List<String> trys) {
		for (final String trytes : trys) {
			if (!validTrytes(trytes, TRYTES_SIZE, ZERO_LENGTH_NOT_ALLOWED)) {
				return ErrorResponse.create("Invalid trytes input.");
			}
			final Transaction transaction = new Transaction(Converter.trits(trytes));
			StorageTransactions.instance().storeTransaction(transaction.hash, transaction, false);
		}
		return AbstractResponse.createEmptyResponse();
	}

	private AbstractResponse getPeerAddressStatement() {
		return GetPeersResponse.create(Node.instance().getPeersWithoutMilestone());
	}

	private AbstractResponse getInclusionStateStatement(final List<String> trans, final List<String> tps) {

		final List<Hash> transactions = trans.stream().map(s -> new Hash(s)).collect(Collectors.toList());
		final List<Hash> tips = tps.stream().map(s -> new Hash(s)).collect(Collectors.toList());

		int numberOfNonMetTransactions = transactions.size();
		final boolean[] inclusionStates = new boolean[numberOfNonMetTransactions];

		synchronized (StorageScratchpad.instance().getAnalyzedTransactionsFlags()) {

			StorageScratchpad.instance().clearAnalyzedTransactionsFlags();

			final Queue<Long> nonAnalyzedTransactions = new LinkedList<>();
			for (final Hash tip : tips) {

				final long pointer = StorageTransactions.instance().transactionPointer(tip.bytes());
				if (pointer <= 0) {
					return ErrorResponse.create("One of the tips absents");
				}
				nonAnalyzedTransactions.offer(pointer);
			}

			{
				Long pointer;
				MAIN_LOOP: while ((pointer = nonAnalyzedTransactions.poll()) != null) {

					if (StorageScratchpad.instance().setAnalyzedTransactionFlag(pointer)) {

						final Transaction transaction = StorageTransactions.instance().loadTransaction(pointer);
						if (transaction.type == Storage.PREFILLED_SLOT) {
							return ErrorResponse.create("The Submesh is not solid");
						} else {

							final Hash transactionHash = new Hash(transaction.hash, 0, Transaction.HASH_SIZE);
							for (int i = 0; i < inclusionStates.length; i++) {

								if (!inclusionStates[i] && transactionHash.equals(transactions.get(i))) {

									inclusionStates[i] = true;

									if (--numberOfNonMetTransactions <= 0) {
										break MAIN_LOOP;
									}
								}
							}
							nonAnalyzedTransactions.offer(transaction.trunkTransactionPointer);
							nonAnalyzedTransactions.offer(transaction.branchTransactionPointer);
						}
					}
				}
				return GetInclusionStatesResponse.create(inclusionStates);
			}
		}
	}

	private AbstractResponse findTransactionStatement(final Map<String, Object> request) {
		final Set<Long> bundlesTransactions = new HashSet<>();
		if (request.containsKey("bundles")) {
			for (final String bundle : (List<String>) request.get("bundles")) {
				bundlesTransactions.addAll(StorageBundle.instance()
						.bundleTransactions(StorageBundle.instance().bundlePointer((new Hash(bundle)).bytes())));
			}
		}

		final Set<Long> addressesTransactions = new HashSet<>();
		if (request.containsKey("addresses")) {
			final List<String> addresses = (List<String>) request.get("addresses");
			log.debug("Searching: {}", addresses.stream().reduce((a, b) -> a += ',' + b));

			for (final String address : addresses) {
				if (address.length() != 81) {
					log.error("Address {} doesn't look a valid address", address);
				}
				addressesTransactions.addAll(StorageAddresses.instance()
						.addressTransactions(StorageAddresses.instance().addressPointer((new Hash(address)).bytes())));
			}
		}

		final Set<Long> tagsTransactions = new HashSet<>();
		if (request.containsKey("tags")) {
			for (String tag : (List<String>) request.get("tags")) {
				while (tag.length() < Curl.HASH_LENGTH / Converter.NUMBER_OF_TRITS_IN_A_TRYTE) {
					tag += Converter.TRYTE_ALPHABET.charAt(0);
				}
				tagsTransactions.addAll(StorageTags.instance()
						.tagTransactions(StorageTags.instance().tagPointer((new Hash(tag)).bytes())));
			}
		}

		final Set<Long> approveeTransactions = new HashSet<>();

		if (request.containsKey("approvees")) {
			for (final String approvee : (List<String>) request.get("approvees")) {
				approveeTransactions.addAll(StorageApprovers.instance().approveeTransactions(
						StorageApprovers.instance().approveePointer((new Hash(approvee)).bytes())));
			}
		}

		// need refactoring
		final Set<Long> foundTransactions = bundlesTransactions.isEmpty() ? (addressesTransactions.isEmpty()
				? (tagsTransactions.isEmpty()
						? (approveeTransactions.isEmpty() ? new HashSet<>() : approveeTransactions) : tagsTransactions)
				: addressesTransactions) : bundlesTransactions;

		if (!addressesTransactions.isEmpty()) {
			foundTransactions.retainAll(addressesTransactions);
		}
		if (!tagsTransactions.isEmpty()) {
			foundTransactions.retainAll(tagsTransactions);
		}
		if (!approveeTransactions.isEmpty()) {
			foundTransactions.retainAll(approveeTransactions);
		}

		final List<String> elements = foundTransactions.stream()
				.map(pointer -> new Hash(StorageTransactions.instance().loadTransaction(pointer).hash, 0,
						Transaction.HASH_SIZE).toString())
				.collect(Collectors.toCollection(LinkedList::new));

		return FindTransactionsResponse.create(elements);
	}

	private AbstractResponse broadcastTransactionStatement(final List<String> trytes2) {
		for (final String tryte : trytes2) {
			final Transaction transaction = new Transaction(Converter.trits(tryte));
			transaction.weightMagnitude = Curl.HASH_LENGTH;
			Node.instance().broadcast(transaction);
		}
		return AbstractResponse.createEmptyResponse();
	}

	private AbstractResponse getBalancesStatement(final List<String> addrss, final int threshold) {

		if (threshold <= 0 || threshold > 100) {
			return ErrorResponse.create("Illegal 'threshold'.");
		}

		final List<Hash> addresses = addrss.stream().map(address -> (new Hash(address)))
				.collect(Collectors.toCollection(LinkedList::new));

		final Map<Hash, Long> balances = new HashMap<>();
		for (final Hash address : addresses) {
			balances.put(address,
					Snapshot.initialState.containsKey(address) ? Snapshot.initialState.get(address) : Long.valueOf(0));
		}

		final Hash milestone = Milestone.latestSolidSubmeshMilestone;
		final int milestoneIndex = Milestone.latestSolidSubmeshMilestoneIndex;

		synchronized (StorageScratchpad.instance().getAnalyzedTransactionsFlags()) {

			StorageScratchpad.instance().clearAnalyzedTransactionsFlags();

			final Queue<Long> nonAnalyzedTransactions = new LinkedList<>(
					Collections.singleton(StorageTransactions.instance().transactionPointer(milestone.bytes())));
			Long pointer;
			while ((pointer = nonAnalyzedTransactions.poll()) != null) {

				if (StorageScratchpad.instance().setAnalyzedTransactionFlag(pointer)) {

					final Transaction transaction = StorageTransactions.instance().loadTransaction(pointer);

					if (transaction.value != 0) {

						final Hash address = new Hash(transaction.address, 0, Transaction.ADDRESS_SIZE);
						final Long balance = balances.get(address);
						if (balance != null) {

							balances.put(address, balance + transaction.value);
						}
					}
					nonAnalyzedTransactions.offer(transaction.trunkTransactionPointer);
					nonAnalyzedTransactions.offer(transaction.branchTransactionPointer);
				}
			}
		}

		final List<String> elements = addresses.stream().map(address -> balances.get(address).toString())
				.collect(Collectors.toCollection(LinkedList::new));

		return GetBalancesResponse.create(elements, milestone, milestoneIndex);
	}

	private static int counter_PoW = 0;

	public static int getCounter_PoW() {
		return counter_PoW;
	}

	public static void incCounter_PoW() {
		API.counter_PoW++;
	}

	private static long ellapsedTime_PoW = 0L;

	public static long getEllapsedTime_PoW() {
		return ellapsedTime_PoW;
	}

	public static void incEllapsedTime_PoW(long ellapsedTime) {
		ellapsedTime_PoW += ellapsedTime;
	}

	public synchronized AbstractResponse attachToMeshStatement(final Hash trunkTransaction,
			final Hash branchTransaction, final int minWeightMagnitude, final List<String> trytes) {
		final List<Transaction> transactions = new LinkedList<>();

		Hash prevTransaction = null;

		for (final String tryte : trytes) {
			long startTime = System.nanoTime();
			final int[] transactionTrits = Converter.trits(tryte);
			System.arraycopy((prevTransaction == null ? trunkTransaction : prevTransaction).trits(), 0,
					transactionTrits, Transaction.TRUNK_TRANSACTION_TRINARY_OFFSET,
					Transaction.TRUNK_TRANSACTION_TRINARY_SIZE);
			System.arraycopy((prevTransaction == null ? branchTransaction : trunkTransaction).trits(), 0,
					transactionTrits, Transaction.BRANCH_TRANSACTION_TRINARY_OFFSET,
					Transaction.BRANCH_TRANSACTION_TRINARY_SIZE);
			if (!pearlDiver.search(transactionTrits, minWeightMagnitude, 0)) {
				transactions.clear();
				break;
			}
			final Transaction transaction = new Transaction(transactionTrits);
			transactions.add(transaction);
			prevTransaction = new Hash(transaction.hash, 0, Transaction.HASH_SIZE);
			API.incEllapsedTime_PoW(System.nanoTime() - startTime);
			API.incCounter_PoW();
			if ((API.getCounter_PoW() % 100) == 0) {
				String sb = "Last 100 PoW consumed " + API.getEllapsedTime_PoW() / 1000000000L
						+ " seconds processing time.";
				log.info(sb);
				counter_PoW = 0;
				ellapsedTime_PoW = 0L;
			}
		}

		final List<String> elements = new LinkedList<>();
		for (int i = transactions.size(); i-- > 0;) {
			elements.add(Converter.trytes(transactions.get(i).trits()));
		}
		return AttachToMeshResponse.create(elements);
	}

	// returns true if valid request, means invoker ip = peer ip to add
	private boolean checkInvokerAddress(final String uri, final InetSocketAddress invoker) throws URISyntaxException {

		final URI uri2 = new URI(uri);
		String uriip = uri2.getHost();

		if ("tcp".equals(uri2.getScheme())) {
			// false not a DNS, true DNS and return ip
			if (Node.checkIp(uri2.getHost()).isPresent()) {
				uriip = Node.checkIp(uri2.getHost()).get();
			}
			// here uriip is an ip for sure
			if (invoker.getAddress().getHostAddress().equals(uriip)) {
				return true;
			}
		}
		return false;
	}

	private AbstractResponse addPeerStatement(final String uri, final ipType type) throws URISyntaxException {
		// -1 maxed, 0 already added, 1 added now
		int numberOfAddedPeers = 0;
		// add only up to maxPeers
		if (Node.instance().getPeers().size() < MAX_PEERS) {
			final URI uri2 = new URI(uri);
			if ("tcp".equals(uri2.getScheme())) {
				final Peers peer = new Peers(new InetSocketAddress(uri2.getHost(), uri2.getPort()), type);
				if (!Node.instance().getPeers().contains(peer)) {
					Node.instance().addPeer(peer);
					numberOfAddedPeers++;
				}
			}
		} else {
			numberOfAddedPeers = -1;
		}
		return AddedPeersResponse.create(numberOfAddedPeers);
	}

	private void sendResponse(final HttpServerExchange exchange, final AbstractResponse res, final long beginningTime)
			throws IOException {
		res.setDuration((int) (System.currentTimeMillis() - beginningTime));
		final String response = gson.toJson(res);

		if (res instanceof ErrorResponse) {
			exchange.setStatusCode(400); // bad request
		} else if (res instanceof AccessLimitedResponse) {
			exchange.setStatusCode(401); // api method not allowed
		} else if (res instanceof ExceptionResponse) {
			exchange.setStatusCode(500); // internal error
		}

		setupResponseHeaders(exchange);

		ByteBuffer responseBuf = ByteBuffer.wrap(response.getBytes(StandardCharsets.UTF_8));
		exchange.setResponseContentLength(responseBuf.array().length);
		StreamSinkChannel sinkChannel = exchange.getResponseChannel();
		sinkChannel.getWriteSetter().set(channel -> {
			if (responseBuf.remaining() > 0)
				try {
					sinkChannel.write(responseBuf);
					if (responseBuf.remaining() == 0) {
						exchange.endExchange();
					}
				} catch (IOException e) {
					log.error("Error writing response", e);
					exchange.endExchange();
					sinkChannel.getWriteSetter().set(null);
				}
			else {
				exchange.endExchange();
			}
		});
		sinkChannel.resumeWrites();
	}

	private boolean validTrytes(String trytes, int minimalLength, char zeroAllowed) {
		if (trytes.length() == 0 && zeroAllowed == ZERO_LENGTH_ALLOWED) {
			return true;
		}
		if (trytes.length() < minimalLength) {
			return false;
		}
		Matcher matcher = trytesPattern.matcher(trytes);
		return matcher.matches();
	}

	private static void setupResponseHeaders(final HttpServerExchange exchange) {
		final HeaderMap headerMap = exchange.getResponseHeaders();
		headerMap.add(new HttpString("Access-Control-Allow-Origin"),
				Configuration.string(DefaultConfSettings.CORS_ENABLED));
		headerMap.add(new HttpString("Keep-Alive"), "timeout=500, max=100");
	}

	public void shutDown() {
		if (server != null) {
			server.stop();
		}
	}

	private static API instance = new API();

	public static API instance() {
		return instance;
	}
}
