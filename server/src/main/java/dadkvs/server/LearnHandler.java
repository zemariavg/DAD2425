package dadkvs.server;

import dadkvs.DadkvsPaxos;

import java.util.HashMap;

public class LearnHandler {
    private final DadkvsServerState serverState;
    private final HashMap<Integer, LearnRequestEntry> learnCountMap;

    public LearnHandler(DadkvsServerState serverState){
        this.serverState = serverState;
        this.learnCountMap = new HashMap<>();
    }

    //TODO: Maybe remove synchronized
    public synchronized void handleLearnRequest(DadkvsPaxos.LearnRequest learnRequest){
        LearnRequestEntry learnRequestEntry = learnCountMap.get(learnRequest.getLearnindex());
        int reqId = learnRequest.getLearnvalue();
        int index = learnRequest.getLearnindex();
        if(learnRequestEntry == null || learnRequest.getLearntimestamp() > learnRequestEntry.getTimestamp()){
            learnCountMap.put(index, new LearnRequestEntry(learnRequest.getLearntimestamp()));
        } else if(learnRequest.getLearntimestamp() == learnRequestEntry.getTimestamp() &&
                learnRequestEntry.increaseCount() == serverState.majority) {
                serverState.moveTransactionToLog(reqId, index);
                if(index >= serverState.current_index) {
                    serverState.accepted_reqid = -1;
                    serverState.current_index = index + 1;
                }
                executeTransaction(reqId, index);
            }

    }

    //TODO: Refactor code, looks like shit
    private void executeTransaction(int reqId, int index){
        serverState.execution_lock.lock();
        System.out.println("Executing transaction for request: " + reqId + ", index: " + index);
        while(!serverState.transactionAvailable(reqId) || !serverState.previousTransactionComplete(index)){
            try {
                if(!serverState.transaction_execution_conditions.containsKey(index))
                    serverState.transaction_execution_conditions.put(index, serverState.execution_lock.newCondition());
                serverState.transaction_execution_conditions.get(index).await();
            } catch(InterruptedException e) {
                System.out.println("Thread Interrupted");
            }
        }
        TransactionLogEntry transactionLogEntry = serverState.getTransactionLogEntry(reqId);
        TransactionRecord transactionRecord = transactionLogEntry.getTransactionRecord();
        transactionRecord.setTimestamp(index);
        boolean commitSuccessful = serverState.store.commit(transactionRecord);
        if(commitSuccessful) {
            transactionLogEntry.setCommited();
        } else {
            transactionLogEntry.setAborted();
        }
        serverState.transaction_execution_conditions.remove(index);
        serverState.completeClientRequest(reqId, commitSuccessful);
        if(serverState.transaction_execution_conditions.containsKey(index + 1))
            serverState.transaction_execution_conditions.get(index + 1).signal();
        serverState.execution_lock.unlock();
    }

}
