package tarefa3;

import java.io.*;
import java.net.*;

public class Connection extends Thread {
    ObjectInputStream ois;              // para ler objetos enviados pelo cliente
    DataOutputStream out;               // para enviar resposta em texto
    Socket clientSocket;

    public Connection(Socket aClientSocket) {
        try {
            clientSocket = aClientSocket;
            // Como só o cliente envia objetos, o servidor cria aqui o ObjectInputStream
            ois = new ObjectInputStream(clientSocket.getInputStream());
            out = new DataOutputStream(clientSocket.getOutputStream());
            this.start();                                       // executa run() numa thread separada
        } catch (IOException e) {
            System.out.println("Connection: " + e.getMessage());
        }
    }

    @Override
    public void run() {
        try {
            // Ler um objeto enviado pelo cliente (bloqueante até o ObjectOutputStream do cliente escrever o cabeçalho)
            Object obj = ois.readObject();
            if (obj instanceof Person) {
                Person p = (Person) obj; // cast seguro após verificação
                String locality = "unknown";
                if (p.getPlace() != null) locality = p.getPlace().getLocality();
                // responder com a localidade da pessoa (prova de serialização do Place)
                out.writeUTF(locality);
            } else {
                out.writeUTF("error,unexpected object");
            }
        } catch (EOFException e) {
            System.out.println("EOF: " + e.getMessage());
        } catch (ClassNotFoundException e) {
            System.out.println("Class not found when reading object: " + e.getMessage());
            try { out.writeUTF("error,ClassNotFound"); } catch (IOException ignored) {}
        } catch (IOException e) {
            System.out.println("IO: " + e.getMessage());
        } finally {
            try {
                clientSocket.close(); // fechar socket do cliente aqui para libertar recursos
            } catch (IOException e) {
                System.out.println("Failed to close client socket: " + e.getMessage());
            }
        }
    }
}
