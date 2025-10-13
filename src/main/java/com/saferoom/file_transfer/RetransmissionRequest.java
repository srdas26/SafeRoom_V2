package com.saferoom.file_transfer;

/**
 * Immutable retransmission request with pre-calculated chunk information
 * 
 * This class encapsulates all information needed to retransmit a single packet,
 * avoiding the need for binary search or recalculation in the hot retransmission path.
 * 
 * By pre-calculating chunk index, offsets, and payload size during NACK processing,
 * the retransmission thread can directly access the correct chunk buffer and send
 * the packet without additional lookups.
 * 
 * Thread-safe due to immutability.
 */
public final class RetransmissionRequest {
    
    /** Index of chunk containing this packet */
    public final int chunkIndex;
    
    /** Global sequence number (used in packet header) */
    public final int globalSeq;
    
    /** Local sequence number within chunk (0-based) */
    public final int localSeq;
    
    /** Byte offset within chunk buffer */
    public final int localOffset;
    
    /** Payload size in bytes (may be smaller for last packet) */
    public final int payloadSize;
    
    /**
     * Create retransmission request
     * 
     * @param chunkIndex    Which chunk to read from
     * @param globalSeq     Global sequence number for packet header
     * @param localSeq      Sequence within chunk (0-based)
     * @param localOffset   Byte offset within chunk buffer
     * @param payloadSize   Bytes to send
     */
    public RetransmissionRequest(int chunkIndex, int globalSeq, 
                                int localSeq, int localOffset, int payloadSize) {
        // Validation
        if (chunkIndex < 0) {
            throw new IllegalArgumentException("chunkIndex must be >= 0: " + chunkIndex);
        }
        if (globalSeq < 0) {
            throw new IllegalArgumentException("globalSeq must be >= 0: " + globalSeq);
        }
        if (localSeq < 0) {
            throw new IllegalArgumentException("localSeq must be >= 0: " + localSeq);
        }
        if (localOffset < 0) {
            throw new IllegalArgumentException("localOffset must be >= 0: " + localOffset);
        }
        if (payloadSize <= 0) {
            throw new IllegalArgumentException("payloadSize must be > 0: " + payloadSize);
        }
        
        this.chunkIndex = chunkIndex;
        this.globalSeq = globalSeq;
        this.localSeq = localSeq;
        this.localOffset = localOffset;
        this.payloadSize = payloadSize;
    }
    
    /**
     * Factory method: Create from ChunkMetadata and global sequence
     * 
     * This is the recommended way to create RetransmissionRequest,
     * as it automatically calculates all derived values from metadata.
     * 
     * @param meta      Chunk metadata
     * @param globalSeq Global sequence number to retransmit
     * @param sliceSize Packet payload size (typically 1450)
     * @return RetransmissionRequest with all fields calculated
     * @throws IllegalArgumentException if globalSeq not in chunk range
     */
    public static RetransmissionRequest fromMetadata(ChunkMetadata meta, 
                                                    int globalSeq, 
                                                    int sliceSize) {
        // Convert global → local sequence
        int localSeq = meta.toLocalSequence(globalSeq);
        
        // Calculate byte offset and payload size
        int localOffset = meta.getLocalOffset(localSeq, sliceSize);
        int payloadSize = meta.getPayloadSize(localSeq, sliceSize);
        
        return new RetransmissionRequest(
            meta.chunkIndex,
            globalSeq,
            localSeq,
            localOffset,
            payloadSize
        );
    }
    
    @Override
    public String toString() {
        return String.format("RetxReq[chunk=%d, globalSeq=%,d, localSeq=%,d, offset=%,d, size=%d]",
            chunkIndex, globalSeq, localSeq, localOffset, payloadSize);
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof RetransmissionRequest)) return false;
        RetransmissionRequest other = (RetransmissionRequest) obj;
        return chunkIndex == other.chunkIndex &&
               globalSeq == other.globalSeq &&
               localSeq == other.localSeq &&
               localOffset == other.localOffset &&
               payloadSize == other.payloadSize;
    }
    
    @Override
    public int hashCode() {
        int result = chunkIndex;
        result = 31 * result + globalSeq;
        result = 31 * result + localSeq;
        result = 31 * result + localOffset;
        result = 31 * result + payloadSize;
        return result;
    }
}
