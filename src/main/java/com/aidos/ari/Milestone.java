package com.aidos.ari;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.aidos.ari.hash.Curl;
import com.aidos.ari.hash.ISS;
import com.aidos.ari.model.Hash;
import com.aidos.ari.model.Transaction;
import com.aidos.ari.service.storage.AbstractStorage;
import com.aidos.ari.service.storage.StorageAddresses;
import com.aidos.ari.service.storage.StorageScratchpad;
import com.aidos.ari.service.storage.StorageTransactions;
import com.aidos.ari.utils.Converter;

public class Milestone {

	private static final Logger log = LoggerFactory.getLogger(StorageTransactions.class);

	public static InetSocketAddress[] milestoneIp = null;
	
	// Address of Milestone Wallet Address
	public static final Hash COORDINATOR = new Hash(
			"HZSMDORPCAFJJJNEEWZSP9OCQZAHCAVPBAXUTJKRCYZXMSNGERFZLQPNWOQQHK9RMJO9PNSVV9KR9DONH");

	public static Hash latestMilestone = Hash.NULL_HASH;
	public static Hash latestSolidSubmeshMilestone = Hash.NULL_HASH;

	public static final int MILESTONE_START_INDEX = 0;

	public static int latestMilestoneIndex = MILESTONE_START_INDEX;
	public static int latestSolidSubmeshMilestoneIndex = MILESTONE_START_INDEX;

	private static final Set<Long> analyzedMilestoneCandidates = new HashSet<>();
	private static final Map<Integer, Hash> milestones = new ConcurrentHashMap<>();

	private static Boolean initialScanCompleted = false; // perform an initial scan of the milestone storage to speed up node restarts
	private static Boolean initialSnapshotCompleted = false; 
	
	static class MilestoneElement implements Comparable<MilestoneElement> { 
		int msindex = 0;
		long pointer = -1;
		MilestoneElement(Long p_pointer) {
			pointer = p_pointer;
			final Transaction transaction = StorageTransactions.instance().loadTransaction(pointer);
			msindex = (int) Converter.longValue(transaction.trits(), Transaction.TAG_TRINARY_OFFSET,15);
			if (msindex > 1048576 || msindex < 0)
				msindex = -1;
	    }
		@Override
		public int compareTo(MilestoneElement me) {
			return msindex-me.msindex;
		}
	}
	
