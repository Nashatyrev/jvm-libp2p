package io.libp2p.quicsim.runner

import com.sun.management.HotSpotDiagnosticMXBean
import io.netty.buffer.ByteBuf
import java.lang.management.ManagementFactory
import java.lang.ref.Reference
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.IdentityHashMap
import java.util.Optional
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

interface SimulatedNodeHeapProfiler : AutoCloseable {
    fun sample(phase: String, simTime: Duration?, nodesStuff: List<SimulatedRunner.NodeStuff>)

    fun maybeSample(phase: String, simTime: Duration, nodesStuff: List<SimulatedRunner.NodeStuff>) {
        sample(phase, simTime, nodesStuff)
    }

    override fun close() {
    }

    companion object {
        val Noop: SimulatedNodeHeapProfiler = object : SimulatedNodeHeapProfiler {
            override fun sample(phase: String, simTime: Duration?, nodesStuff: List<SimulatedRunner.NodeStuff>) {
            }
        }

        fun fromSystemProperties(): SimulatedNodeHeapProfiler {
            val targetNodeId = System.getProperty("quicsim.nodeHeapProfile.nodeId")?.toIntOrNull()
                ?: return Noop
            val outputDir = System.getProperty("quicsim.nodeHeapProfile.dir")
                ?.let { Path(it) }
                ?: Path("build/reports/node-heap-profile")
            val periodMillis = System.getProperty("quicsim.nodeHeapProfile.periodMillis")?.toLongOrNull()
            val maxObjects = System.getProperty("quicsim.nodeHeapProfile.maxObjects")?.toIntOrNull()
                ?: 2_000_000
            val maxDepth = System.getProperty("quicsim.nodeHeapProfile.maxDepth")?.toIntOrNull()
                ?: 256
            return CsvSimulatedNodeHeapProfiler(
                targetNodeId = targetNodeId,
                outputDir = outputDir,
                samplePeriod = periodMillis?.milliseconds,
                maxObjects = maxObjects,
                maxDepth = maxDepth
            )
        }
    }
}

