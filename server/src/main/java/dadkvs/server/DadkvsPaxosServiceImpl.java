package dadkvs.server;


import dadkvs.DadkvsPaxos;
import dadkvs.DadkvsPaxosServiceGrpc;
import io.grpc.stub.StreamObserver;

public class DadkvsPaxosServiceImpl extends DadkvsPaxosServiceGrpc.DadkvsPaxosServiceImplBase {


    DadkvsServerState server_state;


    public DadkvsPaxosServiceImpl(DadkvsServerState state) {
        this.server_state = state;
    }


    @Override
    public void phaseone(DadkvsPaxos.PhaseOneRequest request, StreamObserver<DadkvsPaxos.PhaseOneReply> responseObserver) {
        // for debug purposes
        System.out.println("Receive phase1 request: " + request);
        DadkvsPaxos.PhaseOneReply phase_one_response;
        if(request.getPhase1Timestamp() < server_state.largest_prepare_ts || request.getPhase1Timestamp() < server_state.largest_accept_ts){
            phase_one_response = build_phase_one_response(false, server_state.largest_prepare_ts, server_state.accepted_reqid);
        } else {
            server_state.largest_prepare_ts = request.getPhase1Timestamp();
            phase_one_response = build_phase_one_response(true, 0, server_state.accepted_reqid);
        }
        responseObserver.onNext(phase_one_response);
    }

    private DadkvsPaxos.PhaseOneReply build_phase_one_response(boolean accepted, int ts, int value){
        DadkvsPaxos.PhaseOneReply.Builder phase_one_response_builder = DadkvsPaxos.PhaseOneReply.newBuilder();
        return phase_one_response_builder.setPhase1Accepted(accepted).setPhase1Timestamp(ts).setPhase1Value(value).build();
    }

    @Override
    public void phasetwo(DadkvsPaxos.PhaseTwoRequest request, StreamObserver<DadkvsPaxos.PhaseTwoReply> responseObserver) {
        // for debug purposes
        System.out.println("Receive phase two request: " + request);
        DadkvsPaxos.PhaseTwoReply.Builder phase_two_reponse = DadkvsPaxos.PhaseTwoReply.newBuilder();
        if(request.getPhase2Timestamp() < server_state.largest_prepare_ts || request.getPhase2Timestamp() < server_state.largest_accept_ts){
            phase_two_reponse.setPhase2Accepted(false);
        } else{
            phase_two_reponse.setPhase2Accepted(true);
            server_state.largest_accept_ts = request.getPhase2Timestamp();
            server_state.accepted_reqid = request.getPhase2Value();
        }
    }

    @Override
    public void learn(DadkvsPaxos.LearnRequest request, StreamObserver<DadkvsPaxos.LearnReply> responseObserver) {
        // for debug purposes
        System.out.println("Receive learn request: " + request);

    }

}
