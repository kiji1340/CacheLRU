package com.db1608.cache

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.db1608.cache.disklrucache.DiskLruCache
import com.db1608.cache.disklrucache.Util
import org.apache.commons.io.IOUtils
import java.io.*
import java.lang.RuntimeException
import java.math.BigInteger
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

class SimpleDiskCache {
    companion object {
        private const val VAlUE = 0
        private const val METADATA = 1
        private val usedDirs = ArrayList<File>()

        @Synchronized
        @Throws(IOException::class)
        fun open(dir: File, appVersion: Int, maxSize: Long): SimpleDiskCache {
            if (usedDirs.contains(dir)) {
                throw IllegalStateException("Cache dir ${dir.absolutePath} was used before.")
            }
            usedDirs.add(dir)
            return SimpleDiskCache(dir, appVersion, maxSize)
        }
    }

    private var diskLruCache: DiskLruCache
    private var appVersion: Int = 0

    @Throws(IOException::class)
    constructor(dir: File, appVersion: Int, maxSize: Long) {
        this.appVersion = appVersion
        diskLruCache = DiskLruCache.open(dir, appVersion, 2, maxSize)
    }

    @Throws(IOException::class)
    fun bytesUsed(): Long = diskLruCache.size()

    @Throws(IOException::class)
    fun clear() {
        val dir = diskLruCache.getDirectory()
        val maxSize = diskLruCache.getMaxSize()

        diskLruCache.delete()
        diskLruCache = DiskLruCache.open(dir, appVersion, 2, maxSize)
    }

    @Throws(IOException::class)
    fun delete(key: String) {
        diskLruCache.remove(toInternalKey(key))
    }

    fun getCache(): DiskLruCache {
        return diskLruCache
    }

    @Throws(IllegalAccessException::class, NoSuchFieldException::class)
    fun getLruEntries(): Map<String, String> {
        val field = diskLruCache.javaClass.getDeclaredField("lruEntries")
        field.isAccessible = true
        return field.get(diskLruCache) as Map<String, String>
    }

    @Throws(IOException::class)
    fun getInputStream(key: String): InputStreamEntry? {
        val snapshot = diskLruCache.get(toInternalKey(key)) ?: return null
        return InputStreamEntry(snapshot, readMetaData(snapshot) as Map<String, Serializable>)
    }

    @Throws(IOException::class)
    fun getBitMap(key: String): BitmapEntry? {
        val snapshot = diskLruCache.get(toInternalKey(key)) ?: return null
        snapshot.use {
            val bitmap = BitmapFactory.decodeStream(it.getInputStream(VAlUE))
            return BitmapEntry(bitmap, readMetaData(it) as Map<String, Serializable>)
        }
    }

    fun getString(key: String): StringEntry? {
        val snapshot = diskLruCache.get(toInternalKey(key)) ?: return null
        snapshot.use {
            return StringEntry(snapshot.getString(VAlUE), readMetaData(snapshot) as Map<String, Serializable>)
        }
    }

    @Throws(IOException::class)
    fun contains(key: String): Boolean {
        val snapshot = diskLruCache.get(toInternalKey(key)) ?: return false
        snapshot.close()
        return true
    }

    @Throws(IOException::class)
    fun openStream(key: String): OutputStream? {
        return openStream(key, HashMap())
    }

    @Throws(IOException::class)
    fun openStream(key: String, metadata: Map<String, Serializable>): OutputStream? {
        val editor = diskLruCache.edit(toInternalKey(key)) ?: return null
        try {
            writeMetaData(metadata, editor)
            val bufferedOutputStream = BufferedOutputStream(editor.newOutputStream(VAlUE))
            return CacheOutputStream(bufferedOutputStream, editor)
        } catch (ex: IOException) {
            editor.abort()
            throw ex
        }
    }

    @Throws(IOException::class)
    fun put(key: String, inputStream: InputStream) {
        put(key, inputStream, HashMap())
    }

    @Throws(IOException::class)
    fun put(key: String, inputStream: InputStream, annotations: Map<String, Serializable>) {
        var outputStream: OutputStream? = null
        try {
            outputStream = openStream(key, annotations)
            IOUtils.copy(inputStream, outputStream)
        } finally {
            outputStream?.close()
        }
    }

    @Throws(IOException::class)
    fun put(key: String, value: String) {
        put(key, value, HashMap())
    }

    @Throws(IOException::class)
    fun put(key: String, value: String, annotations: Map<String, Serializable>) {
        var outputStream: OutputStream? = null
        try {
            outputStream = openStream(key, annotations)
            outputStream?.write(value.toByteArray())
        } finally {
            outputStream?.close()
        }
    }

    private fun writeMetaData(
        metadata: Map<String, Serializable>
        , editor: DiskLruCache.Editor
    ) {
        var objectOutputStream: ObjectOutputStream? = null
        try {
            objectOutputStream = ObjectOutputStream(BufferedOutputStream(editor.newOutputStream(METADATA)))
            objectOutputStream.writeObject(metadata)
        } finally {
            IOUtils.closeQuietly(objectOutputStream)
        }
    }

    @Throws(IOException::class)
    private fun readMetaData(snapshot: DiskLruCache.Snapshot): Any? {
        var objectInputStream: ObjectInputStream? = null
        try {
            objectInputStream = ObjectInputStream(BufferedInputStream(snapshot.getInputStream(METADATA)!!))
            return objectInputStream.readObject()
        } catch (ex: ClassNotFoundException) {
            throw RuntimeException(ex)
        } finally {
            IOUtils.closeQuietly(objectInputStream)
        }
    }


    private fun toInternalKey(key: String): String {
        return md5(key)
    }

    private fun md5(content: String): String {
        try {
            val messageDigest = MessageDigest.getInstance("md5")
            messageDigest.update(content.toByteArray(Util.UTF_8))
            val digest = messageDigest.digest()
            val bigInteger = BigInteger(1, digest)
            return bigInteger.toString(16)
        } catch (ex: NoSuchAlgorithmException) {
            throw AssertionError()
        } catch (ex: UnsupportedEncodingException) {
            throw AssertionError()
        }

    }

    private inner class CacheOutputStream(
        outputStream: OutputStream
        , private val editor: DiskLruCache.Editor
    ) : FilterOutputStream(outputStream) {
        private var failed = false

        override fun close() {
            var closeException: IOException? = null
            try {
                super.close()
            } catch (ex: IOException) {
                closeException = ex
            }
            if (failed) editor.abort() else editor.commit()

            if (closeException != null) throw closeException
        }

        override fun flush() {
            try {
                super.flush()
            } catch (ex: IOException) {
                failed = true
                throw ex
            }
        }

        override fun write(oneByte: Int) {
            try {
                super.write(oneByte)
            } catch (ex: IOException) {
                failed = true
                throw ex
            }
        }

        override fun write(b: ByteArray) {
            try {
                super.write(b)
            } catch (ex: IOException) {
                failed = true
                throw ex
            }
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            try {
                super.write(b, off, len)
            } catch (ex: IOException) {
                failed = true
                throw ex
            }

        }
    }

    class InputStreamEntry(
        private val snapshot: DiskLruCache.Snapshot
        , private val metadata: Map<String, Serializable>
    ) {

        fun getInputStream(): InputStream? = snapshot.getInputStream(VAlUE)

        fun getMetaData() = metadata

        fun close() {
            snapshot.close()
        }
    }

    data class BitmapEntry(
        val bitmap: Bitmap
        , val metadata: Map<String, Serializable>
    )

    data class StringEntry(
        val string: String
        , val metadata: Map<String, Serializable>
    )

}

