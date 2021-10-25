package com.aidos.ari.conf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aidos.ari.hash.Curl;
import com.aidos.ari.utils.Converter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Configuration {

	private static final Logger log = LoggerFactory.getLogger(Configuration.class);

	private static final Map<String, String> conf = new ConcurrentHashMap<>();

	public enum DefaultConfSettings {
		API_PORT, API_HOST, MESH_RECEIVER_PORT, CORS_ENABLED, PEERS, LOCAL, // not used yet
		REMOTEAPI, REMOTEWALLET, DEBUG, EXPERIMENTAL, AUTHKEY // experimental features.
	}
	
	public static final int CONNECTION_TIMEOUT = 30000; // in ms

	public static String pubKeyCOORDINATOR = "42e3ed9b7f0f0dc1096ca2e376d09c099a5cb606ef8c146dc388f1669564a012"; // Current Coordinator Pub Auth Key
	public static String pubKeyAUTHCURL = ""; // generated at node start
	public static final String privKeyAUTH = generateRandomString(); // generated at node start (only shown once on console)
	
	public static String challenge = generateRandomString().substring(0,10); // challenge/response for authentication
	
	static {
		// defaults
		conf.put(DefaultConfSettings.API_PORT.name(), "14266");
		conf.put(DefaultConfSettings.API_HOST.name(), "localhost");
		conf.put(DefaultConfSettings.MESH_RECEIVER_PORT.name(), "14265");
		conf.put(DefaultConfSettings.CORS_ENABLED.name(), "*");
		conf.put(DefaultConfSettings.DEBUG.name(), "false");
		// only remote allow: ping addPeer and getPeerAddresses
		conf.put(DefaultConfSettings.REMOTEAPI.name(), "ping addPeer getPeerAddresses");
		conf.put(DefaultConfSettings.REMOTEWALLET.name(), "attachToMesh interruptAttachingToMesh");
		conf.put(DefaultConfSettings.EXPERIMENTAL.name(), "false");
		conf.put(DefaultConfSettings.LOCAL.name(), "");
		// local authentication for API
		conf.put(DefaultConfSettings.AUTHKEY.name(), privKeyAUTH);
		pubKeyAUTHCURL = getPublicKey(privKeyAUTH);
	}
	
	public static String allSettings() {
		final StringBuilder settings = new StringBuilder();
		conf.keySet()
				.forEach(t -> settings.append("Set '").append(t).append("'\t -> ").append(conf.get(t)).append("\n"));
		return settings.toString();
	}

	public static void put(final String k, final String v) {
		log.debug("Setting {} with {}", k, v);
		conf.put(k, v);
	}

	public static void put(final DefaultConfSettings d, String v) {
		log.debug("Setting {} with {}", d.name(), v);
		conf.put(d.name(), v);
	}

	public static String string(String k) {
		return conf.get(k);
	}

	public static float floating(String k) {
		return Float.parseFloat(conf.get(k));
	}

	public static double doubling(String k) {
		return Double.parseDouble(conf.get(k));
	}

	public static int integer(String k) {
		return Integer.parseInt(conf.get(k));
	}

	public static boolean booling(String k) {
		return Boolean.parseBoolean(conf.get(k));
	}

	public static String string(final DefaultConfSettings d) {
		return string(d.name());
	}

	public static int integer(final DefaultConfSettings d) {
		return integer(d.name());
	}

	public static boolean booling(final DefaultConfSettings d) {
		return booling(d.name());
	}
	
	public static String bytesToHex(byte[] hash) {
	    StringBuilder hexString = new StringBuilder(2 * hash.length);
	    for (int i = 0; i < hash.length; i++) {
	        String hex = Integer.toHexString(0xff & hash[i]);
	        if(hex.length() == 1) {
	            hexString.append('0');
	        }
	        hexString.append(hex);
	    }
	    return hexString.toString();
	}
	
	public static byte[] hexToBytes (String s) {
	    int len = s.length();
	    byte[] data = new byte[len / 2];
	    for (int i = 0; i < len; i += 2) {
	        data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
	                             + Character.digit(s.charAt(i+1), 16));
	    }
	    return data;
	}
	
	private static String SHA256(String input)  {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] encodedhash = digest.digest(
					input.getBytes(StandardCharsets.UTF_8));
			return(bytesToHex(encodedhash));
		} catch (NoSuchAlgorithmException e) {
			return "";
		}
	}

	private static String generateRandomString() {
		String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ9";
		String key = "";
		SecureRandom r = new SecureRandom();
		for (int i = 0; i < 81; i++)
			key += alphabet.charAt(r.nextInt(alphabet.length()));
		return key;
	}
	
	private static String getPublicKey(String key) {
		int[] key_trits = Converter.trits(key);
		int[] pubKey = new int[729];
		Curl c = new Curl();
		c.absorb(key_trits, 0, key_trits.length);
		c.squeeze729(pubKey, 0, pubKey.length);
		return Converter.trytes(pubKey);
	}
	
	public static String getNewChallenge() { // get new challenge for Auth Request
		return (challenge = generateRandomString().substring(0,27));
	}
	
	public static String requestAuth() { // new authentication Request (Challenge)
		return getNewChallenge()+pubKeyAUTHCURL; // Challenge + node public key for Step 1
	}
	
	// check that both the provided 
	public static boolean isValidAuthenticationRequest(String checkNodeKey, String checkCoordKey) {
		//check key 1 direct
		if (!SHA256(checkCoordKey).equalsIgnoreCase(pubKeyCOORDINATOR)) return false;
		//check key 2 with challenge
		if (!getPublicKey(challenge+privKeyAUTH).equalsIgnoreCase(checkNodeKey)) return false;
		// only authenticated if both are correct
		return true;
	}
	
	public static boolean isValidAuthenticationRequest_CoordOnly(String checkCoordKey) {
		//check key 1 direct
		if (!SHA256(checkCoordKey).equalsIgnoreCase(pubKeyCOORDINATOR)) return false;
		return true;
	}
	
	public static void printAuth() { // only called once on node init, for local admin tasks
		log.info("Node Restricted API Key (only shown once, changes every node start): " + Configuration.privKeyAUTH);
		log.info("Coordiantor Auth Public Key: " + Configuration.pubKeyCOORDINATOR);
	}

}