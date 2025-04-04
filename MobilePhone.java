import java.io.*;
import java.net.*;
import javax.sound.sampled.*;

public class MobilePhone {
    private static final String MSC_IP = "127.0.0.1";
    private static final int MSC_PORT = 5000;
    private static final int UDP_PORT = 6000;
    private static String msisdn;
    private static Socket socket;
    private static PrintWriter out;
    private static BufferedReader in;
    private static DatagramSocket udpSocket;
    private static volatile boolean callActive = false;
    private static Thread voiceThread; // Store the voice thread reference
    
    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("Usage: java MobilePhone <MSISDN>");
            return;
        }
        
        msisdn = args[0];
        try {
            socket = new Socket(MSC_IP, MSC_PORT);
            out = new PrintWriter(socket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            
            out.println("START_CALL " + msisdn);
            System.out.println("Requesting voice call as MSISDN " + msisdn);
            
            String response = in.readLine();
            
            if (response != null) {
                if (response.equals("CALL_ACCEPTED")) {
                    callActive = true;
                    System.out.println("Call accepted by MSC. Starting voice streaming...");
                    
                    udpSocket = new DatagramSocket();
                    voiceThread = new Thread(MobilePhone::startVoiceStreaming);
                    voiceThread.start();
                    
                    Thread messageListener = new Thread(MobilePhone::listenForServerMessages);
                    messageListener.start();
                    
                    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                        endCall();
                    }));
                    
                    messageListener.join();
                    if (voiceThread != null) {
                        voiceThread.join(); // Ensure voice thread completes
                    }
                } else if (response.startsWith("CALL_REJECTED")) {
                    String reason = response.substring("CALL_REJECTED ".length());
                    System.out.println("Call rejected: " + reason);
                    socket.close();
                } else {
                    System.out.println("Unexpected response from server: " + response);
                    socket.close();
                }
            } else {
                System.out.println("No response received from server");
                socket.close();
            }
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        } finally {
            cleanupResources();
        }
    }
    
    private static void listenForServerMessages() {
        try {
            String message;
            while ((message = in.readLine()) != null) {
                if (message.startsWith("TERMINATE_CALL")) {
                    String reason = message.substring("TERMINATE_CALL ".length());
                    System.out.println("Call terminated by server: " + reason);
                    cleanupCall();
                    return; // Exit the listener thread
                } else if (message.equals("CALL_ENDED")) {
                    System.out.println("Call ended normally");
                    cleanupCall();
                    return; // Exit the listener thread
                }
            }
        } catch (IOException e) {
            System.out.println("Connection to server lost - call terminated");
            cleanupCall();
        }
    }
    
    private static void cleanupCall() {
        callActive = false;
        if (udpSocket != null && !udpSocket.isClosed()) {
            udpSocket.close();
        }
    }
    
    private static void endCall() {
        if (callActive) {
            callActive = false;
            out.println("END_CALL " + msisdn);
            System.out.println("Call ended. Sent END_CALL signal.");
            cleanupResources();
        }
    }
    
    private static void cleanupResources() {
        callActive = false;
        try {
            if (udpSocket != null && !udpSocket.isClosed()) {
                udpSocket.close();
            }
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
            if (out != null) {
                out.close();
            }
            if (in != null) {
                in.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    private static void startVoiceStreaming() {
        TargetDataLine microphone = null;
        try {
            AudioFormat format = new AudioFormat(8000.0f, 16, 1, true, true);
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
            microphone = (TargetDataLine) AudioSystem.getLine(info);
            microphone.open(format);
            microphone.start();
            
            byte[] buffer = new byte[1024];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, InetAddress.getByName(MSC_IP), UDP_PORT);
            
            while (callActive && !Thread.currentThread().isInterrupted()) {
                int bytesRead = microphone.read(buffer, 0, buffer.length);
                if (bytesRead > 0 && udpSocket != null && !udpSocket.isClosed()) {
                    udpSocket.send(packet);
                }
            }
        } catch (Exception e) {
            if (!(e instanceof LineUnavailableException && !callActive)) {
                e.printStackTrace();
            }
        } finally {
            if (microphone != null) {
                microphone.stop();
                microphone.close();
            }
            if (udpSocket != null && !udpSocket.isClosed()) {
                udpSocket.close();
            }
        }
    }
}