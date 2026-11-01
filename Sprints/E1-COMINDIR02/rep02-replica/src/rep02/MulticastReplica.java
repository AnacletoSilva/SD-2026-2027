package rep02;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

public class MulticastReplica {
    static final String GROUP = "230.0.0.1";
    static final int PORT = 6789;

    public static void main(String[] args) {
        String id = args.length > 0 ? args[0] : "1";
        RecordFile file = new RecordFile("replica-" + id + ".txt");
        // registos recebidos fora de ordem, ordenados pelo número de sequência
        TreeMap<Long, SensorRecord> retidos = new TreeMap<>();

        try (MulticastSocket socket = new MulticastSocket(PORT)) {
            InetAddress group = InetAddress.getByName(GROUP);
            socket.joinGroup(group);

            long ultimo = file.lastSeq(); // último seq aplicado (0 se não há ficheiro)
            System.out.println("Réplica " + id + " à escuta em " + GROUP + ":" + PORT
                    + " (último aplicado: " + ultimo + ")");

            // Contadores exigidos
            long recebidos = 0;
            long escritos = 0;
            long duplicados = 0;

            byte[] buffer = new byte[1000];
            boolean aReceberRajada = false;

            while (true) {
                DatagramPacket p = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(p);
                } catch (SocketTimeoutException e) {
                    // Só dispara se já estivéssemos a receber a rajada e ela parou
                    System.out.println("\n=== Relatório da Rajada (Timeout detetado) ===");
                    System.out.println("Recebidos: " + recebidos);
                    System.out.println("Escritos: " + escritos);
                    System.out.println("Retidos (em memória): " + retidos.size());
                    System.out.println("Duplicados: " + duplicados);
                    break;
                }

                // Assim que recebe o primeiro pacote, ativa o timeout de 3 segundos para futuras esperas
                if (!aReceberRajada) {
                    aReceberRajada = true;
                    socket.setSoTimeout(3000);
                }

                recebidos++;
                String line = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);

                // 1) validar: um datagrama inválido não termina a réplica
                SensorRecord r;
                try {
                    r = SensorRecord.fromLine(line);
                } catch (IllegalArgumentException e) { // inclui NumberFormatException
                    System.out.println("REJEITADO: " + line.trim());
                    continue;
                }

                long seq = r.getSeq();

                if (seq <= ultimo || retidos.containsKey(seq)) {
                    // 2) duplicado (já aplicado ou já retido)
                    duplicados++;
                    System.out.println("DUPLICADO: " + seq);
                } else if (seq == ultimo + 1) {
                    // 3) é o próximo esperado: escreve e depois esvazia os retidos em sequência
                    file.append(r);
                    escritos++;
                    ultimo = seq;
                    System.out.println("Aplicado: " + r.toLine());

                    while (!retidos.isEmpty() && retidos.firstKey() == ultimo + 1) {
                        Map.Entry<Long, SensorRecord> e = retidos.pollFirstEntry();
                        file.append(e.getValue());
                        escritos++;
                        ultimo = e.getKey();
                        System.out.println("Aplicado (retido): " + e.getValue().toLine());
                    }
                } else {
                    // 4) seq > ultimo + 1: há um buraco, retém em memória
                    retidos.put(seq, r);
                    System.out.println("EM ESPERA: " + seq + " (falta o registo " + (ultimo + 1) + ")");
                }
            }
        } catch (IOException e) {
            System.out.println("IO: " + e.getMessage());
        }
    }
}