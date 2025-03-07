// MSC.java - Server Side
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.concurrent.*;
import javax.sound.sampled.*;

public class MSC {
    private static final int PORT = 5000;
    private static final int UDP_PORT = 6000;
    private static final int CHARGE_PER_MINUTE = 5;
    private static final String CDR_FILE = "/tmp/calls.cdr";
    private static ConcurrentHashMap<String, Integer> balances = new ConcurrentHashMap<>();
    private static ConcurrentHashMap<String, Long> callStartTimes = new ConcurrentHashMap<>();
    
    public static void main(String[] args) {
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
                balances.put(msisdn, balances.getOrDefault(msisdn, 100) - CHARGE_PER_MINUTE);
                System.out.println("Deducted " + CHARGE_PER_MINUTE + " L.E from " + msisdn + ". Remaining balance: " + balances.get(msisdn));
            }
        }, 1, 1, TimeUnit.MINUTES);
    }
    
    public static void generateCDR(String msisdn, long startTime, long endTime) {
        long duration = (endTime - startTime) / 60000;
        int cost = (int) duration * CHARGE_PER_MINUTE;
        int remainingBalance = balances.getOrDefault(msisdn, 100);
        
        String cdrEntry = String.format("%s, %s, %s, %d, Normal Call Clearing, %d, %d\n",
                msisdn, Instant.ofEpochMilli(startTime), Instant.ofEpochMilli(endTime), duration, cost, remainingBalance);
        
        try {
            Files.write(Paths.get(CDR_FILE), cdrEntry.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            System.out.println("CDR Generated: " + cdrEntry);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    public static void addCallStartTime(String msisdn) {
    callStartTimes.put(msisdn, System.currentTimeMillis());
    }

    public static long removeCallStartTime(String msisdn) {
    return callStartTimes.remove(msisdn);
    }

}

class CallHandler implements Runnable {
    private Socket clientSocket;

    public CallHandler(Socket socket) {
        this.clientSocket = socket;
    }

    @Override
    public void run() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()))) {
            String message;
            while ((message = in.readLine()) != null) {
                if (message.startsWith("START_CALL")) {
                    String msisdn = message.split(" ")[1];
                    System.out.println("Accept Voice call start signaling message from MSISDN " + msisdn);
                    MSC.addCallStartTime(msisdn);
                } else if (message.startsWith("END_CALL")) {
                    String msisdn = message.split(" ")[1];
                    long startTime = MSC.removeCallStartTime(msisdn);
                    long endTime = System.currentTimeMillis();
                    MSC.generateCDR(msisdn, startTime, endTime);
                    break;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
