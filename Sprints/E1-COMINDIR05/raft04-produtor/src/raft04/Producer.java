package raft04;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class Producer {
    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length != 1) {
            System.out.println("Uso: java raft04.Producer <numero-de-particoes>");
            return;
        }

        int numeroParticoes;
        try {
            numeroParticoes = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            System.out.println("NÃºmero de partiÃ§Ãµes invÃ¡lido.");
            return;
        }

        String api = "http://127.0.0.1:5001/api/v0";
        Partitioner partitioner = new Partitioner(numeroParticoes);


        List<HeartbeatMonitor> monitores = new ArrayList<>();

        for (int k = 0; k < numeroParticoes; k++) {
            HeartbeatMonitor monitor = new HeartbeatMonitor(
                    k,
                    5000,
                    3,
                    evento -> System.out.println(evento)
            );

            monitores.add(monitor);
        }


        final long VERIFICACAO_MS = 1000;

        ScheduledExecutorService scheduler =
                new ScheduledThreadPoolExecutor(1);

        scheduler.scheduleAtFixedRate(() -> {
            for (HeartbeatMonitor monitor : monitores) {
                monitor.check();
            }
        }, VERIFICACAO_MS, VERIFICACAO_MS, TimeUnit.MILLISECONDS);



        for (int k = 0; k < numeroParticoes; k++) {
            final int particao = k;

            Thread thread = new Thread(() -> {
                try {
                    IpfsPubSub ps = new IpfsPubSub(api);

                    ps.subscribe(Wire.topicData(particao), mensagem -> {

                        String tipo;

                        try {
                            tipo = Wire.type(mensagem);

                            if (!"LOTE".equals(tipo)) {
                                System.out.println(
                                        "REJEITADO: tipo " + tipo
                                                + " na partição " + particao
                                );
                                return;
                            }

                            Batch batch = Batch.decode(mensagem, numeroParticoes);

                            monitores.get(particao).onHeartbeat(
                                    batch.getInc(),
                                    batch.getHb()
                            );

                        } catch (IllegalArgumentException e) {
                            System.out.println(
                                    "LOTE inválido na partição "
                                            + particao + ": " + e.getMessage()
                            );
                        }
                    });

                } catch (IOException e) {
                    System.out.println(
                            "Erro ao subscrever a partição "
                                    + particao + ": " + e.getMessage()
                    );

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();

                    System.out.println(
                            "Subscrição interrompida na partição "
                                    + particao
                    );
                }
            }, "producer-sub-p" + particao);

            thread.start();
        }


        IpfsPubSub ps = new IpfsPubSub(api);
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        String linha;
        while ((linha = reader.readLine()) != null) {
            linha = linha.trim();
            if (linha.isEmpty()) continue;

            String[] campos = linha.split("\\s+");
            if (campos.length == 3) {
                if (campos[0].equalsIgnoreCase("rajada")) {
                    try {
                        int n = Integer.parseInt(campos[1]);
                        int s = Integer.parseInt(campos[2]);
                        rajada(n, s, ps, partitioner);
                    } catch (NumberFormatException e) {
                        System.out.println("NÃºmero invÃ¡lido: " + campos[1]);
                    }
                    continue;
                }
                System.out.println("Formato invÃ¡lido. Use: <sensor> <temperatura>");
                continue;
            }

            if (campos.length != 2) {
                System.out.println("Formato invÃ¡lido. Use: <sensor> <temperatura>");
                continue;
            }

            try {
                double temperatura = Double.parseDouble(campos[1]);
                String result = Wire.in(campos[0], temperatura, 0, new VectorClock(numeroParticoes));
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
            System.out.println("NÃºmero de mensagens e sensores deve ser positivo.");
            return;
        }

        for (int i = 0; i < n; i++) {
            if (i == 0) inicio = System.currentTimeMillis();
            double temp = 15.0 + (30.0 - 15.0) * r.nextDouble();
            int indice = r.nextInt(s) + 1;
            String sensor = String.format("S%02d", indice);
            String payload = Wire.in(sensor, temp, 0, new VectorClock(partitioner.count()));
            int particao = partitioner.of(sensor);
            ps.publish(Wire.topicIn(particao), payload);
            Thread.sleep(100);
            if (i == n - 1) fim = System.currentTimeMillis();
        }

        System.out.println("Primeira publicaÃ§Ã£o: " + inicio);
        System.out.println("Ãšltima publicaÃ§Ã£o: " + fim);
        System.out.println("Rajada concluÃ­da. Tempo de envio: " + (fim - inicio) + " ms");
    }
}

