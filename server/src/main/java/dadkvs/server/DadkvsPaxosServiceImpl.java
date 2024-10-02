package dadkvs.server;


import dadkvs.DadkvsPaxos;
import dadkvs.DadkvsPaxosServiceGrpc;
import io.grpc.Context;
import io.grpc.stub.StreamObserver;

public class DadkvsPaxosServiceImpl extends DadkvsPaxosServiceGrpc.DadkvsPaxosServiceImplBase {

    DadkvsServerState server_state;

    LearnHandler learnHandler;

    public DadkvsPaxosServiceImpl(DadkvsServerState state) {
        this.server_state = state;
        this.learnHandler = new LearnHandler(state);
    }

    @Override
    public synchronized void phaseone(DadkvsPaxos.PhaseOneRequest request, StreamObserver<DadkvsPaxos.PhaseOneReply> responseObserver) {
        // for debug purposes
        // TODO: Maybe synchronized
        System.out.println("Receive phase1 request: " + request);
        DadkvsPaxos.PhaseOneReply phase_one_response;
        if(request.getPhase1Timestamp() < server_state.largest_prepare_ts
                || request.getPhase1Timestamp() < server_state.largest_accept_ts
                || request.getPhase1Index() < server_state.current_index){
            phase_one_response = build_phase_one_response(false, server_state.largest_prepare_ts, server_state.accepted_reqid, server_state.current_index);
        } else {
            server_state.largest_prepare_ts = request.getPhase1Timestamp();
            phase_one_response = build_phase_one_response(true, 0, server_state.accepted_reqid, server_state.current_index);
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
    public synchronized void phasetwo(DadkvsPaxos.PhaseTwoRequest request, StreamObserver<DadkvsPaxos.PhaseTwoReply> responseObserver) {
        // for debug purposes
        System.out.println("Receive phase two request: " + request);
        DadkvsPaxos.PhaseTwoReply.Builder phase_two_response = DadkvsPaxos.PhaseTwoReply.newBuilder();
        if(request.getPhase2Timestamp() < server_state.largest_prepare_ts
                || request.getPhase2Timestamp() < server_state.largest_accept_ts
                || request.getPhase2Index() < server_state.current_index
                || server_state.checkTransactionProcessed(request.getPhase2Value())){
            phase_two_response
                    .setPhase2Accepted(false)
                    .setPhase2Index(server_state.current_index)
                    .setInvalidValue(server_state.checkTransactionProcessed(request.getPhase2Value()));
        } else {
            phase_two_response.setPhase2Accepted(true);
            server_state.largest_accept_ts = request.getPhase2Timestamp();
            server_state.accepted_reqid = request.getPhase2Value();
            Context forkedContext = Context.current().fork();
            forkedContext.run(() -> {
                server_state.sendLearnRequests(request.getPhase2Index(), request.getPhase2Value(), request.getPhase2Timestamp());
            });
        }
        responseObserver.onNext(phase_two_response.build());
        responseObserver.onCompleted();
    }

    @Override
    public void learn(DadkvsPaxos.LearnRequest request, StreamObserver<DadkvsPaxos.LearnReply> responseObserver) {
        // for debug purposes
        System.out.println("Receive learn request: " + request);
        learnHandler.handleLearnRequest(request);
        responseObserver.onNext(DadkvsPaxos.LearnReply.getDefaultInstance());
        responseObserver.onCompleted();
    }

}
