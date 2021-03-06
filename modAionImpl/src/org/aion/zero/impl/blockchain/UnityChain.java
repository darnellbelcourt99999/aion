package org.aion.zero.impl.blockchain;

import java.math.BigInteger;
import java.util.List;
import org.aion.base.AionTransaction;
import org.aion.mcf.blockchain.Block;
import org.aion.mcf.blockchain.BlockHeader;
import org.aion.zero.impl.core.ImportResult;
import org.aion.zero.impl.types.AionBlock;
import org.aion.zero.impl.types.StakingBlock;

public interface UnityChain {

    BigInteger getTotalDifficulty();

    BigInteger getTotalDifficultyForHash(byte[] hash);

    Block getBlockByNumber(long number);

    Block getBlockByHash(byte[] hash);

    Block getBestBlock();

    void flush();

    byte[] getSeed();

    Block createStakingBlockTemplate(
        Block parent,
        List<AionTransaction> pendingTransactions,
        byte[] publicKey,
        byte[] seed,
        byte[] coinbase);

    StakingBlock getCachingStakingBlockTemplate(byte[] hash);

    AionBlock getCachingMiningBlockTemplate(byte[] hash);

    ImportResult tryToConnect(Block block);

    ImportResult tryToConnect(BlockWrapper blockWrapper);

    Block getBlockWithInfoByHash(byte[] hash);

    Block getBestBlockWithInfo();

    StakingBlock getBestStakingBlock();

    AionBlock getBestMiningBlock();

    boolean isUnityForkEnabledAtNextBlock();

    BigInteger calculateBlockRewards(long block_number);
}
