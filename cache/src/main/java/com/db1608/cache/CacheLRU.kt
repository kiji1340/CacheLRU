package com.db1608.cache

import android.util.Log
import com.google.gson.Gson
import java.lang.RuntimeException
import java.lang.reflect.Type
import android.R.attr.password
import com.db1608.cache.disklrucache.Util
import java.io.*
import java.security.InvalidKeyException
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import javax.crypto.*
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec


class CacheLRU {

    companion object {
        val NO_EXPIRY = 0.toLong()
        private lateinit var cache: SimpleDiskCache
        private lateinit var gson: Gson
        private var initialized = false
        private var passwordEncryption: String? = null
        private var keyEncrypt: ByteArray? = null
        private const val transformation = "AES/CBC/PKCS5Padding"

        @Synchronized
        fun initialize(builder: CacheLRUBuilder) {
            try {
                val dir = File(builder.cacheDir, "cacheLRU")
                if (!dir.exists() && !dir.mkdir()) {
                    throw IOException("Cache folder is not exist")
                }
                cache = SimpleDiskCache.open(dir, 1, builder.maxSize)
                builder.gson?.let { this.gson = it }
                builder.password?.let { this.passwordEncryption = it }
                keyEncrypt = generateKey()
                initialized = true
            } catch (e: Exception) {
                throw RuntimeException("Cache could not initialized!!")
            }
        }

        @Throws(IllegalStateException::class)
        fun failIfNotInitialized() {
            if (!initialized)
                throw IllegalArgumentException("Cache instance is not initialized! You must initialize() before calling any their methods.")
        }

        fun <T> internalGet(key: String, typeOfT: Type): T? {
            return try {
                val inputStream = cache.getInputStream(key)?.getInputStream()
                if (keyEncrypt == null) {
                    val inputStreamReader = InputStreamReader(inputStream!!)
                    gson.fromJson(inputStreamReader, typeOfT)
                } else {
                    decryptObject(inputStream) as T?
                }
            } catch (e: Exception) {
                null
            }
        }

        fun internalPut(key: String, `object`: Any): Boolean {
            return try {
                if (passwordEncryption == null) {
                    val outputStreamWriter = OutputStreamWriter(cache.openStream(key)!!)
                    gson.toJson(`object`, outputStreamWriter)
                    outputStreamWriter.close()
                } else {
                    encryptObject(`object` as Serializable, cache.openStream(key)!!)
                }
                true
            } catch (e: Exception) {
                Log.e("CacheLRU", e.message)
                false
            }
        }

        fun bytesUsed(): Long {
            failIfNotInitialized()
            return try {
                cache.bytesUsed()
            } catch (e: Exception) {
                -1
            }
        }

        fun clear(): Boolean {
            failIfNotInitialized()
            return try {
                cache.clear()
                true
            } catch (e: Exception) {
                false
            }
        }

        fun contains(key: String): Boolean {
            failIfNotInitialized()
            return try {
                cache.contains(key)
            } catch (e: Exception) {
                false
            }
        }

        fun count(): Int {
            failIfNotInitialized()
            return try {
                cache.getLruEntries().size
            } catch (e: Exception) {
                -1
            }
        }

        fun delete(key: String): Boolean {
            failIfNotInitialized()
            return try {
                cache.delete(key)
                true
            } catch (e: Exception) {
                false
            }
        }

        fun <T> get(key: String, classOfT: Class<T>): Get<T> {
            return Get(key, classOfT)
        }

        fun <T> get(key: String, typeOfT: Type): Get<T> {
            return Get(key, typeOfT)
        }

        fun getCache(): SimpleDiskCache = cache

        fun hasExpired(key: String): Expired {
            failIfNotInitialized()
            return Expired(key)
        }

        fun isInitialized() = initialized

        fun <T> put(key: String, `object`: T): Put<T> {
            failIfNotInitialized()
            return Put(key, `object`)
        }

        @Throws(Exception::class)
        private fun generateKey(): ByteArray? {
            if (passwordEncryption == null) return null
            val keyStart = passwordEncryption?.toByteArray(Util.UTF_8)
            val keyGenerator = KeyGenerator.getInstance("AES")
            val secureRandom = SecureRandom.getInstance("SHA1PRNG")
            secureRandom.setSeed(keyStart)
            keyGenerator.init(256, secureRandom)
            return keyGenerator.generateKey().encoded
        }

        @Throws(
            IOException::class,
            NoSuchAlgorithmException::class,
            NoSuchPaddingException::class,
            InvalidKeyException::class
        )
        private fun encryptObject(objectData: Serializable, outputStream: OutputStream) {
            try {
                if (keyEncrypt == null) keyEncrypt = generateKey()
                val secretKeySpec = SecretKeySpec(keyEncrypt, transformation)

                val cipher = Cipher.getInstance(transformation)
                val iv = ByteArray(cipher.blockSize)
                val ivParameterSpec = IvParameterSpec(iv)
                cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, ivParameterSpec)
                val sealedObject = SealedObject(objectData, cipher)

                val cipherOutputStream = CipherOutputStream(outputStream, cipher)
                val objectOutputStream = ObjectOutputStream(cipherOutputStream)
                objectOutputStream.writeObject(sealedObject)
                objectOutputStream.close()
            } catch (ex: IllegalBlockSizeException) {

            }
        }

        @Throws(
            IOException::class,
            NoSuchAlgorithmException::class,
            NoSuchPaddingException::class,
            InvalidKeyException::class
        )
        private fun decryptObject(inputStream: InputStream?): Any? {
            if (keyEncrypt == null) keyEncrypt = generateKey()

            val secretKeySpec = SecretKeySpec(keyEncrypt, transformation)
            val cipher = Cipher.getInstance(transformation)
            val iv = ByteArray(cipher.blockSize)
            val ivParameterSpec = IvParameterSpec(iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivParameterSpec)

            val cipherInputStream = CipherInputStream(inputStream, cipher)
            val objectInputStream = ObjectInputStream(cipherInputStream)
            return try {
                val sealedObject = objectInputStream.readObject() as SealedObject
                sealedObject.getObject(cipher)
            } catch (e: ClassNotFoundException) {
                null
            } catch (e: IllegalBlockSizeException) {
                null
            } catch (e: BadPaddingException) {
                null
            }
        }
    }
}