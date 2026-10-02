package server;

import java.net.*;
import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Servidor {
  private static List<String> rececao = new ArrayList<>();
  private static Map<Integer, String> temporarias = new HashMap<>();


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
              int lastBefore = L;
              int rececaoAntes = rececao.size();
              L = processDeliveredMessages(L, N, texto);
              if (L != lastBefore) {
                resposta = "echo," + texto;
              } else {
                resposta = "waitingfor," + (L + 1);
              }
              System.out.println("Última mensagem em ordem: " + L);
              System.out.println("Estrutura temporária: " + temporarias);
              System.out.println("Número de mensagens entregues neste passo: "
                      + (rececao.size() - rececaoAntes));
              System.out.println("Mensagens entregues neste passo: "
                      + rececao.subList(rececaoAntes, rececao.size()));
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

  public static int processDeliveredMessages(
          int nLastMessageInOrder,
          int nCurrentMessage,
          String currentMessage
  ) {
    if (nCurrentMessage == nLastMessageInOrder + 1){
      rececao.add(currentMessage);
      nLastMessageInOrder = nCurrentMessage;

      while (temporarias.containsKey(nLastMessageInOrder + 1)) {
        int proximaMensagem = nLastMessageInOrder + 1;
        String mensagem = temporarias.remove(proximaMensagem);
        rececao.add(mensagem);
        nLastMessageInOrder = proximaMensagem;
      }
    } else if (nCurrentMessage > nLastMessageInOrder + 1) {
      temporarias.put(nCurrentMessage, currentMessage);

    } else {
      System.out.println("Lista de rececao completa: " + rececao);
      System.out.println("Estrutura temporaria final: " + temporarias);
      return nLastMessageInOrder;
    }

    return nLastMessageInOrder;
  }

}
