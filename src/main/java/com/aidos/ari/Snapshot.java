package com.aidos.ari;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.TreeSet;
import java.util.concurrent.Semaphore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aidos.ari.model.Hash;
import com.aidos.ari.model.Transaction;
import com.aidos.ari.service.storage.Storage;
import com.aidos.ari.service.storage.StorageScratchpad;
import com.aidos.ari.service.storage.StorageTransactions;

public class Snapshot {
	
	private static final Logger log = LoggerFactory.getLogger(StorageTransactions.class);
	
	public static final TreeSet<Long> confirmedSnapshotTransactions = new TreeSet<Long>(); // pointer to all snapshot-confirmed transactions 
	// note: requires 200-300 MB memory but speeds up API calls significantly
	
	public static HashMap<Hash, Long> latestSnapshot;
	private static int latestSnapshotMilestoneIndex = Milestone.MILESTONE_START_INDEX;
	
	public int getLatestSnapshotMilestoneIndex() {
		return latestSnapshotMilestoneIndex;
	}
	
	// we use a semaphore to allow up to X processes to read the latest snapshot, while blocking during update of the snapshot
	public static final int MAX_CONCURRENT = 100;
	public static final Semaphore updateSnapshotSemaphore = new Semaphore(MAX_CONCURRENT);
	
	// get snapshot for latest solid milestone and flag confirmed transactions as confirmed
	public static HashMap<Hash, Long> updateSnapshot() {
		// need to wait until no more getBalances or getInclusionStates are running
		try{
			updateSnapshotSemaphore.acquireUninterruptibly(MAX_CONCURRENT);
			
			if (Milestone.latestSolidSubmeshMilestoneIndex == latestSnapshotMilestoneIndex) {
				log.info("updateSnapshot: current snaphot is the latest");
				return latestSnapshot;
			}
	
			HashMap<Hash, Long> snapshot = new HashMap<Hash, Long>();
			// deep copy initial state
			for (Hash kh : initialState.keySet()) {
				snapshot.put(new Hash(kh.bytes()), new Long(initialState.get(kh)));
			}
			
			int solidMilestoneIndex;
			byte[] solidMilestoneBytes;
			
			solidMilestoneIndex = Milestone.latestSolidSubmeshMilestoneIndex;
			solidMilestoneBytes = Milestone.latestSolidSubmeshMilestone.bytes();
		
			log.info("requesting snapshot for milestone={}", solidMilestoneIndex);
			if (solidMilestoneIndex > Milestone.MILESTONE_START_INDEX) {
		
				log.info("clearing confirmedSnapshotTransactions");
				confirmedSnapshotTransactions.clear();
				final Transaction ms = StorageTransactions.instance().loadTransaction(solidMilestoneBytes);
				
				final Queue<Long> nonAnalyzedTransactions = new LinkedList<>(Collections.singleton(ms.pointer));
	            Long pointer;
	            
	            synchronized (StorageScratchpad.instance().getAnalyzedTransactionsFlags()) {
	            	
					StorageScratchpad.instance().clearAnalyzedTransactionsFlags();
					long transactionCount = 0;
		            while ((pointer = nonAnalyzedTransactions.poll()) != null) {
		
		                 if (StorageScratchpad.instance().setAnalyzedTransactionFlag(pointer)) {
		                	if (transactionCount%100000==0)
									log.info("updateSnapshot: checked {} transactions",transactionCount);
							transactionCount++;
		                	confirmedSnapshotTransactions.add(pointer);
		                	 
		                    final Transaction transaction = StorageTransactions.instance().loadTransaction(pointer);
		                    if (transaction.type == Storage.PREFILLED_SLOT) {
		                    	 log.error("getSnapshotForLatestSolidMilestone found inconsistent mesh!");
		                 		 return null; // mesh inconsistent (solid milestone should not have 'holes')
		                    } else {
								if (transaction.value != 0) {
									final Hash address = new Hash(transaction.address);
									final Long value = snapshot.getOrDefault(address,  0L);
									if ((value + transaction.value) == 0) {
										snapshot.remove(address); // remove if 0
									}
									else {
										snapshot.put(address, value + transaction.value);
									}
								}
								nonAnalyzedTransactions.offer(transaction.trunkTransactionPointer);
								nonAnalyzedTransactions.offer(transaction.branchTransactionPointer);
		                     }
		                 }
		            }
	            }
	            
	            long total = 0L;
	            for (Long value : snapshot.values()) {
	            	if (value < 0) {
	            		 log.error("getSnapshotForLatestSolidMilestone found inconsistent mesh! (negative address balance)");
	            		 return null;
	            	}
	            	total += value;
	            }
	            if (total != 2500000000000000L) {
	            	log.error("getSnapshotForLatestSolidMilestone found inconsistent mesh! (invalid ledger balance: {})",total);
	       		 	return null;
	            }
	            
	            log.info("confirmed {} transactions : confirmedSnapshotTransactions", confirmedSnapshotTransactions.size());
				
			}
		
			
			synchronized(latestSnapshot) {
				latestSnapshot = new HashMap<Hash, Long>(snapshot);
				// store in file also
				try {
					File snapshotFile = new File("mesh.snapshot");
					if (snapshotFile.exists()) snapshotFile.delete();
					BufferedWriter writer = new BufferedWriter(new FileWriter(snapshotFile));
					writer.write("#milestone="+solidMilestoneIndex); //properties format but this is a properties comment
					for (Hash h : latestSnapshot.keySet()) {
						writer.write(h.toString()+"="+latestSnapshot.get(h));
						writer.newLine();
					}
					writer.close();
					log.info("snapshot {} written to mesh.snapshot", solidMilestoneIndex);
				} catch (IOException e) {
					e.printStackTrace(); // log but no throw
				}
			}
			log.info("snapshot performed for milestone={}",Milestone.latestSolidSubmeshMilestoneIndex);
			
			return latestSnapshot;
		}
		finally {
			updateSnapshotSemaphore.release(MAX_CONCURRENT); // this always gets done
		}
	}

