package com.fullstack.ble.sdk.connection

internal object PacketSplitter {
    fun split(data: ByteArray, chunkSize: Int): List<ByteArray> {
        require(data.isNotEmpty()) { "data must not be empty" }
        require(chunkSize > 0) { "chunkSize must be > 0" }
        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < data.size) {
            val length = minOf(chunkSize, data.size - offset)
            chunks.add(data.copyOfRange(offset, offset + length))
            offset += length
        }
        return chunks
    }
}
