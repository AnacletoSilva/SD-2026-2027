package rep03;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class CausalCheck {
    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.out.println("Uso: java rep03.CausalCheck <N> <pasta>");
            return;
        }

        int n;
        try {
            n = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            System.out.println("Número de partições inválido: " + args[0]);
            return;
        }

        Path pasta = Paths.get(args[1]);
        List<Registro> registos = new ArrayList<>();

        for (int k = 0; k < n; k++) {
            Path ficheiro = pasta.resolve("lider-p" + k + ".txt");
            if (!Files.exists(ficheiro)) {
                continue;
            }

            List<String> linhas = Files.readAllLines(ficheiro, StandardCharsets.UTF_8);
            for (String linha : linhas) {
                if (linha.isBlank()) {
                    continue;
                }
                SensorRecord r = SensorRecord.fromLine(linha);
                if (r.getVector() == null || r.getVector().compare(new VectorClock(n)) == VectorClock.Order.EQUAL && r.getSeq() == 0) {
                    // mantém-se a canónica; o vetor real de cada evento é lido corretamente
                }
                if (r.getVector().get(k) != r.getSeq()) {
                    System.out.println("ERRO: V[" + k + "] != seq em " + ficheiro.getFileName() + " :: " + linha);
                }
                registos.add(new Registro(k, r, linha));
            }
        }

        int causal = 0;
        int concorrente = 0;
        int concorrentesComLamportDiferente = 0;
        int falhasLamport = 0;

        for (int i = 0; i < registos.size(); i++) {
            for (int j = i + 1; j < registos.size(); j++) {
                Registro a = registos.get(i);
                Registro b = registos.get(j);
                if (a.particao == b.particao) {
                    continue;
                }

                VectorClock.Order ordem = a.registo.getVector().compare(b.registo.getVector());
                if (ordem == VectorClock.Order.BEFORE) {
                    causal++;
                    if (!(a.registo.getLamport() < b.registo.getLamport())) {
                        falhasLamport++;
                        System.out.println("VIOLAÇÃO LAMPORT: " + a + " -> " + b + " (L=" + a.registo.getLamport() + ", " + b.registo.getLamport() + ")");
                    }
                } else if (ordem == VectorClock.Order.AFTER) {
                    causal++;
                    if (!(b.registo.getLamport() < a.registo.getLamport())) {
                        falhasLamport++;
                        System.out.println("VIOLAÇÃO LAMPORT: " + b + " -> " + a + " (L=" + b.registo.getLamport() + ", " + a.registo.getLamport() + ")");
                    }
                } else {
                    concorrente++;
                    if (a.registo.getLamport() != b.registo.getLamport()) {
                        concorrentesComLamportDiferente++;
                    }
                }
            }
        }

        double frac = concorrente == 0 ? 0.0 : (concorrentesComLamportDiferente * 100.0) / concorrente;
        System.out.println("Pares causalmente relacionados: " + causal);
        System.out.println("Pares concorrentes: " + concorrente);
        System.out.println("Pares concorrentes com L distinto: " + concorrentesComLamportDiferente + " (" + String.format("%.2f%%", frac) + ")");

        List<Registro> ordenados = registos.stream()
                .sorted(Comparator.comparingLong((Registro r) -> r.registo.getLamport())
                        .thenComparingInt(r -> r.particao))
                .collect(Collectors.toList());

        Path vista = pasta.resolve("vista-lamport.txt");
        List<String> linhasVista = new ArrayList<>();
        for (Registro r : ordenados) {
            linhasVista.add(r.linhaOriginal);
        }
        Files.write(vista, linhasVista, StandardCharsets.UTF_8);
        System.out.println("Escrito: " + vista);
    }

    private static class Registro {
        final int particao;
        final SensorRecord registo;
        final String linhaOriginal;

        Registro(int particao, SensorRecord registo, String linhaOriginal) {
            this.particao = particao;
            this.registo = registo;
            this.linhaOriginal = linhaOriginal;
        }

        @Override
        public String toString() {
            return "p" + particao + "#" + registo.getSeq();
        }
    }
}
