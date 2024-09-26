package dadkvs.server;

public class MainLoop implements Runnable {
    DadkvsServerState server_state;

    private boolean has_work;


    public MainLoop(DadkvsServerState state) {
        this.server_state = state;
        this.has_work = false;
    }

    public void run() {
        while (true)
            this.doWork();
    }

    synchronized public void doWork() {
        System.out.println("Main loop do work start");
        this.has_work = false;
        while (!this.has_work) {
            System.out.println("Main loop do work: waiting");
            try {
                wait();
            } catch (InterruptedException e) {
            }
        }
        System.out.println("Main loop do work finish");
    }

    synchronized public void wakeup() {
        this.has_work = true;
        notify();
    }
}
