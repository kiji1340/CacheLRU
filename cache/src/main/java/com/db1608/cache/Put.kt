package com.db1608.cache

import android.util.Log
import com.db1608.cache.model.BaseMethod
import com.db1608.cache.model.EntryData
import java.util.concurrent.TimeUnit

class Put<T>(private val key: String, private val data: T) : BaseMethod<Boolean>() {

    private var expiry = CacheLRU.NO_EXPIRY

    override fun execute(): Boolean {
        val entry = EntryData<T>()
        entry.data = data
        entry.expiry = expiry
        return try {
            CacheLRU.internalPut(key, entry)
            true
        } catch (ex: Exception) {
            Log.e("CacheLRU", ex.message)
            false
        }
    }

    private fun setExpiry(expiry: Long): Put<T> {
        this.expiry = expiry
        return this
    }

    fun setExpiry(duration: Long, unit: TimeUnit): Put<T> {
        return setExpiry(System.currentTimeMillis() + unit.toMillis(duration))
    }
}