package dadkvs.server;

import dadkvs.DadkvsPaxosServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

public class ConfigurationHandler {

    private final DadkvsServerState serverState;
    private int currentConfig;
    private final String[] paxosTargets;
    private DadkvsPaxosServiceGrpc.DadkvsPaxosServiceStub[] asyncPaxosStubs;


    public ConfigurationHandler(DadkvsServerState serverState){
        this.serverState = serverState;
        this.paxosTargets = new String[serverState.n_servers];
        initPaxosStubs();
    }

    private void initPaxosStubs() {
        ManagedChannel[] channels = new ManagedChannel[serverState.n_servers];

        for (int i = 0; i < serverState.n_servers; i++) {
            int target_port = serverState.base_port + i;
            paxosTargets[i] = serverState.default_host + ":" + target_port;
        }

        for (int i = 0; i < serverState.n_servers; i++) {
            channels[i] = ManagedChannelBuilder.forTarget(paxosTargets[i]).usePlaintext().build();
        }

        asyncPaxosStubs = new DadkvsPaxosServiceGrpc.DadkvsPaxosServiceStub[serverState.n_servers];

        for (int i = 0; i < serverState.n_servers; i++) {
            asyncPaxosStubs[i] = DadkvsPaxosServiceGrpc.newStub(channels[i]);
        }
    }

    public void loadConfiguration(){
        int i = serverState.current_index;
        // If we find a request that is completed before we find an incomplete reconfiguration, we can set the current configuration to what is in key 0
        // else if we find an incomplete reconfiguration, we set the current configuration to the respective configuration
        while(!serverState.previousTransactionComplete(i)){

        }
    }

    public DadkvsPaxosServiceGrpc.DadkvsPaxosServiceStub[] getCurrentConfigStubs(){
        return asyncPaxosStubs;
    }

    public int getCurrentConfig() {
        return currentConfig;
    }

    public void setCurrentConfig(int currentConfig) {
        this.currentConfig = currentConfig;
    }
}
