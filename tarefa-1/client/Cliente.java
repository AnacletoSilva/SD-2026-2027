package client;

import java.net.*;
import java.io.*;

public class Cliente {
  public static void main(String[] args) {
    DatagramSocket aSocket = null;
    BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
    try {
      aSocket = new DatagramSocket();
      InetAddress aHost = InetAddress.getByName("localhost");
      int serverPort = 6789;

      System.out.println("Escreve mensagens no formato N, texto. Escreve 'sair' para terminar.");
      while (true) {
        System.out.print("> ");
        String line = in.readLine();
        if (line == null || line.equalsIgnoreCase("sair"))
          break;

        byte[] m = line.getBytes();
        DatagramPacket request = new DatagramPacket(m, m.length, aHost, serverPort);
        aSocket.send(request);

        byte[] buffer = new byte[1000];
        DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
        aSocket.receive(reply);

        String resposta = new String(reply.getData(), 0, reply.getLength());
        if (resposta.startsWith("error,")) {
          System.out.println("Erro: " + resposta.substring("error,".length()));
        } else if (resposta.startsWith("waitingfor,")) {
          System.out.println("Resposta: " + resposta + " (espera pela sequencia correta)");
        } else if (resposta.startsWith("echo,")) {
          System.out.println("Resposta: " + resposta + " (echo)");
        } else {
          System.out.println("Resposta: " + resposta);
        }
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
