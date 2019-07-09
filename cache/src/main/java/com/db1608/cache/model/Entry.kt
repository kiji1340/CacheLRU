package com.db1608.cache.model

import java.io.Serializable

open class Entry: Serializable {
    var expiry: Long = 0

    fun hasExpired(): Boolean = (expiry > 0) && (System.currentTimeMillis() >= expiry)
}