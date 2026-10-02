import java.net.*;
import java.io.*;

public class UDPClient {
  public static void main(String[] args) {
    DatagramSocket aSocket = null;
    BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
    try {
      aSocket = new DatagramSocket();
      InetAddress aHost = InetAddress.getByName("localhost");
      int serverPort = 6789;
      System.out.println("Escreve mensagens. Escreve 'sair' para terminar.");
      while (true) {
        System.out.print("> ");
        String line = in.readLine();
        if (line == null)
          break;
        if (line.equalsIgnoreCase("sair"))
          break;
        byte[] m = line.getBytes();
        DatagramPacket request = new DatagramPacket(m, m.length, aHost, serverPort);
        aSocket.send(request);
        byte[] buffer = new byte[1000];
        DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
        aSocket.receive(reply);
        String echoed = new String(reply.getData(), 0, reply.getLength());
        System.out.println("Reply: " + echoed);
      }
    } catch (SocketException e) {
      System.out.println("Socket: " + e.getMessage());
    } catch (IOException e) {
      System.out.println("IO: " + e.getMessage());
    } finally {
      if (aSocket != null)
        aSocket.close();
    }
  }
}
