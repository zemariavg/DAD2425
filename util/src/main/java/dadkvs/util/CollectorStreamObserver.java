package dadkvs.util;

import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;
import io.grpc.stub.StreamObserver;

import java.util.List;

public class CollectorStreamObserver<T> implements StreamObserver<T> {

    dadkvs.util.GenericResponseCollector collector;
    boolean done;

    public CollectorStreamObserver(GenericResponseCollector c) {
        collector = c;
        done = false;
    }

    @Override
    public void onNext(T value) {
        // Handle the received response of type T
        System.out.println("Received response: ");
        printMessageFields((Message) value);
        if (done == false) {
            collector.addResponse(value);
            done = true;
        }
    }

    @Override
    public void onError(Throwable t) {
        // Handle error
        System.err.println("Error occurred: " + t.getMessage());
        if (done == false) {
            collector.addNoResponse();
            done = true;
        }
    }

    @Override
    public void onCompleted() {
        // Handle stream completion
        System.out.println("Stream completed");
        if (done == false) {
            collector.addNoResponse();
            done = true;
        }
    }

    public static void printMessageFields(Message message) {
        // Get the descriptor for the message
        List<Descriptors.FieldDescriptor> fields = message.getDescriptorForType().getFields();
        // Iterate over each field and print its name and value
        for (Descriptors.FieldDescriptor field : fields) {
            // Get the value of the field
            Object value = message.getField(field);
            // Print the field name and value
            System.out.println(field.getName() + ": " + value);
        }
        System.out.println();
    }
}
