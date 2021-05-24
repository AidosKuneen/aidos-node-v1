package com.aidos.ari.service;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aidos.ari.Bundle;
import com.aidos.ari.Milestone;
import com.aidos.ari.Snapshot;
import com.aidos.ari.model.Hash;
import com.aidos.ari.model.Transaction;
import com.aidos.ari.service.storage.Storage;
import com.aidos.ari.service.storage.StorageApprovers;
import com.aidos.ari.service.storage.StorageScratchpad;
import com.aidos.ari.service.storage.StorageTransactions;

public class TipsManager {

	public static int minWeightMagnitude = 18;
	
    private static final Logger log = LoggerFactory.getLogger(TipsManager.class);

    private volatile boolean shuttingDown;

    public void init() {

        (new Thread(() -> {
        	
            while (!shuttingDown) {

                try {
                    final int previousLatestMilestoneIndex = Milestone.latestMilestoneIndex;
                    final int previousSolidSubmeshLatestMilestoneIndex = Milestone.latestSolidSubmeshMilestoneIndex;

                    Milestone.updateLatestMilestone();
                    Milestone.updateLatestSolidSubmeshMilestone();

                    if (previousLatestMilestoneIndex != Milestone.latestMilestoneIndex) {
                        log.info("Latest milestone has changed from #" + previousLatestMilestoneIndex + " to #" + Milestone.latestMilestoneIndex);
                    }
                    if (previousSolidSubmeshLatestMilestoneIndex != Milestone.latestSolidSubmeshMilestoneIndex) {
                    	log.info("Latest SOLID SUBMESH milestone has changed from #" + previousSolidSubmeshLatestMilestoneIndex + " to #" + Milestone.latestSolidSubmeshMilestoneIndex);
                    }
                    Thread.sleep(5000);

                } catch (final Exception e) {
                	log.error("Error during TipsManager Milestone updating", e);
                }
            }
        }, "Latest Milestone Tracker")).start();
    }

    public void shutDown() {
        shuttingDown = true;
    }

