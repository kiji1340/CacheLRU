package com.db1608.cache.model

interface Callback<T>{
    fun onResult(result: T)
}