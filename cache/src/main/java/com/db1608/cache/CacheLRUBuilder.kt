package com.db1608.cache

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.lang.RuntimeException

class CacheLRUBuilder(val maxSize: Long) {

    enum class Storage {
        INTERNAL,
        PREFER_EXTERNAL
    }

    companion object {
        fun configure(maxSize: Long): CacheLRUBuilder {
            return CacheLRUBuilder(maxSize)
        }
    }

    var cacheDir: File? = null
    var gson: Gson? = null
    var version: Double = 0.toDouble()
    var password: String? = null

    private fun createGson(): Gson {
        val builder = GsonBuilder()
        builder.setVersion(version)
        return builder.create()
    }

    @Synchronized
    fun initialize() {
        if (cacheDir == null) {
            throw RuntimeException("No cache directory has")
        }
        if (gson == null) {
            gson = createGson()
        }
        CacheLRU.initialize(this)
    }

    fun setCacheDirectory(context: Context, storage: Storage): CacheLRUBuilder {
        return when (storage) {
            Storage.INTERNAL -> setCacheDirectory(context.cacheDir)
            Storage.PREFER_EXTERNAL -> {
                var dir = context.externalCacheDir
                if (dir == null || !dir.exists() || !dir.canWrite()) {
                    dir = context.cacheDir
                }
                setCacheDirectory(dir)
            }
            else -> {
                throw IllegalArgumentException("Invalid storage value: $storage")
            }
        }
    }

    fun setDefaultCacheDirectory(context: Context): CacheLRUBuilder {
        return setCacheDirectory(context, Storage.INTERNAL)
    }

    fun setCacheDirectory(cacheDir: File): CacheLRUBuilder {
        this.cacheDir = cacheDir
        return this
    }

    fun setGsonInstance(gson: Gson): CacheLRUBuilder {
        this.gson = gson
        return this
    }

    fun setVersion(version: Double): CacheLRUBuilder {
        this.version = version
        return this
    }

    fun setPasswordEncryption(password: String): CacheLRUBuilder {
        this.password = password
        return this
    }
}