    static synchronized Hash transactionToApprove(final Hash extraTip, int depth) {

        final Hash preferableMilestone = Milestone.latestSolidSubmeshMilestone;

        synchronized (StorageScratchpad.instance().getAnalyzedTransactionsFlags()) {

        	StorageScratchpad.instance().clearAnalyzedTransactionsFlags();

            Map<Hash, Long> state = new HashMap<>(Snapshot.initialState);

            {
                int numberOfAnalyzedTransactions = 0;

                final Queue<Long> nonAnalyzedTransactions = new LinkedList<>(Collections.singleton(StorageTransactions.instance().transactionPointer((extraTip == null ? preferableMilestone : extraTip).bytes())));
                Long pointer;
                while ((pointer = nonAnalyzedTransactions.poll()) != null) {

                    if (StorageScratchpad.instance().setAnalyzedTransactionFlag(pointer)) {

                        numberOfAnalyzedTransactions++;

                        final Transaction transaction = StorageTransactions.instance().loadTransaction(pointer);
                        if (transaction.type == Storage.PREFILLED_SLOT) {
                            return null;
                        } else {

                            if (transaction.currentIndex == 0) {

                                boolean validBundle = false;

                                final Bundle bundle = new Bundle(transaction.bundle);
                                for (final List<Transaction> bundleTransactions : bundle.getTransactions()) {

                                    if (bundleTransactions.get(0).pointer == transaction.pointer) {

                                        validBundle = true;

                                        bundleTransactions.stream().filter(bundleTransaction -> bundleTransaction.value != 0).forEach(bundleTransaction -> {

                                        
                                        
                                        });
                                        break;
                                    }
                                }

                                if (!validBundle) {
                                    return null;
                                }
                            }
                            if (transaction.value != 0) {
	                            final Hash address = new Hash(transaction.address);
	                            final Long value = state.get(address);
	                            state.put(address, value == null ? transaction.value : (value + transaction.value));
                            }
                            nonAnalyzedTransactions.offer(transaction.trunkTransactionPointer);
                            nonAnalyzedTransactions.offer(transaction.branchTransactionPointer);
                        }
                    }
                }

                log.info("Confirmed transactions = {}", numberOfAnalyzedTransactions);
            }

            final Iterator<Map.Entry<Hash, Long>> stateIterator = state.entrySet().iterator();
            while (stateIterator.hasNext()) {

                final Map.Entry<Hash, Long> entry = stateIterator.next();
                if (entry.getValue() <= 0) {

                    if (entry.getValue() < 0) {
                    	log.error("Ledger inconsistency detected");
                        return null;
                    }
                    stateIterator.remove();
                }
            }

            StorageScratchpad.instance().saveAnalyzedTransactionsFlags();
            StorageScratchpad.instance().clearAnalyzedTransactionsFlags();

            final Set<Hash> tailsToAnalyze = new HashSet<>();

            Hash tip = preferableMilestone;
            if (extraTip != null) {

                Transaction transaction = StorageTransactions.instance().loadTransaction(StorageTransactions.instance().transactionPointer(tip.bytes()));
                while (depth-- > 0 && !tip.equals(Hash.NULL_HASH)) {

                    tip = new Hash(transaction.hash, 0, Transaction.HASH_SIZE);
                    do {
                        transaction = StorageTransactions.instance().loadTransaction(transaction.trunkTransactionPointer);
                    } while (transaction.currentIndex != 0);
                }
            }
            final Queue<Long> nonAnalyzedTransactions = new LinkedList<>(Collections.singleton(StorageTransactions.instance().transactionPointer(tip.bytes())));
            Long pointer;
            while ((pointer = nonAnalyzedTransactions.poll()) != null) {

                if (StorageScratchpad.instance().setAnalyzedTransactionFlag(pointer)) {

                    final Transaction transaction = StorageTransactions.instance().loadTransaction(pointer);

                    if (transaction.currentIndex == 0) {
                        tailsToAnalyze.add(new Hash(transaction.hash, 0, Transaction.HASH_SIZE));
                    }

                    StorageApprovers.instance().approveeTransactions(StorageApprovers.instance().approveePointer(transaction.hash)).forEach(nonAnalyzedTransactions::offer);
                }
            }

            if (extraTip != null) {

                StorageScratchpad.instance().loadAnalyzedTransactionsFlags();

                final Iterator<Hash> tailsToAnalyzeIterator = tailsToAnalyze.iterator();
                while (tailsToAnalyzeIterator.hasNext()) {

                    final Transaction tail = StorageTransactions.instance().loadTransaction(tailsToAnalyzeIterator.next().bytes());
                    if (StorageScratchpad.instance().analyzedTransactionFlag(tail.pointer)) {
                        tailsToAnalyzeIterator.remove();
                    }
                }
            }

            log.info(tailsToAnalyze.size() + " tails need to be analyzed");
            Hash bestTip = preferableMilestone;
            int bestRating = 0;
            for (final Hash tail : tailsToAnalyze) {

            	StorageScratchpad.instance().loadAnalyzedTransactionsFlags();

                Set<Hash> extraTransactions = new HashSet<>();

                nonAnalyzedTransactions.clear();
                nonAnalyzedTransactions.offer(StorageTransactions.instance().transactionPointer(tail.bytes()));
                while ((pointer = nonAnalyzedTransactions.poll()) != null) {

                    if (StorageScratchpad.instance().setAnalyzedTransactionFlag(pointer)) {

                        final Transaction transaction = StorageTransactions.instance().loadTransaction(pointer);
                        
                        if (transaction.type == Storage.PREFILLED_SLOT) {
                            extraTransactions = null;
                            break;
                        } else {
                        	
                        	int weightMagnitude = 0;
                            int[] transactionTrits = new Hash(transaction.hash, 0, Transaction.HASH_SIZE).trits();
                            for (int pos = transactionTrits.length-1; pos>=0; pos-- ) {
                            	if (transactionTrits[pos] == 0) weightMagnitude++;
                            	else break;
                            }
                            
                            if (weightMagnitude < minWeightMagnitude) {
                            	extraTransactions = null;
                                break;
                            }
                        	
                            extraTransactions.add(new Hash(transaction.hash, 0, Transaction.HASH_SIZE));
                            nonAnalyzedTransactions.offer(transaction.trunkTransactionPointer);
                            nonAnalyzedTransactions.offer(transaction.branchTransactionPointer);
                        }
                    }
                }

                if (extraTransactions != null) {

                    Set<Hash> extraTransactionsCopy = new HashSet<>(extraTransactions);

                    for (final Hash extraTransaction : extraTransactions) {

                        final Transaction transaction = StorageTransactions.instance().loadTransaction(extraTransaction.bytes());
                        if (transaction != null && transaction.currentIndex == 0) {

                            final Bundle bundle = new Bundle(transaction.bundle);
                            for (final List<Transaction> bundleTransactions : bundle.getTransactions()) {

                                if (Arrays.equals(bundleTransactions.get(0).hash, transaction.hash)) {

                                    for (final Transaction bundleTransaction : bundleTransactions) {

                                        if (!extraTransactionsCopy.remove(new Hash(bundleTransaction.hash, 0, Transaction.HASH_SIZE))) {
                                            extraTransactionsCopy = null;
                                            break;
                                        }
                                    }
                                    break;
                                }
                            }
                        }
                        if (extraTransactionsCopy == null) {
                            break;
                        }
                    }

                    if (extraTransactionsCopy != null && extraTransactionsCopy.isEmpty()) {

                        final Map<Hash, Long> stateCopy = new HashMap<>(state);

                        for (final Hash extraTransaction : extraTransactions) {

                            final Transaction transaction = StorageTransactions.instance().loadTransaction(extraTransaction.bytes());
                            if (transaction.value != 0) {
                                final Hash address = new Hash(transaction.address);
                                final Long value = stateCopy.get(address);
                                stateCopy.put(address, value == null ? transaction.value : (value + transaction.value));
                            }
                        }
                        long total = 0l;
                        for (final long value : stateCopy.values()) {
                            if (value < 0) {
                                extraTransactions = null;
                                break;
                            }
                            total += value;
                        }
                        if (total != 2500000000000000l) {
                        	log.info("discarding tip. would cause invalid state.");
                        }
                        if (extraTransactions != null) {
                            if (extraTransactions.size() > bestRating) {
                                bestTip = tail;
                                bestRating = extraTransactions.size();
                            }
                        }
                    }
                }
            }
            log.info("{} extra transactions approved", bestRating);
            return bestTip;
        }
    }
    
    private static TipsManager instance = new TipsManager();

    private TipsManager() {}

    public static TipsManager instance() {
        return instance;
    }
}
