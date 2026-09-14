import java.net.*;
import java.io.*;

public class Servidor {
  public static void main(String[] args) {
    DatagramSocket aSocket = null;
    try {
      aSocket = new DatagramSocket(6789);
      byte[] buffer = new byte[1000];
      System.out.println("Servidor UDP à escuta na porta 6789...");
      while (true) {
        DatagramPacket request = new DatagramPacket(buffer, buffer.length);
        aSocket.receive(request);
        String msg = new String(request.getData(), 0, request.getLength());
        System.out.println("Recebido de " + request.getAddress() + ":" + request.getPort() + " -> " + msg);
        DatagramPacket reply = new DatagramPacket(
          request.getData(),
          request.getLength(),
          request.getAddress(),
          request.getPort()
        );
        aSocket.send(reply);
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
