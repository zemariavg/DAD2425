package dadkvs.server;

import dadkvs.DadkvsPaxos;
import dadkvs.DadkvsPaxosServiceGrpc;
import dadkvs.util.CollectorStreamObserver;
import dadkvs.util.GenericResponseCollector;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.util.*;

public class DadkvsServerState {
    private final LinkedHashMap<Integer, TransactionRecord> transaction_queue;
    private final Map<Integer, TransactionRecord> transactions_pending_execution;
    private final List<TransactionLogEntry> transaction_execution_log;
    private final Object transaction_lock;
    private final Object leader_lock;
    private final Object transaction_queue_lock;
    boolean i_am_leader;
    int debug_mode;
    int base_port;
    int my_id;
    int store_size;
    int n_servers;
    int largest_prepare_ts;
    int largest_accept_ts;
    int majority_responses;
    int current_index;
    int leader_ts;
    int accepted_reqid;
    String default_host;
    KeyValueStore store;
    MainLoop main_loop;
    Thread main_loop_worker;
    DadkvsPaxosServiceGrpc.DadkvsPaxosServiceStub[] async_paxos_stubs;
    String[] paxos_targets;
    private boolean paxos_ongoing;

    public DadkvsServerState(int kv_size, int port, int myself) {
        base_port = port;
        n_servers = 5;
        my_id = myself;
        i_am_leader = false;
        default_host = "localhost";
        debug_mode = 0;
        largest_accept_ts = 0;
        largest_prepare_ts = 0;
        majority_responses = n_servers / 2 + 1;
        leader_ts = myself;
        current_index = 0;
        paxos_ongoing = false;
        transaction_queue = new LinkedHashMap<>();
        transactions_pending_execution = new HashMap<>();
        transaction_execution_log = new ArrayList<>();
        transaction_lock = new Object();
        leader_lock = new Object();
        transaction_queue_lock = new Object();

        store_size = kv_size;
        store = new KeyValueStore(kv_size);
        main_loop = new MainLoop(this);
        main_loop_worker = new Thread(main_loop);
        main_loop_worker.start();
        paxos_targets = new String[n_servers - 1];

        for (int i = 0; i < n_servers; i++) {
            int target_port = base_port + i;
            paxos_targets[i] = default_host + ":" + target_port;
        }

        initPaxosStubs();
        runPaxos();
    }

    public synchronized void runPaxos() {
        while (true) {
            if (i_am_leader) {
                if (transaction_queue.size() > 0) {
                    paxos_ongoing = true;
                    runAsLeader();
                } else {
                    try {
                        // Waiting for queue to have transactions to be proposed
                        transaction_queue_lock.wait();
                    } catch (InterruptedException e) {
                        // Ignore
                    }
                }
            } else {
                try {
                    leader_lock.wait();
                    //TODO: Don't forget to notify lock when i_am_leader changes
                } catch (InterruptedException e) {
                    // Ignore
                }
            }
        }
    }

    public boolean waitForTransactionExecution(Integer reqid) {
        //TODO: fetch transaction from log by reqid and not key
        while (transaction_queue.containsKey(reqid) || transactions_pending_execution.containsKey(reqid)) {
            try {
                transaction_lock.wait();
            } catch (InterruptedException e) {
                // Ignore
            }
        }
        TransactionLogEntry tx_log_entry = getTransactionfromLog(reqid);
        return tx_log_entry != null && tx_log_entry.wasExecuted();
    }

    private TransactionLogEntry getTransactionfromLog(int reqid) {
        for (TransactionLogEntry tx : transaction_execution_log) {
            if (tx.getReqId() == reqid)
                return tx;
        }
        return null;
    }

