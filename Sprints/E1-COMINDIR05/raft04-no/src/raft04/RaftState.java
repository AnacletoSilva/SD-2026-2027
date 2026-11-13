package raft04;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

public final class RaftState {

    public enum Papel {
        SEGUIDOR,
        CANDIDATO,
        LIDER
    }

    public static final class Voto {
        public final long termo;
        public final boolean concedido;

        Voto(long termo, boolean concedido) {
            this.termo = termo;
            this.concedido = concedido;
        }
    }

    private final int particao;
    private final int proprio;
    private final int tamanhoGrupo;
    private final long nanosMinimos;
    private final long nanosMaximos;
    private final Path ficheiroEstado;
    private final Consumer<String> registo;

    // Estado persistente
    private long termo = 0;
    private int votadoPor = 0;

    // Estado volátil
    private Papel papel = Papel.SEGUIDOR;
    private int lider = 0;
    private final Set<Integer> votos = new HashSet<>();
    private long prazo;

    public RaftState(int particao, int proprio, int tamanhoGrupo,
                     long minimoMs, long maximoMs,
                     Path ficheiroEstado,
                     Consumer<String> registo) throws IOException {

        this.particao = particao;
        this.proprio = proprio;
        this.tamanhoGrupo = tamanhoGrupo;
        this.nanosMinimos = minimoMs * 1_000_000L;
        this.nanosMaximos = maximoMs * 1_000_000L;
        this.ficheiroEstado = ficheiroEstado;
        this.registo = registo;

        if (Files.exists(ficheiroEstado)) {
            String[] campos = Files.readString(
                    ficheiroEstado,
                    StandardCharsets.UTF_8
            ).trim().split(";");

            termo = Long.parseLong(campos[0]);
            votadoPor = Integer.parseInt(campos[1]);
        }

        reiniciarTemporizador();
    }

    private void reiniciarTemporizador() {
        long tempo = nanosMaximos > nanosMinimos
                ? ThreadLocalRandom.current()
                .nextLong(nanosMinimos, nanosMaximos + 1)
                : nanosMinimos;

        prazo = System.nanoTime() + tempo;
    }

    private void persistir() {
        try {
            Path ficheiroTemporario = ficheiroEstado.resolveSibling(
                    ficheiroEstado.getFileName() + ".tmp"
            );

            Files.writeString(
                    ficheiroTemporario,
                    termo + ";" + votadoPor + "\n",
                    StandardCharsets.UTF_8
            );

            Files.move(
                    ficheiroTemporario,
                    ficheiroEstado,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void adotarTermo(long novoTermo) {
        if (papel == Papel.LIDER) {
            registo.accept(
                    "DEMISSAO;p" + particao + ";termo=" + termo
            );
        }

        registo.accept(
                "TERMO;p" + particao + ";" + termo + "->" + novoTermo
        );

        termo = novoTermo;
        votadoPor = 0;
        papel = Papel.SEGUIDOR;
        lider = 0;

        persistir();
    }

    public synchronized long pulsar() {
        if (papel == Papel.LIDER || System.nanoTime() < prazo) {
            return -1;
        }

        termo++;
        votadoPor = proprio;
        persistir();

        papel = Papel.CANDIDATO;
        lider = 0;

        votos.clear();
        votos.add(proprio);

        reiniciarTemporizador();

        registo.accept(
                "CANDIDATO;p" + particao + ";termo=" + termo
        );

        return termo;
    }

    public synchronized Voto aoPedidoDeVoto(
            long termoDoCandidato,
            int candidato,
            long ultimaSequenciaDoCandidato,
            long ultimoTermoDoCandidato,
            long minhaUltimaSequencia,
            long meuUltimoTermo) {

        if (termoDoCandidato > termo) {
            adotarTermo(termoDoCandidato);
        }

        boolean atualizado =
                ultimoTermoDoCandidato > meuUltimoTermo
                        || (ultimoTermoDoCandidato == meuUltimoTermo
                        && ultimaSequenciaDoCandidato >= minhaUltimaSequencia);

        boolean concedido =
                termoDoCandidato == termo
                        && (votadoPor == 0 || votadoPor == candidato)
                        && atualizado;

        if (concedido) {
            votadoPor = candidato;
            persistir();
            reiniciarTemporizador();
        }

        registo.accept(
                "VOTO;p" + particao
                        + ";termo=" + termo
                        + ";candidato=n" + candidato
                        + ";" + (concedido ? "concedido" : "recusado")
        );

        return new Voto(termo, concedido);
    }

    public synchronized boolean aoReceberVoto(
            long termoDoVoto,
            int eleitor,
            int candidato,
            boolean concedido) {

        if (termoDoVoto > termo) {
            adotarTermo(termoDoVoto);
            return false;
        }

        if (papel != Papel.CANDIDATO
                || termoDoVoto != termo
                || candidato != proprio
                || !concedido) {
            return false;
        }

        votos.add(eleitor);

        if (votos.size() <= tamanhoGrupo / 2) {
            return false;
        }

        papel = Papel.LIDER;
        lider = proprio;

        registo.accept(
                "LIDER;p" + particao
                        + ";termo=" + termo
                        + ";votos=" + votos
        );

        return true;
    }

    public synchronized boolean aoReceberLote(
            long termoDoLote,
            int liderDoLote) {

        if (termoDoLote < termo) {
            return false;
        }

        if (termoDoLote > termo) {
            adotarTermo(termoDoLote);
        }

        if (papel == Papel.LIDER) {
            if (liderDoLote != proprio) {
                // Outro nó diz-se líder no mesmo termo → dois líderes de facto
                registo.accept(
                        "ERRO;p" + particao
                                + ";dois lideres no termo " + termo
                );

                return false;
            }

            // liderDoLote == proprio: é o próprio lote reenviado pelo PubSub.
            // Não é conflito — aceita sem emitir erro.
            reiniciarTemporizador();

            return true;
        }

        papel = Papel.SEGUIDOR;

        if (lider != liderDoLote) {
            lider = liderDoLote;

            registo.accept(
                    "NOVO_LIDER;p" + particao
                            + ";termo=" + termo
                            + ";lider=n" + liderDoLote
            );
        }

        reiniciarTemporizador();

        return true;
    }

    public synchronized long termoSeLider() {
        return papel == Papel.LIDER ? termo : -1;
    }

    public synchronized long termo() {
        return termo;
    }

    public synchronized Papel papel() {
        return papel;
    }

    public synchronized int lider() {
        return lider;
    }
}