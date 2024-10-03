package dadkvs.util;

import java.util.ArrayList;
import java.util.List;

public class GenericResponseCollector<T> {
    List<T> collected_responses;
    int received;
    int pending;
    boolean target_reached;

    public GenericResponseCollector(List<T> responses, int maxresponses) {
        collected_responses = responses;
        received = 0;
        pending = maxresponses;
        target_reached = false;
    }

    synchronized public void addResponse(T resp) {
        if (!target_reached) {
            collected_responses.add(resp);
        }
        received++;
        pending--;
        notifyAll();
    }

    synchronized public void addNoResponse() {
        pending--;
        notifyAll();
    }

    synchronized public void waitForTarget(int target) {
        while ((pending > 0) && (received < target)) {
            try {
                wait();
            } catch (InterruptedException e) {
            }
        }
        target_reached = true;
    }

    public int getReceived() {
        return received;
    }

    public int getPending() {
        return pending;
    }
}
