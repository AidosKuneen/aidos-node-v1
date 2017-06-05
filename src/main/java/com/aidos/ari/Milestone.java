package com.aidos.ari;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aidos.ari.conf.Configuration;
import com.aidos.ari.conf.Configuration.DefaultConfSettings;
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
	public static final Hash COORDINATOR = Configuration.booling(DefaultConfSettings.TESTNET)
			? new Hash("GKDKHMIGVU9FYFYCFFWREXPQEFDFNEPRRIWDRJDDRDVTNNKBXABPFDQTEHKUXXPPQXXS9YOITKFIYYFHS")
			: new Hash("HZSMDORPCAFJJJNEEWZSP9OCQZAHCAVPBAXUTJKRCYZXMSNGERFZLQPNWOQQHK9RMJO9PNSVV9KR9DONH");

	public static Hash latestMilestone = Hash.NULL_HASH;
	public static Hash latestSolidSubmeshMilestone = Hash.NULL_HASH;

	public static final int MILESTONE_START_INDEX = 0;

	public static int latestMilestoneIndex = MILESTONE_START_INDEX;
	public static int latestSolidSubmeshMilestoneIndex = MILESTONE_START_INDEX;

	private static final Set<Long> analyzedMilestoneCandidates = new HashSet<>();
	private static final Map<Integer, Hash> milestones = new ConcurrentHashMap<>();

	public static void updateLatestMilestone() {

		for (final Long pointer : StorageAddresses.instance().addressesOf(COORDINATOR)) {

			if (analyzedMilestoneCandidates.add(pointer)) {

				final Transaction transaction = StorageTransactions.instance().loadTransaction(pointer);
				if (transaction.currentIndex == 0) {

					final int index = (int) Converter.longValue(transaction.trits(), Transaction.TAG_TRINARY_OFFSET,
							15);
					if (index > latestMilestoneIndex) {
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

				synchronized (StorageScratchpad.instance().getAnalyzedTransactionsFlags()) {

					StorageScratchpad.instance().clearAnalyzedTransactionsFlags();

					final Queue<Long> nonAnalyzedTransactions = new LinkedList<>();
					nonAnalyzedTransactions.offer(StorageTransactions.instance().transactionPointer(milestone.bytes()));
					Long pointer;
					while ((pointer = nonAnalyzedTransactions.poll()) != null) {

						if (StorageScratchpad.instance().setAnalyzedTransactionFlag(pointer)) {

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
					return;
				}
			}
		}
	}
}
