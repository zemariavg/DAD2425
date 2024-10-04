package dadkvs.server;


import dadkvs.DadkvsPaxos;
import dadkvs.DadkvsPaxosServiceGrpc;
import io.grpc.Context;
import io.grpc.stub.StreamObserver;

import java.util.concurrent.locks.ReentrantLock;

public class DadkvsPaxosServiceImpl extends DadkvsPaxosServiceGrpc.DadkvsPaxosServiceImplBase {

    DadkvsServerState server_state;

    LearnHandler learnHandler;

    ReentrantLock paxosStateLock;

    DebugHandler debugHandler;

    public DadkvsPaxosServiceImpl(DadkvsServerState state, DebugHandler debugHandler) {
        this.server_state = state;
        this.learnHandler = new LearnHandler(state);
        this.paxosStateLock = new ReentrantLock();
        this.debugHandler = debugHandler;
    }

    @Override
    public void phaseone(DadkvsPaxos.PhaseOneRequest request, StreamObserver<DadkvsPaxos.PhaseOneReply> responseObserver) {
        // for debug purposes
        System.out.println("Receive phase1 request: " + request);
        debugHandler.runDebug(server_state.debug_mode, false);
        DadkvsPaxos.PhaseOneReply phase_one_response;
        try {
            paxosStateLock.lock();
            if (request.getPhase1Timestamp() < server_state.largest_prepare_ts
                    || request.getPhase1Timestamp() < server_state.largest_accept_ts) {
                phase_one_response = build_phase_one_response(false, server_state.largest_prepare_ts,
                        -1, request.getPhase1Index());
            } else {
                int request_to_send;
                server_state.largest_prepare_ts = request.getPhase1Timestamp();
                if (!server_state.isIndexEmpty(request.getPhase1Index()))
                    request_to_send = server_state.getValueFromLog(request.getPhase1Index());
                else
                    request_to_send = server_state.getUncommitedConsensusAccept(request.getPhase1Index());
                phase_one_response = build_phase_one_response(true,
                        request_to_send != -1 ? server_state.largest_accept_ts : -1, request_to_send, request.getPhase1Index());
            }
        } finally {
            paxosStateLock.unlock();
        }
        responseObserver.onNext(phase_one_response);
        responseObserver.onCompleted();
    }

    private DadkvsPaxos.PhaseOneReply build_phase_one_response(boolean accepted, int ts, int value, int index){
        DadkvsPaxos.PhaseOneReply.Builder phase_one_response_builder = DadkvsPaxos.PhaseOneReply.newBuilder();
        return phase_one_response_builder
                .setPhase1Accepted(accepted)
                .setPhase1Timestamp(ts)
                .setPhase1Value(value)
                .setPhase1Index(index)
                .build();
    }

    @Override
    public void phasetwo(DadkvsPaxos.PhaseTwoRequest request, StreamObserver<DadkvsPaxos.PhaseTwoReply> responseObserver) {
        // for debug purposes
        System.out.println("Receive phase two request: " + request);
        debugHandler.runDebug(server_state.debug_mode, false);
        DadkvsPaxos.PhaseTwoReply.Builder phase_two_response = DadkvsPaxos.PhaseTwoReply.newBuilder();
        try {
            paxosStateLock.lock();
            if(request.getPhase2Timestamp() < server_state.largest_prepare_ts
                    || request.getPhase2Timestamp() < server_state.largest_accept_ts){
                phase_two_response
                        .setPhase2Accepted(false)
                        .setPhase2Index(request.getPhase2Index())
                        .setPhase2Timestamp(server_state.largest_prepare_ts);
            } else {
                phase_two_response.setPhase2Accepted(true);
                server_state.largest_accept_ts = request.getPhase2Timestamp();
                server_state.addAcceptedValue(request.getPhase2Index(), request.getPhase2Value());
                Context forkedContext = Context.current().fork();
                forkedContext.run(() -> {
                    server_state.sendLearnRequests(request.getPhase2Index(), request.getPhase2Value(), request.getPhase2Timestamp());
                });
            }
        } finally {
            paxosStateLock.unlock();
        }
        responseObserver.onNext(phase_two_response.build());
        responseObserver.onCompleted();
    }

    @Override
    public void learn(DadkvsPaxos.LearnRequest request, StreamObserver<DadkvsPaxos.LearnReply> responseObserver) {
        // for debug purposes
        System.out.println("Receive learn request: " + request);
        debugHandler.runDebug(server_state.debug_mode, false);
        learnHandler.handleLearnRequest(request);
        responseObserver.onNext(DadkvsPaxos.LearnReply.getDefaultInstance());
        responseObserver.onCompleted();
    }

}
