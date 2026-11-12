package raft04;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class AlarmProcessor {
    public static void main(String[] args)
            throws IOException, InterruptedException {
        if (args.length < 1 || args.length > 2) {
            System.out.println(
                    "Uso: java raft04.AlarmProcessor <N> [api]"
            );
            return;
        }

        int n;

        try {
            n = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            System.out.println(
                    "Numero de particoes invalido: " + args[0]
            );
            return;
        }

        String api = args.length > 1
                ? args[1]
                : "http://127.0.0.1:5001/api/v0";

        Estado estado = new Estado(n);

        ScheduledExecutorService scheduler =
                new ScheduledThreadPoolExecutor(1);

        scheduler.scheduleAtFixedRate(
                () -> {
                    for (HeartbeatMonitor monitor
                            : estado.monitores) {
                        monitor.check();
                    }
                },
                1000,
                1000,
                TimeUnit.MILLISECONDS
        );

        for (int k = 0; k < n; k++) {
            final int particao = k;

            Thread thread = new Thread(() -> {
                try {
                    IpfsPubSub pubSub =
                            new IpfsPubSub(api);

                    pubSub.subscribe(
                            Wire.topicData(particao),
                            mensagem -> processarMensagem(
                                    mensagem,
                                    particao,
                                    estado,
                                    api
                            )
                    );
                } catch (IOException e) {
                    System.out.println(
                            "Erro ao subscrever "
                                    + Wire.topicData(particao)
                                    + ": "
                                    + e.getMessage()
                    );
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();

                    System.out.println(
                            "Subscricao interrompida de "
                                    + Wire.topicData(particao)
                    );
                }
            });

            thread.start();
        }

        while (true) {
            Thread.sleep(1000);
        }
    }

    private static void processarMensagem(
            String mensagem,
            int particao,
            Estado estado,
            String api
    ) {
        String tipo;

        try {
            tipo = Wire.type(mensagem);
        } catch (IllegalArgumentException e) {
            System.out.println(
                    "Mensagem invalida: " + e.getMessage()
            );
            return;
        }

        if (!"LOTE".equals(tipo)) {
            System.out.println(
                    "REJEITADO: tipo " + tipo
            );
            return;
        }

        try {
            Batch batch = Batch.decode(
                    mensagem,
                    estado.n
            );

            synchronized (estado) {
                if (batch.getTerm()
                        < estado.maiorTermoObservado[particao]) {
                    System.out.println(
                            "IGNORADO;p" + particao
                                    + ";term=" + batch.getTerm()
                                    + ";maiorTerm="
                                    + estado.maiorTermoObservado[particao]
                    );
                    return;
                }

                if (batch.getTerm()
                        > estado.maiorTermoObservado[particao]) {
                    estado.maiorTermoObservado[particao] =
                            batch.getTerm();
                }
            }

            estado.monitores
                    .get(particao)
                    .onHeartbeat(
                            batch.getTerm(),
                            batch.getLeader(),
                            batch.getHb()
                    );

            for (SensorRecord registo
                    : batch.getRecords()) {
                processarRegisto(
                        registo,
                        particao,
                        estado,
                        api
                );
            }
        } catch (IllegalArgumentException e) {
            System.out.println(
                    "LOTE invalido na particao "
                            + particao
                            + ": "
                            + e.getMessage()
            );
        }
    }

    private static void processarRegisto(
            SensorRecord registo,
            int particao,
            Estado estado,
            String api
    ) {
        if (registo.getSensorId().startsWith("ALARM-")) {
            return;
        }

        long lamportAtual;
        VectorClock vectorAtual;
        boolean devePublicar = false;

        double temperatura = registo.getTemperature();
        String sensor = registo.getSensorId();

        synchronized (estado) {
            String chave = particao
                    + ":"
                    + registo.getSeq()
                    + ":"
                    + registo.getTerm();

            if (estado.duplicados.contains(chave)) {
                return;
            }

            estado.duplicados.add(chave);

            estado.lamportProcessador = Math.max(
                    estado.lamportProcessador,
                    registo.getLamport()
            );

            if (registo.getVector() != null) {
                estado.vectorProcessador.merge(
                        registo.getVector()
                );
            }

            lamportAtual = estado.lamportProcessador;
            vectorAtual = estado.vectorProcessador.copy();

            if (temperatura >= Wire.LIMIAR_ALARME) {
                devePublicar = true;
            }
        }

        if (!devePublicar) {
            return;
        }

        String sensorAlarme = "ALARM-" + sensor;

        int destino = new Partitioner(estado.n)
                .of(sensorAlarme);

        String payload = Wire.in(
                sensorAlarme,
                temperatura,
                lamportAtual,
                vectorAtual
        );

        try {
            new IpfsPubSub(api).publish(
                    Wire.topicIn(destino),
                    payload
            );

            System.out.println(
                    "ALARME: " + sensor
                            + " p" + particao
                            + "#" + registo.getSeq()
                            + " term=" + registo.getTerm()
                            + " -> p" + destino
            );
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }

            System.out.println(
                    "Erro ao publicar alarme: "
                            + e.getMessage()
            );
        }
    }

    private static class Estado {
        final int n;
        long lamportProcessador;
        VectorClock vectorProcessador;
        Set<String> duplicados;
        long[] maiorTermoObservado;
        final List<HeartbeatMonitor> monitores;

        Estado(int n) {
            this.n = n;
            this.vectorProcessador = new VectorClock(n);
            this.duplicados = new HashSet<>();
            this.maiorTermoObservado = new long[n];
            this.monitores = new ArrayList<>();

            for (int k = 0; k < n; k++) {
                maiorTermoObservado[k] = -1;

                monitores.add(
                        new HeartbeatMonitor(
                                k,
                                5000,
                                3,
                                evento -> System.out.println(evento)
                        )
                );
            }
        }
    }
}