package org.aion.zero.impl.sync;

import static org.aion.util.string.StringUtils.getNodeIdShort;

import com.google.common.annotations.VisibleForTesting;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.aion.evtmgr.IEvent;
import org.aion.evtmgr.IEventMgr;
import org.aion.evtmgr.impl.evt.EventConsensus;
import org.aion.log.AionLoggerFactory;
import org.aion.log.LogEnum;
import org.aion.mcf.blockchain.Block;
import org.aion.mcf.blockchain.BlockHeader;
import org.aion.p2p.INode;
import org.aion.zero.impl.config.StatsType;
import org.aion.p2p.IP2pMgr;
import org.aion.util.bytes.ByteUtil;
import org.aion.util.conversions.Hex;
import org.aion.util.types.ByteArrayWrapper;
import org.aion.zero.impl.blockchain.AionBlockchainImpl;
import org.aion.zero.impl.blockchain.ChainConfiguration;
import org.aion.zero.impl.sync.msg.ReqBlocksBodies;
import org.aion.zero.impl.sync.msg.ReqStatus;
import org.aion.zero.impl.sync.statistics.BlockType;
import org.aion.zero.impl.sync.statistics.RequestType;
import org.aion.zero.impl.types.BlockUtil;
import org.aion.zero.impl.valid.BlockHeaderValidator;
import org.apache.commons.collections4.map.LRUMap;
import org.slf4j.Logger;

/** @author chris */
public final class SyncMgr {

    // interval - show status
    private static final int INTERVAL_SHOW_STATUS = 10000;
    /**
     * NOTE: This value was selected based on heap dumps for normal execution where the queue was
     * holding around 60 items.
     */
    private static final int QUEUE_CAPACITY = 100;

    private static final Logger log = AionLoggerFactory.getLogger(LogEnum.SYNC.name());
    private static final Logger survey_log = AionLoggerFactory.getLogger(LogEnum.SURVEY.name());
    private static final Logger p2pLog = AionLoggerFactory.getLogger(LogEnum.P2P.name());

    private final NetworkStatus networkStatus = new NetworkStatus();

    private SyncHeaderRequestManager syncHeaderRequestManager;

    // store the hashes of blocks which have been successfully imported
    private final Map<ByteArrayWrapper, Object> importedBlockHashes =
            Collections.synchronizedMap(new LRUMap<>(4096));
    private AionBlockchainImpl chain;
    private IP2pMgr p2pMgr;
    private IEventMgr evtMgr;
    private SyncStats stats;

    private ScheduledExecutorService syncExecutors = Executors.newScheduledThreadPool(3);
    private ExecutorService importExecutor = Executors.newSingleThreadExecutor();

    private BlockHeaderValidator blockHeaderValidator;
    private volatile long timeUpdated = 0;

    public SyncMgr(final AionBlockchainImpl _chain,
        final IP2pMgr _p2pMgr,
        final IEventMgr _evtMgr,
        final boolean _showStatus,
        final Set<StatsType> showStatistics,
        final int maxActivePeers) {

        p2pMgr = _p2pMgr;
        chain = _chain;
        evtMgr = _evtMgr;

        blockHeaderValidator = new ChainConfiguration().createBlockHeaderValidator();

        long selfBest = chain.getBestBlock().getNumber();
        stats = new SyncStats(selfBest, _showStatus, showStatistics, maxActivePeers);

        syncHeaderRequestManager =  new SyncHeaderRequestManager(log, survey_log);

        syncExecutors.scheduleAtFixedRate(() -> getStatus(p2pMgr, stats), 0L, 2, TimeUnit.SECONDS);

        if (_showStatus) {
            syncExecutors.scheduleWithFixedDelay(() -> showStatus(chain, networkStatus, Collections.unmodifiableSet(new HashSet<>(showStatistics)), stats), 0, INTERVAL_SHOW_STATUS, TimeUnit.MILLISECONDS);
        }

        setupEventHandler();
    }

    private static final ReqStatus reqStatus = new ReqStatus();

    /**
     * Makes a status request to each peer.
     */
    @VisibleForTesting
    static void getStatus(IP2pMgr p2pMgr, SyncStats syncStats) {
        Thread.currentThread().setName("sync-gs");
        for (INode node : p2pMgr.getActiveNodes().values()) {
            p2pMgr.send(node.getIdHash(), node.getIdShort(), reqStatus);
            syncStats.updateTotalRequestsToPeer(node.getIdShort(), RequestType.STATUS);
            syncStats.updateRequestTime(node.getIdShort(), System.nanoTime(), RequestType.STATUS);
        }
    }

