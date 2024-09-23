package dadkvs.server;

import dadkvs.DadkvsPaxos;
import dadkvs.DadkvsPaxosServiceGrpc;
import dadkvs.util.CollectorStreamObserver;
import dadkvs.util.GenericResponseCollector;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.util.ArrayList;
import java.util.List;

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
    int current_value;
    int leader_ts;

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
        majority_responses = 3;
        leader_ts = myself;
        current_value = 1;

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

    public synchronized int runPaxos(int write_key){
        List<DadkvsPaxos.PhaseOneReply> phase_one_responses = new ArrayList<>();
        GenericResponseCollector<DadkvsPaxos.PhaseOneReply> phase_one_collector = new GenericResponseCollector<>(phase_one_responses, n_servers);
        boolean reached_consensus = false;
        if(i_am_leader){
            primaryLoop:
            while(!reached_consensus) {
                runPhase1(phase_one_collector, buildPhaseOneRequest(write_key, leader_ts));
                phase_one_collector.waitForTarget(majority_responses);
                for (DadkvsPaxos.PhaseOneReply response : phase_one_responses) {
                    if (!response.getPhase1Accepted()) {
                        updateLeaderTimestamp(response.getPhase1Timestamp());
                        continue primaryLoop;
                    }
                }
                if(phase_one_responses.size() >= majority_responses){
                    int chosen_value = pickValue(phase_one_responses);
                } else {
                    updateLeaderTimestamp();
                }

            }
        }
    }

    public DadkvsPaxos.PhaseOneRequest buildPhaseOneRequest(int write_key, int ts){
        DadkvsPaxos.PhaseOneRequest.Builder phase_one_request = DadkvsPaxos.PhaseOneRequest.newBuilder();

        phase_one_request.setPhase1Index(write_key)
                .setPhase1Config(0)
                .setPhase1Timestamp(ts);

        return phase_one_request.build();
    }

    private void updateLeaderTimestamp(int response_ts){
        leader_ts += (int) Math.ceil((double)(response_ts - leader_ts) / n_servers) * n_servers;
    }

    private void updateLeaderTimestamp(){
        leader_ts += n_servers;
    }

    private void runPhase1(GenericResponseCollector<DadkvsPaxos.PhaseOneReply> phase_one_collector, DadkvsPaxos.PhaseOneRequest request){
        for(DadkvsPaxosServiceGrpc.DadkvsPaxosServiceStub stub : async_paxos_stubs){
            CollectorStreamObserver<DadkvsPaxos.PhaseOneReply> phase_one_observer = new CollectorStreamObserver<>(phase_one_collector);
            stub.phaseone(request, phase_one_observer);
        }
    }

    private void runPhase2(GenericResponseCollector<DadkvsPaxos.PhaseTwoReply> phase_one_collector, DadkvsPaxos.PhaseTwoRequest request){

    }

    private int pickValue(List<DadkvsPaxos.PhaseOneReply> responses){
        int largest_accepted_ts = 0;
        int largest_accept_value = 0;
        for(DadkvsPaxos.PhaseOneReply response: responses){
            if(response.getPhase1Value() != 0 && response.getPhase1Timestamp() > largest_accepted_ts){
                largest_accept_value = response.getPhase1Value();
                largest_accepted_ts = response.getPhase1Timestamp();
            }
        }
        return largest_accept_value > 0 ? largest_accept_value : current_value;
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
