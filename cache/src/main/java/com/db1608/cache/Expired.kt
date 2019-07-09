package com.db1608.cache

import com.db1608.cache.model.BaseMethod

class Expired(private val key: String) : BaseMethod<Boolean?>() {

    override fun execute(): Boolean? {
        val entry = GetEntry(key).execute() ?: return null
        return entry.hasExpired()
    }

}