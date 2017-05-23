package com.aidos.ari.service.dto;

import java.util.List;

import com.aidos.ari.service.PD;

public class GetPeersResponse extends AbstractResponse {

	private Peer[] peers;
	private String[] peerlist;

	public Peer[] getPeers() {
		return peers;
	}

	public String[] getPeerlist() {
		return peerlist;
	}

	static class Peer {

		private String address;
		public int numberOfAllTransactions, numberOfNewTransactions, numberOfInvalidTransactions;

		public String getAddress() {
			return address;
		}

		public int getNumberOfAllTransactions() {
			return numberOfAllTransactions;
		}

		public int getNumberOfNewTransactions() {
			return numberOfNewTransactions;
		}

		public int getNumberOfInvalidTransactions() {
			return numberOfInvalidTransactions;
		}

		public static Peer createFrom(com.aidos.ari.Peers n) {
			Peer ne = new Peer();
			ne.address = PD.getHostURL(n.getAddress());
			ne.numberOfAllTransactions = n.getNumberOfAllTransactions();
			ne.numberOfInvalidTransactions = n.getNumberOfInvalidTransactions();
			ne.numberOfNewTransactions = n.getNumberOfNewTransactions();
			return ne;
		}

	}

	// public static AbstractResponse create(final List<com.aidos.ari.Peers> elements) {
	// GetPeersResponse res = new GetPeersResponse();
	// res.peers = new Peer[elements.size()]; int i = 0;
	// for (com.aidos.ari.Peers n : elements) {
	// res.peers[i++] = Peer.createFrom(n);
	// }
	// return res;
	// }

	public static AbstractResponse create(final List<com.aidos.ari.Peers> elements) {
		GetPeersResponse res = new GetPeersResponse();
		res.peerlist = new String[elements.size()];
		int i = 0;
		for (com.aidos.ari.Peers n : elements) {
			res.peerlist[i++] = PD.getHostURL(n.getAddress()) + "|" + n.getType().name();
		}
		return res;
	}

}
