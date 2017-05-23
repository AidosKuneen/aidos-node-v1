package com.aidos.ari.service.dto;

import com.aidos.ari.model.Hash;

public class GetNodeInfoResponse extends AbstractResponse {

	private String appName;
	private String appVersion;
	private int jreAvailableProcessors;
	private long jreFreeMemory;
	private String jreVersion;

    private long jreMaxMemory;
    private long jreTotalMemory;
    private String latestMilestone;
    private int latestMilestoneIndex;

    private String latestSolidSubmeshMilestone;
    private int latestSolidSubmeshMilestoneIndex;

    private int peers;
    private int packetsQueueSize;
    private long time;
    private int tips;
    private int transactionsToRequest;

	public static AbstractResponse create(String appName, String appVersion, int jreAvailableProcessors, long jreFreeMemory,
	        String jreVersion, long maxMemory, long totalMemory, Hash latestMilestone, int latestMilestoneIndex,
	        Hash latestSolidSubmeshMilestone, int latestSolidSubmeshMilestoneIndex,
	        int peers, int packetsQueueSize,
	        long currentTimeMillis, int tips, int numberOfTransactionsToRequest) {
		final GetNodeInfoResponse res = new GetNodeInfoResponse();
		res.appName = appName;
		res.appVersion = appVersion;
		res.jreAvailableProcessors = jreAvailableProcessors;
		res.jreFreeMemory = jreFreeMemory;
		res.jreVersion = jreVersion;

		res.jreMaxMemory = maxMemory;
		res.jreTotalMemory = totalMemory;
		res.latestMilestone = latestMilestone.toString();
		res.latestMilestoneIndex = latestMilestoneIndex;

		res.latestSolidSubmeshMilestone = latestSolidSubmeshMilestone.toString();
		res.latestSolidSubmeshMilestoneIndex = latestSolidSubmeshMilestoneIndex;

		res.peers = peers;
		res.packetsQueueSize = packetsQueueSize;
		res.time = currentTimeMillis;
		res.tips = tips;
		res.transactionsToRequest = numberOfTransactionsToRequest;
		return res;
	}

	public String getAppName() {
		return appName;
	}

	public String getAppVersion() {
		return appVersion;
	}

	public int getJreAvailableProcessors() {
		return jreAvailableProcessors;
	}

	public long getJreFreeMemory() {
		return jreFreeMemory;
	}

	public long getJreMaxMemory() {
		return jreMaxMemory;
	}

	public long getJreTotalMemory() {
		return jreTotalMemory;
	}

	public String getJreVersion() {
		return jreVersion;
	}

	public String getLatestMilestone() {
		return latestMilestone;
	}

	public int getLatestMilestoneIndex() {
		return latestMilestoneIndex;
	}

	public String getLatestSolidSubmeshMilestone() {
		return latestSolidSubmeshMilestone;
	}

	public int getLatestSolidSubmeshMilestoneIndex() {
		return latestSolidSubmeshMilestoneIndex;
	}

	public int getPeers() {
		return peers;
	}

	public int getPacketsQueueSize() {
		return packetsQueueSize;
	}

	public long getTime() {
		return time;
	}

	public int getTips() {
		return tips;
	}

	public int getTransactionsToRequest() {
		return transactionsToRequest;
	}

}
