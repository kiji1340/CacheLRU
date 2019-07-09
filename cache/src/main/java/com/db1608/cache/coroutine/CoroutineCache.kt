package com.db1608.cache.coroutine

import com.db1608.cache.CacheLRU
import com.db1608.cache.model.BaseMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class CoroutineCache {
    companion object {
        fun <T> getAsync(key: String, classOfT: Class<T>): T?{
            return async(CacheLRU.get(key, classOfT))
        }


        fun <T> async(method: BaseMethod<T>): T? {
            return runBlocking(Dispatchers.IO) {
                with(method)
            }
        }

        suspend fun <T> with(method: BaseMethod<T>): T {
            return method.execute()
        }
    }
}