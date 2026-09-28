package tcp01;

import java.io.*;
import java.net.*;
npublic class TCPClient {
    public static void main(String[] args) {
        Socket s = null;
        try {
            int serverPort = 7896;
            s = new Socket("localhost", serverPort);
n            ObjectOutputStream oos = new ObjectOutputStream(s.getOutputStream());
            DataInputStream in = new DataInputStream(s.getInputStream());
n            Person p = new Person("Alice", new Place("1000-000", "Lisboa"), 1990);
            oos.writeObject(p);
            oos.flush();
n            String data = in.readUTF();
            System.out.println("Received: " + data);
        } catch (UnknownHostException e) {
            System.out.println("Sock: " + e.getMessage());
        } catch (EOFException e) {
            System.out.println("EOF: " + e.getMessage());
        } catch (IOException e) {
            System.out.println("IO: " + e.getMessage());
        } finally {
            if (s != null) {
                try { s.close(); } catch (IOException e) { System.out.println("close: " + e.getMessage()); }
            }
        }
    }
}