    /**
     * Display the current node status.
     */
    @VisibleForTesting
    static void showStatus(AionBlockchainImpl chain, NetworkStatus networkStatus, Set<StatsType> showStatistics, SyncStats syncStats) {
        Thread.currentThread().setName("sync-ss");
        p2pLog.info(getStatus(chain, networkStatus, syncStats));

        String requestedStats;
        if (showStatistics.contains(StatsType.REQUESTS)) {
            requestedStats = syncStats.dumpRequestStats();
            if (!requestedStats.isEmpty()) {
                p2pLog.info(requestedStats);
            }
        }

        if (showStatistics.contains(StatsType.SEEDS)) {
            requestedStats = syncStats.dumpTopSeedsStats();
            if (!requestedStats.isEmpty()) {
                p2pLog.info(requestedStats);
            }
        }

        if (showStatistics.contains(StatsType.LEECHES)) {
            requestedStats = syncStats.dumpTopLeechesStats();
            if (!requestedStats.isEmpty()) {
                p2pLog.info(requestedStats);
            }
        }

        if (showStatistics.contains(StatsType.RESPONSES)) {
            requestedStats = syncStats.dumpResponseStats();
            if (!requestedStats.isEmpty()) {
                p2pLog.info(requestedStats);
            }
        }

        if (showStatistics.contains(StatsType.SYSTEMINFO)) {
            requestedStats = syncStats.dumpSystemInfo();
            if (!requestedStats.isEmpty()) {
                p2pLog.info(requestedStats);
            }
        }
    }

    private static String getStatus(AionBlockchainImpl chain, NetworkStatus networkStatus, SyncStats syncStats) {
        Block selfBest = chain.getBestBlock();
        String selfTd = selfBest.getTotalDifficulty().toString(10);

        return "sync-status avg-import="
                + String.format("%.2f", syncStats.getAvgBlocksPerSec()) + " b/s"
                + " td=" + selfTd + "/" + networkStatus.getTargetTotalDiff().toString(10)
                + " b-num=" + selfBest.getNumber() + "/" + networkStatus.getTargetBestBlockNumber()
                + " b-hash=" + Hex.toHexString(chain.getBestBlockHash()) + "/" + networkStatus.getTargetBestBlockHash();
    }

    /**
     * @param _displayId String
     * @param _remoteBestBlockNumber long
     * @param _remoteBestBlockHash byte[]
     * @param _remoteTotalDiff BigInteger null check for _remoteBestBlockHash && _remoteTotalDiff
     *     implemented on ResStatusHandler before pass through
     */
    public void updateNetworkStatus(
            String _displayId,
            long _remoteBestBlockNumber,
            final byte[] _remoteBestBlockHash,
            BigInteger _remoteTotalDiff,
            byte _apiVersion,
            short _peerCount,
            int _pendingTxCount,
            int _latency) {

        // self
        BigInteger selfTd = this.chain.getTotalDifficulty();

        // trigger send headers routine immediately
        if (_remoteTotalDiff.compareTo(selfTd) > 0) {
            this.getHeaders(selfTd);
        }

        long now = System.currentTimeMillis();
        if ((now - timeUpdated) > 1000) {
            timeUpdated = now;
            // update network best status
            synchronized (this.networkStatus) {
                BigInteger networkTd = this.networkStatus.getTargetTotalDiff();
                if (_remoteTotalDiff.compareTo(networkTd) > 0) {
                    String remoteBestBlockHash = Hex.toHexString(_remoteBestBlockHash);

                    if (log.isDebugEnabled()) {
                        log.debug(
                                "network-status-updated on-sync id={}->{} td={}->{} bn={}->{} bh={}->{}",
                                this.networkStatus.getTargetDisplayId(),
                                _displayId,
                                this.networkStatus.getTargetTotalDiff().toString(10),
                                _remoteTotalDiff.toString(10),
                                this.networkStatus.getTargetBestBlockNumber(),
                                _remoteBestBlockNumber,
                                this.networkStatus.getTargetBestBlockHash().isEmpty()
                                        ? ""
                                        : getNodeIdShort(
                                                this.networkStatus.getTargetBestBlockHash()),
                                getNodeIdShort(remoteBestBlockHash),
                                this.networkStatus.getTargetApiVersion(),
                                (int) _apiVersion,
                                this.networkStatus.getTargetPeerCount(),
                                _peerCount,
                                this.networkStatus.getTargetPendingTxCount(),
                                _pendingTxCount,
                                this.networkStatus.getTargetLatency(),
                                _latency);
                    }

                    this.networkStatus.update(
                            _displayId,
                            _remoteTotalDiff,
                            _remoteBestBlockNumber,
                            remoteBestBlockHash,
                            (int) _apiVersion,
                            _peerCount,
                            _pendingTxCount,
                            _latency);
                }
            }
        }
    }