class CsvSimulatedNodeHeapProfiler(
    private val targetNodeId: Int,
    private val outputDir: Path,
    private val samplePeriod: Duration? = null,
    private val maxObjects: Int = 2_000_000,
    private val maxDepth: Int = 256,
) : SimulatedNodeHeapProfiler {
    private val summaryPath = outputDir.resolve("node-$targetNodeId-heap-summary.csv")
    private val classesPath = outputDir.resolve("node-$targetNodeId-heap-classes.csv")
    private val measurer = ReachableObjectGraphMeasurer(maxObjects, maxDepth)
    private var lastPeriodicSample: Duration? = null
    private var sampleIndex = 0

    init {
        outputDir.createDirectories()
        initializeCsv(
            summaryPath,
            "sample_index,phase,sim_time_ns,heap_used_bytes,total_reachable_bytes,shallow_bytes," +
                "byte_buf_capacity_bytes,unique_byte_buf_storage_bytes,unique_byte_buf_storage_count," +
                "visited_objects,truncated,duration_ms,roots"
        )
        initializeCsv(
            classesPath,
            "sample_index,phase,sim_time_ns,class_name,object_count,bytes"
        )
    }

    @Synchronized
    override fun sample(phase: String, simTime: Duration?, nodesStuff: List<SimulatedRunner.NodeStuff>) {
        val nodeStuff = nodesStuff.firstOrNull { it.id == targetNodeId } ?: return
        val roots = rootsFor(nodeStuff)
        val measured = measurer.measure(roots)
        val currentIndex = sampleIndex++
        appendSummary(currentIndex, phase, simTime, measured, roots)
        appendClasses(currentIndex, phase, simTime, measured)
        println(
            "Node heap profile sample=$currentIndex node=$targetNodeId phase=$phase " +
                "reachableBytes=${measured.totalReachableBytes} visitedObjects=${measured.visitedObjects} " +
                "truncated=${measured.truncated} wrote=$outputDir"
        )
    }

    override fun maybeSample(phase: String, simTime: Duration, nodesStuff: List<SimulatedRunner.NodeStuff>) {
        val period = samplePeriod ?: return
        val lastSample = lastPeriodicSample
        if (lastSample != null && simTime < lastSample + period) return
        lastPeriodicSample = simTime
        sample(phase, simTime, nodesStuff)
    }

    private fun rootsFor(nodeStuff: SimulatedRunner.NodeStuff): List<NamedRoot> =
        listOf(
            NamedRoot("nodeProgram", nodeStuff.nodeProgram),
            NamedRoot("nodeScheduler", nodeStuff.nodeScheduler),
            NamedRoot("simNodeImpl", nodeStuff.simNodeImpl),
            NamedRoot("simContext", nodeStuff.simContext),
            NamedRoot("networkContext", nodeStuff.networkContext),
            NamedRoot("host", nodeStuff.host),
            NamedRoot("startFuture", nodeStuff.startFuture),
            NamedRoot("completeTimeFuture", nodeStuff.completeTimeFuture)
        )

    private fun appendSummary(
        sampleIndex: Int,
        phase: String,
        simTime: Duration?,
        snapshot: ObjectGraphSnapshot,
        roots: List<NamedRoot>
    ) {
        appendLine(
            summaryPath,
            listOf(
                sampleIndex,
                phase,
                simTime?.inWholeNanoseconds ?: "",
                currentHeapUsedBytes(),
                snapshot.totalReachableBytes,
                snapshot.shallowBytes,
                snapshot.byteBufCapacityBytes,
                snapshot.uniqueByteBufStorageBytes,
                snapshot.uniqueByteBufStorageCount,
                snapshot.visitedObjects,
                snapshot.truncated,
                snapshot.durationMillis,
                roots.joinToString("|") { it.name }
            ).toCsvRow()
        )
    }

    private fun appendClasses(
        sampleIndex: Int,
        phase: String,
        simTime: Duration?,
        snapshot: ObjectGraphSnapshot
    ) {
        Files.newBufferedWriter(
            classesPath,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
        ).use { writer ->
            (snapshot.classStats.values + snapshot.byteBufCapacityStats.values + snapshot.uniqueByteBufStorageStats.values)
                .sortedWith(compareByDescending<ClassMemoryStats> { it.shallowBytes }.thenBy { it.className })
                .forEach { classStats ->
                    writer.appendLine(
                        listOf(
                            sampleIndex,
                            phase,
                            simTime?.inWholeNanoseconds ?: "",
                            classStats.className,
                            classStats.count,
                            classStats.shallowBytes
                        ).toCsvRow()
                    )
                }
        }
    }

    private fun initializeCsv(path: Path, header: String) {
        if (Files.notExists(path)) {
            appendLine(path, header)
        }
    }

    private fun appendLine(path: Path, line: String) {
        Files.newBufferedWriter(
            path,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
        ).use { writer ->
            writer.appendLine(line)
        }
    }
}

data class NamedRoot(
    val name: String,
    val value: Any
)

data class ClassMemoryStats(
    val className: String,
    var count: Long = 0,
    var shallowBytes: Long = 0
)

data class ObjectGraphSnapshot(
    val totalReachableBytes: Long,
    val shallowBytes: Long,
    val byteBufCapacityBytes: Long,
    val uniqueByteBufStorageBytes: Long,
    val uniqueByteBufStorageCount: Int,
    val visitedObjects: Int,
    val truncated: Boolean,
    val durationMillis: Long,
    val classStats: Map<String, ClassMemoryStats>,
    val byteBufCapacityStats: Map<String, ClassMemoryStats>,
    val uniqueByteBufStorageStats: Map<String, ClassMemoryStats>
)

