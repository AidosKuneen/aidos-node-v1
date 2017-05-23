package com.aidos.ari.service.dto;

public class AddedPeersResponse extends AbstractResponse {

	// -1 maxed, 0 already added, 1 added now
	private int addedPeer;

	public static AbstractResponse create(int status) {
		AddedPeersResponse res = new AddedPeersResponse();
		res.addedPeer = status;
		return res;
	}

	public int getAddedPeers() {
		return addedPeer;
	}

}