    private void setupEventHandler() {
        List<IEvent> events = new ArrayList<>();
        events.add(new EventConsensus(EventConsensus.CALLBACK.ON_SYNC_DONE));
        this.evtMgr.registerEvent(events);
    }

    private void getHeaders(BigInteger _selfTd) {
        // Using the same executor as imports ensures that the requests are sent only when the kernel is not busy importing blocks.
        // Therefore the system will not be overwhelmed with responses it cannot currently handle.
        importExecutor.execute(() -> {
            Thread.currentThread().setName("sync-gh");
            syncHeaderRequestManager.sendHeadersRequests(chain.getBestBlock().getNumber(), _selfTd, p2pMgr, stats);
        });
    }

    /**
     * @param _nodeIdHashcode int
     * @param _displayId String
     * @param _headers List validate headers batch and add batch to imported headers
     */
    public void validateAndAddHeaders(int _nodeIdHashcode, String _displayId, List<BlockHeader> _headers) {
        if (_headers == null || _headers.isEmpty()) {
            return;
        }

        if (log.isDebugEnabled()) {
            log.debug(
                    "<incoming-headers from={} size={} node={}>",
                    _headers.get(0).getNumber(),
                    _headers.size(),
                    _displayId);
        }

        // filter imported block headers
        List<BlockHeader> filtered = new ArrayList<>();
        BlockHeader prev = null;
        for (BlockHeader current : _headers) {

            // ignore this batch if any invalidated header
            if (!this.blockHeaderValidator.validate(current, log)) {
                log.debug(
                        "<invalid-header num={} hash={}>", current.getNumber(), current.getHash());

                // Print header to allow debugging
                log.debug("Invalid header: {}", current.toString());

                return;
            }

            // break if not consisting
            if (prev != null
                    && (current.getNumber() != (prev.getNumber() + 1)
                            || !Arrays.equals(current.getParentHash(), prev.getHash()))) {
                log.debug(
                        "<inconsistent-block-headers from={}, num={}, prev+1={}, p_hash={}, prev={}>",
                        _displayId,
                        current.getNumber(),
                        prev.getNumber() + 1,
                        ByteUtil.toHexString(current.getParentHash()),
                        ByteUtil.toHexString(prev.getHash()));
                return;
            }

            // add if not cached
            if (!importedBlockHashes.containsKey(ByteArrayWrapper.wrap(current.getHash()))) {
                filtered.add(current);
            }

            prev = current;
        }

        // NOTE: the filtered headers is still continuous
        if (!filtered.isEmpty()) {
            syncExecutors.execute(() -> requestBodies(new HeadersWrapper(_nodeIdHashcode, _displayId, filtered), syncHeaderRequestManager, p2pMgr, stats));
        }
    }

    /**
     * Requests the bodies associated to the given block headers.
     */
    @VisibleForTesting
    static void requestBodies(final HeadersWrapper hw, SyncHeaderRequestManager syncHeaderRequestManager, IP2pMgr p2pMgr, SyncStats syncStats) {
        Thread.currentThread().setName("sync-gb");
        long startTime = System.nanoTime();
        int idHash = hw.nodeId;
        String displayId = hw.displayId;
        List<BlockHeader> headers = hw.headers;

        // save headers for matching with bodies
        syncHeaderRequestManager.storeHeaders(idHash, hw);

        // log bodies request before sending the request
        log.debug("<get-bodies from-num={} to-num={} node={}>", headers.get(0).getNumber(), headers.get(headers.size() - 1).getNumber(), hw.displayId);

        p2pMgr.send(idHash, displayId, new ReqBlocksBodies(headers.stream().map(k -> k.getHash()).collect(Collectors.toList())));
        syncStats.updateTotalRequestsToPeer(displayId, RequestType.BODIES);
        syncStats.updateRequestTime(displayId, System.nanoTime(), RequestType.BODIES);

        long duration = System.nanoTime() - startTime;
        survey_log.debug("TaskGetBodies: make request, duration = {} ns.", duration);
    }

