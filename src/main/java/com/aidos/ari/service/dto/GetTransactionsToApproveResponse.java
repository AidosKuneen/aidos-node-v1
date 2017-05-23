package com.aidos.ari.service.dto;

import com.aidos.ari.model.Hash;

public class GetTransactionsToApproveResponse extends AbstractResponse {

    private String trunkTransaction;
    private String branchTransaction;

	public static AbstractResponse create(Hash trunkTransactionToApprove, Hash branchTransactionToApprove) {
		GetTransactionsToApproveResponse res = new GetTransactionsToApproveResponse();
		res.trunkTransaction = trunkTransactionToApprove.toString();
		res.branchTransaction = branchTransactionToApprove.toString();
		return res;
	}
	
	public String getBranchTransaction() {
		return branchTransaction;
	}
	
	public String getTrunkTransaction() {
		return trunkTransaction;
	}
}
