package tarefa3;

import java.io.*;
import java.net.*;

public class TCPServer {
    public static void main(String[] args) {
        try {
            int serverPort = 7896;
            ServerSocket listenSocket = new ServerSocket(serverPort); // bloqueia até o SO atribuir/abrir o porto
            System.out.println("TCPServer: à escuta na porta " + serverPort);
            while (true) {
                Socket clientSocket = listenSocket.accept();    // BLOQUEANTE: espera por uma ligação de cliente
                Connection c = new Connection(clientSocket);    // processa o pedido noutra thread
            }
        } catch (IOException e) {
            System.out.println("Listen: " + e.getMessage());
        }
    }
}