    /**
     * @param _nodeIdHashcode int
     * @param _displayId String
     * @param _bodies List<byte[]> Assemble and validate blocks batch and add batch to import queue
     *     from network response blocks bodies
     */
    public void validateAndAddBlocks(
            int _nodeIdHashcode, String _displayId, final List<byte[]> _bodies) {
        if (_bodies == null) return;
        log.debug("<received-bodies size={} node={}>", _bodies.size(), _displayId);

        // the requests are made such that the size varies to better map headers to bodies
        HeadersWrapper hw = syncHeaderRequestManager.matchHeaders(_nodeIdHashcode, _bodies.size());
        if (hw == null) return;

        // assemble batch
        List<BlockHeader> headers = hw.headers;
        List<Block> blocks = new ArrayList<>(_bodies.size());
        Iterator<BlockHeader> headerIt = headers.iterator();
        Iterator<byte[]> bodyIt = _bodies.iterator();
        while (headerIt.hasNext() && bodyIt.hasNext()) {
            Block block = BlockUtil.newBlockWithHeaderFromUnsafeSource(headerIt.next(), bodyIt.next());
            if (block == null) {
                log.warn("<assemble-and-validate-blocks node={} size={}>", _displayId, _bodies.size());
                break;
            } else {
                blocks.add(block);
            }
        }

        int m = blocks.size();
        if (m == 0) {
            return;
        }

        log.debug("<assembled-blocks from={} size={} node={}>", blocks.get(0).getNumber(), blocks.size(), _displayId);

        // add batch
        syncExecutors.execute(() -> filterBlocks(new BlocksWrapper(_nodeIdHashcode, _displayId, blocks), chain, stats, importedBlockHashes, syncHeaderRequestManager, importExecutor));
    }

    /** @implNote Should be lower than {@link SyncHeaderRequestManager#MAX_BLOCK_DIFF}. */
    private static final int MAX_STORAGE_DIFF = 100;

    /**
     * Filters received blocks by delegating the ones far in the future to storage and delaying queue population when the predefined capacity is reached.
     */
    private static void filterBlocks(final BlocksWrapper downloadedBlocks, AionBlockchainImpl chain, SyncStats syncStats, Map<ByteArrayWrapper, Object> importedBlockHashes, SyncHeaderRequestManager syncHeaderRequestManager, ExecutorService importExecutor) {
        Thread.currentThread().setName("sync-fb");
        long currentBest = chain.getBestBlock() == null ? 0 : chain.getBestBlock().getNumber();
        boolean isFarInFuture = downloadedBlocks.firstBlockNumber > currentBest + MAX_STORAGE_DIFF;

        if (isFarInFuture) {
            int stored = chain.storePendingBlockRange(downloadedBlocks.blocks, log);
            syncStats.updatePeerBlocks(downloadedBlocks.displayId, stored, BlockType.STORED);
        } else {
            importExecutor.execute(() -> TaskImportBlocks.importBlocks(chain, syncStats, downloadedBlocks, importedBlockHashes, syncHeaderRequestManager));
        }
    }

    public long getNetworkBestBlockNumber() {
        synchronized (this.networkStatus) {
            return this.networkStatus.getTargetBestBlockNumber();
        }
    }

    public synchronized void shutdown() {
        if (p2pLog.isDebugEnabled()) {
            // print all the gathered information before shutdown
            p2pLog.debug(getStatus(chain, networkStatus, stats));

            String requestedStats = stats.dumpRequestStats();
            if (!requestedStats.isEmpty()) {
                p2pLog.debug(requestedStats);
            }
            requestedStats = stats.dumpTopSeedsStats();
            if (!requestedStats.isEmpty()) {
                p2pLog.debug(requestedStats);
            }
            requestedStats = stats.dumpTopLeechesStats();
            if (!requestedStats.isEmpty()) {
                p2pLog.debug(requestedStats);
            }
            requestedStats = stats.dumpResponseStats();
            if (!requestedStats.isEmpty()) {
                p2pLog.debug(requestedStats);
            }
        }

        shutdownAndAwaitTermination(syncExecutors);
        shutdownAndAwaitTermination(importExecutor);
    }

    private void shutdownAndAwaitTermination(ExecutorService pool) {
        pool.shutdown(); // Disable new tasks from being submitted
        try {
            // Wait a while for existing tasks to terminate
            if (!pool.awaitTermination(60, TimeUnit.SECONDS)) {
                pool.shutdownNow(); // Cancel currently executing tasks
                // Wait a while for tasks to respond to being cancelled
                if (!pool.awaitTermination(60, TimeUnit.SECONDS)) {
                    log.error("Pool did not terminate");
                }
            }
        } catch (InterruptedException ie) {
            // (Re-)Cancel if current thread also interrupted
            pool.shutdownNow();
            // Preserve interrupt status
            Thread.currentThread().interrupt();
        }
    }

    public SyncStats getSyncStats() {
        return this.stats;
    }
}
