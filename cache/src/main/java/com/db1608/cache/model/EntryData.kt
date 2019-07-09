package com.db1608.cache.model

import java.io.Serializable

class EntryData<T> : Entry(), Serializable {
    var data: T? = null
}