package dadkvs.server;

import dadkvs.DadkvsPaxos;
import dadkvs.DadkvsPaxosServiceGrpc;
import dadkvs.util.CollectorStreamObserver;
import dadkvs.util.GenericResponseCollector;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.util.*;

public class DadkvsServerState {
    boolean i_am_leader;
    int debug_mode;
    int base_port;
    int my_id;
    int store_size;
    int n_servers;
    int largest_prepare_ts;
    int largest_accept_ts;
    int majority_responses;
    int transaction_ts;
    int leader_ts;
    int accepted_reqid;
    private boolean paxos_ongoing;
    private final HashMap<Integer, TransactionRecord> pending_transaction_commits;
    private final List<Integer> transaction_execution_log;

    String default_host;
    KeyValueStore store;
    MainLoop main_loop;
    Thread main_loop_worker;
    DadkvsPaxosServiceGrpc.DadkvsPaxosServiceStub[] async_paxos_stubs;
    String[] paxos_targets;

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
        transaction_ts = 0;
        paxos_ongoing = false;
        pending_transaction_commits = new LinkedHashMap<>();
        transaction_execution_log = new ArrayList<>();

        store_size = kv_size;
        store = new KeyValueStore(kv_size);
        main_loop = new MainLoop(this);
        main_loop_worker = new Thread(main_loop);
        main_loop_worker.start();
        paxos_targets = new String[n_servers-1];

        for (int i = 0; i < n_servers; i++){
            int target_port = base_port + i;
            paxos_targets[i] = default_host + ":" + target_port;
        }

        initPaxosStubs();

    }

    public synchronized void runPaxos(){
        if(i_am_leader){
            paxos_ongoing = true;
            runAsLeader();
        } else {
            while(paxos_ongoing){
                try {
                    wait();
                } catch(InterruptedException e){
                    // TODO
                }
            }
        }
    }

    private void runAsLeader(){
        List<DadkvsPaxos.PhaseOneReply> phase_one_responses = new ArrayList<>();
        GenericResponseCollector<DadkvsPaxos.PhaseOneReply> phase_one_collector = new GenericResponseCollector<>(phase_one_responses, n_servers);
        List<DadkvsPaxos.PhaseTwoReply> phase_two_responses = new ArrayList<>();
        GenericResponseCollector<DadkvsPaxos.PhaseTwoReply> phase_two_collector = new GenericResponseCollector<>(phase_two_responses, n_servers);
        primaryLoop:
        while(paxos_ongoing) {
            runPhase1(phase_one_collector, buildPhaseOneRequest(leader_ts));
            phase_one_collector.waitForTarget(majority_responses);
            for (DadkvsPaxos.PhaseOneReply response : phase_one_responses) {
                if (!response.getPhase1Accepted()) {
                    updateLeaderTimestamp(response.getPhase1Timestamp());
                    continue primaryLoop;
                }
            }
            if(phase_one_responses.size() >= majority_responses){
                int chosen_value = pickValue(phase_one_responses);
                runPhase2(phase_two_collector, buildPhaseTwoRequest(chosen_value, leader_ts));
                phase_two_collector.waitForTarget(majority_responses);
                for (DadkvsPaxos.PhaseTwoReply response : phase_two_responses) {
                    if (!response.getPhase2Accepted()) {
                        updateLeaderTimestamp();
                        continue primaryLoop;
                    }
                }
                if(phase_two_responses.size() >= majority_responses) {
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

    public DadkvsPaxos.PhaseOneRequest buildPhaseOneRequest(int ts){
        DadkvsPaxos.PhaseOneRequest.Builder phase_one_request = DadkvsPaxos.PhaseOneRequest.newBuilder();

        phase_one_request
                .setPhase1Config(0)
                .setPhase1Timestamp(ts);

        return phase_one_request.build();
    }

    public DadkvsPaxos.PhaseTwoRequest buildPhaseTwoRequest(int value, int ts){
        DadkvsPaxos.PhaseTwoRequest.Builder phase_two_request = DadkvsPaxos.PhaseTwoRequest.newBuilder();

        phase_two_request.setPhase2Config(0)
                .setPhase2Timestamp(ts)
                .setPhase2Value(value);

        return phase_two_request.build();
    }

    private void updateLeaderTimestamp(int response_ts){
        leader_ts += (int) Math.ceil((double)(response_ts - leader_ts) / n_servers) * n_servers;
    }

    private void updateLeaderTimestamp(){
        leader_ts += n_servers;
    }

    public void addTransactionRecord(Integer reqid, TransactionRecord transactionRecord){
        pending_transaction_commits.put(reqid, transactionRecord);
    }

    private int getReqId(){
        return pending_transaction_commits.keySet().iterator().next();
    }

    private void runPhase1(GenericResponseCollector<DadkvsPaxos.PhaseOneReply> phase_one_collector, DadkvsPaxos.PhaseOneRequest request){
        for(DadkvsPaxosServiceGrpc.DadkvsPaxosServiceStub stub : async_paxos_stubs){
            CollectorStreamObserver<DadkvsPaxos.PhaseOneReply> phase_one_observer = new CollectorStreamObserver<>(phase_one_collector);
            stub.phaseone(request, phase_one_observer);
        }
    }

    private void runPhase2(GenericResponseCollector<DadkvsPaxos.PhaseTwoReply> phase_two_collector, DadkvsPaxos.PhaseTwoRequest request){
        for(DadkvsPaxosServiceGrpc.DadkvsPaxosServiceStub stub : async_paxos_stubs){
            CollectorStreamObserver<DadkvsPaxos.PhaseTwoReply> phase_two_observer = new CollectorStreamObserver<>(phase_two_collector);
            stub.phasetwo(request, phase_two_observer);
        }
    }

    private int pickValue(List<DadkvsPaxos.PhaseOneReply> responses){
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
