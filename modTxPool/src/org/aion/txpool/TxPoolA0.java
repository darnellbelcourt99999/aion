package org.aion.txpool;

import com.google.common.annotations.VisibleForTesting;
import java.math.BigInteger;
import java.util.AbstractMap;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import org.aion.base.AionTransaction;
import org.aion.base.PooledTransaction;
import org.aion.log.AionLoggerFactory;
import org.aion.log.LogEnum;
import org.aion.types.AionAddress;
import org.aion.util.bytes.ByteUtil;
import org.aion.util.time.TimeInstant;
import org.aion.util.types.ByteArrayWrapper;
import org.slf4j.Logger;

public class TxPoolA0 implements ITxPool {

    private static final Logger LOG = AionLoggerFactory.getLogger(LogEnum.TXPOOL.toString());

    private int seqTxCountMax = 16;
    private int txn_timeout = 3600; // 1 hour
    private int blkSizeLimit = Constant.MAX_BLK_SIZE; // 2MB
    public final static long MIN_ENERGY_CONSUME = 21_000L;

    private final AtomicLong blkNrgLimit = new AtomicLong(10_000_000L);
    private final int multiplyM = 1_000_000;
    private final int TXN_TIMEOUT_MIN = 10; // 10s

    private final int BLK_SIZE_MAX = 16 * 1024 * 1024; // 16MB
    private final int BLK_SIZE_MIN = 1024 * 1024; // 1MB

    private final int BLK_NRG_MAX = 100_000_000;
    private final int BLK_NRG_MIN = 1_000_000;
    private final int SEQ_TX_MAX = 25;
    private final int SEQ_TX_MIN = 5;
    
    public TxPoolA0(Properties config) {
        setPoolArgs(config);
    }

    private void setPoolArgs(Properties config) {
        if (Optional.ofNullable(config.get(PROP_TX_TIMEOUT)).isPresent()) {
            txn_timeout = Integer.valueOf(config.get(PROP_TX_TIMEOUT).toString());
            if (txn_timeout < TXN_TIMEOUT_MIN) {
                txn_timeout = TXN_TIMEOUT_MIN;
            }
        }

        txn_timeout--; // final timeout value sub -1 sec

        if (Optional.ofNullable(config.get(PROP_BLOCK_SIZE_LIMIT)).isPresent()) {
            blkSizeLimit = Integer.valueOf(config.get(PROP_BLOCK_SIZE_LIMIT).toString());
            if (blkSizeLimit < BLK_SIZE_MIN) {
                blkSizeLimit = BLK_SIZE_MIN;
            } else if (blkSizeLimit > BLK_SIZE_MAX) {
                blkSizeLimit = BLK_SIZE_MAX;
            }
        }

        if (Optional.ofNullable(config.get(PROP_BLOCK_NRG_LIMIT)).isPresent()) {
            updateBlkNrgLimit(Long.valueOf((String) config.get(PROP_BLOCK_NRG_LIMIT)));
        }

        if (Optional.ofNullable(config.get(PROP_TX_SEQ_MAX)).isPresent()) {
            seqTxCountMax = Integer.valueOf(config.get(PROP_TX_SEQ_MAX).toString());
            if (seqTxCountMax < SEQ_TX_MIN) {
                seqTxCountMax = SEQ_TX_MIN;
            } else if (seqTxCountMax > SEQ_TX_MAX) {
                seqTxCountMax = SEQ_TX_MAX;
            }
        }
    }

    /**
     * This function is a test function
     *
     * @param acc
     * @return
     */
    public List<BigInteger> getNonceList(AionAddress acc) {

        List<BigInteger> nl = Collections.synchronizedList(new ArrayList<>());
        lock.readLock().lock();
        this.getAccView(acc).getMap().entrySet().parallelStream().forEach(e -> nl.add(e.getKey()));
        lock.readLock().unlock();

        return nl.parallelStream().sorted().collect(Collectors.toList());
    }

    @Override
    public PooledTransaction add(PooledTransaction tx) {
        List<PooledTransaction> rtn = this.add(Collections.singletonList(tx));
        return rtn.isEmpty() ? null : rtn.get(0);
    }

    /**
     * this is a test function
     *
     * @return
     */
    public List<BigInteger> getFeeList() {
        List<BigInteger> nl = Collections.synchronizedList(new ArrayList<>());

        this.getFeeView().entrySet().parallelStream().forEach(e -> nl.add(e.getKey()));

        return nl.parallelStream().sorted(Collections.reverseOrder()).collect(Collectors.toList());
    }

    @Override
    public List<PooledTransaction> add(List<PooledTransaction> txl) {
        if (txl == null || txl.isEmpty()) return new ArrayList<>();

        List<PooledTransaction> newPendingTx = new ArrayList<>();
        Map<ByteArrayWrapper, TXState> mainMap = new HashMap<>();
        for (PooledTransaction pendingTx : txl) {

            ByteArrayWrapper bw = ByteArrayWrapper.wrap(pendingTx.tx.getTransactionHash());
            if (this.getMainMap().get(bw) != null) {
                if (LOG.isWarnEnabled()) {
                    LOG.warn(
                            "The tx hash existed in the pool! [{}]",
                            ByteUtil.toHexString(bw.toBytes()));
                }
                continue;
            }

            if (LOG.isTraceEnabled()) {
                LOG.trace(
                        "Put tx into mainMap: hash:[{}] tx:[{}]",
                        ByteUtil.toHexString(bw.toBytes()),
                        pendingTx.toString());
            }

            mainMap.put(bw, new TXState(pendingTx));

            BigInteger txNonce = pendingTx.tx.getNonceBI();
            BigInteger bn = getBestNonce(pendingTx.tx.getSenderAddress());

            if (bn != null && txNonce.compareTo(bn) < 1) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("repay tx, do snapshot!");
                }
                snapshot();
            }

            AbstractMap.SimpleEntry<ByteArrayWrapper, BigInteger> entry =
                    this.getAccView(pendingTx.tx.getSenderAddress()).getMap().get(txNonce);
            if (entry != null) {
                if (LOG.isTraceEnabled()) {
                    LOG.trace("repay tx, remove previous tx!");
                }
                PooledTransaction oldTx = remove(this.getMainMap().get(entry.getKey()).getTx());

                if (oldTx != null) {
                    newPendingTx.add(oldTx);
                }
            } else {
                if (LOG.isTraceEnabled()) {
                    LOG.trace("new tx! n[{}]", pendingTx.tx.getNonceBI().toString());
                }
                newPendingTx.add(pendingTx);
            }

