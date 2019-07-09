package com.db1608.cache.disklrucache

import java.io.*
import java.lang.NumberFormatException
import java.lang.StringBuilder
import java.util.LinkedHashMap
import java.util.concurrent.Callable
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class DiskLruCache(
    private val directory: File
    , private val appVersion: Int
    , private val valueCount: Int
    , private var maxSize: Long
) : Closeable {

    companion object {
        const val CACHE_FILE: String = "file"
        const val CACHE_FILE_TEMP: String = "file.tmp"
        const val CACHE_FILE_BACKUP: String = "file.bkp"
        const val MAGIC = "cache.DiskLruCache"
        const val VERSION_1 = "1"
        const val ANY_SEQUENCE_NUMBER = (-1).toLong()
        private const val STRING_KEY_PATTERN = "[a-z0-9_-]{1,120}"
        val LEGAL_KEY_PATTERN: Pattern = Pattern.compile(STRING_KEY_PATTERN)
        const val CLEAN = "CLEAN"
        const val DIRTY = "DIRTY"
        const val REMOVE = "REMOVE"
        const val READ = "READ"


        /**
         * Opens the cache in {@code directory}, creating a cache if none exists
         * there.
         *
         * @param directory a writable directory
         * @param valueCount the number of values per cache entry. Must be positive.
         * @param maxSize the maximum number of bytes this cache should use to store
         * @throws IOException if reading or writing the cache directory fails
         */
        @Throws(IOException::class)
        fun open(directory: File, appVersion: Int, valueCount: Int, maxSize: Long): DiskLruCache {
            if (maxSize <= 0) {
                throw java.lang.IllegalArgumentException("maxSize <= 0")
            }
            if (valueCount <= 0) {
                throw java.lang.IllegalArgumentException("valueCount <= 0")
            }

            // If a bkp file exists, use it instead.
            val backupFile = File(directory, CACHE_FILE_BACKUP)
            if (backupFile.exists()) {
                val file = File(directory, CACHE_FILE)
                // If journal file also exists just delete backup file.
                if (file.exists()) {
                    backupFile.delete()
                } else {
                    renameTo(backupFile, file, false)
                }
            }

            var cache = DiskLruCache(directory, appVersion, valueCount, maxSize)
            if (cache.cacheFile.exists()) {
                try {
                    println("start")
                    cache.readCache()
                    println("read cache")
                    cache.processCache()
                    println("process cache")
                    return cache
                } catch (cacheIsCorrupt: IOException) {
                    println(
                        "DiskLruCache "
                                + directory
                                + " is corrupt: "
                                + cacheIsCorrupt.message
                                + ", removing"
                    )
                    cache.delete()
                }
            }

            //Create a new empty cache
            directory.mkdirs()
            cache = DiskLruCache(directory, appVersion, valueCount, maxSize)
            cache.rebuildCache()
            return cache
        }

        @Throws(IOException::class)
        private fun deleteIfExists(file: File) {
            if (file.exists() && !file.delete()) {
                throw IOException()
            }
        }

        @Throws(IOException::class)
        private fun renameTo(from: File, to: File, deleteDestination: Boolean) {
            if (deleteDestination) {
                deleteIfExists(to)
            }
            if (!from.renameTo(to)) {
                throw IOException()
            }
        }

        @Throws(IOException::class)
        private fun inputStreamToString(inputStream: InputStream): String {
            return Util.readFully(
                InputStreamReader(
                    inputStream,
                    Util.UTF_8
                )
            )
        }

        private val NULL_OUTPUT_STREAM: OutputStream = object : OutputStream() {
            override fun write(b: Int) {
            }
        }
    }

    private val cacheFile = File(directory, CACHE_FILE)
    private val cacheFileTemp = File(directory, CACHE_FILE_TEMP)
    private val cacheFileBackup = File(directory,
        CACHE_FILE_BACKUP
    )
    private var size: Long = 0
    private var cacheWriter: Writer? = null
    private val lruEntries = LinkedHashMap<String, Entry>(0, 0.75f, true)
    private var redundantOpCount: Int = 0

    /**
     * To differentiate between old and current snapshots, each entry is given
     * a sequence number each time an edit is committed. A snapshot is stale if
     * its sequence number is not equal to its entry's sequence number.
     */
    private var nextSequenceNumber: Long = 0

    /** This cache uses a single background thread to evict entries. */
    val executorService = ThreadPoolExecutor(
        0
        , 1
        , 60L
        , TimeUnit.SECONDS
        , LinkedBlockingDeque<Runnable>()
    )
    private val cleanupCallable = Callable<Void> {
        synchronized(this@DiskLruCache) {
            if (cacheWriter == null) {
                return@Callable null
            }
            trimToSize()
            if (cacheRebuildRequired()) {
                rebuildCache()
                redundantOpCount = 0
            }
            return@Callable null
        }
    }


    @Synchronized
    @Throws(IOException::class)
    fun get(key: String): Snapshot? {
        checkNotClosed()
        validateKey(key)
        val entry = lruEntries[key] ?: return null

        if (!entry.readable) {
            return null
        }

        // Open all streams eagerly to guarantee that we see a single published
        // snapshot. If we opened streams lazily then the streams could come
        // from different edits.
        val ins = arrayOfNulls<InputStream>(valueCount)
        try {
            for (i in 0.until(valueCount)) {
                ins[i] = FileInputStream(entry.getCleanFile(i))
            }
        } catch (e: FileNotFoundException) {
            for (i in 0.until(valueCount)) {
                if (ins[i] != null) {
                    Util.closeQuietly(ins[i])
                } else {
                    break
                }
            }
            return null
        }
        redundantOpCount++
        cacheWriter?.append("$READ $key\n")
        if (cacheRebuildRequired()) {
            executorService.submit(cleanupCallable)
        }
        return Snapshot(key, entry.sequenceNumber, ins, entry.lengths)
    }

    private fun checkNotClosed() {
        if (cacheWriter == null) {
            throw java.lang.IllegalStateException("cache is closed")
        }
    }

    @Synchronized
    @Throws(IOException::class)
    fun flush() {
        checkNotClosed()
        trimToSize()
        cacheWriter?.flush()
    }

    private fun validateKey(key: String) {
        val matcher = LEGAL_KEY_PATTERN.matcher(key)
        if (!matcher.matches()) {
            throw IllegalArgumentException("keys must match regex $STRING_KEY_PATTERN: \"$key\"")
        }
    }

    private fun cacheRebuildRequired(): Boolean {
        val redundantOpCompactThreshold = 2000
        return redundantOpCount >= redundantOpCompactThreshold
                && redundantOpCount >= lruEntries.size
    }

    @Synchronized
    @Throws(IOException::class)
    fun rebuildCache() {
        cacheWriter?.close()

        val writer = BufferedWriter(OutputStreamWriter(FileOutputStream(cacheFileTemp),
            Util.US_ASCII
        ))
        writer.use {
            writer.write(MAGIC)
            writer.write("\n")
            writer.write(VERSION_1)
            writer.write("\n")
            writer.write(appVersion.toString())
            writer.write("\n")
            writer.write(valueCount.toString())
            writer.write("\n")
            writer.write("\n")

            for (entry in lruEntries.values) {
                if (entry.currentEditor != null) {
                    writer.write("$DIRTY ${entry.key}\n")
                } else {
                    writer.write("$CLEAN ${entry.key}${entry.getLengths()}\n")
                }
            }
        }


        if (cacheFile.exists()) {
            renameTo(cacheFile, cacheFileBackup, true)
        }
        renameTo(cacheFileTemp, cacheFile, false)
        cacheFileBackup.delete()

        cacheWriter = BufferedWriter(OutputStreamWriter(FileOutputStream(cacheFile, true),
            Util.US_ASCII
        ))
    }

    @Synchronized
    @Throws(IOException::class)
    private fun completeEdit(editor: Editor, success: Boolean) {
        val entry = editor.entry
        if (entry.currentEditor != editor) {
            throw java.lang.IllegalStateException()
        }

        if (success && !entry.readable) {
            for (i in 0.until(valueCount)) {
                if (editor.written != null && !editor.written[i]) {
                    editor.abort()
                    throw IllegalStateException("Newly created entry didn't create value for index $i")
                }
                if (!entry.getDirtyFile(i).exists()) {
                    editor.abort()
                    return
                }
            }
        }
        for (i in 0.until(valueCount)) {
            val dirty = entry.getDirtyFile(i)
            if (success) {
                if (dirty.exists()) {
                    val clean = entry.getCleanFile(i)
                    dirty.renameTo(clean)
                    val oldLength = entry.lengths[i]
                    val newLength = clean.length()
                    entry.lengths[i] = newLength
                    size = size - oldLength + newLength
                }
            } else {
                deleteIfExists(dirty)
            }
        }

        redundantOpCount++
        entry.currentEditor = null
        if (entry.readable or success) {
            entry.readable = true
            cacheWriter?.write("$CLEAN ${entry.key}${entry.getLengths()}\n")
            if (success) {
                entry.sequenceNumber = nextSequenceNumber++
            }
        } else {
            lruEntries.remove(entry.key)
            cacheWriter?.write("$REMOVE ${entry.key}\n")
        }
        cacheWriter?.flush()

        if (size > maxSize || cacheRebuildRequired()) {
            executorService.submit(cleanupCallable)
        }
    }

    /**
     * Drops the entry for {@code key} if it exists and can be removed. Entries
     * actively being edited cannot be removed.
     *
     * @return true if an entry was removed.
     */
    @Synchronized
    @Throws(IOException::class)
    fun remove(key: String): Boolean {
        checkNotClosed()
        validateKey(key)
        val entry = lruEntries[key]
        if (entry == null || entry.currentEditor != null) {
            return false
        }

        for (i in 0.until(valueCount)) {
            val file = entry.getCleanFile(i)
            if (file.exists() && !file.delete()) {
                throw IOException("failed to delete $file")
            }
            size -= entry.lengths[i]
            entry.lengths[i] = 0
        }


        redundantOpCount++
        cacheWriter?.append("$REMOVE $key\n")
        lruEntries.remove(key)

        if (cacheRebuildRequired()) {
            executorService.submit(cleanupCallable)
        }
        return true
    }

    @Synchronized
    fun isClosed(): Boolean = cacheWriter == null

    @Throws(IOException::class)
    fun edit(key: String): Editor? {
        return edit(key, ANY_SEQUENCE_NUMBER)
    }

    @Synchronized
    @Throws(IOException::class)
    private fun edit(key: String, expectedSequenceNumber: Long): Editor? {
        checkNotClosed()
        validateKey(key)
        var entry = lruEntries[key]
        if (expectedSequenceNumber != ANY_SEQUENCE_NUMBER
            && (entry == null || entry.sequenceNumber != expectedSequenceNumber)
        ) {
            return null
        }

        if (entry == null) {
            entry = Entry(key)
            lruEntries[key] = entry
        } else if (entry.currentEditor != null) {
            return null
        }
        val editor = Editor(entry)
        entry.currentEditor = editor

        cacheWriter?.write("$DIRTY $key\n")
        cacheWriter?.flush()
        return editor
    }

    /** Returns the directory where this cache stores its data. */
    fun getDirectory() = directory

    /**
     * Returns the maximum number of bytes that this cache should use to store
     * its data.
     */
    @Synchronized
    fun getMaxSize() = maxSize

    /**
     * Changes the maximum number of bytes the cache can store and queues a job
     * to trim the existing store, if necessary.
     */
    fun setMaxSize(maxSize: Long) {
        this.maxSize = maxSize
        executorService.submit(cleanupCallable)
    }

    /**
     * Returns the number of bytes currently being used to store the values in
     * this cache. This may be greater than the max size if a background
     * deletion is pending.
     */
    @Synchronized
    fun size() = size


    @Throws(IOException::class)
    private fun trimToSize() {
        while (size > maxSize) {
            val toEvict = lruEntries.entries.iterator().next()
            remove(toEvict.key)
        }
    }

    @Throws(IOException::class)
    private fun readCache() {
        val reader = StrictLineReader(
            FileInputStream(cacheFile),
            Util.US_ASCII
        )
        try {
            val magic = reader.readLine()
            val version = reader.readLine()
            val appVersionString = reader.readLine()
            val valueCountString = reader.readLine()
            val blank = reader.readLine()
            if (MAGIC != magic
                || VERSION_1 != version
                || appVersion.toString() != appVersionString
                || valueCount.toString() != valueCountString
                || "" != blank
            ) {
                throw IOException("unexpected cache header: [ $magic, $version, $valueCountString, $blank ]")
            }

            var lineCount = 0
            while (true) {
                try {
                    readCacheLine(reader.readLine())
                    lineCount++
                } catch (endOfCache: EOFException) {
                    break
                }
            }
            redundantOpCount = lineCount - lruEntries.size

            // If we ended on a truncated line, rebuild the cache before appending to it.
            if (reader.hasUnterminatedLine()) {
                rebuildCache()
            } else {
                cacheWriter = BufferedWriter(OutputStreamWriter(FileOutputStream(cacheFile, true),
                    Util.US_ASCII
                ))
            }
        } finally {
            Util.closeQuietly(reader)
        }

    }

    @Throws(IOException::class)
    private fun readCacheLine(line: String) {
        val firstSpace = line.indexOf(' ')
        if (firstSpace == -1) {
            throw IOException("unexpected cache line: $line")
        }
        val keyBegin = firstSpace + 1
        val secondSpace = line.indexOf(' ', keyBegin)
        val key: String
        if (secondSpace == -1) {
            key = line.substring(keyBegin)
            if (firstSpace == REMOVE.length && line.startsWith(REMOVE)) {
                lruEntries.remove(key)
                return
            }
        } else {
            key = line.substring(keyBegin, secondSpace)
        }

        var entry = lruEntries[key]
        if (entry == null) {
            entry = Entry(key)
            lruEntries[key] = entry
        }

        if (secondSpace != -1 && firstSpace == CLEAN.length && line.startsWith(
                CLEAN
            )) {
            val parts = line.substring(secondSpace + 1)
                .split(" ".toRegex())
                .dropLastWhile { it.isEmpty() }
                .toTypedArray()
            entry.readable = true
            entry.currentEditor = null
            entry.setLengths(parts)
        } else if (secondSpace == -1 && firstSpace == DIRTY.length && line.startsWith(
                DIRTY
            )) {
            entry.currentEditor = Editor(entry)
        } else if (secondSpace == -1 && firstSpace == READ.length && line.startsWith(
                READ
            )) {
            // This work was already done by calling lruEntries.get().
        } else {
            throw IOException("unexpected cache line: $line")
        }

    }

    /**
     * Computes the initial size and collects garbage as a part of opening the
     * cache. Dirty entries are assumed to be inconsistent and will be deleted.
     */
    @Throws(IOException::class)
    private fun processCache() {
        deleteIfExists(cacheFileTemp)
        val i = lruEntries.values.iterator()
        while (i.hasNext()) {
            val entry = i.next()
            if (entry.currentEditor == null) {
                for (t in 0 until valueCount) {
                    size += entry.lengths[t]
                }
            } else {
                entry.currentEditor = null
                for (t in 0 until valueCount) {
                    deleteIfExists(entry.getCleanFile(t))
                    deleteIfExists(entry.getDirtyFile(t))
                }
                i.remove()
            }
        }
    }

    /**
     * Closes the cache and deletes all of its stored values. This will delete
     * all files in the cache directory including files that weren't created by
     * the cache.
     */
    @Throws(IOException::class)
    fun delete() {
        close()
        Util.deleteContents(directory)
    }

    @Synchronized
    @Throws(IOException::class)
    override fun close() {
        if (cacheWriter == null) {
            return
        }
        for (entry in ArrayList(lruEntries.values)) {
            entry.currentEditor?.abort()
        }
        trimToSize()
        cacheWriter?.close()
        cacheWriter = null
    }

    inner class Editor(internal val entry: Entry) {
        internal val written = if (entry.readable) null else BooleanArray(valueCount)
        private var hasErrors: Boolean = false
        private var committed: Boolean = false

        /**
         * Returns an unbuffered input stream to read the last committed value,
         * or null if no value has been committed.
         */
        @Throws(IOException::class)
        fun newInputStream(index: Int): InputStream? {
            synchronized(this) {
                if (entry.currentEditor != this)
                    throw IllegalStateException()
                if (!entry.readable) return null
                return try {
                    FileInputStream(entry.getCleanFile(index))
                } catch (e: FileNotFoundException) {
                    null
                }
            }
        }

        /**
         * Returns the last committed value as a string, or null if no value
         * has been committed.
         */
        fun getString(index: Int): String? {
            val inputStream = newInputStream(index)
            return if (inputStream != null) inputStreamToString(
                inputStream
            ) else null
        }

        @Throws(IOException::class)
        fun newOutputStream(index: Int): OutputStream {
            if (index < 0 || index >= valueCount) {
                throw IllegalArgumentException(
                    "Expected index " + index + " to "
                            + "be greater than 0 and less than the maximum value count "
                            + "of " + valueCount
                )
            }
            synchronized(DiskLruCache) {
                if (entry.currentEditor != this) {
                    throw IllegalStateException()
                }
                if (!entry.readable) {
                    written!![index] = true
                }
                val dirtyFile = entry.getDirtyFile(index)
                var outputStream: FileOutputStream
                outputStream = try {
                    FileOutputStream(dirtyFile)
                } catch (e: FileNotFoundException) {
                    directory.mkdirs()
                    try {
                        FileOutputStream(dirtyFile)
                    } catch (e2: FileNotFoundException) {
                        return NULL_OUTPUT_STREAM
                    }
                }
                return FaultHidingOutputStream(outputStream)
            }
        }

        @Throws(IOException::class)
        fun set(index: Int, value: String) {
            var writer: Writer? = null
            try {
                writer = OutputStreamWriter(newOutputStream(index = index),
                    Util.UTF_8
                )
                writer.write(value)
            } finally {
                writer?.let { Util.closeQuietly(it) }
            }
        }

        @Throws(IOException::class)
        fun commit() {
            if (hasErrors) {
                completeEdit(this, false)
                remove(entry.key)
            } else {
                completeEdit(this, true)
            }
        }

        @Throws(IOException::class)
        fun abort() {
            completeEdit(this, false)
        }

        fun abortUnlessComitted() {
            if (!committed) {
                try {
                    abort()
                } catch (ignored: IOException) {

                }
            }
        }


        private inner class FaultHidingOutputStream(out: OutputStream) : FilterOutputStream(out) {
            override fun write(b: Int) {
                try {
                    out.write(b)
                } catch (e: IOException) {
                    hasErrors = false
                }
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                try {
                    out.write(b, off, len)
                } catch (e: IOException) {
                    hasErrors = false
                }
            }

            override fun close() {
                try {
                    out.close()
                } catch (e: IOException) {
                    hasErrors = false
                }
            }

            override fun flush() {
                try {
                    out.flush()
                } catch (e: IOException) {
                    hasErrors = false
                }
            }
        }
    }

    inner class Entry(val key: String) {
        /** Lengths of this entry's files. */
        internal val lengths = LongArray(valueCount)
        /** True if this entry has ever been published. */
        var readable: Boolean = false
        /** The ongoing edit or null if this entry is not being edited. */
        var currentEditor: Editor? = null
        /** The sequence number of the most recently committed edit to this entry. */
        internal var sequenceNumber: Long = 0

        @Throws(IOException::class)
        fun getLengths(): String {
            val result = StringBuilder()
            for (size in lengths) {
                result.append(' ').append(size)
            }
            return result.toString()
        }

        /** Set lengths using decimal numbers like "10123". */
        @Throws(IOException::class)
        internal fun setLengths(strings: Array<String>) {
            if (strings.size != valueCount) {
                throw invalidLengths(strings)
            }
            try {
                for (i in 0.until(strings.size)) {
                    lengths[i] = strings[i].toLong()
                }
            } catch (e: NumberFormatException) {
                throw invalidLengths(strings)
            }
        }

        @Throws(IOException::class)
        private fun invalidLengths(strings: Array<String>): IOException {
            throw IOException("unexpected cache line: ${java.util.Arrays.toString(strings)}")
        }

        fun getCleanFile(i: Int) = File(directory, "$key.$i")
        fun getDirtyFile(i: Int) = File(directory, "$key.$i.tmp")
    }


    inner class Snapshot(
        private val key: String
        , private val sequenceNumber: Long
        , private val ins: Array<InputStream?>
        , private val lengths: LongArray
    ) :
        Closeable {

        /**
         * Returns an editor for this snapshot's entry, or null if either the
         * entry has changed since this snapshot was created or if another edit
         * is in progress.
         */
        @Throws(IOException::class)
        fun edit(): Editor? {
            return this@DiskLruCache.edit(key, sequenceNumber)
        }

        /** Returns the unbuffered stream with the value for {@code index}. */
        fun getInputStream(index: Int) = ins[index]

        /** Returns the string value for {@code index}. */
        @Throws(IOException::class)
        fun getString(index: Int): String {
            return inputStreamToString(getInputStream(index)!!)
        }

        fun getLength(index: Int) = lengths[index]


        override fun close() {
            for (`in` in ins) {
                Util.closeQuietly(`in`)
            }
        }

    }

}