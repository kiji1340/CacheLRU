package com.db1608.cachelru

import android.app.Application
import com.db1608.cache.CacheLRUBuilder

class MyApplication : Application(){

    override fun onCreate() {
        super.onCreate()
        CacheLRUBuilder.configure(8152)
            .setDefaultCacheDirectory(this)
//            .setPasswordEncryption("test") //turn on encryption
            .initialize()
    }
}