class ReachableObjectGraphMeasurer(
    private val maxObjects: Int = 2_000_000,
    private val maxDepth: Int = 256,
    private val layout: HeapLayout = HeapLayout.current()
) {
    fun measure(roots: List<NamedRoot>): ObjectGraphSnapshot {
        val startedAt = System.nanoTime()
        val visited = IdentityHashMap<Any, Unit>(maxObjects.coerceAtMost(1_000_000))
        val queue = java.util.ArrayDeque<QueuedObject>()
        val classStats = linkedMapOf<String, ClassMemoryStats>()
        val byteBufStorageTracker = ByteBufStorageTracker()

        roots.forEach { root ->
            queue.add(QueuedObject(root.value, 0))
        }

        var truncated = false
        while (!queue.isEmpty()) {
            if (visited.size >= maxObjects) {
                truncated = true
                break
            }
            val queued = queue.removeFirst()
            val value = queued.value
            if (visited.containsKey(value) || shouldSkipObject(value)) continue
            visited[value] = Unit

            val objectSize = layout.shallowSize(value)
            addClassStats(classStats, value.javaClass.name, 1, objectSize)
            if (value is ByteBuf) {
                byteBufStorageTracker.record(value)
            }
            enqueueReferences(value, queued.depth, queue, classStats)
        }

        val durationMillis = (System.nanoTime() - startedAt) / 1_000_000
        val shallowBytes = classStats.values.sumOf { it.shallowBytes }
        return ObjectGraphSnapshot(
            totalReachableBytes = shallowBytes + byteBufStorageTracker.uniqueStorageBytes,
            shallowBytes = shallowBytes,
            byteBufCapacityBytes = byteBufStorageTracker.capacityBytes,
            uniqueByteBufStorageBytes = byteBufStorageTracker.uniqueStorageBytes,
            uniqueByteBufStorageCount = byteBufStorageTracker.uniqueStorageCount,
            visitedObjects = visited.size,
            truncated = truncated,
            durationMillis = durationMillis,
            classStats = classStats,
            byteBufCapacityStats = byteBufStorageTracker.capacityStats,
            uniqueByteBufStorageStats = byteBufStorageTracker.uniqueStorageStats
        )
    }

    private fun enqueueReferences(
        value: Any,
        depth: Int,
        queue: java.util.ArrayDeque<QueuedObject>,
        classStats: MutableMap<String, ClassMemoryStats>
    ) {
        if (depth >= maxDepth) return
        when (value) {
            is Map<*, *> -> {
                addContainerBackingEstimate(value, classStats)
                enqueueMapEntries(value, depth, queue)
            }
            is Iterable<*> -> {
                addContainerBackingEstimate(value, classStats)
                enqueueIterableEntries(value, depth, queue)
            }
            is Optional<*> -> value.ifPresent { queue.add(QueuedObject(it, depth + 1)) }
            is AtomicReference<*> -> value.get()?.let { queue.add(QueuedObject(it, depth + 1)) }
            is ByteBuf -> enqueueByteBufBackingArray(value, depth, queue)
            is Array<*> -> value.forEach { it?.let { item -> queue.add(QueuedObject(item, depth + 1)) } }
        }

        if (value is Reference<*>) return
        if (value.javaClass.isArray) return
        enqueueFieldReferences(value, depth, queue)
    }

    private fun enqueueMapEntries(value: Map<*, *>, depth: Int, queue: java.util.ArrayDeque<QueuedObject>) {
        try {
            value.forEach { (key, mapValue) ->
                key?.let { queue.add(QueuedObject(it, depth + 1)) }
                mapValue?.let { queue.add(QueuedObject(it, depth + 1)) }
            }
        } catch (_: RuntimeException) {
            // Diagnostic walk over live simulator state: skip containers that mutate while being sampled.
        }
    }

    private fun enqueueIterableEntries(value: Iterable<*>, depth: Int, queue: java.util.ArrayDeque<QueuedObject>) {
        try {
            value.forEach { item ->
                item?.let { queue.add(QueuedObject(it, depth + 1)) }
            }
        } catch (_: RuntimeException) {
            // Diagnostic walk over live simulator state: skip containers that mutate while being sampled.
        }
    }

    private fun enqueueByteBufBackingArray(
        value: ByteBuf,
        depth: Int,
        queue: java.util.ArrayDeque<QueuedObject>
    ) {
        runCatching {
            if (value.hasArray()) {
                queue.add(QueuedObject(value.array(), depth + 1))
            }
        }
    }

    private fun enqueueFieldReferences(value: Any, depth: Int, queue: java.util.ArrayDeque<QueuedObject>) {
        var clazz: Class<*>? = value.javaClass
        while (clazz != null && clazz != Any::class.java) {
            val currentClass = clazz
            if (shouldSkipFieldsForClass(currentClass)) return
            currentClass.declaredFields.forEach { field ->
                if (shouldSkipField(currentClass, field)) return@forEach
                val fieldValue = getFieldValue(field, value) ?: return@forEach
                queue.add(QueuedObject(fieldValue, depth + 1))
            }
            clazz = currentClass.superclass
        }
    }

    private fun getFieldValue(field: Field, owner: Any): Any? =
        runCatching {
            if (!field.trySetAccessible()) return null
            field.get(owner)
        }.getOrNull()

    private fun shouldSkipField(ownerClass: Class<*>, field: Field): Boolean {
        if (Modifier.isStatic(field.modifiers) || field.type.isPrimitive) return true
        if (field.name.startsWith("\$")) return true
        if (field.name == "eventSink") return true
        return ownerClass.name == "io.libp2p.quicsim.sim.NetworkContext" && field.name == "allNodes"
    }

    private fun shouldSkipObject(value: Any): Boolean {
        val className = value.javaClass.name
        return value is Class<*> ||
            value is ClassLoader ||
            value is Thread ||
            value is ThreadGroup ||
            value is Package ||
            className.startsWith("org.apache.logging.") ||
            className.startsWith("org.slf4j.")
    }

    private fun shouldSkipFieldsForClass(clazz: Class<*>): Boolean {
        val name = clazz.name
        return name == "java.lang.Class" ||
            name.startsWith("java.lang.reflect.") ||
            name.startsWith("java.lang.invoke.") ||
            name.startsWith("jdk.") ||
            name.startsWith("sun.")
    }

    private fun addContainerBackingEstimate(value: Any, classStats: MutableMap<String, ClassMemoryStats>) {
        val size = when (value) {
            is Map<*, *> -> value.size
            is Collection<*> -> value.size
            else -> return
        }
        if (size == 0) return

        val className = value.javaClass.name
        val estimatedBytes = when {
            value is ArrayList<*> -> layout.arraySize(size.coerceAtLeast(10), layout.referenceSize)
            value is HashMap<*, *> || className.contains("LinkedHashMap") ->
                layout.hashTableBackingSize(size, linked = className.contains("LinkedHashMap"))
            value is HashSet<*> || className.contains("LinkedHashSet") ->
                layout.hashTableBackingSize(size, linked = className.contains("LinkedHashSet"))
            className == "java.util.concurrent.ConcurrentHashMap" ->
                layout.hashTableBackingSize(size, linked = false)
            className.startsWith("java.util.Collections\$") -> return
            else -> return
        }
        addClassStats(classStats, "$className.[estimatedBacking]", 1, estimatedBytes)
    }

    private fun addClassStats(
        classStats: MutableMap<String, ClassMemoryStats>,
        className: String,
        count: Long,
        shallowBytes: Long
    ) {
        val stats = classStats.getOrPut(className) { ClassMemoryStats(className) }
        stats.count += count
        stats.shallowBytes += shallowBytes
    }

    private data class QueuedObject(
        val value: Any,
        val depth: Int
    )

    private inner class ByteBufStorageTracker {
        private val uniqueObjectStorage = IdentityHashMap<Any, Unit>()
        private val uniqueAddressStorage = mutableSetOf<AddressStorageKey>()
        val capacityStats = linkedMapOf<String, ClassMemoryStats>()
        val uniqueStorageStats = linkedMapOf<String, ClassMemoryStats>()

        val capacityBytes: Long
            get() = capacityStats.values.sumOf { it.shallowBytes }

        val uniqueStorageBytes: Long
            get() = uniqueStorageStats.values.sumOf { it.shallowBytes }

        val uniqueStorageCount: Int
            get() = uniqueObjectStorage.size + uniqueAddressStorage.size

        fun record(byteBuf: ByteBuf) {
            val capacity = runCatching { byteBuf.capacity().toLong() }.getOrDefault(0)
            addClassStats(
                capacityStats,
                "${byteBuf.javaClass.name}.[byteBufCapacity]",
                count = 1,
                shallowBytes = capacity
            )

            val storage = storageFor(byteBuf) ?: return
            if (!markUnique(storage)) return
            addClassStats(
                uniqueStorageStats,
                "${storage.className}.[uniqueByteBufStorage]",
                count = 1,
                shallowBytes = storage.bytes
            )
        }

        private fun markUnique(storage: ByteBufStorage): Boolean =
            storage.objectKey?.let { uniqueObjectStorage.put(it, Unit) == null }
                ?: storage.addressKey?.let { uniqueAddressStorage.add(it) }
                ?: false

        private fun storageFor(byteBuf: ByteBuf): ByteBufStorage? {
            val root = unwrapRoot(byteBuf)
            chunkStorage(byteBuf)?.let { return it }
            if (root !== byteBuf) {
                chunkStorage(root)?.let { return it }
            }
            parentByteBufStorage(byteBuf)?.let { return it }
            if (root !== byteBuf) {
                parentByteBufStorage(root)?.let { return it }
            }
            heapArrayStorage(root)?.let { return it }
            byteBufferFieldStorage(root)?.let { return it }
            heapArrayStorage(byteBuf)?.let { return it }
            byteBufferFieldStorage(byteBuf)?.let { return it }
            memoryAddressStorage(root)?.let { return it }
            memoryAddressStorage(byteBuf)?.let { return it }
            return ByteBufStorage(
                className = root.javaClass.name,
                bytes = runCatching { root.capacity().toLong() }.getOrDefault(byteBuf.capacity().toLong()),
                objectKey = root
            )
        }

        private fun unwrapRoot(byteBuf: ByteBuf): ByteBuf {
            var current = byteBuf
            val seen = IdentityHashMap<ByteBuf, Unit>()
            repeat(32) {
                if (seen.put(current, Unit) != null) return current
                val unwrapped = runCatching { current.unwrap() }.getOrNull() ?: return current
                current = unwrapped
            }
            return current
        }

        private fun chunkStorage(byteBuf: ByteBuf): ByteBufStorage? {
            val chunk = getNamedFieldValue(byteBuf, "chunk") ?: return null
            val bytes = invokeLongLikeNoArg(chunk, "capacity")
                ?: getNamedFieldValue(chunk, "capacity")?.let { it as? Number }?.toLong()
                ?: byteBuf.capacity().toLong()
            return ByteBufStorage(
                className = chunk.javaClass.name,
                bytes = bytes,
                objectKey = chunk
            )
        }

        private fun parentByteBufStorage(byteBuf: ByteBuf): ByteBufStorage? {
            val parent = getNamedFieldValue(byteBuf, "rootParent") as? ByteBuf ?: return null
            if (parent === byteBuf) return null
            val parentRoot = unwrapRoot(parent)
            chunkStorage(parentRoot)?.let { return it }
            heapArrayStorage(parentRoot)?.let { return it }
            byteBufferFieldStorage(parentRoot)?.let { return it }
            memoryAddressStorage(parentRoot)?.let { return it }
            return ByteBufStorage(
                className = "${parentRoot.javaClass.name}.[rootParent]",
                bytes = runCatching { parentRoot.capacity().toLong() }.getOrDefault(parent.capacity().toLong()),
                objectKey = parentRoot
            )
        }

        private fun heapArrayStorage(byteBuf: ByteBuf): ByteBufStorage? =
            runCatching {
                if (!byteBuf.hasArray()) return null
                val array = byteBuf.array()
                ByteBufStorage(
                    className = "${byteBuf.javaClass.name}.[array]",
                    bytes = array.size.toLong(),
                    objectKey = array
                )
            }.getOrNull()

        private fun byteBufferFieldStorage(byteBuf: ByteBuf): ByteBufStorage? {
            val buffer = getNamedFieldValue(byteBuf, "buffer") as? java.nio.ByteBuffer ?: return null
            return ByteBufStorage(
                className = "${byteBuf.javaClass.name}.[byteBuffer]",
                bytes = buffer.capacity().toLong(),
                objectKey = buffer
            )
        }

        private fun memoryAddressStorage(byteBuf: ByteBuf): ByteBufStorage? =
            runCatching {
                if (!byteBuf.hasMemoryAddress()) return null
                ByteBufStorage(
                    className = "${byteBuf.javaClass.name}.[memoryAddress]",
                    bytes = byteBuf.capacity().toLong(),
                    addressKey = AddressStorageKey(byteBuf.memoryAddress(), byteBuf.capacity())
                )
            }.getOrNull()

        private fun getNamedFieldValue(owner: Any, fieldName: String): Any? {
            var clazz: Class<*>? = owner.javaClass
            while (clazz != null && clazz != Any::class.java) {
                val field = clazz.declaredFields.firstOrNull { it.name == fieldName }
                if (field != null) return getFieldValue(field, owner)
                clazz = clazz.superclass
            }
            return null
        }

        private fun invokeLongLikeNoArg(owner: Any, methodName: String): Long? =
            runCatching {
                val method = owner.javaClass.methods.firstOrNull { it.name == methodName && it.parameterCount == 0 }
                    ?: return null
                (method.invoke(owner) as? Number)?.toLong()
            }.getOrNull()
    }

    private data class ByteBufStorage(
        val className: String,
        val bytes: Long,
        val objectKey: Any? = null,
        val addressKey: AddressStorageKey? = null
    )

    private data class AddressStorageKey(
        val address: Long,
        val capacity: Int
    )
}

