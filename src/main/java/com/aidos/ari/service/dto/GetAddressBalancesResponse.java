package com.aidos.ari.service.dto;

import java.util.List;

import com.aidos.ari.model.Hash;

public class GetAddressBalancesResponse extends AbstractResponse {
	
	private List<String> addresses;
	private List<String> balances;
	private String milestone;
	private int milestoneIndex;

	public static AbstractResponse create(List<String> elements, List<String> addr_elements, Hash milestone, int milestoneIndex) {
		GetAddressBalancesResponse res = new GetAddressBalancesResponse();
		res.addresses = addr_elements;
		res.balances = elements;
		res.milestone = milestone.toString();
		res.milestoneIndex = milestoneIndex;
		return res;
	}
	
	public String getMilestone() {
		return milestone;
	}
	
	public int getMilestoneIndex() {
		return milestoneIndex;
	}
	
	public List<String> getBalances() {
		return balances;
	}
	
	public List<String> getAddresses() {
		return addresses;
	}
}
