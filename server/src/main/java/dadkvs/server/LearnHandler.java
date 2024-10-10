package dadkvs.server;

import dadkvs.DadkvsPaxos;

import java.util.HashMap;

public class LearnHandler {
    private final DadkvsServerState serverState;
    private final HashMap<Integer, LearnRequestEntry> learnCountMap;

    public LearnHandler(DadkvsServerState serverState) {
        this.serverState = serverState;
        this.learnCountMap = new HashMap<>();
    }

    public synchronized void handleLearnRequest(DadkvsPaxos.LearnRequest learnRequest) {
        LearnRequestEntry learnRequestEntry = learnCountMap.get(learnRequest.getLearnindex());
        int reqId = learnRequest.getLearnvalue();
        int index = learnRequest.getLearnindex();
        if (learnRequestEntry == null || learnRequest.getLearntimestamp() > learnRequestEntry.getTimestamp()) {
            learnCountMap.put(index, new LearnRequestEntry(learnRequest.getLearntimestamp()));
        } else if (learnRequest.getLearntimestamp() == learnRequestEntry.getTimestamp() &&
                learnRequestEntry.increaseCount() == serverState.majority) {
            System.out.println("LEARNER COUNT: " + learnRequestEntry.getCount() + " TIMESTAMP: " + learnRequest.getLearntimestamp());
            //if (serverState.isIndexEmpty(index)) {
                System.out.println("MOVING REQ TO LOG: req-" + reqId + " index- " + index);
                serverState.moveTransactionToLog(reqId, index);
                Thread executionWorker = new Thread(() -> executeTransaction(reqId, index));
                executionWorker.start();
            //}
            serverState.clearAcceptedValue(learnRequest.getLearnindex());
        }

    }

    private void executeTransaction(int reqId, int index) {
        System.out.println("Executing transaction for request: " + reqId + ", index: " + index);
        try {
            serverState.execution_lock.lock();
            verifyExecution(reqId, index);
            TransactionLogEntry transactionLogEntry = serverState.getTransactionLogEntry(reqId);
            TransactionRecord transactionRecord = transactionLogEntry.getTransactionRecord();
            transactionRecord.setTimestamp(index);
            boolean commitSuccessful = serverState.store.commit(transactionRecord);
            if (commitSuccessful) {
                transactionLogEntry.setCommited();
            } else {
                transactionLogEntry.setAborted();
            }
            serverState.transaction_execution_conditions.remove(index);
            serverState.completeClientRequest(reqId, commitSuccessful);

            int nextPendingRequest = serverState.getValueFromLog(index + 1);
            if (nextPendingRequest != -1 && serverState.transaction_execution_conditions.containsKey(nextPendingRequest))
                serverState.transaction_execution_conditions.get(nextPendingRequest).signal();

        } finally {
            serverState.execution_lock.unlock();
        }

    }

    private void verifyExecution(int reqId, int index){
        while (!serverState.transactionAvailable(reqId) || !serverState.previousTransactionComplete(index)) {
            try {
                if (!serverState.transaction_execution_conditions.containsKey(reqId)) {
                    serverState.transaction_execution_conditions.put(reqId, serverState.execution_lock.newCondition());
                }
                serverState.transaction_execution_conditions.get(reqId).await();
            } catch (InterruptedException e) {
                System.out.println("Thread Interrupted");
            }
        }
    }

}
