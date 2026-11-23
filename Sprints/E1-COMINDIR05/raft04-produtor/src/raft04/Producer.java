package raft04;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class Producer {
    private static final Path FICHEIRO_EVENTOS =
            Path.of("produtor.txt");

    private static final ExecutorService LOGGER =
            Executors.newSingleThreadExecutor();

    public static void main(String[] args)
            throws IOException, InterruptedException {
        if (args.length != 1) {
            System.out.println(
                    "Uso: java raft04.Producer "
                            + "<numero-de-particoes>"
            );
            return;
        }

        int numeroParticoes;

        try {
            numeroParticoes = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            System.out.println(
                    "Numero de particoes invalido."
            );
            return;
        }

        Runtime.getRuntime().addShutdownHook(
                new Thread(() -> {
                    LOGGER.shutdown();

                    try {
                        LOGGER.awaitTermination(
                                5,
                                TimeUnit.SECONDS
                        );
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                })
        );

        String api =
                "http://127.0.0.1:5001/api/v0";

        Partitioner partitioner =
                new Partitioner(numeroParticoes);

        Consumer<String> registarEvento =
                Producer::registarEvento;

        List<HeartbeatMonitor> monitores =
                new ArrayList<>();

        for (int k = 0; k < numeroParticoes; k++) {
            monitores.add(
                    new HeartbeatMonitor(
                            k,
                            5000,
                            3,
                            registarEvento
                    )
            );
        }

        ScheduledExecutorService scheduler =
                new ScheduledThreadPoolExecutor(1);

        scheduler.scheduleAtFixedRate(
                () -> {
                    for (HeartbeatMonitor monitor
                            : monitores) {
                        monitor.check();
                    }
                },
                1000,
                1000,
                TimeUnit.MILLISECONDS
        );

        for (int k = 0; k < numeroParticoes; k++) {
            final int particao = k;

            Thread thread = new Thread(() -> {
                try {
                    IpfsPubSub pubSub =
                            new IpfsPubSub(api);

                    pubSub.subscribe(
                            Wire.topicData(particao),
                            mensagem -> {
                                try {
                                    String tipo =
                                            Wire.type(mensagem);

                                    if (!"LOTE".equals(tipo)) {
                                        System.out.println(
                                                "REJEITADO: tipo "
                                                        + tipo
                                                        + " na particao "
                                                        + particao
                                        );
                                        return;
                                    }

                                    Batch batch =
                                            Batch.decode(
                                                    mensagem,
                                                    numeroParticoes
                                            );

                                    monitores
                                            .get(particao)
                                            .onHeartbeat(
                                                    batch.getTerm(),
                                                    batch.getLeader(),
                                                    batch.getHb()
                                            );
                                } catch (
                                        IllegalArgumentException e
                                ) {
                                    System.out.println(
                                            "LOTE invalido na particao "
                                                    + particao
                                                    + ": "
                                                    + e.getMessage()
                                    );
                                }
                            }
                    );
                } catch (IOException e) {
                    System.out.println(
                            "Erro ao subscrever a particao "
                                    + particao
                                    + ": "
                                    + e.getMessage()
                    );
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();

                    System.out.println(
                            "Subscricao interrompida na particao "
                                    + particao
                    );
                }
            });

            thread.setName(
                    "producer-sub-p" + particao
            );

            thread.start();
        }

        IpfsPubSub pubSub = new IpfsPubSub(api);

        BufferedReader reader =
                new BufferedReader(
                        new InputStreamReader(System.in)
                );

        String linha;

        while ((linha = reader.readLine()) != null) {
            linha = linha.trim();

            if (linha.isEmpty()) {
                continue;
            }

            String[] campos = linha.split("\\s+");

            if (campos.length == 3) {
                if (campos[0].equalsIgnoreCase("rajada")) {
                    try {
                        int quantidade =
                                Integer.parseInt(campos[1]);

                        int sensores =
                                Integer.parseInt(campos[2]);

                        rajada(
                                quantidade,
                                sensores,
                                pubSub,
                                partitioner
                        );
                    } catch (NumberFormatException e) {
                        System.out.println(
                                "Numero invalido: "
                                        + campos[1]
                        );
                    }

                    continue;
                }

                System.out.println(
                        "Formato invalido. Use: "
                                + "<sensor> <temperatura>"
                );
                continue;
            }

            if (campos.length != 2) {
                System.out.println(
                        "Formato invalido. Use: "
                                + "<sensor> <temperatura>"
                );
                continue;
            }

            try {
                double temperatura =
                        Double.parseDouble(campos[1]);

                String payload = Wire.in(
                        campos[0],
                        temperatura,
                        0,
                        new VectorClock(numeroParticoes)
                );

                int particao =
                        partitioner.of(campos[0]);

                pubSub.publish(
                        Wire.topicIn(particao),
                        payload
                );

                registarLeitura(
                        particao,
                        campos[0],
                        temperatura
                );
            } catch (NumberFormatException e) {
                System.out.println(
                        "Erro ao converter temperatura: "
                                + e.getMessage()
                );
            }
        }
    }

    private static void registarEvento(
            String evento
    ) {
        System.out.println(evento);

        LOGGER.execute(() -> {
            try {
                Files.writeString(
                        FICHEIRO_EVENTOS,
                        evento + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                );
            } catch (IOException e) {
                System.out.println(
                        "Erro ao escrever produtor.txt: "
                                + e.getMessage()
                );
            }
        });
    }

    private static void registarLeitura(
            int particao,
            String sensor,
            double temperatura
    ) {
        String instante = LocalDateTime.now()
                .withNano(0)
                .toString();

        String linha = instante
                + ";p" + particao
                + ";" + sensor
                + ";" + temperatura;

        System.out.println(linha);

        LOGGER.execute(() -> {
            try {
                Files.writeString(
                        FICHEIRO_EVENTOS,
                        linha + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                );
            } catch (IOException e) {
                System.out.println(
                        "Erro ao escrever produtor.txt: "
                                + e.getMessage()
                );
            }
        });
    }

    public static void rajada(
            int n,
            int s,
            IpfsPubSub pubSub,
            Partitioner partitioner
    ) throws IOException, InterruptedException {
        if (n <= 0 || s <= 0) {
            System.out.println(
                    "Quantidade de mensagens e sensores "
                            + "deve ser positiva."
            );
            return;
        }

        Random random = new Random();
        long inicio = 0;
        long fim = 0;

        for (int i = 0; i < n; i++) {
            if (i == 0) {
                inicio = System.currentTimeMillis();
            }

            double temperatura =
                    15.0
                            + (30.0 - 15.0)
                            * random.nextDouble();

            int indice =
                    random.nextInt(s) + 1;

            String sensor =
                    String.format("S%02d", indice);

            String payload = Wire.in(
                    sensor,
                    temperatura,
                    0,
                    new VectorClock(
                            partitioner.count()
                    )
            );

            int particao =
                    partitioner.of(sensor);

            pubSub.publish(
                    Wire.topicIn(particao),
                    payload
            );

            registarLeitura(
                    particao,
                    sensor,
                    temperatura
            );

            Thread.sleep(100);

            if (i == n - 1) {
                fim = System.currentTimeMillis();
            }
        }

        System.out.println(
                "Primeira publicacao: " + inicio
        );

        System.out.println(
                "Ultima publicacao: " + fim
        );

        System.out.println(
                "Rajada concluida. Tempo de envio: "
                        + (fim - inicio)
                        + " ms"
        );
    }
}