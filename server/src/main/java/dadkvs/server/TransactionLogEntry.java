package dadkvs.server;

public class TransactionLogEntry {
    private final Integer reqId;
    private boolean wasExecuted;
    private boolean wasAborted;

    public TransactionLogEntry(Integer reqId) {
        this.reqId = reqId;
        this.wasExecuted = false;
        this.wasAborted = false;
    }

    public boolean wasAborted() {
        return wasAborted;
    }

    public void setAborted() {
        this.wasAborted = true;
        this.wasExecuted = false;
    }

    public boolean hasCompleted(){
        return this.wasAborted || this.wasExecuted;
    }

    public Integer getReqId() {
        return reqId;
    }

    public boolean wasExecuted() {
        return wasExecuted;
    }

    public void setExecuted(){
        wasExecuted = true;
        wasAborted = false;
    }
}
