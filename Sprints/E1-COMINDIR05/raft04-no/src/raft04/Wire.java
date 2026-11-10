package raft04;

import java.nio.file.Files;
import java.nio.file.Path;

public final class Wire {
    public static final int GRUPO = 3;

    public static final int LIMITE_RETIDOS = 10;
    public static final int T_TCP_MS = 5000;
    public static final int PORTO_BASE = 7000;
    public static final double LIMIAR_ALARME = 28.0;

    public static final long PERIODO_HB_MS =
            propriedadePositiva("raft04.periodo", 2000L);

    public static final long ELEICAO_MIN_MS =
            propriedadePositiva("raft04.eleicaoMin", 6000L);

    public static final long ELEICAO_MAX_MS =
            propriedadePositiva("raft04.eleicaoMax", 12000L);

    public static final long VERIFICACAO_MS = 200L;


    private Wire() {
    }

    private static long propriedadePositiva(
            String nome,
            long valorPorOmissao
    ) {
        String valor = System.getProperty(nome);

        if (valor == null) {
            return valorPorOmissao;
        }

        try {
            long resultado = Long.parseLong(valor);

            if (resultado <= 0) {
                throw new IllegalArgumentException(
                        "A propriedade " + nome
                                + " deve ser positiva"
                );
            }

            return resultado;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "A propriedade " + nome
                            + " deve ser um número inteiro",
                    e
            );
        }
    }

    public static String topicIn(int k) {
        return "raft04-in-p" + k;
    }

    public static String topicData(int k) {
        return "raft04-p" + k;
    }

    public static String topicElection(int k) {
        return "raft04-eleicao-p" + k;
    }

    public static int nodePort(int k, int i) {
        return PORTO_BASE + (k * GRUPO) + i;
    }

    public static boolean isolated(int k, int i) {
        return Files.exists(Path.of("isolar-p" + k + "-n" + i));
    }

    public static String in(String sensor, double temp) {
        return in(sensor, temp, 0, new VectorClock(1));
    }

    public static String in(
            String sensor,
            double temp,
            long lamport,
            VectorClock vector
    ) {
        return "IN;"
                + sensor
                + ";"
                + temp
                + ";"
                + lamport
                + ";"
                + vector;
    }

    public static String reg(String host, int port, SensorRecord r) {
        return "REG;"
                + host
                + ";"
                + port
                + ";"
                + r.toLine();
    }

    public static String type(String msg) {
        String m = msg.trim();
        int i = m.indexOf(';');

        if (i <= 0) {
            throw new IllegalArgumentException(
                    "Mensagem sem tipo: " + msg
            );
        }

        return m.substring(0, i);
    }

    public static String body(String msg) {
        String m = msg.trim();
        return m.substring(m.indexOf(';') + 1);
    }

    public static String[] regParts(String msg) {
        String[] f = msg.trim().split(";", 4);

        if (f.length != 4 || !f[0].equals("REG")) {
            throw new IllegalArgumentException(
                    "REG mal formado: " + msg
            );
        }

        return new String[]{f[1], f[2], f[3]};
    }
}