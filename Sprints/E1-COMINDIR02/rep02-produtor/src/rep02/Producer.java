package rep02;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Random;

public class Producer {
    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length != 1) {
            System.out.println("Uso: java rep02.Producer <numero-de-partições>");
            return;
        }

        int numeroParticoes;
        try {
            numeroParticoes = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            System.out.println("Número de partições inválido.");
            return;
        }

        Partitioner partitioner = new Partitioner(numeroParticoes);
        String api = "http://127.0.0.1:5001/api/v0";

        IpfsPubSub ps = new IpfsPubSub(api);
        BufferedReader reader =
                new BufferedReader(new InputStreamReader(System.in));
        String linha;
        while ((linha = reader.readLine()) != null) {
            linha = linha.trim();
            if (linha.isEmpty()) {
                continue;
            }

            String[] campos = linha.split("\\s+");
            // tratamento dos campos aqui
            if (campos.length == 3) {
                if (campos[0].equalsIgnoreCase("rajada")) {
                    try {
                        int n = Integer.parseInt(campos[1]);
                        int s = Integer.parseInt(campos[2]);
                        rajada(n, s, ps, partitioner);
                    } catch (NumberFormatException e) {
                        System.out.println("Número inválido: " + campos[1]);
                    }
                    continue;
                } else {
                    System.out.println("Formato inválido. Use: <sensor> <temperatura>");
                    continue;
                }
            }
            if (campos.length!=2) {
                System.out.println("Formato inválido. Use: <sensor> <temperatura>");
                continue;
            }
            try {
                double temperatura = Double.parseDouble(campos[1]);
                System.out.println("Enviando dados para o sensor " + campos[0] + ": " + temperatura);
                String result = Wire.in(campos[0], temperatura);
                int particao = partitioner.of(campos[0]);
                ps.publish(Wire.topicIn(particao), result);
            } catch (NumberFormatException e) {
                System.out.println("Erro ao converter temperatura: " + e.getMessage());
            }
        }
    }
    public static void rajada(int n, int s, IpfsPubSub ps, Partitioner partitioner) throws IOException, InterruptedException {
        Random r = new Random();
        long inicio = 0;
        long fim = 0;
        if (n <= 0 || s <= 0) {
            System.out.println("Número de mensagens e sensores deve ser positivo.");
            return;
        }
        for (int i = 0; i < n; i++) {
            if (i == 0) {
                inicio = System.currentTimeMillis();
            }
            double temp = 15.0 + (30.0 - 15.0) * r.nextDouble();
            int indice = r.nextInt(s) + 1;
            String sensor = String.format("S%02d", indice);
            String payload = Wire.in(sensor, temp);
            System.out.println("Enviando dados para o sensor " + sensor + ": " + temp);
            int particao = partitioner.of(sensor);
            ps.publish(Wire.topicIn(particao), payload);
            Thread.sleep(100);
            if (i == n - 1) {
                fim = System.currentTimeMillis();
            }
        }
        System.out.println("Primeira publicação: " + inicio);
        System.out.println("Última publicação: " + fim);
        System.out.println("Rajada concluída. Tempo de envio: " + (fim - inicio) + " ms");
    }
}
