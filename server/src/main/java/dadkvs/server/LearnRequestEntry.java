package dadkvs.server;

public class LearnRequestEntry {
    private final int timestamp;
    private int count;

    public LearnRequestEntry(int timestamp) {
        this.timestamp = timestamp;
        this.count = 1;
    }

    public int getTimestamp() {
        return timestamp;
    }

    public int increaseCount() {
        return ++count;
    }

    public int getCount(){
        return count;
    }
}
