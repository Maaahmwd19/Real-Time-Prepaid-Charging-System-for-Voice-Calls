// MobilePhone.java - Client Side
import java.io.*;
import java.net.*;
import javax.sound.sampled.*;

public class MobilePhone {
    private static final String MSC_IP = "127.0.0.1"; // MSC Server IP
    private static final int MSC_PORT = 5000; // TCP Port for signaling
    private static final int UDP_PORT = 6000; // UDP Port for voice streaming
    private static String msisdn;
    private static Socket socket;
    private static PrintWriter out;
    private static DatagramSocket udpSocket;
    
    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("Usage: java MobilePhone <MSISDN>");
            return;
        }
        
        msisdn = args[0];
        try {
            socket = new Socket(MSC_IP, MSC_PORT);
            out = new PrintWriter(socket.getOutputStream(), true);
            
            // Send Start Call signaling message
            out.println("START_CALL " + msisdn);
            System.out.println("Starting voice call as MSISDN " + msisdn);
            
            // Start UDP voice streaming
            udpSocket = new DatagramSocket();
            new Thread(MobilePhone::startVoiceStreaming).start();
            
            // Add shutdown hook to send End Call signal
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                out.println("END_CALL " + msisdn);
                System.out.println("Call ended. Sent END_CALL signal.");
                udpSocket.close();
            }));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    private static void startVoiceStreaming() {
        try {
            AudioFormat format = new AudioFormat(8000.0f, 16, 1, true, true);
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
            TargetDataLine microphone = (TargetDataLine) AudioSystem.getLine(info);
            microphone.open(format);
            microphone.start();
            
            byte[] buffer = new byte[1024];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, InetAddress.getByName(MSC_IP), UDP_PORT);
            
            while (true) {
                int bytesRead = microphone.read(buffer, 0, buffer.length);
                if (bytesRead > 0) {
                    udpSocket.send(packet);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

