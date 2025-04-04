import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sound.sampled.*;

public class MSC {
    private static final int PORT = 5000;
    private static final int UDP_PORT = 6000;
    private static final int CHARGE_PER_MINUTE = 5;
    private static final String CDR_FILE = "/tmp/calls.cdr";
    
    private static Map<String, Integer> predefinedBalances = new HashMap<>();
    private static ConcurrentHashMap<String, Integer> balances = new ConcurrentHashMap<>();
    private static ConcurrentHashMap<String, Long> callStartTimes = new ConcurrentHashMap<>();
    private static ConcurrentHashMap<String, Socket> clientSockets = new ConcurrentHashMap<>();
    
    static {
        predefinedBalances.put("01001234567", 100);
        predefinedBalances.put("01112345678", 50);
        predefinedBalances.put("01223456789", 0);
        predefinedBalances.put("01334567890", 10);
        predefinedBalances.put("01445678901", 500);
        predefinedBalances.put("01556789012", 1000);
    }
    
    public static void main(String[] args) {
        for (Map.Entry<String, Integer> entry : predefinedBalances.entrySet()) {
            balances.put(entry.getKey(), entry.getValue());
        }
        
        new Thread(MSC::startTCPServer).start();
        new Thread(MSC::startUDPServer).start();
        new Thread(MSC::startChargingService).start();
    }
    
    private static void startTCPServer() {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("MSC Server is running and waiting for connections...");
            while (true) {
                Socket clientSocket = serverSocket.accept();
                new Thread(new CallHandler(clientSocket)).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    private static void startUDPServer() {
        try (DatagramSocket udpSocket = new DatagramSocket(UDP_PORT)) {
            System.out.println("MSC UDP Server is ready to receive audio...");
            byte[] buffer = new byte[1024];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            
            AudioFormat format = new AudioFormat(8000.0f, 16, 1, true, true);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            SourceDataLine speakers = (SourceDataLine) AudioSystem.getLine(info);
            speakers.open(format);
            speakers.start();
            
            while (true) {
                udpSocket.receive(packet);
                speakers.write(packet.getData(), 0, packet.getLength());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private static void startChargingService() {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(() -> {
            for (String msisdn : callStartTimes.keySet()) {
                int currentBalance = balances.getOrDefault(msisdn, 0);
                int newBalance = currentBalance - CHARGE_PER_MINUTE;
                
                if (newBalance <= 0) {
                    newBalance = 0;
                    balances.put(msisdn, newBalance);
                    System.out.println("Insufficient balance for " + msisdn + ". Call will be terminated.");
                    terminateCallDueToInsufficientBalance(msisdn);
                } else {
                    balances.put(msisdn, newBalance);
                    System.out.println("Deducted " + CHARGE_PER_MINUTE + " L.E from " + msisdn + ". Remaining balance: " + newBalance);
                }
            }
        }, 1, 1, TimeUnit.MINUTES);
    }
    
    private static void terminateCallDueToInsufficientBalance(String msisdn) {
        try {
            Long startTime = callStartTimes.get(msisdn);
            if (startTime != null) {
                long endTime = System.currentTimeMillis();
                generateCDR(msisdn, startTime, endTime, "Insufficient Balance");
            }
            
            Socket clientSocket = clientSockets.remove(msisdn);
            if (clientSocket != null && !clientSocket.isClosed()) {
                try (PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)) {
                    out.println("TERMINATE_CALL Insufficient Balance");
                    Thread.sleep(1000);
                    clientSocket.close();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            callStartTimes.remove(msisdn); // Remove after successful termination
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    public static void generateCDR(String msisdn, long startTime, long endTime) {
        generateCDR(msisdn, startTime, endTime, "Normal Call Clearing");
    }
    
    public static void generateCDR(String msisdn, long startTime, long endTime, String cause) {
        long duration = (endTime - startTime) / 60000;
        int cost = (int) duration * CHARGE_PER_MINUTE;
        int remainingBalance = balances.getOrDefault(msisdn, 0);
        
        String cdrEntry = String.format("%s, %s, %s, %d, %s, %d, %d\n",
                msisdn, Instant.ofEpochMilli(startTime), Instant.ofEpochMilli(endTime), 
                duration, cause, cost, remainingBalance);
        
        try {
            Files.write(Paths.get(CDR_FILE), cdrEntry.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            System.out.println("CDR Generated: " + cdrEntry);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    public static String startCall(String msisdn, Socket clientSocket) {
        if (!balances.containsKey(msisdn)) {
            System.out.println("Unknown MSISDN: " + msisdn + ". Call rejected.");
            return "Unknown MSISDN";
        }
        
        int balance = balances.get(msisdn);
        if (balance <= 0) {
            System.out.println("Call rejected for " + msisdn + " due to zero balance");
            return "Insufficient Balance";
        }
        
        clientSockets.put(msisdn, clientSocket);
        callStartTimes.put(msisdn, System.currentTimeMillis());
        System.out.println("Call started for " + msisdn + " with balance " + balance);
        return "Accepted";
    }

    public static Long removeCallStartTime(String msisdn) {
        clientSockets.remove(msisdn);
        Long startTime = callStartTimes.remove(msisdn);
        return startTime != null ? startTime : System.currentTimeMillis(); // Return current time if null
    }
}

class CallHandler implements Runnable {
    private Socket clientSocket;

    public CallHandler(Socket socket) {
        this.clientSocket = socket;
    }

    @Override
    public void run() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
             PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)) {
            
            String message;
            while ((message = in.readLine()) != null) {
                if (message.startsWith("START_CALL")) {
                    String msisdn = message.split(" ")[1];
                    System.out.println("Received Voice call start signaling message from MSISDN " + msisdn);
                    
                    String callStatus = MSC.startCall(msisdn, clientSocket);
                    switch(callStatus) {
                        case "Accepted":
                            out.println("CALL_ACCEPTED");
                            break;
                        case "Unknown MSISDN":
                            out.println("CALL_REJECTED Unknown MSISDN");
                            break;
                        case "Insufficient Balance":
                            out.println("CALL_REJECTED Insufficient Balance");
                            break;
                        default:
                            out.println("CALL_REJECTED Internal Error");
                            break;
                    }
                } else if (message.startsWith("END_CALL")) {
                    String msisdn = message.split(" ")[1];
                    Long startTime = MSC.removeCallStartTime(msisdn);
                    if (startTime != null) {
                        long endTime = System.currentTimeMillis();
                        MSC.generateCDR(msisdn, startTime, endTime);
                        out.println("CALL_ENDED");
                    }
                    break;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}