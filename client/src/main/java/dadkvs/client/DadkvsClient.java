package dadkvs.client;


import dadkvs.DadkvsMain;
import dadkvs.DadkvsMainServiceGrpc;
import dadkvs.server.VersionedValue;
import dadkvs.util.CollectorStreamObserver;
import dadkvs.util.GenericResponseCollector;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Random;
import java.util.Scanner;


public class DadkvsClient {

    boolean interactive_mode;
    int key_range;
    int sleep_range;
    int loop_size;
    int n_servers;
    int client_id;
    int sequence_number;
    int responses_needed;
    Random rnd;
    String host;
    int port;

    String[] targets;
    ManagedChannel[] channels;
    DadkvsMainServiceGrpc.DadkvsMainServiceStub[] async_stubs;


    public DadkvsClient() {
        interactive_mode = false;
        key_range = 5;
        sleep_range = 5;
        loop_size = 1;
        n_servers = 5;
        client_id = 1;
        port = 8080;
        host = "localhost";
        sequence_number = 0;
        responses_needed = 1;
        rnd = new Random();
        targets = new String[n_servers];
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Starting...");

        DadkvsClient client = new DadkvsClient();

        client.main_loop(args);
    }

    private boolean doCommit(int key1, int key1_version, int key2, int key2_version, int write_key, int write_value) {
        sequence_number = sequence_number + 1;
        int reqid = sequence_number * 100 + client_id;
        boolean result = false;

        DadkvsMain.CommitRequest.Builder commit_request = DadkvsMain.CommitRequest.newBuilder();

        commit_request.setReqid(reqid)
                .setKey1(key1)
                .setVersion1(key1_version)
                .setKey2(key2)
                .setVersion2(key2_version)
                .setWritekey(write_key)
                .setWriteval(write_value);

        System.out.println("Reqid " + reqid);
        System.out.println("Read1 key " + key1 + " with version " + key1_version);
        System.out.println("Read1 key " + key2 + " with version " + key2_version);
        System.out.println("Write key " + write_key + " with value " + write_value);


        ArrayList<DadkvsMain.CommitReply> commit_responses = new ArrayList<DadkvsMain.CommitReply>();
        GenericResponseCollector<DadkvsMain.CommitReply> commit_collector = new GenericResponseCollector<DadkvsMain.CommitReply>(commit_responses, n_servers);

        for (int i = 0; i < n_servers; i++) {
            CollectorStreamObserver<DadkvsMain.CommitReply> commit_observer = new CollectorStreamObserver<DadkvsMain.CommitReply>(commit_collector);
            async_stubs[i].committx(commit_request.build(), commit_observer);
        }
        commit_collector.waitForTarget(responses_needed);
        if (commit_responses.size() >= responses_needed) {
            Iterator<DadkvsMain.CommitReply> commit_iterator = commit_responses.iterator();
            DadkvsMain.CommitReply commit_reply = commit_iterator.next();
            System.out.println("Reqid = " + reqid + " id in reply = " + commit_reply.getReqid());
            result = commit_reply.getAck();
            if (result) {
                System.out.println("Committed key " + commit_request.getWritekey() + " with value " + commit_request.getWriteval());
            } else {
                System.out.println("Commit Failed");
            }
        } else
            System.out.println("Panic...error commiting");
        return result;
    }

    private VersionedValue doRead(int key) {
        sequence_number = sequence_number + 1;
        int reqid = sequence_number * 100 + client_id;

        DadkvsMain.ReadRequest.Builder read_request = DadkvsMain.ReadRequest.newBuilder();
        ;
        ArrayList<DadkvsMain.ReadReply> read_responses = new ArrayList<DadkvsMain.ReadReply>();
        ;
        GenericResponseCollector<DadkvsMain.ReadReply> read_collector = new GenericResponseCollector<DadkvsMain.ReadReply>(read_responses, n_servers);
        ;

        read_request.setReqid(reqid).setKey(key);
        for (int i = 0; i < n_servers; i++) {
            CollectorStreamObserver<DadkvsMain.ReadReply> read_observer = new CollectorStreamObserver<DadkvsMain.ReadReply>(read_collector);
            async_stubs[i].read(read_request.build(), read_observer);
        }
        read_collector.waitForTarget(responses_needed);
        if (read_responses.size() >= responses_needed) {
            Iterator<DadkvsMain.ReadReply> read_iterator = read_responses.iterator();
            DadkvsMain.ReadReply read_reply = read_iterator.next();
            System.out.println("Reqid = " + reqid + " id in reply = " + read_reply.getReqid());
            System.out.println("read key " + read_request.getKey() + " = <" + read_reply.getValue() + "," + read_reply.getTimestamp() + ">");
            VersionedValue kv_entry = new VersionedValue(read_reply.getValue(), read_reply.getTimestamp());
            ;
            return kv_entry;
        } else {
            System.out.println("error reading");
            return null;
        }
    }

