package com.aidos.ari.service.dto;

public class RetrieveIpResponse extends AbstractResponse {
	
	private String ip;

	public static AbstractResponse create(String ip) {
		RetrieveIpResponse res = new RetrieveIpResponse();
		res.ip = ip;
		return res;
	}

	public String getIp() {
		return ip;
	}
}