    // this is the genesis snapshot
	public static final Map<Hash, Long> initialState = new HashMap<>();

    static {
    	initialState.put(new Hash("OZZIZKFIQPGBLZKWLTBNMFKKTHFKYOYTRMGJU9AJTVRI9BUKULPQFFPZT9AIT9GTQSZLKKLVLXDVCYLZZ"), 55964400000000L);
    	initialState.put(new Hash("LEHBAUZNHMMMOLFCWNZWXIXQDOPSOSGMXEVNJU9XRZLQQXMUUVXTUJORSXUCQRHSEAEOBLHACETOCHJBD"), 57713600000000L);
    	initialState.put(new Hash("WKSSTRFS9VKDR9VXUMRKZXJMAXUDFQGZR9NQCRROMUGCA99LRYIL9RWLRCZDIBSUIMNFGRGYIANAYWEXX"), 56140525000000L);
    	initialState.put(new Hash("IHVGFAFQIEEPKCXSTQLKV9LHRDLINHG9MUYDIQNVYOPGWSCUDFHSXNULUETGZBUNULTDHLOXWVXUXBTPP"), 57524100000000L);
    	initialState.put(new Hash("OT9YIEOUNEL9LTXCDDJNNHQCNRDSRGBKTZQOQKNEJIEGDRBUCEARONPSRFROALIJRGBPLDIIBHVRQTTXD"), 56209525000000L);
    	initialState.put(new Hash("XJBQ9HYSVXMXRKFIYLHTB9OBHUCSZDKMHRSXSMQRTVAMIHNJOAMNCTGUPNFLQOMSKPBETG9BNJWOBVHL9"), 56142525000000L);
    	initialState.put(new Hash("BTZXSPMFOPPVWCAOEXHGNGUPPHLXRUPGFOPTVKLWARBJOQBK9HEIRK9PMACHJOOCSHWWKXBOOUDUSYHSI"), 57621600000000L);
    	initialState.put(new Hash("TRCJWPLKLHWJZXQJRRPQBZMNKRKJDSGDDKTWJEHSAWLKGSOFLTJAEETKZYNJPFZSQQINQDFKXHYWND9ZF"), 56078025000000L);
    	initialState.put(new Hash("EYFHQHDWMAMSBUSMWJ9ZCQNVAPWEWSTAKFXNCEAZXLRTLNEFDGEYTVWFRQHMVYHOOTJCHAOFDJCVDYG9P"), 56111025000000L);
    	initialState.put(new Hash("AH9JQYDSPECQZOIEEKNNNXUUOQTW9DPVC9BUZUJGBMBSFLU9L9NWHXWWB9DFUDEKNGXWNWZGLSBQCHCXJ"), 57577100000000L);
    	initialState.put(new Hash("YIGWDCSGXVEPCGLPWIYMIWWHHSLVBQVRSGWZACHYGVWMGEAIYIPZFVZNFMYXABI9QAEOCDUVDWMJYI9CQ"), 56201525000000L);
    	initialState.put(new Hash("JPKZOGVKIHOHPAGTURLKCUMDFMOM9UVNMGBSYFGPOJHJNPAVVQRXVBWYSHDXAZAHUWGUWYWTFRXLR9UEY"), 56263025000000L);
    	initialState.put(new Hash("LXBGWTGSBTGXHORLPLKKNUJPFJQNOOCCQDKWTJKPVBCPBIUEWDF9ANMAOAWMMTMWRCEEMRCPNCGOJHKMB"), 57584100000000L);
    	initialState.put(new Hash("GFSNBIGRTOXUXXQPGQMJUXCXXEJLPZRNMDOJQZXGWBLWZAXNFEQRJKEUWZMWU9BYLWUXBZGTNVKEGVG9N"), 57595100000000L);
    	initialState.put(new Hash("FIWNFROVMSNIG9F9NZAOQQJEMHWFWFUOAOJFOVROR9CJZJMLRBHXJOHJDUZ9NWGSEVCYVGOEVGCMDMBGD"), 56362525000000L);
    	initialState.put(new Hash("MRDJJ9RZDJRCFTIBGBZA9WCLPTAPCYTOONQ9EQF9A9TOIGGLCAEGFSQQVKZEFFTSXPAA9XUWVGCOUAZID"), 57379100000000L);
    	initialState.put(new Hash("PIXZUZYAWSGEOBVAHYYW9YZFLSQIFYPNLFXGHWTUUF99IUIPPKOZSNEHKLNCIWTKRLPB9CDVKREVVNKMA"), 57391100000000L);
    	initialState.put(new Hash("SNF9MHILXSLOFVIBQKC9IDERFTCPAGESPUCT9AYIIZJATHHGGSQBAFCHL9SLMAT9BWYBKWFBKKHLGBLQB"), 57671600000000L);
    	initialState.put(new Hash("VHBR9KDJVBQ9YHXSFSSYNPJIKRB9LTEYIHUATENDDBK9PMQWMWKVU9HYXRLWBOVZLOMUYIPVZBSYVXHMK"), 56067025000000L);
    	initialState.put(new Hash("AIYKGSFKYARDQGULXDMWYHBEYDOPHZCUNWCWDOGYEVIHCVQVVTPZUPUPMRGOGJXATMRJOXMSMNLFMJNBZ"), 56349525000000L);
    	initialState.put(new Hash("UQUEGUDDMOVKRKQKDFAIAWBLHRKBHZITWMEZFHAZBWZEFLVURHYEMQQZHTGTCR9ZXYEXCU9WSNUEEJIGO"), 57702600000000L);
    	initialState.put(new Hash("TGDOSM9UIRQKTYRXZYBWMUBFEOQRYERHPVEAUDVV9MYD9DHJJMVTHVVSSKMACOWVTBFRLVDWFDZVXFLOM"), 56033525000000L);
    	initialState.put(new Hash("DXPYMHUIMKZMUNYMGGZQLRCHUX9ECWMZOEBDYQADSDJNVJAIMAQBBVPCVYAALAFJAFDBLSFUEHDOATALL"), 56256025000000L);
    	initialState.put(new Hash("EQXBYBICJXYGDAUVDDPTKTDOMOGXKSLLZBWURIJGFQPDZSXUFEU9XYQU9DUGVIZG9JQCQIJNWEKXMDCYX"), 56086025000000L);
    	initialState.put(new Hash("HBPGQOONQWLVVPIDOQTGYZQPTNUBUBCJNQPDHELEXGOFMNJTISDGGJROXL99NVOLTWCFU9YSBGUUSOCUI"), 56315025000000L);
    	initialState.put(new Hash("RAHSGSUUGFFGYLGBWOOCNTVX99LQVCSLWORSCNKNOIYKDUWQOUYMTWQUGAPFJKLQUXO9BNFWXNEYJLLSA"), 56079025000000L);
    	initialState.put(new Hash("CTWKREZYJVZBYPNXXKNGKKGFRAJNYDGSLVQXXPG9I9TEFEEO9KMACEAWDSNBJKGJCDLABE9VOHQYRFR9G"), 56101025000000L);
    	initialState.put(new Hash("EJIYR9A9VFOQXO999LTXQ9VNXIZBFWOXKMASJNNFPTFLELZDYSVBKNJOYRFGCG9XKAXVRSYZBPIJRXFM9"), 57585600000000L);
    	initialState.put(new Hash("CWXR9TTQFJAP9GR9BAOCGUVLEBBUAQHYJEIZWMFDGVFHEOUUTZ9MHEZJUEHBYRPGXVAZDALKSQOPUOXIL"), 57471600000000L);
    	initialState.put(new Hash("PTCYE9UMLOJYDVYCN9PZYZLPUVQTR9Q9LRAJSWTAUMYXGZQUMFTNNHBBXDGNT9BR99SKGRYFIQJRPLULB"), 57576100000000L);
    	initialState.put(new Hash("FNFLHIDHZFRBLSVMOLXKMWRSGHIBPKZMXY9AMURB9KHSEDYECFXUOAZFNNCXNMSEEZKRINGNL9FVJJBOQ"), 55977025000000L);
    	initialState.put(new Hash("GBHF9AOBKSRXYY9JPXRLBSRRCRPTFUGIKFHJCVJBNVDYOSIFYZIHTVNFJNX9VXQHRRLVGPNPWGQLLR9T9"), 57381600000000L);
    	initialState.put(new Hash("OVNBURGVTZRGRJUZUTRHUVO9OMASXEFWGTWYJYTMXZFPYPMU9IHZYSZFBDNRS9FXTDNXINLTSIHPFKJJT"), 55993525000000L);
    	initialState.put(new Hash("OWQUELUQHYTTQYHEZMSFPCPVWUH9MFDPFGWINVNRXHDFAVJ9QPJ9NRWHIEFABBZZHXAFBLUAETLMMAG9F"), 57585100000000L);
    	initialState.put(new Hash("BQSKUFKKJMWQBPUWPAJIGVNIOXEFCADPSPZVBXHTDCKJERUNOAMSMPBFQAFIWS9WSTDZR9YXOBWQ9VVAZ"), 56344525000000L);
    	initialState.put(new Hash("XOSUVYALDMGJBZJQTHOEXCLBEVYFKQXMOMUPMMYBMLWYUXRZWSUKZMTOTJOZUBOBZAVNJLYVSYFJEWWQZ"), 57693600000000L);
    	initialState.put(new Hash("EYJRSKNWWELDBHXVDQBHIWBKZJIKBIGUQBNNIBGJKUKRGEAKIBH9EARS9OZZFQENXIYCSSWYEMLDSO9PY"), 57573600000000L);
    	initialState.put(new Hash("WSCBGNRNTHLKQRHQTVDLZDYJYJIEXJXZBBWFVKJDWTQVTRIMRFCGDWQABWJHZMGWOTYUNSWFLQLDOWKOX"), 57596600000000L);
    	initialState.put(new Hash("QKEITKZIDFYKQD9HJJO9YKLGSEFHPIZWUUKB9KCDOAF9FHYGANTGELPBNQIQEODCBQJAMOUHXWDR9WXEW"), 55558975000000L);
    	initialState.put(new Hash("RGQEJZMCDJNIXHSGNIUNABAAAPNVRLPSVJWJNG9VMWPNBJHSLAUDLUKDFIJPUQCRTWDRYYNRAHQUAZRFI"), 57538100000000L);
    	initialState.put(new Hash("AEVQOUHSZMIOPIAZGPEQQUDEPPPSOCFKA9EHPU9TRULHZJUX9XMIIDEQEPQYVLNRBQKBJJJQTZWHYJYJA"), 57724600000000L);
    	initialState.put(new Hash("JDEUWDBRNPBPWHZXCBUDLQRAZYUHAVXWGWLVPYPDPBZQTMPCWADXQDWWFQSDKYVXNASTKKHVUFXFZOMBE"), 57549100000000L);
    	initialState.put(new Hash("AUVBUAHWJHDXZWKKMCSOCVNSYMLQYGRSWEDOGFYKWYTNJRSJCHNLANZ9B9QXWKMRZHBCHCEVIWEQJKRDZ"), 56076525000000L);
    	initialState.put(new Hash("NYFB9URUDMCZCZLXNQFVLKFAQUANROBHWYNQMLIGRXSNOSZGEFCVCVCKPKYSPQERYTWCLIDHVXBYAJIKF"), 56253525000000L);
    	
    	latestSnapshot = new HashMap<>(initialState);
    	
    }
}
