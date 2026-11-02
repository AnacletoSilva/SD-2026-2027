package rep03;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

public class MulticastLeader {
    static final String GROUP = "230.0.0.1";
    static final int PORT = 6789;

    public static void main(String[] args) {
        try (MulticastSocket socket = new MulticastSocket();
             Scanner sc = new Scanner(System.in)) {
            InetAddress group = InetAddress.getByName(GROUP);
            socket.setTimeToLive(1); // o datagrama nÃ£o sai da rede local
            RecordFile file = new RecordFile("lider.txt");
            long seq = file.lastSeq(); // continua a numeraÃ§Ã£o apÃ³s reinÃ­cio
            System.out.println("LÃ­der pronto. Formato: <sensor> <temperatura> | 'sair' para terminar");
            while (true) {
                System.out.print("> ");
                if (!sc.hasNextLine()) break; // bloqueia Ã  espera do utilizador
                String line = sc.nextLine().trim();
                if (line.equalsIgnoreCase("sair")) break;
                if (line.startsWith("rajada")) {
                    String[] pLine = line.split("\\s+");
                    if (pLine.length != 2) {
                        System.out.println("Formato invÃ¡lido. Use: rajada <n>");
                        continue;
                    }
                    int n;
                    try {
                        n = Integer.parseInt(pLine[1]);
                        if (n <= 0) {
                            System.out.println("O nÃºmero deve ser inteiro positivo.");
                            continue;
                        }
                    } catch (NumberFormatException e) {
                        System.out.println("NÃºmero invÃ¡lido: " + pLine[1]);
                        continue;
                    }

                    long inicio = 0;
                    long fim = 0;

                    for (int i = 0; i < n; i++) {
                        double temp = 15.0 + Math.random() * 15.0;
                        SensorRecord r = SensorRecord.now(++seq, "SIM", temp);
                        file.append(r);
                        byte[] m = r.toLine().getBytes(StandardCharsets.UTF_8);

                        if (i == 0) {
                            inicio = System.currentTimeMillis();
                        }
                        socket.send(new DatagramPacket(m, m.length, group, PORT));
                        if (i == n - 1) {
                            fim = System.currentTimeMillis();
                        }
                    }
                    System.out.println("Rajada concluÃ­da. Tempo de envio: " + (fim - inicio) + " ms");
                    continue;
                }
                String[] p = line.split("\\s+");
                if (p.length != 2) {
                    System.out.println("Formato invÃ¡lido.");
                    continue;
                }
                double temp;
                try {
                    temp = Double.parseDouble(p[1]);
                } catch (NumberFormatException e) {
                    System.out.println("Temperatura invÃ¡lida: " + p[1]);
                    continue;
                }
                SensorRecord r = SensorRecord.now(++seq, p[0], temp);
                file.append(r); // 1) escreve localmente
                byte[] m = r.toLine().getBytes(StandardCharsets.UTF_8);
                socket.send(new DatagramPacket(m, m.length, group, PORT)); // 2) propaga para o grupo
                System.out.println("Registado e enviado: " + r.toLine());
            }
        } catch (IOException e) {
            System.out.println("IO: " + e.getMessage());
        }
    }
}