	public static void updateLatestMilestone() {
		
		List<Long> cooTransPointers = StorageAddresses.instance().addressesOf(COORDINATOR); // load all transactions with address==coo
		
		synchronized(initialScanCompleted) { // for the initial load we will rearrange this list so high ms candidates come first
			if (!initialScanCompleted) {
				log.info("performing initial milestone scan sort on {} transactions",cooTransPointers.size());
				List<MilestoneElement> meList= new ArrayList<MilestoneElement>();
				int percCompleted = 0, lastPercCompleted = -1, counter = 0;
				for (final Long pointer : cooTransPointers) {
					percCompleted = (int)((counter++)*100.0/cooTransPointers.size());
					if (percCompleted>lastPercCompleted) {
						lastPercCompleted = percCompleted;
						log.info("initial storage milestone load {}% complete",percCompleted);
					}
					MilestoneElement me = new MilestoneElement(pointer);
					if (me.msindex>=0)
						meList.add(new MilestoneElement(pointer));
				}	
				Collections.sort(meList); // sort by Milestone Index
				Collections.reverse(meList); // and reverse (latest MS first)
				initialScanCompleted = true;
				if (meList.size()>0) {
					cooTransPointers.clear();
					for (MilestoneElement me2 : meList) {
						cooTransPointers.add(me2.pointer);
					}
				}
			}
		}
		
		for (final Long pointer : cooTransPointers) {

			if (analyzedMilestoneCandidates.add(pointer)) {

				final Transaction transaction = StorageTransactions.instance().loadTransaction(pointer);
				if (transaction.currentIndex == 0) {

					final int index = (int) Converter.longValue(transaction.trits(), Transaction.TAG_TRINARY_OFFSET,
							15);
					if (index > latestMilestoneIndex && index >= 0 && index <= 1048576 ) { // valid index range up to 2^20
						log.info("index is " + index);

						final Bundle bundle = new Bundle(transaction.bundle);
						log.info("milestone tx num={}", bundle.getTransactions().size());
						for (int i = 0; i < bundle.getTransactions().size(); i++) {
							log.info("milestone tx{} num={}", i, bundle.getTransactions().get(i).size());
						}
						for (final List<Transaction> bundleTransactions : bundle.getTransactions()) {
							if (bundleTransactions.get(0).pointer == transaction.pointer) {

								final Transaction transaction2 = StorageTransactions.instance()
										.loadTransaction(transaction.trunkTransactionPointer);
								if (transaction2.type == AbstractStorage.FILLED_SLOT
										&& transaction.branchTransactionPointer == transaction2.trunkTransactionPointer) {

									final int[] trunkTransactionTrits = new int[Transaction.TRUNK_TRANSACTION_TRINARY_SIZE];
									Converter.getTrits(transaction.trunkTransaction, trunkTransactionTrits);
									final int[] signatureFragmentTrits = Arrays.copyOfRange(transaction.trits(),
											Transaction.SIGNATURE_MESSAGE_FRAGMENT_TRINARY_OFFSET,
											Transaction.SIGNATURE_MESSAGE_FRAGMENT_TRINARY_OFFSET
													+ Transaction.SIGNATURE_MESSAGE_FRAGMENT_TRINARY_SIZE);

									final int[] hash = ISS.address(
											ISS.digest(Arrays.copyOf(ISS.normalizedBundle(trunkTransactionTrits),
													ISS.NUMBER_OF_FRAGMENT_CHUNKS), signatureFragmentTrits));
									log.info("address is extracted");
									int indexCopy = index;
									for (int i = 0; i < 20; i++) {

										final Curl curl = new Curl();
										if ((indexCopy & 1) == 0) {
											curl.absorb(hash, 0, hash.length);
											curl.absorb(transaction2.trits(), i * Curl.HASH_LENGTH, Curl.HASH_LENGTH);
										} else {
											curl.absorb(transaction2.trits(), i * Curl.HASH_LENGTH, Curl.HASH_LENGTH);
											curl.absorb(hash, 0, hash.length);
										}
										curl.squeeze(hash, 0, hash.length);

										indexCopy >>= 1;
									}

									if ((new Hash(hash)).equals(COORDINATOR)) {
										latestMilestone = new Hash(transaction.hash, 0, Transaction.HASH_SIZE);
										latestMilestoneIndex = index;

										milestones.put(latestMilestoneIndex, latestMilestone);

									} else {
										log.info("coordinator hash unmatched");
									}
								}
								break;
							}
						}
					}
				}
			}
		}
	}

	public static void updateLatestSolidSubmeshMilestone() {
		for (int milestoneIndex = latestMilestoneIndex; milestoneIndex > latestSolidSubmeshMilestoneIndex; milestoneIndex--) {

			final Hash milestone = milestones.get(milestoneIndex);
			if (milestone != null) {

				boolean solid = true;
				log.info("solidSubmeshMilestone: checking solidity for MS {}",milestoneIndex);
				synchronized (StorageScratchpad.instance().getAnalyzedTransactionsFlags()) {

					StorageScratchpad.instance().clearAnalyzedTransactionsFlags();
					Long transactionCount = 0L;
					final Queue<Long> nonAnalyzedTransactions = new LinkedList<>();
					nonAnalyzedTransactions.offer(StorageTransactions.instance().transactionPointer(milestone.bytes()));
					Long pointer;
					while ((pointer = nonAnalyzedTransactions.poll()) != null) {
						if (StorageScratchpad.instance().setAnalyzedTransactionFlag(pointer)) {
							if (transactionCount%100000==0)
								log.info("solidSubmeshMilestone: checked {} transactions",transactionCount);
							transactionCount++;
							
							final Transaction transaction2 = StorageTransactions.instance().loadTransaction(pointer);
							if (transaction2.type == AbstractStorage.PREFILLED_SLOT) {
								solid = false;
								break;

							} else {
								nonAnalyzedTransactions.offer(transaction2.trunkTransactionPointer);
								nonAnalyzedTransactions.offer(transaction2.branchTransactionPointer);
							}
						}
					}
				}

				if (solid) {
					latestSolidSubmeshMilestone = milestone;
					latestSolidSubmeshMilestoneIndex = milestoneIndex;
					if (initialScanCompleted && !initialSnapshotCompleted) {
						initialSnapshotCompleted = true;
						Snapshot.updateSnapshot(); // updating the first snapshot (e.g. after a restart). thereafter it has to be requested via an API call
					}
					return;
				}
			}
		}
	}
}
