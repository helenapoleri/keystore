package tpc;

import io.atomix.storage.journal.SegmentedJournal;
import io.atomix.storage.journal.SegmentedJournalReader;
import io.atomix.storage.journal.SegmentedJournalWriter;
import io.atomix.utils.serializer.Serializer;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;


/**
 * Representa um log.
 *
 * @param <T> TIpo de entrada no log
 */
class Log<T> {

    /**
     * Representa uma entrada no log.
     *
     * @param <T>   Tipo de entrada no log.
     */
    public static class LogEntry<T> {


        // **********************************************************************
        // Variáveis
        // **********************************************************************

        private int id;
        private T action;


        // **********************************************************************
        // Construtores
        // **********************************************************************

        /**
         * Construtor parametrizado de uma entrada no log.
         *
         * @param id            ID da entrada.
         * @param action        Conteúdo da entrada.
         */
        LogEntry(int id, T action) {
            this.id = id;
            this.action = action;

        }

/*       public void setPhase(Phase phase){
            this.phase = phase;
        }*/

        // **********************************************************************
        // Getters e setters
        // **********************************************************************

        /**
         * Retorna o ID de uma entrada.
         *
         * @return  o ID de uma entrada.
         */
        int getId() {
            return id;
        }

        /**
         * Retorna o conteúdo de uma entrada.
         *
         * @return o conteúdo de uma entrada.
         */
         T getAction() {
            return action;
        }

        // **********************************************************************
        // Métodos públicos
        // **********************************************************************


        /**
         * Constrói a representação textual de uma entrada no log.
         *
         * @return a representação textual de uma entrada no log.
         */
        public String toString() {
            return "xid=" + id + " " + action;
        }

    }


    // **************************************************************************
    // Variáveis
    // **************************************************************************

    private SegmentedJournal<Object> j;
    private SegmentedJournalWriter<Object> w;


    // **************************************************************************
    // Construtores
    // **************************************************************************

    /**
     * Construtor parametrizado do log.
     *
     * @param name Nome do log.
     */
     Log(String name) {
        Serializer s = Serializer.builder()
                .withTypes(LogEntry.class)
                .withTypes(SimpleTwoPCTransaction.class)
                .withTypes(TreeMap.class)
                .build();

        this.j = SegmentedJournal.builder()
                .withName(name)
                .withSerializer(s)
                .build();

        this.w = j.writer();
    }



    // **************************************************************************
    // Métodos públicos
    // **************************************************************************

    /**
     * Escreve no log.
     *
     * @param transId   Identificador da entrada.
     * @param action    Conteúdo da entrada no log.
     */
     synchronized void write(int transId, T action) {
        w = j.writer();
        w.append(new Log.LogEntry<Object>(transId, action));
        w.flush();
    }

    /**
     * Lê o log.
     *
     * @return          Lista de entradas do log.
     */
     List<LogEntry> read() {
        List<LogEntry> entries = new ArrayList<>();
        SegmentedJournalReader<Object> r = j.openReader(0);
        while(r.hasNext()) {
            Log.LogEntry e = (Log.LogEntry) r.next().entry();
            entries.add(e);
        }
        return entries;
    }
}
