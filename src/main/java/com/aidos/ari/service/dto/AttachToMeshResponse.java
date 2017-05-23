package com.aidos.ari.service.dto;

import java.util.List;

public class AttachToMeshResponse extends AbstractResponse {

	private List<String> trytes;
	
	public static AbstractResponse create(List<String> elements) {
		AttachToMeshResponse res = new AttachToMeshResponse();
		res.trytes = elements;
		return res;
	}
	
	public List<String> getTrytes() {
		return trytes;
	}
}
