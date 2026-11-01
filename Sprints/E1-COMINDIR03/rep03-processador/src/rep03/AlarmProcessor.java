package rep03;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

public class AlarmProcessor {
    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length < 1 || args.length > 2) {
            System.out.println("Uso: java rep03.AlarmProcessor <N> [api]");
            return;
        }

        int n;
        try {
            n = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            System.out.println("Número de partições inválido: " + args[0]);
            return;
        }

        String api = args.length > 1 ? args[1] : "http://127.0.0.1:5001/api/v0";
        Estado estado = new Estado(n);

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
                    System.out.println("Interrompido na subscrição de " + Wire.topicData(part));
                }
            });
            thread.start();
        }

        while (true) {
            Thread.sleep(1000);
        }
    }

    private static void processarMensagem(String mensagem, int particao, Estado estado, String api) {
        String tipo;
        try {
            tipo = Wire.type(mensagem);
        } catch (IllegalArgumentException e) {
            return;
        }

        if (!"REG".equals(tipo)) {
            return;
        }

        String[] partes;
        try {
            partes = Wire.regParts(mensagem);
            if (partes.length != 3) {
                return;
            }
        } catch (IllegalArgumentException e) {
            return;
        }

        SensorRecord registo;
        try {
            registo = SensorRecord.fromLine(partes[2]);
        } catch (IllegalArgumentException e) {
            System.out.println("REG mal formado: " + mensagem);
            return;
        }

        if (registo.getSensorId().startsWith("ALARM-")) {
            return;
        }

        long lamportAtual;
        VectorClock vectorAtual;
        boolean devePublicar = false;
        double temperatura = registo.getTemperature();
        String sensor = registo.getSensorId();

        synchronized (estado) {
            String chave = particao + ":" + registo.getSeq();
            if (estado.duplicados.contains(chave)) {
                return;
            }
            estado.duplicados.add(chave);

            estado.lamportProcessador = Math.max(estado.lamportProcessador, registo.getLamport());
            estado.vectorProcessador.merge(registo.getVector());

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
        int destino = new Partitioner(estado.n).of(sensorAlarme);
        String payload = Wire.in(sensorAlarme, temperatura, lamportAtual, vectorAtual);
        try {
            new IpfsPubSub(api).publish(Wire.topicIn(destino), payload);
            System.out.println("ALARME: " + sensor + " p" + particao + "#" + registo.getSeq() + " → p" + destino);
        } catch (IOException | InterruptedException e) {
            System.out.println("Erro ao publicar alarme: " + e.getMessage());
        }
    }

    private static class Estado {
        final int n;
        long lamportProcessador;
        VectorClock vectorProcessador;
        Set<String> duplicados;

        Estado(int n) {
            this.n = n;
            this.vectorProcessador = new VectorClock(n);
            this.duplicados = new HashSet<>();
        }
    }
}
