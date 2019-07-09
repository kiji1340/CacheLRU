package com.db1608.cache.model

import android.util.Log
import java.util.concurrent.Executors

abstract class BaseMethod<T>{

    fun async(callback: Callback<T>){
        val executor = Executors.newSingleThreadExecutor()
        executor.execute {
            val result = execute()
            callback.onResult(result)
        }
        executor.shutdown()
    }

    abstract fun execute(): T
}