    private void doTransactions() throws Exception {
        int counter = 0;
        int committed = 0;

        System.out.println("going to run " + loop_size + " transactions with key range = " + key_range + " sleep delay range = " + sleep_range);

        while (counter < loop_size) {
            int write_key = rnd.nextInt(key_range) + 1;
            int write_value = rnd.nextInt(1000);

            // read key 1
            int read_key1 = rnd.nextInt(key_range) + 1;
            VersionedValue kv_entry1 = doRead(read_key1);
            if (kv_entry1 == null) {
                System.out.println("Panic! ..");
                return;
            }
            Thread.sleep(rnd.nextInt(sleep_range) * 1000);

            // read key 2
            int read_key2 = rnd.nextInt(key_range) + 1;
            VersionedValue kv_entry2 = doRead(read_key2);
            if (kv_entry2 == null) {
                System.out.println("Panic! ..");
                return;
            }
            Thread.sleep(rnd.nextInt(sleep_range) * 1000);


            System.out.println("Commiting transaction number " + (counter + 1));
            if (doCommit(read_key1, kv_entry1.getVersion(), read_key2, kv_entry2.getVersion(), write_key, write_value))
                committed++;
            Thread.sleep(rnd.nextInt(sleep_range) * 1000);
            counter++;
        }
        System.out.println("loop done. transactions committed = " + committed + ". transactions aborted = " + (loop_size - committed) + ".");

    }

    private void initComms() {
        // Let us use plaintext communication because we do not have certificates
        channels = new ManagedChannel[n_servers];

        for (int i = 0; i < n_servers; i++) {
            channels[i] = ManagedChannelBuilder.forTarget(targets[i]).usePlaintext().build();
        }

        async_stubs = new DadkvsMainServiceGrpc.DadkvsMainServiceStub[n_servers];

        for (int i = 0; i < n_servers; i++) {
            async_stubs[i] = DadkvsMainServiceGrpc.newStub(channels[i]);
        }
    }

    private void terminateComms() {
        for (int i = 0; i < n_servers; i++) {
            channels[i].shutdownNow();
        }
    }

    public void parseArgs(String[] args) {
        int length = args.length;
        int cursor = 0;

        if (length < 1) {
            System.err.println("Argument(s) missing!");
            System.err.printf("Usage: java %s clientid [options]%n", DadkvsClient.class.getName());
        } else {
            int id = Integer.parseInt(args[0]);
            if (id == 0)
                System.err.println("Client id=0 reserved for console, using client id=1!");
            else
                client_id = id;
        }

        cursor = 1;
        while (cursor < length) {
            String option = args[cursor];
            String[] option_parts = option.split(" ");
            String option_name = option_parts[0].toLowerCase();
            String option_parameter = option_parts.length > 1 ? option_parts[1] : null;

            switch (option_name) {
                case "--help":
                    System.out.printf("--help");
                    System.out.printf("--host hostname");
                    System.out.printf("--port portnumber");
                    System.out.printf("--range keyrange");
                    System.out.printf("--length looplength");
                    System.out.printf("--sleep sleeprange");
                    System.out.printf("-i (iterative mode)");
                    cursor++;
                    break;
                case "--port":
                    if (option_parameter == null)
                        System.err.println("missing portnumber");
                    else
                        port = Integer.parseInt(option_parameter);
                    break;
                case "--host":
                    if (option_parameter == null)
                        System.err.println("missing hostname");
                    else
                        host = option_parameter;
                    break;
                case "--range":
                    if (option_parameter == null)
                        System.err.println("missing keyrange");
                    else
                        key_range = Integer.parseInt(option_parameter);
                    break;
                case "--length":
                    if (option_parameter == null)
                        System.err.println("missing looplength");
                    else
                        loop_size = Integer.parseInt(option_parameter);
                    break;
                case "--sleep":
                    if (option_parameter == null)
                        System.err.println("missing sleeprange");
                    else
                        sleep_range = Integer.parseInt(option_parameter);
                    break;
                case "-i":
                    interactive_mode = true;
                    break;
                default:
                    System.err.println("Unknown option");
                    break;
            }
            cursor++;
        }
    }

