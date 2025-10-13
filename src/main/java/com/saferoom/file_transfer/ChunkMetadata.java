package com.saferoom.file_transfer;

/**
 * Immutable metadata for a single file chunk
 * 
 * Represents a contiguous segment of a file that will be mapped
 * as a single MappedByteBuffer. Contains all necessary information
 * to locate and process packets within this chunk.
 * 
 * Thread-safe due to immutability.
 */
public final class ChunkMetadata {
    
    /** Zero-based index of this chunk (0, 1, 2, ...) */
    public final int chunkIndex;
    
    /** Byte offset of this chunk within the file */
    public final long fileOffset;
    
    /** Size of this chunk in bytes (last chunk may be smaller) */
    public final long chunkSize;
    
    /** First global sequence number in this chunk (inclusive) */
    public final int globalSeqStart;
    
    /** Last global sequence number in this chunk (inclusive) */
    public final int globalSeqEnd;
    
    /** Total number of packets in this chunk */
    public final int packetCount;
    
    /**
     * Create chunk metadata
     * 
     * @param chunkIndex    Zero-based chunk number
     * @param fileOffset    Byte offset in file
     * @param chunkSize     Size in bytes
     * @param globalSeqStart First sequence number (global)
     * @param globalSeqEnd   Last sequence number (global, inclusive)
     * @param packetCount    Total packets in chunk
     */
    public ChunkMetadata(int chunkIndex, long fileOffset, long chunkSize,
                        int globalSeqStart, int globalSeqEnd, int packetCount) {
        // Validation
        if (chunkIndex < 0) {
            throw new IllegalArgumentException("chunkIndex must be >= 0: " + chunkIndex);
        }
        if (fileOffset < 0) {
            throw new IllegalArgumentException("fileOffset must be >= 0: " + fileOffset);
        }
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be > 0: " + chunkSize);
        }
        if (globalSeqStart < 0) {
            throw new IllegalArgumentException("globalSeqStart must be >= 0: " + globalSeqStart);
        }
        if (globalSeqEnd < globalSeqStart) {
            throw new IllegalArgumentException("globalSeqEnd must be >= globalSeqStart: " + 
                globalSeqEnd + " < " + globalSeqStart);
        }
        if (packetCount <= 0) {
            throw new IllegalArgumentException("packetCount must be > 0: " + packetCount);
        }
        
        this.chunkIndex = chunkIndex;
        this.fileOffset = fileOffset;
        this.chunkSize = chunkSize;
        this.globalSeqStart = globalSeqStart;
        this.globalSeqEnd = globalSeqEnd;
        this.packetCount = packetCount;
    }
    
    /**
     * Check if given global sequence number belongs to this chunk
     * 
     * @param globalSeq Global sequence number to check
     * @return true if this sequence is in this chunk's range
     */
    public boolean containsSequence(int globalSeq) {
        return globalSeq >= globalSeqStart && globalSeq <= globalSeqEnd;
    }
    
    /**
     * Convert global sequence to local sequence within this chunk
     * 
     * @param globalSeq Global sequence number
     * @return Local sequence number (0-based within chunk)
     * @throws IllegalArgumentException if globalSeq not in this chunk
     */
    public int toLocalSequence(int globalSeq) {
        if (!containsSequence(globalSeq)) {
            throw new IllegalArgumentException("Sequence " + globalSeq + 
                " not in chunk " + chunkIndex + " range [" + 
                globalSeqStart + ", " + globalSeqEnd + "]");
        }
        return globalSeq - globalSeqStart;
    }
    
    /**
     * Convert local sequence to global sequence
     * 
     * @param localSeq Local sequence number (0-based within chunk)
     * @return Global sequence number
     * @throws IllegalArgumentException if localSeq out of range
     */
    public int toGlobalSequence(int localSeq) {
        if (localSeq < 0 || localSeq >= packetCount) {
            throw new IllegalArgumentException("Local sequence " + localSeq + 
                " out of range [0, " + (packetCount - 1) + "]");
        }
        return globalSeqStart + localSeq;
    }
    
    /**
     * Get byte offset within chunk for given local sequence
     * 
     * @param localSeq Local sequence number
     * @param sliceSize Packet payload size
     * @return Byte offset within chunk buffer
     */
    public int getLocalOffset(int localSeq, int sliceSize) {
        if (localSeq < 0 || localSeq >= packetCount) {
            throw new IllegalArgumentException("Local sequence " + localSeq + 
                " out of range [0, " + (packetCount - 1) + "]");
        }
        return localSeq * sliceSize;
    }
    
    /**
     * Get payload size for packet at given local sequence
     * 
     * @param localSeq Local sequence number
     * @param sliceSize Packet payload size
     * @return Actual payload size (may be smaller for last packet)
     */
    public int getPayloadSize(int localSeq, int sliceSize) {
        if (localSeq < 0 || localSeq >= packetCount) {
            throw new IllegalArgumentException("Local sequence " + localSeq + 
                " out of range [0, " + (packetCount - 1) + "]");
        }
        
        long localOffset = (long)localSeq * sliceSize;
        long remaining = chunkSize - localOffset;
        return (int)Math.min(sliceSize, remaining);
    }
    
    @Override
    public String toString() {
        return String.format("Chunk[idx=%d, offset=%,d, size=%,d bytes (%.1f MB), seq=%,d-%,d (%,d pkts)]",
            chunkIndex, 
            fileOffset, 
            chunkSize, 
            chunkSize / (1024.0 * 1024.0),
            globalSeqStart, 
            globalSeqEnd, 
            packetCount);
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof ChunkMetadata)) return false;
        ChunkMetadata other = (ChunkMetadata) obj;
        return chunkIndex == other.chunkIndex &&
               fileOffset == other.fileOffset &&
               chunkSize == other.chunkSize &&
               globalSeqStart == other.globalSeqStart &&
               globalSeqEnd == other.globalSeqEnd &&
               packetCount == other.packetCount;
    }
    
    @Override
    public int hashCode() {
        int result = chunkIndex;
        result = 31 * result + Long.hashCode(fileOffset);
        result = 31 * result + Long.hashCode(chunkSize);
        result = 31 * result + globalSeqStart;
        result = 31 * result + globalSeqEnd;
        result = 31 * result + packetCount;
        return result;
    }
}
