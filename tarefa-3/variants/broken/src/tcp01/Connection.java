package tcp01;

import java.io.*;
import java.net.*;

public class Connection extends Thread {
    ObjectInputStream ois;
    DataOutputStream out;
    Socket clientSocket;
n    public Connection(Socket aClientSocket) {
        try {
            clientSocket = aClientSocket;
            ois = new ObjectInputStream(clientSocket.getInputStream());
            out = new DataOutputStream(clientSocket.getOutputStream());
            this.start();
        } catch (IOException e) {
            System.out.println("Connection: " + e.getMessage());
        }
    }
n    @Override
    public void run() {
        try {
            Object obj = ois.readObject();
            if (obj instanceof Person) {
                Person p = (Person) obj;
                String locality = "unknown";
                if (p.getPlace() != null) locality = p.getPlace().getLocality();
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
            try { clientSocket.close(); } catch (IOException e) { System.out.println("Failed to close client socket: " + e.getMessage()); }
        }
    }
}
