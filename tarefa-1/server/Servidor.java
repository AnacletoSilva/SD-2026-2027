package server;

import java.net.*;
import java.io.*;

public class Servidor {
  public static void main(String[] args) {
    DatagramSocket aSocket = null;
    int L = 0;
    try {
      aSocket = new DatagramSocket(6789);
      byte[] buffer = new byte[1000];
      System.out.println("Servidor UDP à escuta na porta 6789...");
      while (true) {
        DatagramPacket request = new DatagramPacket(buffer, buffer.length);
        aSocket.receive(request);

        String msg = new String(request.getData(), 0, request.getLength());
        System.out.println("Recebido de " + request.getAddress() + ":" + request.getPort() + " -> " + msg);

        int posVirgula = msg.indexOf(',');
        String resposta;
        if (posVirgula == -1) {
          System.out.println("Mensagem mal formada: sem virgula");
          resposta = "error,formato invalido: esperado N, texto";
        } else {
          String numeroStr = msg.substring(0, posVirgula).trim();
          String texto = msg.substring(posVirgula + 1).trim();

          if (texto.isEmpty()) {
            System.out.println("Mensagem mal formada: texto vazio");
            resposta = "error,texto vazio";
          } else {
            try {
              int N = Integer.parseInt(numeroStr);
              if (N == L + 1) {
                resposta = "echo," + texto;
                L = N;
                System.out.println("Aceitei em ordem. Agora L = " + L);
              } else {
                resposta = "waitingfor," + (L + 1);
                System.out.println("Fora de ordem. Resposta: " + resposta + " | L = " + L);
              }
            } catch (NumberFormatException e) {
              System.out.println("Mensagem mal formada: numero invalido");
              resposta = "error,numero invalido";
            }
          }
        }

        byte[] replyData = resposta.getBytes();
        DatagramPacket reply = new DatagramPacket(
          replyData,
          replyData.length,
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
