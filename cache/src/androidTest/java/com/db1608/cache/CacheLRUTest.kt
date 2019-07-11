package com.db1608.cache

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.db1608.cache.coroutine.CoroutineCache
import com.db1608.cache.model.Callback
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.BeforeClass
import org.junit.Test
import java.io.IOException
import java.io.Serializable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CacheLRUTest {
    private val data: Data = Data("name", "content")

    companion object {
        @BeforeClass
        @JvmStatic
        fun setup() {
            val context = InstrumentationRegistry.getInstrumentation().context
            CacheLRUBuilder.configure(8192)
                .setDefaultCacheDirectory(context)
                .setPasswordEncryption("test")
                .initialize()
        }
    }


    @Test
    @Throws(IOException::class)
    fun writeAndCheckCache() {
        CacheLRU.put("data", data).execute()
        assertThat(CacheLRU.contains("data")).isTrue()
    }

    @Test
    @Throws(IOException::class)
    fun write_ReadCache() {
        CacheLRU.put("data", data).execute()
        val dataCache = CacheLRU.get("data", Data::class.java).execute()
        assertThat(dataCache?.name).isEqualTo(data.name)
        assertThat(dataCache?.content).isEqualTo(data.content)
    }

    @Test
    fun writeAsync() {
        val signal = CountDownLatch(1)
        CacheLRU.put("data", data).async(object : Callback<Boolean> {
            override fun onResult(result: Boolean) {
                assertThat(result).isTrue()
                signal.countDown()
            }
        })
        signal.await()
    }

    @Test
    fun write_ReadAsync() {
        val signal = CountDownLatch(1)
        CacheLRU.put("data", data).execute()
        CacheLRU.get("data", Data::class.java).async(object : Callback<Data?> {
            override fun onResult(result: Data?) {
                assertThat(result?.name).isEqualTo(data.name)
                assertThat(result?.content).isEqualTo(data.content)
                signal.countDown()
            }
        })
        signal.await()
    }

    @Test
    fun writeExpire_GetAsyncExpire() {
        val signal = CountDownLatch(2)
        CacheLRU.put("data", data).setExpiry(5, TimeUnit.SECONDS).execute()
        CacheLRU.get("data", Data::class.java).async(object : Callback<Data?> {
            override fun onResult(result: Data?) {
                assertThat(result).isNotNull()
                signal.countDown()
            }
        })
        Thread.sleep(5000)
        CacheLRU.get("data", Data::class.java).async(object : Callback<Data?> {
            override fun onResult(result: Data?) {
                assertThat(result).isNull()
                signal.countDown()
            }
        })
        signal.await()


    }

    @Test
    @Throws(IOException::class)
    fun writeExpire_CheckData() {
        CacheLRU.put("data", data).setExpiry(5, TimeUnit.SECONDS).execute()
        val expired1 = CacheLRU.hasExpired("data").execute()
        assertThat(expired1).isFalse()
        Thread.sleep(5100)
        val expired2 = CacheLRU.hasExpired("data").execute()
        assertThat(expired2).isTrue()
    }

    @Test
    @Throws(IOException::class)
    fun write_And_GetByCoroutine() {
        CacheLRU.put("data", data).execute()
        val result = runBlocking { CoroutineCache.with(CacheLRU.get("data", Data::class.java)) }
        assertThat(result?.name).isEqualTo(data.name)
    }

    @Test
    @Throws(IOException::class)
    fun write_And_GetByCoroutineAsync() {
        CacheLRU.put("data", data).execute()
        val result = CoroutineCache.getAsync("data", Data::class.java)
        assertThat(result?.name).isEqualTo(data.name)
    }
}

data class Data(val name: String, val content: String) : Serializable
