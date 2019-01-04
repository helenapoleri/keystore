package keystore;

import io.atomix.cluster.messaging.ManagedMessagingService;
import io.atomix.cluster.messaging.impl.NettyMessagingService;
import io.atomix.utils.net.Address;
import io.atomix.utils.serializer.Serializer;
import tpc.Coordinator;
import tpc.Phase;
import tpc.TwoPCTransaction;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;

public class Server {

    private static final Address[] addresses = new Address[] {
            Address.from("localhost", 12345),
            Address.from("localhost", 12346),
            Address.from("localhost", 12347)
    };


    private static Serializer s = KeystoreProtocol
            .newSerializer();
    private static Serializer sp = ServerKeystoreSrvProtocol.newSerializer();

    private HashMap<Integer, Transaction> currentGets;
    private ReentrantLock currentGetsGlobalLock;
    private AtomicInteger nextGetId;
    private Coordinator<Map<Long, byte[]>> coordinator;
    private ManagedMessagingService ms;


    private Server() {
        ExecutorService es = Executors.newSingleThreadExecutor();

        this.ms = NettyMessagingService.builder()
                .withAddress(Address.from("localhost", 12350))
                .build();

        this.ms.start();

        BiConsumer<Boolean,TwoPCTransaction> whenDone = (aBoolean, twoPCTransaction) -> {
            KeystoreProtocol.PutResp p = new KeystoreProtocol.PutResp( aBoolean, twoPCTransaction.getClientTxId());
            ms.sendAsync(twoPCTransaction.getAddress(), KeystoreProtocol.PutResp.class.getName(), s.encode(p));
        };


        this.coordinator = new Coordinator<>(addresses, ms, whenDone, "Server", es);

        this.currentGets = new HashMap<>();
        this.currentGetsGlobalLock = new ReentrantLock();
        this.nextGetId =  new AtomicInteger(0);

        this.ms.registerHandler("put", (c, m) -> {
            KeystoreProtocol.PutReq req = s.decode(m);

            Map<Long, byte[]> values = req.values;
            Map<Integer, Map<Long, byte[]>> separatedValues = valuesSeparator(values);

            coordinator.initProcess(req.txId, c, separatedValues);
        }, es);

        this.ms.registerHandler("get", (c, m) -> {

            KeystoreProtocol.GetReq req = s.decode(m);

            int txId = nextGetId.incrementAndGet();
            Transaction trans = new Transaction(txId, req.txId, c);

            this.currentGetsGlobalLock.lock();
            currentGets.put(txId, trans);
            this.currentGetsGlobalLock.unlock();

            processGetReq(trans, req);
        }, es);


        this.ms.registerHandler(ServerKeystoreSrvProtocol.GetControllerResp.class.getName(), (o, m)-> {
            processGetResp(m);
        }, es);

        this.ms.start();
    }


    /////////////////////////GET///////////////////////////

    private void processGetResp(byte[] m) {
        ServerKeystoreSrvProtocol.GetControllerResp rp = sp.decode(m);

        currentGetsGlobalLock.lock();
        Transaction e = currentGets.get(rp.txId);
        currentGetsGlobalLock.unlock();

        if (e != null) {
            e.lock();
            if (e.getParticipantStatus(rp.pId) != Phase.COMMITTED) {
                e.setParticipantStatus(rp.pId, Phase.COMMITTED);
                e.setKeys(rp.values);
            }
            if (e.checkParticipantsPhases(Phase.COMMITTED)) {
                e.setPhase(Phase.COMMITTED);
                e.unlock();
                KeystoreProtocol.GetResp p = new KeystoreProtocol.GetResp(e.getKeys(), e.getClientTxId());
                ms.sendAsync(e.getAddress(),KeystoreProtocol.GetResp.class.getName(), s.encode(p));

                currentGetsGlobalLock.lock();
                currentGets.remove(rp.txId);
                currentGetsGlobalLock.unlock();
            }
            else{
                e.unlock();
            }
        }
    }


    private void processGetReq(Transaction tx, KeystoreProtocol.GetReq req) {
        Collection<Long> keys = req.keys;
        Map<Integer, Collection<Long>> separatedValues = valuesSeparator(keys);
        tx.setParticipants(separatedValues.keySet());
        initGet(tx.getId(), separatedValues);
    }

    private Map<Integer,Collection<Long>> valuesSeparator(Collection<Long> keys) {
        Map<Integer, Collection<Long>> res = new HashMap<>();
        for(Long key : keys) {
            int ks = (int) (key % (long) addresses.length);
            if(!res.containsKey(ks)) res.put(ks, new ArrayList<>());
            Collection<Long> ksCol = res.get(ks);
            ksCol.add(key);
            res.put(ks, ksCol);
        }
        return res;
    }



    private void initGet(int txId, Map<Integer, Collection<Long>> separatedValues) {

        for (Map.Entry<Integer, Collection<Long>> ksValues : separatedValues.entrySet()) {
            int participant = ksValues.getKey();
            ServerKeystoreSrvProtocol.GetControllerReq contReq = new ServerKeystoreSrvProtocol.GetControllerReq(txId, participant, ksValues.getValue());
            this.ms.sendAsync(addresses[participant], ServerKeystoreSrvProtocol.GetControllerReq.class.getName(), sp.encode(contReq));
        }

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        scheduler.schedule(() -> {
            currentGetsGlobalLock.lock();
            Transaction e = currentGets.get(txId);
            currentGetsGlobalLock.unlock();

            if (e != null) {
                e.lock();
                if (!e.checkParticipantsPhases(Phase.COMMITTED)) {
                    KeystoreProtocol.GetResp p = new KeystoreProtocol.GetResp(e.getKeys(), e.getClientTxId());
                    ms.sendAsync(e.getAddress(), KeystoreProtocol.GetResp.class.getName(), s.encode(p));

                    currentGetsGlobalLock.lock();
                    currentGets.remove(txId);
                    currentGetsGlobalLock.unlock();
                }
                e.unlock();
            }
        }, 10, TimeUnit.SECONDS);
    }



    /////////////////////////PUT///////////////////////////


    private Map<Integer, Map<Long,byte[]>> valuesSeparator(Map<Long,byte[]> values) {
        Map<Integer, Map<Long, byte []>> res = new TreeMap<>();
        for(Map.Entry<Long, byte []> value: values.entrySet()) {
            int ks = (int) (value.getKey() % (long) addresses.length);
            if(!res.containsKey(ks)) res.put(ks, new HashMap<>());
            Map<Long, byte []> ksMap = res.get(ks);
            ksMap.put(value.getKey(), value.getValue());
            res.put(ks, ksMap);
        }
        return res;
    }

    public static void main(String[] args) {
        new Server();
    }
}
