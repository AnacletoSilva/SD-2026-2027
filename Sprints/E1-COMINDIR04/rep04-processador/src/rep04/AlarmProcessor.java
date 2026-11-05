package rep04;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class AlarmProcessor {
    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length < 1 || args.length > 2) {
            System.out.println("Uso: java rep04.AlarmProcessor <N> [api]");
            return;
        }

        int n;
        try {
            n = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            System.out.println("NÃºmero de partiÃ§Ãµes invÃ¡lido: " + args[0]);
            return;
        }

        String api = args.length > 1 ? args[1] : "http://127.0.0.1:5001/api/v0";
        Estado estado = new Estado(n);


        final long VERIFICACAO_MS = 1000;

        ScheduledExecutorService scheduler =
                new ScheduledThreadPoolExecutor(1);

        scheduler.scheduleAtFixedRate(() -> {

            for (HeartbeatMonitor monitor : estado.monitores) {
                monitor.check();
            }

        }, VERIFICACAO_MS, VERIFICACAO_MS, TimeUnit.MILLISECONDS);


        for (int k = 0; k < n; k++) {
            final int part = k;
            Thread thread = new Thread(() -> {
                try {
                    IpfsPubSub ps = new IpfsPubSub(api);
                    ps.subscribe(Wire.topicData(part), mensagem -> processarMensagem(mensagem, part, estado, api));
                } catch (IOException e) {
                    System.out.println("Erro ao subscrever " + Wire.topicData(part) + ": " + e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.out.println("Interrompido na subscriÃ§Ã£o de " + Wire.topicData(part));
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
            String api) {

        String tipo;

        try {
            tipo = Wire.type(mensagem);
        } catch (IllegalArgumentException e) {
            System.out.println("Mensagem inválida: " + e.getMessage());
            return;
        }

        // Aceitar apenas mensagens LOTE
        if (!"LOTE".equals(tipo)) {
            System.out.println("REJEITADO: tipo " + tipo);
            return;
        }

        try {
            // Desserializar e validar o lote
            Batch batch = Batch.decode(mensagem, estado.n);

            // Atualizar o monitor da partição
            estado.monitores.get(particao).onHeartbeat(
                    batch.getInc(),
                    batch.getHb()
            );

            // Processar individualmente os registos do lote
            for (SensorRecord registo : batch.getRecords()) {
                processarRegisto(registo, particao, estado, api);
            }

        } catch (IllegalArgumentException e) {
            System.out.println(
                    "LOTE inválido na partição "
                            + particao + ": " + e.getMessage()
            );
        }
    }

    private static void processarRegisto(
            SensorRecord registo,
            int particao,
            Estado estado,
            String api) {

        // Ignorar alarmes para evitar ciclos
        if (registo.getSensorId().startsWith("ALARM-")) {
            return;
        }

        long lamportAtual;
        VectorClock vectorAtual;
        boolean devePublicar = false;

        double temperatura = registo.getTemperature();
        String sensor = registo.getSensorId();

        synchronized (estado) {

            // Evitar processar registos duplicados
            String chave = particao + ":" + registo.getSeq();

            if (estado.duplicados.contains(chave)) {
                return;
            }

            estado.duplicados.add(chave);

            // Atualizar relógios lógicos do processador
            estado.lamportProcessador = Math.max(
                    estado.lamportProcessador,
                    registo.getLamport()
            );

            estado.vectorProcessador.merge(
                    registo.getVector()
            );

            lamportAtual = estado.lamportProcessador;
            vectorAtual = estado.vectorProcessador.copy();

            // Verificar limiar de temperatura
            if (temperatura >= Wire.LIMIAR_ALARME) {
                devePublicar = true;
            }
        }

        if (!devePublicar) {
            return;
        }

        // Construir o alarme
        String sensorAlarme = "ALARM-" + sensor;

        int destino = new Partitioner(estado.n)
                .of(sensorAlarme);

        String payload = Wire.in(
                sensorAlarme,
                temperatura,
                lamportAtual,
                vectorAtual
        );

        // Publicar alarme na partição de destino
        try {
            new IpfsPubSub(api).publish(
                    Wire.topicIn(destino),
                    payload
            );

            System.out.println(
                    "ALARME: " + sensor
                            + " p" + particao
                            + "#" + registo.getSeq()
                            + " -> p" + destino
            );

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }

            System.out.println(
                    "Erro ao publicar alarme: " + e.getMessage()
            );
        }
    }

    private static class Estado {
        final int n;
        long lamportProcessador;
        VectorClock vectorProcessador;
        Set<String> duplicados;

        // Um monitor de heartbeat por partição
        final List<HeartbeatMonitor> monitores;

        Estado(int n) {
            this.n = n;
            this.vectorProcessador = new VectorClock(n);
            this.duplicados = new HashSet<>();

            // Inicializar os monitores das partições
            this.monitores = new ArrayList<>();

            for (int k = 0; k < n; k++) {
                HeartbeatMonitor monitor = new HeartbeatMonitor(
                        k,
                        5000,
                        3,
                        evento -> System.out.println(evento)
                );

                this.monitores.add(monitor);
            }
        }
    }

}