    private void runAsLeader() {
        List<DadkvsPaxos.PhaseOneReply> phase_one_responses = new ArrayList<>();
        GenericResponseCollector<DadkvsPaxos.PhaseOneReply> phase_one_collector = new GenericResponseCollector<>(phase_one_responses, n_servers);
        List<DadkvsPaxos.PhaseTwoReply> phase_two_responses = new ArrayList<>();
        GenericResponseCollector<DadkvsPaxos.PhaseTwoReply> phase_two_collector = new GenericResponseCollector<>(phase_two_responses, n_servers);
        primaryLoop:
        while (paxos_ongoing) {
            runPhase1(phase_one_collector, buildPhaseOneRequest(leader_ts));
            phase_one_collector.waitForTarget(majority_responses);
            for (DadkvsPaxos.PhaseOneReply response : phase_one_responses) {
                if (!response.getPhase1Accepted()) {
                    updateLeaderTimestamp(response.getPhase1Timestamp());
                    continue primaryLoop;
                }
            }
            if (phase_one_responses.size() >= majority_responses) {
                int chosen_value = pickValue(phase_one_responses);
                runPhase2(phase_two_collector, buildPhaseTwoRequest(chosen_value, leader_ts));
                phase_two_collector.waitForTarget(majority_responses);
                for (DadkvsPaxos.PhaseTwoReply response : phase_two_responses) {
                    if (!response.getPhase2Accepted()) {
                        updateLeaderTimestamp();
                        continue primaryLoop;
                    }
                }
                if (phase_two_responses.size() >= majority_responses) {
                    paxos_ongoing = false;
                    notifyAll();
                    //TODO: broadcast to learners ?
                } else {
                    updateLeaderTimestamp();
                }
            } else {
                updateLeaderTimestamp();
            }
        }
    }

    public DadkvsPaxos.PhaseOneRequest buildPhaseOneRequest(int ts) {
        DadkvsPaxos.PhaseOneRequest.Builder phase_one_request = DadkvsPaxos.PhaseOneRequest.newBuilder();

        phase_one_request
                .setPhase1Config(0)
                .setPhase1Timestamp(ts);

        return phase_one_request.build();
    }

    public DadkvsPaxos.PhaseTwoRequest buildPhaseTwoRequest(int value, int ts) {
        DadkvsPaxos.PhaseTwoRequest.Builder phase_two_request = DadkvsPaxos.PhaseTwoRequest.newBuilder();

        phase_two_request.setPhase2Config(0)
                .setPhase2Timestamp(ts)
                .setPhase2Value(value);

        return phase_two_request.build();
    }

    private void updateLeaderTimestamp(int response_ts) {
        leader_ts += (int) Math.ceil((double) (response_ts - leader_ts) / n_servers) * n_servers;
    }

    private void updateLeaderTimestamp() {
        leader_ts += n_servers;
    }

    public void addTransactionRecord(Integer reqid, TransactionRecord transactionRecord) {
        boolean was_empty = transaction_queue.size() == 0;
        transaction_queue.put(reqid, transactionRecord);
        if (was_empty)
            transaction_queue_lock.notifyAll();
    }

    private int getReqId() {
        return transaction_queue.keySet().iterator().next();
    }

    private void runPhase1(GenericResponseCollector<DadkvsPaxos.PhaseOneReply> phase_one_collector, DadkvsPaxos.PhaseOneRequest request) {
        for (DadkvsPaxosServiceGrpc.DadkvsPaxosServiceStub stub : async_paxos_stubs) {
            CollectorStreamObserver<DadkvsPaxos.PhaseOneReply> phase_one_observer = new CollectorStreamObserver<>(phase_one_collector);
            stub.phaseone(request, phase_one_observer);
        }
    }

    private void runPhase2(GenericResponseCollector<DadkvsPaxos.PhaseTwoReply> phase_two_collector, DadkvsPaxos.PhaseTwoRequest request) {
        for (DadkvsPaxosServiceGrpc.DadkvsPaxosServiceStub stub : async_paxos_stubs) {
            CollectorStreamObserver<DadkvsPaxos.PhaseTwoReply> phase_two_observer = new CollectorStreamObserver<>(phase_two_collector);
            stub.phasetwo(request, phase_two_observer);
        }
    }

    private int pickValue(List<DadkvsPaxos.PhaseOneReply> responses) {
        // Retrieving the response with the largest accepted timestamp
        DadkvsPaxos.PhaseOneReply largest_accept_response = responses.stream()
                .max(Comparator.comparingInt(DadkvsPaxos.PhaseOneReply::getPhase1Timestamp)).get();
        return largest_accept_response.getPhase1Value() > 0 ? largest_accept_response.getPhase1Value() : getReqId();
    }

    private void initPaxosStubs() {
        ManagedChannel[] channels = new ManagedChannel[n_servers];

        for (int i = 0; i < n_servers; i++) {
            channels[i] = ManagedChannelBuilder.forTarget(paxos_targets[i]).usePlaintext().build();
        }

        async_paxos_stubs = new DadkvsPaxosServiceGrpc.DadkvsPaxosServiceStub[n_servers - 1];

        for (int i = 0; i < n_servers; i++) {
            async_paxos_stubs[i] = DadkvsPaxosServiceGrpc.newStub(channels[i]);
        }
    }
}