    public void goInteractive() {

        System.out.println("Client Start. For the full command list, write \"help\"");

        Scanner scanner = new Scanner(System.in);
        String command;

        boolean keep_going = true;

        while (keep_going) {
            System.out.print("client> ");
            command = scanner.nextLine();
            String[] commandParts = command.split(" ");
            String mainCommand = commandParts[0].toLowerCase();
            String parameter1 = commandParts.length > 1 ? commandParts[1] : null;
            String parameter2 = commandParts.length > 2 ? commandParts[2] : null;
            String parameter3 = commandParts.length > 3 ? commandParts[3] : null;

            switch (mainCommand) {
                case "help":
                    System.out.println("\thelp");
                    System.out.println("\tread key");
                    System.out.println("\ttx read_key read_key write_key");
                    System.out.println("\tloop");
                    System.out.println("\trange key-range");
                    System.out.println("\tlength loop-length");
                    System.out.println("\ttime sleep-range");
                    System.out.println("\texit");
                    break;
                case "read":
                    System.out.println("read " + parameter1);
                    if (parameter1 != null) {
                        try {
                            int key = Integer.parseInt(parameter1);
                            VersionedValue kv_entry = doRead(key);
                            if (kv_entry != null)
                                System.out.println("did read " + key + " with value " + kv_entry.getValue() + " and version " + kv_entry.getVersion());
                            else
                                System.out.println("failed to read " + key);
                        } catch (NumberFormatException e) {
                            System.out.println("usage: read key");
                        }
                    } else
                        System.out.println("usage: read key");
                    break;
                case "tx":
                    System.out.println("tx reading key " + parameter1 + " and key " + parameter2 + " : writting key " + parameter3);
                    if ((parameter1 != null) && (parameter2 != null) && (parameter3 != null)) {
                        try {
                            int read_key1 = Integer.parseInt(parameter1);
                            int read_key2 = Integer.parseInt(parameter2);
                            int write_key = Integer.parseInt(parameter3);
                            if (write_key == 0)
                                System.out.println("key 0 is reserverded for reconfiguration!");
                            else {
                                int write_value = rnd.nextInt(1000);
                                VersionedValue kv_entry1 = doRead(read_key1);
                                VersionedValue kv_entry2 = doRead(read_key2);
                                if ((kv_entry1 != null) && (kv_entry2 != null))
                                    doCommit(read_key1, kv_entry1.getVersion(), read_key2, kv_entry2.getVersion(), write_key, write_value);
                                else
                                    System.out.println("failed to read keys");
                            }
                        } catch (NumberFormatException e) {
                            System.out.println("usage: read key");
                        }
                    } else
                        System.out.println("usage: tx read_key read_key write_key");
                    break;
                case "length":
                    System.out.println("length " + parameter1);
                    if (parameter1 != null) {
                        try {
                            loop_size = Integer.parseInt(parameter1);
                        } catch (NumberFormatException e) {
                            System.out.println("usage: length loop-length");
                        }
                    } else
                        System.out.println("usage: length loop-length");
                    break;
                case "range":
                    System.out.println("range " + parameter1);
                    if (parameter1 != null) {
                        try {
                            key_range = Integer.parseInt(parameter1);
                        } catch (NumberFormatException e) {
                            System.out.println("usage: range key-range");
                        }
                    } else
                        System.out.println("usage: range key-range");
                    break;
                case "time":
                    System.out.println("time " + parameter1);
                    if (parameter1 != null) {
                        try {
                            sleep_range = Integer.parseInt(parameter1);
                        } catch (NumberFormatException e) {
                            System.out.println("usage: time sleep-range");
                        }
                    } else
                        System.out.println("usage: time sleep-range");
                    break;
                case "loop":
                    try {
                        doTransactions();
                    } catch (Exception e) {
                    }
                    break;
                case "exit":
                    keep_going = false;
                    break;
                case "":
                    break;
                default:
                    System.out.println("Unknown command: " + mainCommand);
                    break;
            }
        }
    }

    public void main_loop(String[] args) throws Exception {
        System.out.println(DadkvsClient.class.getSimpleName());


        // receive and print arguments
        System.out.printf("Received %d arguments%n", args.length);
        for (int i = 0; i < args.length; i++) {
            System.out.printf("arg[%d] = %s%n", i, args[i]);
        }

        // check arguments
        this.parseArgs(args);


        System.out.println("Client id = " + client_id + " serverhost = " + host + " port = " + port);

        System.out.println("Client key_range = " + key_range + " sleep_range = " + sleep_range + " loop_size = " + loop_size);


        System.out.println("Interactive mode = " + interactive_mode);

        // set servers
        for (int i = 0; i < n_servers; i++) {
            int target_port = port + i;
            targets[i] = host + ":" + target_port;
            System.out.printf("targets[%d] = %s%n", i, targets[i]);
        }


        // init the communication stuff
        this.initComms();

        if (interactive_mode == false)
            doTransactions();
        else
            goInteractive();


        System.out.println("closing channels...");
        terminateComms();
        System.out.println("Exiting...");

    }
}