data class HeapLayout(
    val objectHeaderSize: Int,
    val arrayHeaderSize: Int,
    val referenceSize: Int,
    val objectAlignment: Int
) {
    fun shallowSize(value: Any): Long {
        val clazz = value.javaClass
        if (clazz.isArray) {
            return arraySize(java.lang.reflect.Array.getLength(value), arrayElementSize(clazz.componentType))
        }
        var size = objectHeaderSize
        var currentClass: Class<*>? = clazz
        while (currentClass != null && currentClass != Any::class.java) {
            currentClass.declaredFields.forEach { field ->
                if (!Modifier.isStatic(field.modifiers)) {
                    size += fieldSize(field.type)
                }
            }
            currentClass = currentClass.superclass
        }
        return align(size)
    }

    fun arraySize(length: Int, elementSize: Int): Long =
        align(arrayHeaderSize + length.toLong() * elementSize)

    fun hashTableBackingSize(size: Int, linked: Boolean): Long {
        val capacity = tableCapacity(size)
        val nodeSize = if (linked) {
            objectHeaderSize + 4 + referenceSize * 4
        } else {
            objectHeaderSize + 4 + referenceSize * 3
        }
        return arraySize(capacity, referenceSize) + align(nodeSize) * size
    }

    private fun fieldSize(type: Class<*>): Int =
        when (type) {
            java.lang.Boolean.TYPE, java.lang.Byte.TYPE -> 1
            java.lang.Character.TYPE, java.lang.Short.TYPE -> 2
            java.lang.Integer.TYPE, java.lang.Float.TYPE -> 4
            java.lang.Long.TYPE, java.lang.Double.TYPE -> 8
            else -> referenceSize
        }

    private fun arrayElementSize(componentType: Class<*>): Int =
        if (componentType.isPrimitive) fieldSize(componentType) else referenceSize

    private fun align(value: Int): Long = align(value.toLong())

    private fun align(value: Long): Long {
        val remainder = value % objectAlignment
        return if (remainder == 0L) value else value + objectAlignment - remainder
    }

    private fun tableCapacity(size: Int): Int {
        var capacity = 1
        val target = (size / 0.75).toInt() + 1
        while (capacity < target) {
            capacity = capacity shl 1
        }
        return capacity
    }

    companion object {
        fun current(): HeapLayout {
            val compressedOops = hotSpotVmOption("UseCompressedOops")?.equals("true", ignoreCase = true) ?: true
            val alignment = hotSpotVmOption("ObjectAlignmentInBytes")?.toIntOrNull() ?: 8
            return HeapLayout(
                objectHeaderSize = if (compressedOops) 12 else 16,
                arrayHeaderSize = if (compressedOops) 16 else 24,
                referenceSize = if (compressedOops) 4 else 8,
                objectAlignment = alignment
            )
        }

        private fun hotSpotVmOption(name: String): String? =
            runCatching {
                ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean::class.java)
                    .getVMOption(name)
                    .value
            }.getOrNull()
    }
}

private fun currentHeapUsedBytes(): Long {
    val runtime = Runtime.getRuntime()
    return runtime.totalMemory() - runtime.freeMemory()
}

private fun List<Any>.toCsvRow(): String =
    joinToString(",") { value -> value.toString().csvEscape() }

private fun String.csvEscape(): String =
    if (contains(',') || contains('"') || contains('\n')) {
        "\"" + replace("\"", "\"\"") + "\""
    } else {
        this
    }
