package com.aidos.ari;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.aidos.ari.conf.Configuration;
import com.aidos.ari.conf.Configuration.DefaultConfSettings;
import com.aidos.ari.service.API;
import com.aidos.ari.service.Node;
import com.aidos.ari.service.PD;
import com.aidos.ari.service.TipsManager;
import com.aidos.ari.service.storage.Storage;
import com.sanityinc.jargs.CmdLineParser;
import com.sanityinc.jargs.CmdLineParser.Option;

// Main AIDOS Reference Implementation starting class
public class Main {

	private static final Logger log = LoggerFactory.getLogger(Main.class);

	public static final String NAME = "ARI Testnet";
	public static final String VERSION = "1.0.1.0";

	public static void main(final String[] args) {

		log.info("Welcome to {} {}", NAME, VERSION);
		validateParams(args);
		shutdownHook();

		try {

			Storage.instance().init();
			// Initialize without any peers
			Node.instance().init();
			TipsManager.instance().init();
			// Has to be before PD so API is reachable, but also last so API calls don't cause errors for uninitialized
			// other commands.
			API.instance().init();
			PD.instance().init();

		} catch (final Exception e) {
			log.error("Exception during Aidos Node init: ", e);
			System.exit(-1);
		}
		log.info("Aidos Node initialised correctly.");
	}

	private static void validateParams(final String[] args) {

		if (args == null || args.length < 2) {
			log.error("Invalid arguments list. Provide Receiver port number (i.e. '-r 14265').");
			printUsage();
		}

		final CmdLineParser parser = new CmdLineParser();

		final Option<String> rport = parser.addStringOption('r', "receiver-port");
		final Option<String> cors = parser.addStringOption('c', "enabled-cors");
		final Option<Boolean> debug = parser.addBooleanOption('d', "debug");
		final Option<Boolean> peer_discovery = parser.addBooleanOption('p', "peer-discovery");
		final Option<Boolean> remote_wallet = parser.addBooleanOption('w', "remote-wallet");
		final Option<Boolean> testnet = parser.addBooleanOption("testnet");
		final Option<Boolean> experimental = parser.addBooleanOption('e', "experimental");
		final Option<Boolean> help = parser.addBooleanOption('h', "help");
		final Option<String> local = parser.addStringOption('l', "local");

		try {
			parser.parse(args);
		} catch (CmdLineParser.OptionException e) {
			log.error("CLI error: {}", e);
			printUsage();
			System.exit(2);
		}

		log.info(parser.toString());

		// optional flags
		if (parser.getOptionValue(help) != null) {
			printUsage();
		}

		if (parser.getOptionValue(testnet) != null) {
			Configuration.put(DefaultConfSettings.TESTNET, "true");
		} else {
			log.error("This version is usable for the Testnet only.");
			printUsage();
		}

		final String vrport = parser.getOptionValue(rport);
		if (vrport == null) {
			log.error("Invalid arguments list. You have to specify the port.");
			printUsage();
		}
		if (vrport.equals("14265") && Configuration.booling(DefaultConfSettings.TESTNET)) {
			log.error("You need to run the Testnet on a different port than the default meshport.");
			printUsage();
		}
		Configuration.put(DefaultConfSettings.MESH_RECEIVER_PORT, vrport);

		// Have to save argument because getOptionValue sets to null after retrieving once
		final Boolean save_remote_wallet = parser.getOptionValue(remote_wallet);

		if (parser.getOptionValue(peer_discovery) != null) {
			log.info(
					"Peer Discovery enabled. Binding API socket to listen any interface and limiting remote API commands.");
			Configuration.put(DefaultConfSettings.API_HOST, "0.0.0.0");
		} else if (save_remote_wallet == null) {
			log.error("Invalid arguments list. You have to either use -p / --peer-discovery or -w / --remote-wallet.");
			printUsage();
		}

		if (save_remote_wallet != null) {
			log.info(
					"Necessary API commands for usage of remote-wallet are now enabled. Also remote access has been enabled.");
			Configuration.put(DefaultConfSettings.REMOTEWALLET, "true");
			Configuration.put(DefaultConfSettings.API_HOST, "0.0.0.0");
		}

		final String localIp = parser.getOptionValue(local);
		if (localIp != null) {
			log.debug("Setting local ip to : {} ", local);
			try {
				InetAddress.getByName(localIp);
			} catch (UnknownHostException e) {
				log.error("Invalid IP specified: {}", e);
				System.exit(2);
			}
			Configuration.put(DefaultConfSettings.LOCAL, localIp);
		}

		final String vcors = parser.getOptionValue(cors);
		if (vcors != null) {
			log.debug("Enabled CORS with value : {} ", vcors);
			Configuration.put(DefaultConfSettings.CORS_ENABLED, vcors);
		}

		if (parser.getOptionValue(experimental) != null) {
			log.info("Experimental Aidos features turned on.");
			Configuration.put(DefaultConfSettings.EXPERIMENTAL, "true");
		}

		if (Integer.parseInt(vrport) < 1024) {
			log.warn("Warning: API port value seems too low.");
		}

		// via logback.xml
		if (parser.getOptionValue(debug) != null) {
			Configuration.put(DefaultConfSettings.DEBUG, "true");
			log.info(Configuration.allSettings());
			log.info("You have set the debug flag. To enable debug output, you need to set <root level=\"DEBUG\"> "
					+ "in the source tree at ari/src/main/resources/logback.xml and re-package ari.jar");
		}

	}

	private static void printUsage() {
		log.info("Usage: java -jar {}-{}.jar " + "[{-r,--receiver-port} 14265] " + "[{-p,--peer-discovery}]"
				+ "[{-w,--remote-wallet}]" + "[{-l,--local} ipv4/ipv6]" + "[{--testnet}]" + "[{-c,--enabled-cors} *]"
				+ "[{-d,--debug}]" + "[{-e,--experimental}]", NAME, VERSION);
		System.exit(0);
	}

	private static void shutdownHook() {
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {

			log.info("Shutting down AIDOS node, please hold tight...");
			try {

				// Needs to be before node so it can read peers and write them to file
				PD.instance().shutdown();
				API.instance().shutDown();
				TipsManager.instance().shutDown();
				Node.instance().shutdown();
				Storage.instance().shutdown();

			} catch (final Exception e) {
				log.error("Exception occurred shutting down Aidos node: ", e);
			}
		}, "Shutdown Hook"));
	}
}
