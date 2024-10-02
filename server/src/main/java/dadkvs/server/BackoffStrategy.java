package dadkvs.server;

import java.util.Random;

public class BackoffStrategy {

    private static final Random random = new Random();

    public static void randomSleep(int minMillis, int maxMillis) {
        // Generate a random sleep time within the specified range
        int sleepTime = random.nextInt(maxMillis - minMillis + 1) + minMillis;
        try {
            Thread.sleep(sleepTime);
        } catch (InterruptedException e) {
            // Handle interruption
            Thread.currentThread().interrupt(); // Restore interrupt status
        }
    }
}
