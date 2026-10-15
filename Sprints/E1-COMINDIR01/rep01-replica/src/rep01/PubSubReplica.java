package rep01;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

public class PubSubReplica {
    static final String TOPIC = "rep01-temperaturas";

    public static void main(String[] args) {
        String id = args.length > 0 ? args[0] : "1";
        String port = args.length > 1 ? args[1] : "5001";
        String apiUrl = "http://127.0.0.1:" + port + "/api/v0";

        RecordFile file = new RecordFile("replica-" + id + ".txt");
        TreeMap<Long, SensorRecord> retidos = new TreeMap<>();

        try {
            IpfsPubSub pubSub = new IpfsPubSub(apiUrl);

            long ultimoInicial = file.lastSeq();
            System.out.println("Réplica PubSub " + id + " à escuta no tópico " + TOPIC
                    + " (API: " + apiUrl + ") - último aplicado: " + ultimoInicial);

            // Usamos arrays para manter o estado mutável acessível (efetivamente final) dentro da lambda
            final long[] ultimo = { ultimoInicial };
            final long[] recebidos = { 0 };
            final long[] escritos = { 0 };
            final long[] duplicados = { 0 };

            pubSub.subscribe(TOPIC, line -> {
                SensorRecord r;
                try {
                    r = SensorRecord.fromLine(line);
                } catch (IllegalArgumentException e) {
                    System.out.println("REJEITADO: " + line.trim());
                    return;
                }

                long seq = r.getSeq();
                recebidos[0]++;

                try {
                    if (seq <= ultimo[0] || retidos.containsKey(seq)) {
                        duplicados[0]++;
                        System.out.println("DUPLICADO: " + seq);
                    } else if (seq == ultimo[0] + 1) {
                        file.append(r);
                        escritos[0]++;
                        ultimo[0] = seq;
                        System.out.println("Aplicado: " + r.toLine());

                        while (!retidos.isEmpty() && retidos.firstKey() == ultimo[0] + 1) {
                            Map.Entry<Long, SensorRecord> e = retidos.pollFirstEntry();
                            file.append(e.getValue());
                            escritos[0]++;
                            ultimo[0] = e.getKey();
                            System.out.println("Aplicado (retido): " + e.getValue().toLine());
                        }
                    } else {
                        retidos.put(seq, r);
                        System.out.println("EM ESPERA: " + seq + " (falta o registo " + (ultimo[0] + 1) + ")");
                    }
                } catch (IOException e) {
                    System.out.println("IO ao escrever no ficheiro: " + e.getMessage());
                }
            });

        } catch (Exception e) {
            System.out.println("IO/Erro: " + e.getMessage());
        }
    }
}