            setBestNonce(pendingTx.tx.getSenderAddress(), txNonce);
        }

        this.getMainMap().putAll(mainMap);

        if (LOG.isTraceEnabled()) {
            LOG.trace("new add tx! np[{}] tx[{}]", newPendingTx.size(), txl.size());
        }

        if (newPendingTx.size() != txl.size()) {
            LOG.error("error");
        }

        return newPendingTx;
    }

    public List<PooledTransaction> getOutdatedList() {
        return this.getOutdatedListImpl();
    }

    /** For each address in map, removes any tx with a smaller nonce for that address */
    @Override
    public List<PooledTransaction> removeTxsWithNonceLessThan(Map<AionAddress, BigInteger> accNonce) {

        List<ByteArrayWrapper> bwList = new ArrayList<>();
        for (Map.Entry<AionAddress, BigInteger> en1 : accNonce.entrySet()) {
            AccountState as = this.getAccView(en1.getKey());
            lock.writeLock().lock();
            Iterator<Map.Entry<BigInteger, AbstractMap.SimpleEntry<ByteArrayWrapper, BigInteger>>>
                    it = as.getMap().entrySet().iterator();

            while (it.hasNext()) {
                Map.Entry<BigInteger, AbstractMap.SimpleEntry<ByteArrayWrapper, BigInteger>> en =
                        it.next();
                if (en1.getValue().compareTo(en.getKey()) > 0) {
                    bwList.add(en.getValue().getKey());
                    it.remove();
                } else {
                    break;
                }
            }
            lock.writeLock().unlock();

            Set<BigInteger> fee = Collections.synchronizedSet(new HashSet<>());
            if (this.getPoolStateView(en1.getKey()) != null) {
                this.getPoolStateView(en1.getKey())
                        .parallelStream()
                        .forEach(ps -> fee.add(ps.getFee()));
            }

            fee.parallelStream()
                    .forEach(
                            bi -> {
                                if (this.getFeeView().get(bi) != null) {
                                    this.getFeeView()
                                            .get(bi)
                                            .entrySet()
                                            .removeIf(
                                                    byteArrayWrapperTxDependListEntry ->
                                                            byteArrayWrapperTxDependListEntry
                                                                    .getValue()
                                                                    .getAddress()
                                                                    .equals(en1.getKey()));

                                    if (this.getFeeView().get(bi).isEmpty()) {
                                        this.getFeeView().remove(bi);
                                    }
                                }
                            });

            as.setDirty();
        }

        List<PooledTransaction> removedTxl = Collections.synchronizedList(new ArrayList<>());
        bwList.parallelStream()
                .forEach(
                        bw -> {
                            if (this.getMainMap().get(bw) != null) {
                                PooledTransaction pooledTx = this.getMainMap().get(bw).getTx();
                                removedTxl.add(pooledTx);

                                long timestamp = pooledTx.tx.getTimeStampBI().longValue() / multiplyM;
                                synchronized (this.getTimeView().get(timestamp)) {
                                    if (this.getTimeView().get(timestamp) == null) {
                                        LOG.error(
                                                "Txpool.remove can't find the timestamp in the map [{}]",
                                                pooledTx.toString());
                                        return;
                                    }

                                    this.getTimeView().get(timestamp).remove(bw);
                                    if (this.getTimeView().get(timestamp).isEmpty()) {
                                        this.getTimeView().remove(timestamp);
                                    }
                                }

                                lock.writeLock().lock();
                                this.getMainMap().remove(bw);
                                lock.writeLock().unlock();
                            }
                        });

        this.updateAccPoolState();
        this.updateFeeMap();

        if (LOG.isInfoEnabled()) {
            LOG.info("TxPoolA0.remove {} TX", removedTxl.size());
        }

        return removedTxl;
    }

    @Override
    public PooledTransaction remove(PooledTransaction tx) {
        return remove(Collections.singletonList(tx)).get(0);
    }

    /** Removes only txs whose transactionHash the given transactions (disregards energyUsed) */
    @Override
    public List<PooledTransaction> remove(List<PooledTransaction> pooledTxs) {

        List<PooledTransaction> removedTxl = Collections.synchronizedList(new ArrayList<>());
        Set<AionAddress> checkedAddress = Collections.synchronizedSet(new HashSet<>());

        for (PooledTransaction pooledTx : pooledTxs) {
            ByteArrayWrapper bw = ByteArrayWrapper.wrap(pooledTx.tx.getTransactionHash());
            lock.writeLock().lock();
            try {
                if (this.getMainMap().remove(bw) == null) {
                    continue;
                }
            } finally {
                lock.writeLock().unlock();
            }

            removedTxl.add(pooledTx);

            if (LOG.isTraceEnabled()) {
                LOG.trace(
                        "TxPoolA0.remove:[{}] nonce:[{}]",
                        ByteUtil.toHexString(pooledTx.tx.getTransactionHash()),
                        pooledTx.tx.getNonceBI().toString());
            }

            long timestamp = pooledTx.tx.getTimeStampBI().longValue() / multiplyM;
            if (this.getTimeView().get(timestamp) != null) {
                if (this.getTimeView().get(timestamp).remove(bw)) {
                    if (this.getTimeView().get(timestamp).isEmpty()) {
                        this.getTimeView().remove(timestamp);
                    }
                }
            }

            // remove the all transactions belong to the given address in the feeView
            AionAddress address = pooledTx.tx.getSenderAddress();
            Set<BigInteger> fee = Collections.synchronizedSet(new HashSet<>());
            if (!checkedAddress.contains(address)) {

                if (this.getPoolStateView(pooledTx.tx.getSenderAddress()) != null) {
                    this.getPoolStateView(pooledTx.tx.getSenderAddress())
                            .parallelStream()
                            .forEach(ps -> fee.add(ps.getFee()));
                }

                fee.parallelStream()
                        .forEach(
                                bi -> {
                                    if (this.getFeeView().get(bi) != null) {
                                        this.getFeeView()
                                                .get(bi)
                                                .entrySet()
                                                .removeIf(
                                                        byteArrayWrapperTxDependListEntry ->
                                                                byteArrayWrapperTxDependListEntry
                                                                        .getValue()
                                                                        .getAddress()
                                                                        .equals(address));

                                        if (this.getFeeView().get(bi).isEmpty()) {
                                            this.getFeeView().remove(bi);
                                        }
                                    }
                                });

                checkedAddress.add(address);
            }

            AccountState as = this.getAccView(pooledTx.tx.getSenderAddress());

            lock.writeLock().lock();
            as.getMap().remove(pooledTx.tx.getNonceBI());
            lock.writeLock().unlock();

            as.setDirty();
        }

        this.updateAccPoolState();
        this.updateFeeMap();

        if (LOG.isDebugEnabled()) {
            LOG.debug("TxPoolA0.remove TX remove [{}] removed [{}]", pooledTxs.size(), removedTxl.size());
        }

        return removedTxl;
    }

    public long getOutDateTime() {
        return txn_timeout;
    }

    @Override
    public int size() {
        return this.getMainMap().size();
    }

    @Override
    public void updateBlkNrgLimit(long nrg) {
        if (nrg < BLK_NRG_MIN) {
            blkNrgLimit.set(BLK_NRG_MIN);
        } else if (nrg > BLK_NRG_MAX) {
            blkNrgLimit.set(BLK_NRG_MAX);
        } else {
            blkNrgLimit.set(nrg);
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("TxPoolA0.updateBlkNrgLimit nrg[{}] blkNrgLimit[{}]", nrg, blkNrgLimit.get());
        }
    }

    @Override
    public PooledTransaction getPoolTx(AionAddress from, BigInteger txNonce) {
        if (from == null || txNonce == null) {
            LOG.error("TxPoolA0.getPoolTx null args");
            return null;
        }

        sortTxn();

        lock.readLock().lock();
        try {
            AbstractMap.SimpleEntry<ByteArrayWrapper, BigInteger> entry =
                    this.getAccView(from).getMap().get(txNonce);
            return (entry == null ? null : this.getMainMap().get(entry.getKey()).getTx());
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<AionTransaction> snapshotAll() {

        sortTxn();
        removeTimeoutTxn();

        List<AionTransaction> rtn = new ArrayList<>();
        for (Map.Entry<AionAddress, AccountState> as : this.getFullAcc().entrySet()) {
            for (Map.Entry<ByteArrayWrapper, BigInteger> txMap : as.getValue().getMap().values()) {
                if (this.getMainMap().get(txMap.getKey()) == null) {
                    LOG.error("can't find the tx in the mainMap");
                    continue;
                }

                rtn.add(this.getMainMap().get(txMap.getKey()).getTx().tx);
            }
        }

        if (LOG.isInfoEnabled()) {
            LOG.info(
                    "TxPoolA0.snapshot All return [{}] TX, poolSize[{}]",
                    rtn.size(),
                    getMainMap().size());
        }

        if (rtn.size() != getMainMap().size()) {
            LOG.error("size does not match!");
        }

        return rtn;
    }

    @Override
    public List<AionTransaction> snapshot() {

        sortTxn();
        removeTimeoutTxn();

        int cnt_txSz = 0;
        long cnt_nrg = 0;
        List<AionTransaction> rtn = new ArrayList<>();
        Set<ByteArrayWrapper> snapshotSet = new HashSet<>();
        Map<ByteArrayWrapper, Entry<ByteArrayWrapper, TxDependList>> nonPickedTx =
                new HashMap<>();
        for (Entry<BigInteger, Map<ByteArrayWrapper, TxDependList>> e :
                this.getFeeView().entrySet()) {

            if (LOG.isTraceEnabled()) {
                LOG.trace("snapshot  fee[{}]", e.getKey().toString());
            }

            SortedMap<BigInteger, Entry<ByteArrayWrapper, TxDependList>>
                    timeTxDep = Collections.synchronizedSortedMap(new TreeMap<>());
            for (Entry<ByteArrayWrapper, TxDependList> pair :
                    e.getValue().entrySet()) {
                BigInteger ts = pair.getValue().getTimeStamp();
                // If timestamp has collision, increase 1 for getting a new slot to put the
                // transaction pair.
                while (timeTxDep.get(ts) != null) {
                    ts = ts.add(BigInteger.ONE);
                }
                timeTxDep.put(ts, pair);
            }

            for (Entry<ByteArrayWrapper, TxDependList> pair :
                    timeTxDep.values()) {
                // Check the small nonce tx must been picked before put the high nonce tx
                ByteArrayWrapper dependTx = pair.getValue().getDependTx();
                if (dependTx == null || snapshotSet.contains(dependTx)) {
                    boolean firstTx = true;
                    for (ByteArrayWrapper bw : pair.getValue().getTxList()) {
                        PooledTransaction pendingTx = this.getMainMap().get(bw).getTx();

                        byte[] encodedItx = pendingTx.tx.getEncoded();
                        cnt_txSz += encodedItx.length;
                        // Set the lowerbound energy consume for the energy refund case.
                        // In the solidity, the refund energy might exceed the transaction energy consume like 21K.
                        // But the AVM does not. We use half of the Minimum energy consume as the transaction picking rule
                        cnt_nrg += pendingTx.energyConsumed < (MIN_ENERGY_CONSUME / 2 ) ? (MIN_ENERGY_CONSUME / 2) : pendingTx.energyConsumed;
                        if (LOG.isTraceEnabled()) {
                            LOG.trace(
                                    "from:[{}] nonce:[{}] txSize: txSize[{}] nrgConsume[{}]",
                                    pendingTx.tx.getSenderAddress().toString(),
                                    pendingTx.tx.getNonceBI().toString(),
                                    encodedItx.length,
                                    pendingTx.energyConsumed);
                        }

                        if (cnt_txSz < blkSizeLimit && cnt_nrg < blkNrgLimit.get()) {
                            try {
                                rtn.add(pendingTx.tx);
                                if (firstTx) {
                                    snapshotSet.add(bw);
                                    firstTx = false;
                                }
                            } catch (Exception ex) {
                                if (LOG.isErrorEnabled()) {
                                    LOG.error(
                                            "TxPoolA0.snapshot  exception[{}], return [{}] TX",
                                            ex.toString(),
                                            rtn.size());
                                }
                                return rtn;
                            }
                        } else {
                            if (LOG.isDebugEnabled()) {
                                LOG.debug(
                                        "Reach blockLimit: txSize[{}], nrgConsume[{}], tx#[{}]",
                                        cnt_txSz,
                                        cnt_nrg,
                                        rtn.size());
                            }

                            return rtn;
                        }
                    }

                    ByteArrayWrapper ancestor = pair.getKey();
                    while (nonPickedTx.get(ancestor) != null) {
                        firstTx = true;
                        for (ByteArrayWrapper bw :
                                nonPickedTx.get(ancestor).getValue().getTxList()) {
                            PooledTransaction pendingTx = this.getMainMap().get(bw).getTx();

                            byte[] encodedItx = pendingTx.tx.getEncoded();
                            cnt_txSz += encodedItx.length;
                            // Set the lowerbound energy consume for the energy refund case.
                            // In the solidity, the refund energy might exceed the transaction energy consume like 21K.
                            // But the AVM does not. We use half of the Minimum energy consume as the transaction picking rule
                            cnt_nrg += pendingTx.energyConsumed < (MIN_ENERGY_CONSUME / 2 ) ? (MIN_ENERGY_CONSUME / 2) : pendingTx.energyConsumed;
                            if (LOG.isTraceEnabled()) {
                                LOG.trace(
                                        "from:[{}] nonce:[{}] txSize: txSize[{}] nrgConsume[{}]",
                                        pendingTx.tx.getSenderAddress().toString(),
                                        pendingTx.tx.getNonceBI().toString(),
                                        encodedItx.length,
                                        pendingTx.energyConsumed);
                            }

                            if (cnt_txSz < blkSizeLimit && cnt_nrg < blkNrgLimit.get()) {
                                try {
                                    rtn.add(pendingTx.tx);
                                    if (firstTx) {
                                        snapshotSet.add(bw);
                                        firstTx = false;
                                    }
                                } catch (Exception ex) {
                                    if (LOG.isErrorEnabled()) {
                                        LOG.error(
                                                "TxPoolA0.snapshot  exception[{}], return [{}] TX",
                                                ex.toString(),
                                                rtn.size());
                                    }
                                    return rtn;
                                }
                            } else {
                                if (LOG.isInfoEnabled()) {
                                    LOG.info(
                                            "TxPoolA0.snapshot return Tx[{}] TxSize[{}] Nrg[{}] Pool[{}]",
                                            rtn.size(),
                                            cnt_txSz,
                                            cnt_nrg,
                                            getMainMap().size());
                                }

                                return rtn;
                            }
                        }

                        ancestor = nonPickedTx.get(ancestor).getKey();
                    }
                } else {
                    // one low fee small nonce tx has been picked,and then search from this map.
                    nonPickedTx.put(pair.getValue().getDependTx(), pair);
                }
            }
        }

        if (LOG.isInfoEnabled()) {
            LOG.info(
                    "TxPoolA0.snapshot return [{}] TX, poolSize[{}]",
                    rtn.size(),
                    getMainMap().size());
        }

        return rtn;
    }

    @VisibleForTesting
    // This is a duplicated method from snapshot() for testing
    public List<AionTransaction> snapshot(long time) {

        sortTxn();
        removeTimeoutTxn(time);

        int cnt_txSz = 0;
        long cnt_nrg = 0;
        List<AionTransaction> rtn = new ArrayList<>();
        Set<ByteArrayWrapper> snapshotSet = new HashSet<>();
        Map<ByteArrayWrapper, Entry<ByteArrayWrapper, TxDependList>> nonPickedTx =
            new HashMap<>();
        for (Entry<BigInteger, Map<ByteArrayWrapper, TxDependList>> e :
            this.getFeeView().entrySet()) {

            if (LOG.isTraceEnabled()) {
                LOG.trace("snapshot  fee[{}]", e.getKey().toString());
            }

            SortedMap<BigInteger, Entry<ByteArrayWrapper, TxDependList>>
                timeTxDep = Collections.synchronizedSortedMap(new TreeMap<>());
            for (Entry<ByteArrayWrapper, TxDependList> pair :
                e.getValue().entrySet()) {
                BigInteger ts = pair.getValue().getTimeStamp();
                // If timestamp has collision, increase 1 for getting a new slot to put the
                // transaction pair.
                while (timeTxDep.get(ts) != null) {
                    ts = ts.add(BigInteger.ONE);
                }
                timeTxDep.put(ts, pair);
            }

            for (Entry<ByteArrayWrapper, TxDependList> pair :
                timeTxDep.values()) {
                // Check the small nonce tx must been picked before put the high nonce tx
                ByteArrayWrapper dependTx = pair.getValue().getDependTx();
                if (dependTx == null || snapshotSet.contains(dependTx)) {
                    boolean firstTx = true;
                    for (ByteArrayWrapper bw : pair.getValue().getTxList()) {
                        PooledTransaction pendingTx = this.getMainMap().get(bw).getTx();

                        byte[] encodedItx = pendingTx.tx.getEncoded();
                        cnt_txSz += encodedItx.length;
                        // Set the lowerbound energy consume for the energy refund case.
                        // In the solidity, the refund energy might exceed the transaction energy consume like 21K.
                        // But the AVM does not. We use half of the Minimum energy consume as the transaction picking rule
                        cnt_nrg += pendingTx.energyConsumed < (MIN_ENERGY_CONSUME / 2 ) ? (MIN_ENERGY_CONSUME / 2) : pendingTx.energyConsumed;
                        if (LOG.isTraceEnabled()) {
                            LOG.trace(
                                "from:[{}] nonce:[{}] txSize: txSize[{}] nrgConsume[{}]",
                                pendingTx.tx.getSenderAddress().toString(),
                                pendingTx.tx.getNonceBI().toString(),
                                encodedItx.length,
                                pendingTx.energyConsumed);
                        }

                        if (cnt_txSz < blkSizeLimit && cnt_nrg < blkNrgLimit.get()) {
                            try {
                                rtn.add(pendingTx.tx);
                                if (firstTx) {
                                    snapshotSet.add(bw);
                                    firstTx = false;
                                }
                            } catch (Exception ex) {
                                if (LOG.isErrorEnabled()) {
                                    LOG.error(
                                        "TxPoolA0.snapshot  exception[{}], return [{}] TX",
                                        ex.toString(),
                                        rtn.size());
                                }
                                return rtn;
                            }
                        } else {
                            if (LOG.isDebugEnabled()) {
                                LOG.debug(
                                    "Reach blockLimit: txSize[{}], nrgConsume[{}], tx#[{}]",
                                    cnt_txSz,
                                    cnt_nrg,
                                    rtn.size());
                            }

                            return rtn;
                        }
                    }

                    ByteArrayWrapper ancestor = pair.getKey();
                    while (nonPickedTx.get(ancestor) != null) {
                        firstTx = true;
                        for (ByteArrayWrapper bw :
                            nonPickedTx.get(ancestor).getValue().getTxList()) {
                            PooledTransaction pendingTx = this.getMainMap().get(bw).getTx();

                            byte[] encodedItx = pendingTx.tx.getEncoded();
                            cnt_txSz += encodedItx.length;
                            // Set the lowerbound energy consume for the energy refund case.
                            // In the solidity, the refund energy might exceed the transaction energy consume like 21K.
                            // But the AVM does not. We use half of the Minimum energy consume as the transaction picking rule
                            cnt_nrg += pendingTx.energyConsumed < (MIN_ENERGY_CONSUME / 2 ) ? (MIN_ENERGY_CONSUME / 2) : pendingTx.energyConsumed;
                            if (LOG.isTraceEnabled()) {
                                LOG.trace(
                                    "from:[{}] nonce:[{}] txSize: txSize[{}] nrgConsume[{}]",
                                    pendingTx.tx.getSenderAddress().toString(),
                                    pendingTx.tx.getNonceBI().toString(),
                                    encodedItx.length,
                                    pendingTx.energyConsumed);
                            }

                            if (cnt_txSz < blkSizeLimit && cnt_nrg < blkNrgLimit.get()) {
                                try {
                                    rtn.add(pendingTx.tx);
                                    if (firstTx) {
                                        snapshotSet.add(bw);
                                        firstTx = false;
                                    }
                                } catch (Exception ex) {
                                    if (LOG.isErrorEnabled()) {
                                        LOG.error(
                                            "TxPoolA0.snapshot  exception[{}], return [{}] TX",
                                            ex.toString(),
                                            rtn.size());
                                    }
                                    return rtn;
                                }
                            } else {
                                if (LOG.isInfoEnabled()) {
                                    LOG.info(
                                        "TxPoolA0.snapshot return Tx[{}] TxSize[{}] Nrg[{}] Pool[{}]",
                                        rtn.size(),
                                        cnt_txSz,
                                        cnt_nrg,
                                        getMainMap().size());
                                }

                                return rtn;
                            }
                        }

                        ancestor = nonPickedTx.get(ancestor).getKey();
                    }
                } else {
                    // one low fee small nonce tx has been picked,and then search from this map.
                    nonPickedTx.put(pair.getValue().getDependTx(), pair);
                }
            }
        }

        if (LOG.isInfoEnabled()) {
            LOG.info(
                "TxPoolA0.snapshot return [{}] TX, poolSize[{}]",
                rtn.size(),
                getMainMap().size());
        }

        return rtn;
    }

    @Override
    public String getVersion() {
        return "0.1.0";
    }

    public BigInteger bestPoolNonce(AionAddress addr) {
        return getBestNonce(addr);
    }

    private void removeTimeoutTxn() {

        long ts = TimeInstant.now().toEpochSec() - txn_timeout;
        List<PooledTransaction> txl = Collections.synchronizedList(new ArrayList<>());

        this.getTimeView()
                .entrySet()
                .parallelStream()
                .forEach(
                        e -> {
                            if (e.getKey() < ts) {
                                for (ByteArrayWrapper bw : e.getValue()) {
                                    txl.add(this.getMainMap().get(bw).getTx());
                                }
                            }
                        });

        if (txl.isEmpty()) {
            return;
        }

        this.addOutDatedList(txl);
        this.remove(txl);

        if (LOG.isDebugEnabled()) {
            LOG.debug(
                    "TxPoolA0.remove return [{}] TX, poolSize[{}]",
                    txl.size(),
                    getMainMap().size());
        }
    }

    @VisibleForTesting
    // This is a duplicated method from removeTimeoutTxn() for testing
    private void removeTimeoutTxn(long time) {

        long ts = time - txn_timeout;
        List<PooledTransaction> txl = Collections.synchronizedList(new ArrayList<>());

        this.getTimeView()
            .entrySet()
            .parallelStream()
            .forEach(
                e -> {
                    if (e.getKey() < ts) {
                        for (ByteArrayWrapper bw : e.getValue()) {
                            txl.add(this.getMainMap().get(bw).getTx());
                        }
                    }
                });

        if (txl.isEmpty()) {
            return;
        }

        this.addOutDatedList(txl);
        this.remove(txl);

        if (LOG.isDebugEnabled()) {
            LOG.debug(
                "TxPoolA0.remove return [{}] TX, poolSize[{}]",
                txl.size(),
                getMainMap().size());
        }
    }

    /**
     * mainMap : Map<ByteArrayWrapper, TXState> @ByteArrayWrapper transaction hash @TXState
     * transaction data and sort status
     */
    // TODO : should limit size
    private final Map<ByteArrayWrapper, TXState> mainMap = new ConcurrentHashMap<>();
    /**
     * timeView : SortedMap<Long, LinkedHashSet<ByteArrayWrapper>> @Long transaction
     * timestamp @LinkedHashSet<ByteArrayWrapper> the hashSet of the transaction hash*
     */
    private final SortedMap<Long, LinkedHashSet<ByteArrayWrapper>> timeView =
        Collections.synchronizedSortedMap(new TreeMap<>());
    /**
     * feeView : SortedMap<BigInteger, LinkedHashSet<TxPoolList<ByteArrayWrapper>>> @BigInteger
     * energy cost = energy consumption * energy price @LinkedHashSet<TxPoolList<ByteArrayWrapper>>
     * the TxPoolList of the first transaction hash
     */
    private final SortedMap<BigInteger, Map<ByteArrayWrapper, TxDependList>>
        feeView = Collections.synchronizedSortedMap(new TreeMap<>(Collections.reverseOrder()));
    /**
     * accountView : Map<ByteArrayWrapper, AccountState> @ByteArrayWrapper account
     * address @AccountState
     */
    private final Map<AionAddress, AccountState> accountView = new ConcurrentHashMap<>();
    /**
     * poolStateView : Map<ByteArrayWrapper, List<PoolState>> @ByteArrayWrapper account
     * address @PoolState continuous transaction state including starting nonce
     */
    private final Map<AionAddress, List<PoolState>> poolStateView = new ConcurrentHashMap<>();

    private final List<PooledTransaction> outDated = new ArrayList<>();

    private final Map<AionAddress, BigInteger> bestNonce = new ConcurrentHashMap<>();

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private Map<ByteArrayWrapper, TXState> getMainMap() {
        return this.mainMap;
    }

    private SortedMap<BigInteger, Map<ByteArrayWrapper, TxDependList>>
    getFeeView() {
        return this.feeView;
    }

    private AccountState getAccView(AionAddress acc) {

        this.accountView.computeIfAbsent(acc, k -> new AccountState());
        return this.accountView.get(acc);
    }

    private Map<AionAddress, AccountState> getFullAcc() {
        return this.accountView;
    }

    private List<PoolState> getPoolStateView(AionAddress acc) {

        if (this.accountView.get(acc) == null) {
            this.poolStateView.put(acc, new LinkedList<>());
        }
        return this.poolStateView.get(acc);
    }

    private List<PooledTransaction> getOutdatedListImpl() {
        List<PooledTransaction> rtn = new ArrayList<>(this.outDated);
        this.outDated.clear();

        return rtn;
    }

    private void addOutDatedList(List<PooledTransaction> txl) {
        this.outDated.addAll(txl);
    }

    public void clear() {
        this.mainMap.clear();
        this.timeView.clear();
        this.feeView.clear();
        this.accountView.clear();
        this.poolStateView.clear();
        this.outDated.clear();
    }

    private void sortTxn() {

        Map<AionAddress, Map<BigInteger, SimpleEntry<ByteArrayWrapper, BigInteger>>> accMap =
            new ConcurrentHashMap<>();
        SortedMap<Long, LinkedHashSet<ByteArrayWrapper>> timeMap =
            Collections.synchronizedSortedMap(new TreeMap<>());

        Map<PooledTransaction, Long> updatedTx = new HashMap<>();
        this.mainMap
            .entrySet()
            .parallelStream()
            .forEach(
                e -> {
                    TXState ts = e.getValue();
                    if (ts.sorted()) {
                        return;
                    }

                    PooledTransaction pooledTx = ts.getTx();

                    // Gen temp timeMap
                    long timestamp = pooledTx.tx.getTimeStampBI().longValue() / multiplyM;

                    Map<BigInteger, SimpleEntry<ByteArrayWrapper, BigInteger>> nonceMap;
                    PooledTransaction replacedTx = null;
                    synchronized (accMap) {
                        if (accMap.get(pooledTx.tx.getSenderAddress()) != null) {
                            nonceMap = accMap.get(pooledTx.tx.getSenderAddress());
                        } else {
                            nonceMap = Collections.synchronizedSortedMap(new TreeMap<>());
                        }

                        // considering refactor later
                        BigInteger nonce = pooledTx.tx.getNonceBI();

                        long nrgConsumed = pooledTx.energyConsumed < (MIN_ENERGY_CONSUME / 2 ) ? (MIN_ENERGY_CONSUME / 2) : pooledTx.energyConsumed;

                        BigInteger nrgCharge =
                            BigInteger.valueOf(pooledTx.tx.getEnergyPrice())
                                .multiply(BigInteger.valueOf(nrgConsumed));

                        if (LOG.isTraceEnabled()) {
                            LOG.trace(
                                "AbstractTxPool.sortTxn Put tx into nonceMap: nonce:[{}] ts:[{}] nrgCharge:[{}]",
                                nonce,
                                ByteUtil.toHexString(e.getKey().toBytes()),
                                nrgCharge.toString());
                        }

                        // considering same nonce tx, only put the latest tx.
                        if (nonceMap.get(nonce) != null) {
                            try {
                                if (this.mainMap
                                    .get(nonceMap.get(nonce).getKey())
                                    .getTx()
                                    .tx
                                    .getTimeStampBI()
                                    .compareTo(pooledTx.tx.getTimeStampBI())
                                    < 1) {
                                    replacedTx =
                                        this.mainMap
                                            .get(nonceMap.get(nonce).getKey())
                                            .getTx();
                                    updatedTx.put(replacedTx, timestamp);
                                    nonceMap.put(
                                        nonce,
                                        new SimpleEntry<>(e.getKey(), nrgCharge));
                                }
                            } catch (Exception ex) {
                                LOG.error(
                                    "AbsTxPool.sortTxn {} [{}]",
                                    ex.toString(),
                                    pooledTx.toString());
                            }
                        } else {
                            nonceMap.put(nonce, new SimpleEntry<>(e.getKey(), nrgCharge));
                        }

                        if (LOG.isTraceEnabled()) {
                            LOG.trace(
                                "AbstractTxPool.sortTxn Put tx into accMap: acc:[{}] mapSize[{}] ",
                                pooledTx.tx.getSenderAddress().toString(),
                                nonceMap.size());
                        }

                        accMap.put(pooledTx.tx.getSenderAddress(), nonceMap);
                    }

                    LinkedHashSet<ByteArrayWrapper> lhs;
                    synchronized (timeMap) {
                        if (timeMap.get(timestamp) != null) {
                            lhs = timeMap.get(timestamp);
                        } else {
                            lhs = new LinkedHashSet<>();
                        }

                        lhs.add(e.getKey());

                        if (LOG.isTraceEnabled()) {
                            LOG.trace(
                                "AbstractTxPool.sortTxn Put txHash into timeMap: ts:[{}] size:[{}]",
                                timestamp,
                                lhs.size());
                        }

                        timeMap.put(timestamp, lhs);

                        if (replacedTx != null) {
                            long t = replacedTx.tx.getTimeStampBI().longValue() / multiplyM;
                            if (timeMap.get(t) != null) {
                                timeMap.get(t)
                                    .remove(
                                        ByteArrayWrapper.wrap(
                                            replacedTx.tx.getTransactionHash()));
                            }
                        }
                    }

                    ts.setSorted();
                });

        if (!updatedTx.isEmpty()) {
            for (Map.Entry<PooledTransaction, Long> en : updatedTx.entrySet()) {
                ByteArrayWrapper bw = ByteArrayWrapper.wrap(en.getKey().tx.getTransactionHash());
                if (this.timeView.get(en.getValue()) != null) {
                    this.timeView.get(en.getValue()).remove(bw);
                }

                lock.writeLock().lock();
                this.mainMap.remove(bw);
                lock.writeLock().unlock();
            }
        }

        if (!accMap.isEmpty()) {

            timeMap.entrySet()
                .parallelStream()
                .forEach(
                    e -> {
                        if (this.timeView.get(e.getKey()) == null) {
                            this.timeView.put(e.getKey(), e.getValue());
                        } else {
                            this.timeView.get(e.getKey()).addAll(e.getValue());
                        }
                    });

            accMap.entrySet()
                .parallelStream()
                .forEach(
                    e -> {
                        lock.writeLock().lock();
                        this.accountView.computeIfAbsent(
                            e.getKey(), k -> new AccountState());
                        this.accountView.get(e.getKey()).updateMap(e.getValue());
                        lock.writeLock().unlock();
                    });

            updateAccPoolState();
            updateFeeMap();
        }
    }

    private SortedMap<Long, LinkedHashSet<ByteArrayWrapper>> getTimeView() {
        return this.timeView;
    }

    private void updateAccPoolState() {

        // iterate tx by account
        List<AionAddress> clearAddr = new ArrayList<>();
        for (Entry<AionAddress, AccountState> e : this.accountView.entrySet()) {
            AccountState as = e.getValue();
            if (as.isDirty()) {

                if (as.getMap().isEmpty()) {
                    this.poolStateView.remove(e.getKey());
                    clearAddr.add(e.getKey());
                } else {
                    // checking AccountState given by account
                    List<PoolState> psl = this.poolStateView.get(e.getKey());
                    if (psl == null) {
                        psl = new LinkedList<>();
                    }

                    List<PoolState> newPoolState = new LinkedList<>();
                    // Checking new tx has been include into old pools.
                    BigInteger txNonceStart = as.getFirstNonce();

                    if (txNonceStart != null) {
                        if (LOG.isTraceEnabled()) {
                            LOG.trace(
                                "AbstractTxPool.updateAccPoolState fn [{}]",
                                txNonceStart.toString());
                        }
                        for (PoolState ps : psl) {
                            // check the previous txn status in the old
                            // PoolState
                            if (isClean(ps, as)
                                && ps.firstNonce.equals(txNonceStart)
                                && ps.combo == seqTxCountMax) {
                                ps.resetInFeePool();
                                newPoolState.add(ps);

                                if (LOG.isTraceEnabled()) {
                                    LOG.trace(
                                        "AbstractTxPool.updateAccPoolState add fn [{}]",
                                        ps.firstNonce.toString());
                                }

                                txNonceStart = txNonceStart.add(BigInteger.valueOf(seqTxCountMax));
                            } else {
                                // remove old poolState in the feeMap
                                if (this.feeView.get(ps.getFee()) != null) {

                                    if (e.getValue().getMap().get(ps.firstNonce) != null) {
                                        this.feeView
                                            .get(ps.getFee())
                                            .remove(
                                                e.getValue()
                                                    .getMap()
                                                    .get(ps.firstNonce)
                                                    .getKey());
                                    }

                                    if (LOG.isTraceEnabled()) {
                                        LOG.trace(
                                            "AbstractTxPool.updateAccPoolState remove fn [{}]",
                                            ps.firstNonce.toString());
                                    }

                                    if (this.feeView.get(ps.getFee()).isEmpty()) {
                                        this.feeView.remove(ps.getFee());
                                    }
                                }
                            }
                        }
                    }

                    int cnt = 0;
                    BigInteger fee = BigInteger.ZERO;
                    BigInteger totalFee = BigInteger.ZERO;

                    for (Entry<BigInteger, SimpleEntry<ByteArrayWrapper, BigInteger>> en :
                        as.getMap().entrySet()) {
                        if (LOG.isTraceEnabled()) {
                            LOG.trace(
                                "AbstractTxPool.updateAccPoolState mapsize[{}] nonce:[{}] cnt[{}] txNonceStart[{}]",
                                as.getMap().size(),
                                en.getKey().toString(),
                                cnt,
                                txNonceStart != null ? txNonceStart.toString() : null);
                        }
                        if (en.getKey()
                            .equals(
                                txNonceStart != null
                                    ? txNonceStart.add(BigInteger.valueOf(cnt))
                                    : null)) {
                            if (en.getValue().getValue().compareTo(fee) > -1) {
                                fee = en.getValue().getValue();
                                totalFee = totalFee.add(fee);

                                if (++cnt == seqTxCountMax) {
                                    if (LOG.isTraceEnabled()) {
                                        LOG.trace(
                                            "AbstractTxPool.updateAccPoolState case1 - nonce:[{}] totalFee:[{}] cnt:[{}]",
                                            txNonceStart,
                                            totalFee.toString(),
                                            cnt);
                                    }
                                    newPoolState.add(
                                        new PoolState(
                                            txNonceStart,
                                            totalFee.divide(BigInteger.valueOf(cnt)),
                                            cnt));

                                    txNonceStart = en.getKey().add(BigInteger.ONE);
                                    totalFee = BigInteger.ZERO;
                                    fee = BigInteger.ZERO;
                                    cnt = 0;
                                }
                            } else {
                                if (LOG.isTraceEnabled()) {
                                    LOG.trace(
                                        "AbstractTxPool.updateAccPoolState case2 - nonce:[{}] totalFee:[{}] cnt:[{}]",
                                        txNonceStart,
                                        totalFee.toString(),
                                        cnt);
                                }
                                newPoolState.add(
                                    new PoolState(
                                        txNonceStart,
                                        totalFee.divide(BigInteger.valueOf(cnt)),
                                        cnt));

                                // next PoolState
                                txNonceStart = en.getKey();
                                fee = en.getValue().getValue();
                                totalFee = fee;
                                cnt = 1;
                            }
                        }
                    }

                    if (totalFee.signum() == 1) {

                        if (LOG.isTraceEnabled()) {
                            LOG.trace(
                                "AbstractTxPool.updateAccPoolState case3 - nonce:[{}] totalFee:[{}] cnt:[{}] bw:[{}]",
                                txNonceStart,
                                totalFee.toString(),
                                cnt,
                                e.getKey().toString());
                        }

                        newPoolState.add(
                            new PoolState(
                                txNonceStart,
                                totalFee.divide(BigInteger.valueOf(cnt)),
                                cnt));
                    }

                    this.poolStateView.put(e.getKey(), newPoolState);

                    if (LOG.isTraceEnabled()) {
                        this.poolStateView.forEach(
                            (k, v) ->
                                v.forEach(
                                    l -> {
                                        LOG.trace(
                                            "AbstractTxPool.updateAccPoolState - the first nonce of the poolState list:[{}]",
                                            l.firstNonce);
                                    }));
                    }
                    as.sorted();
                }
            }
        }

        if (!clearAddr.isEmpty()) {
            clearAddr.forEach(
                addr -> {
                    lock.writeLock().lock();
                    this.accountView.remove(addr);
                    lock.writeLock().unlock();
                    this.bestNonce.remove(addr);
                });
        }
    }

    private boolean isClean(PoolState ps, AccountState as) {
        if (ps == null || as == null) {
            throw new NullPointerException();
        }

        for (BigInteger bi = ps.getFirstNonce();
            bi.compareTo(ps.firstNonce.add(BigInteger.valueOf(ps.getCombo()))) < 0;
            bi = bi.add(BigInteger.ONE)) {
            if (!as.getMap().containsKey(bi)) {
                return false;
            }
        }

        return true;
    }

    private void updateFeeMap() {
        for (Entry<AionAddress, List<PoolState>> e : this.poolStateView.entrySet()) {
            ByteArrayWrapper dependTx = null;
            for (PoolState ps : e.getValue()) {

                if (LOG.isTraceEnabled()) {
                    LOG.trace(
                        "updateFeeMap addr[{}] inFp[{}] fn[{}] cb[{}] fee[{}]",
                        e.getKey().toString(),
                        ps.isInFeePool(),
                        ps.getFirstNonce().toString(),
                        ps.getCombo(),
                        ps.getFee().toString());
                }

                if (ps.isInFeePool()) {
                    dependTx =
                        this.accountView
                            .get(e.getKey())
                            .getMap()
                            .get(ps.getFirstNonce())
                            .getKey();
                    if (LOG.isTraceEnabled()) {
                        LOG.trace("updateFeeMap isInFeePool [{}]", dependTx.toString());
                    }
                } else {

                    TxDependList txl = new TxDependList();
                    BigInteger timestamp = BigInteger.ZERO;
                    for (BigInteger i = ps.firstNonce;
                        i.compareTo(ps.firstNonce.add(BigInteger.valueOf(ps.combo))) < 0;
                        i = i.add(BigInteger.ONE)) {

                        ByteArrayWrapper bw =
                            this.accountView.get(e.getKey()).getMap().get(i).getKey();
                        if (i.equals(ps.firstNonce)) {
                            timestamp = this.mainMap.get(bw).getTx().tx.getTimeStampBI();
                        }

                        txl.addTx(bw);
                    }

                    if (!txl.isEmpty()) {
                        txl.setDependTx(dependTx);
                        dependTx = txl.getTxList().get(0);
                        txl.setAddress(e.getKey());
                        txl.setTimeStamp(timestamp);
                    }

                    if (this.feeView.get(ps.fee) == null) {
                        Map<ByteArrayWrapper, TxDependList> set =
                            new LinkedHashMap<>();
                        set.put(txl.getTxList().get(0), txl);

                        if (LOG.isTraceEnabled()) {
                            LOG.trace("updateFeeMap new feeView put fee[{}]", ps.fee);
                        }

                        this.feeView.put(ps.fee, set);
                    } else {

                        if (LOG.isTraceEnabled()) {
                            LOG.trace("updateFeeMap update feeView put fee[{}]", ps.fee);
                        }

                        this.feeView.get(ps.fee).put(txl.getTxList().get(0), txl);
                    }

                    ps.setInFeePool();
                }
            }
        }
    }

    private void setBestNonce(AionAddress addr, BigInteger bn) {
        if (addr == null || bn == null) {
            throw new NullPointerException();
        }

        if (LOG.isTraceEnabled()) {
            LOG.trace(
                "addr[{}] bn[{}] txnonce[{}]",
                addr.toString(),
                bestNonce.get(addr) == null ? "-1" : bestNonce.get(addr).toString(),
                bn.toString());
        }

        if (bestNonce.get(addr) == null || bestNonce.get(addr).compareTo(bn) < 0) {
            bestNonce.put(addr, bn);
        }
    }

    private BigInteger getBestNonce(AionAddress addr) {
        if (addr == null || bestNonce.get(addr) == null) {
            return BigInteger.ONE.negate();
        }

        return bestNonce.get(addr);
    }

    protected class TXState {
        private boolean sorted = false;
        private PooledTransaction tx;

        TXState(PooledTransaction tx) {
            this.tx = tx;
        }

        public PooledTransaction getTx() {
            return this.tx;
        }

        boolean sorted() {
            return this.sorted;
        }

        void setSorted() {
            this.sorted = true;
        }
    }

    protected class PoolState {
        private final AtomicBoolean inFeePool = new AtomicBoolean(false);
        private BigInteger fee;
        private BigInteger firstNonce;
        private int combo;

        PoolState(BigInteger nonce, BigInteger fee, int combo) {
            this.firstNonce = nonce;
            this.combo = combo;
            this.fee = fee;
        }

        public boolean contains(BigInteger bi) {
            return (bi.compareTo(firstNonce) > -1)
                && (bi.compareTo(firstNonce.add(BigInteger.valueOf(combo))) < 0);
        }

        BigInteger getFee() {
            return fee;
        }

        BigInteger getFirstNonce() {
            return firstNonce;
        }

        int getCombo() {
            return combo;
        }

        boolean isInFeePool() {
            return inFeePool.get();
        }

        void setInFeePool() {
            inFeePool.set(true);
        }

        void resetInFeePool() {
            inFeePool.set(false);
        }
    }
}
