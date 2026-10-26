package rep02;

import java.io.IOException;
import java.util.Scanner;

public class PubSubLeader {
    static final String TOPIC = "rep01-temperaturas";

    public static void main(String[] args) {
        String apiUrl = (args.length > 0) ? args[0] : "http://127.0.0.1:5001/api/v0";

        try (Scanner sc = new Scanner(System.in)) {
            IpfsPubSub pubSub = new IpfsPubSub(apiUrl);
            RecordFile file = new RecordFile("lider.txt");
            long seq = file.lastSeq();

            System.out.println("Líder PubSub pronto. Tópico: " + TOPIC);
            System.out.println("Formato: <sensor> <temperatura> | 'rajada <n>' | 'sair' para terminar");

            while (true) {
                System.out.print("> ");
                if (!sc.hasNextLine()) break;
                String line = sc.nextLine().trim();
                if (line.equalsIgnoreCase("sair")) break;

                if (line.startsWith("rajada")) {
                    String[] pLine = line.split("\\s+");
                    if (pLine.length != 2) {
                        System.out.println("Formato inválido. Use: rajada <n>");
                        continue;
                    }
                    int n;
                    try {
                        n = Integer.parseInt(pLine[1]);
                        if (n <= 0) {
                            System.out.println("O número deve ser inteiro positivo.");
                            continue;
                        }
                    } catch (NumberFormatException e) {
                        System.out.println("Número inválido: " + pLine[1]);
                        continue;
                    }

                    long inicio = 0;
                    long fim = 0;

                    for (int i = 0; i < n; i++) {
                        double temp = 15.0 + Math.random() * 15.0;
                        SensorRecord r = SensorRecord.now(++seq, "SIM", temp);
                        file.append(r);
                        String payload = r.toLine();

                        if (i == 0) {
                            inicio = System.currentTimeMillis();
                        }
                        try {
                            pubSub.publish(TOPIC, payload);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            System.out.println("Interrompido durante a rajada.");
                            break;
                        }
                        if (i == n - 1) {
                            fim = System.currentTimeMillis();
                        }
                    }
                    System.out.println("Rajada concluída. Tempo de envio: " + (fim - inicio) + " ms");
                    continue;
                }

                String[] p = line.split("\\s+");
                if (p.length != 2) {
                    System.out.println("Formato inválido.");
                    continue;
                }
                double temp;
                try {
                    temp = Double.parseDouble(p[1]);
                } catch (NumberFormatException e) {
                    System.out.println("Temperatura inválida: " + p[1]);
                    continue;
                }

                SensorRecord r = SensorRecord.now(++seq, p[0], temp);
                file.append(r);
                try {
                    pubSub.publish(TOPIC, r.toLine());
                    System.out.println("Registado e publicado: " + r.toLine());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.out.println("Interrompido durante a publicação.");
                    break;
                }
            }
        } catch (IOException e) {
            System.out.println("IO: " + e.getMessage());
        }
    }
}