package ichttt.mods.sendmoarchunks.mixin.client;

import ichttt.mods.sendmoarchunks.client.SendMoarChunksClient;
import net.minecraft.client.multiplayer.ChunkBatchSizeCalculator;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.text.DecimalFormat;
import java.util.concurrent.TimeUnit;

@Mixin(ChunkBatchSizeCalculator.class)
public class ChunkBatchSizeCalculatorMixin {
    @Unique
    private static final double MAX_NSPT_FOR_CHUNK_LOADING = TimeUnit.MILLISECONDS.toNanos(35);
    @Unique
    private static final double MIN_NSPT_FOR_CHUNK_LOADING = TimeUnit.MILLISECONDS.toNanos(7);
    @Unique
    private static final double TARGET_NSPT_FOR_CHUNK_LOADING = TimeUnit.MILLISECONDS.toNanos(12);
    @Unique
    private static final double TARGET_NANOS_PER_CHUNK = TimeUnit.MILLISECONDS.toNanos(2); // starting value of aggregatedNanosPerChunk
    @Unique
    private static final DecimalFormat FORMAT = new DecimalFormat("0.00");

    @Shadow
    private double aggregatedNanosPerChunk;

    @Shadow
    private int oldSamplesWeight;

    @Unique
    private final double[] previousMultipliers = new double[10];
    @Unique
    private int multiplierIndex;

    @Unique
    public double getNsptAllowedForChunks() {
        // Reasoning: If we take more time than the target, we decrease the allowed time for chunk sending
        // This means when the network is slow, chunk loading will be slowed down even more
        // This is done to make room for the bandwidth the other packets need, which can not be throttled
        double timePerChunkRatio = TARGET_NANOS_PER_CHUNK / aggregatedNanosPerChunk;

        // Make the multiplier non-linear, as the non-chunk loading parts need a constant bandwidth.
        // x * (log(x) + 1) seems like a good function that is faster than linear and slower than squared
        double multiplier = (timePerChunkRatio * (Math.log10(timePerChunkRatio) + 1));

        // the first few request are slower most of the time (because of many factors like code warm up, server resumption, TCP congestion control, etc.)
        // But when joining, we want fast chunk loading (especially of nearby chunks), so we artificially increase the multiplier if we are in the first requests
        // We dial this back quickly to make sure we can keep up though
        int numBatchesAnswered = Math.max(0, oldSamplesWeight - 2); // oldSamplesWeight starts at 1 and gets incremented by 1 once the first batch is received
        if (numBatchesAnswered < 8) {
            double adjustment = (8 - numBatchesAnswered) / 16D;
            multiplier += adjustment;
        }

        // Smooth out the multiplier:
        // Not all chunks are equally big, e.g. ocean chunks are typically much smaller than tightly build chunks
        // To account for this variance (in the aggregatedNanosPerChunk), we smooth out the multiplier
        // We take the average of the last multipliers and compare it to the just calculated multiplier, picking what ever is lower
        // This makes the algorithm be a bit more stable and resilient
        previousMultipliers[multiplierIndex] = multiplier;
        multiplierIndex = (multiplierIndex + 1) % previousMultipliers.length;
        if (numBatchesAnswered > 0) {
            double averageMultiplier = 0.0;
            int samplesCount = Math.min(previousMultipliers.length, numBatchesAnswered);
            for (int i = 0; i < samplesCount; i++) {
                averageMultiplier += previousMultipliers[i];
            }
            averageMultiplier /= samplesCount;
            if (SendMoarChunksClient.DEBUG) {
                SendMoarChunksClient.LOGGER.info("Calculated multiplier {}, average multiplier {}", multiplier, averageMultiplier);
            }
            multiplier = Math.min(averageMultiplier, multiplier);
        }


        double allowedNsptForChunkLoadingRaw = TARGET_NSPT_FOR_CHUNK_LOADING * multiplier;
        double allowedNsptForChunkLoading = Mth.clamp(allowedNsptForChunkLoadingRaw, MIN_NSPT_FOR_CHUNK_LOADING, MAX_NSPT_FOR_CHUNK_LOADING);
        if (SendMoarChunksClient.DEBUG) {
            String msPerChunk = FORMAT.format((aggregatedNanosPerChunk / 1000D) / 1000D);
            String ratio = FORMAT.format(timePerChunkRatio);
            String multiplierString = FORMAT.format(multiplier);
            String msptForChunkLoadingRaw = FORMAT.format((allowedNsptForChunkLoadingRaw / 1000D) / 1000D);
            String msptForChunkLoading = FORMAT.format((allowedNsptForChunkLoading / 1000D) / 1000D);
            SendMoarChunksClient.LOGGER.info("We got {} ms per chunk, which results in a ratio of {}. The base muliplier is {}. This means we can allocate {} mspt (raw value: {}) for chunk loading", msPerChunk, ratio, multiplierString, msptForChunkLoading, msptForChunkLoadingRaw);
        }
        return allowedNsptForChunkLoading;
    }

    /**
     * @author ichttt
     * @reason new impl that dynamically calculates the allowed nspt for chunk downloading
     */
    @Overwrite
    public float getDesiredChunksPerTick() {
        double allowedNsptForChunkLoading = getNsptAllowedForChunks();
        float result = (float)(allowedNsptForChunkLoading / this.aggregatedNanosPerChunk);
        if (SendMoarChunksClient.DEBUG) {
            SendMoarChunksClient.LOGGER.info("Requesting {} chunks per tick", result);
        }
        return result;
    }
}
