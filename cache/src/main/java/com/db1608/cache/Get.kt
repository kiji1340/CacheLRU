package com.db1608.cache

import android.util.Log
import com.db1608.cache.model.BaseMethod
import com.db1608.cache.model.EntryData
import com.db1608.cache.model.EntryDataType
import java.lang.reflect.Type

class Get<T>(private val key: String, private val typeOfT: Type) : BaseMethod<T?>() {

    private var ignoreExpiry = false

    override fun execute(): T? {
        if (!ignoreExpiry) {
            val expired = CacheLRU.hasExpired(key).execute()
            if (expired != null && expired) {
                return null
            }
        }
        val entry = CacheLRU.internalGet<EntryData<T>>(key, EntryDataType(typeOfT)) ?: return null
        return entry.data
    }

    fun getIgnoreExpiry(ignore: Boolean): Get<T> {
        ignoreExpiry = ignore
        return this
    }

}