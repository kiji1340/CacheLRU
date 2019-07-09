package com.db1608.cache

import com.db1608.cache.model.BaseMethod
import com.db1608.cache.model.Entry

class GetEntry(private val key: String): BaseMethod<Entry?>() {

    override fun execute(): Entry? {
        return CacheLRU.internalGet(key, Entry::class.java)
    }

}