package com.fullstack.ble.sdk.connection

internal class SerialOperationQueue<T> {
    private val pending = ArrayDeque<T>()

    var current: T? = null
        private set

    val pendingCount: Int
        get() = pending.size

    fun enqueue(operation: T) {
        pending.addLast(operation)
    }

    fun startNext(): T? {
        if (current != null || pending.isEmpty()) return null
        return pending.removeFirst().also { current = it }
    }

    fun complete(operation: T): Boolean {
        if (current !== operation) return false
        current = null
        return true
    }

    fun drain(): List<T> {
        val operations = mutableListOf<T>()
        current?.let(operations::add)
        current = null
        while (pending.isNotEmpty()) operations.add(pending.removeFirst())
        return operations
    }
}
