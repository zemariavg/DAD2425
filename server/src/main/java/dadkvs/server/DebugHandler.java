package dadkvs.server;

public class DebugHandler {

    public DebugHandler() {
    }

    public synchronized void runDebug(int debug_mode, boolean isClientService){
        switch (debug_mode) {
            case 2:
                System.out.println("Server is frozen, not processing commit request.");
                try {
                    if(isClientService)
                        wait();
                } catch (InterruptedException e) {
                    System.out.println("Thread interrupted");
                }
                break;
            case 4:
                try {
                    System.out.println("Slow-mode on delaying processing.");
                    long delay = (long) (Math.random() * 1000);  // Random delay up to 1 second
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                break;
        }
    }

    public synchronized void wakeUpFrozenRequests(){
        System.out.println("Waking up frozen requests");
        notifyAll();
    }
}
