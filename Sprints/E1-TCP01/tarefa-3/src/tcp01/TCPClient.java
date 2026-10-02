package tcp01;

import java.io.*;
import java.net.*;

public class TCPClient {
    public static void main(String[] args) {
        Socket s = null;
        try {
            int serverPort = 7896;                              // porto do servidor
            s = new Socket("localhost", serverPort);          // pode bloquear/lançar exception se não houver servidor

            // Enviar um objeto Person usando ObjectOutputStream
            ObjectOutputStream oos = new ObjectOutputStream(s.getOutputStream());
            DataInputStream in = new DataInputStream(s.getInputStream());

            // Criar um exemplo de pessoa com Place e enviar
            Person p = new Person("Alice", new Place("1000-000", "Lisboa"), 1990);
            oos.writeObject(p);
            oos.flush();

            // Ler resposta de texto do servidor
            String data = in.readUTF();                         // BLOQUEANTE: espera até chegar uma string UTF completa ou EOF
            System.out.println("Received: " + data);
        } catch (UnknownHostException e) {
            System.out.println("Sock: " + e.getMessage());
        } catch (EOFException e) {
            System.out.println("EOF: " + e.getMessage());
        } catch (IOException e) {
            System.out.println("IO: " + e.getMessage());
        } finally {
            if (s != null) {
                try {
                    s.close();
                } catch (IOException e) {
                    System.out.println("close: " + e.getMessage());
                }
            }
        }
    }
}
