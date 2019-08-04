# CaheLRU

CacheLRU is an Android library for caching the serializable object, base on LRU cache.

## How to install

Step 1: Add the JitPack to the root Gradle file

```Gradle
allprojects {
	repositories {
		maven { url 'https://jitpack.io' }
	}
}
```
Step 2: Add the dependency
```gradle
dependencies {
	implementation 'com.github.kiji1340:CacheLRU:1.0.0'
}
```

## How to use
initialize the library
```kotlin
CacheLRUBuilder.configure(8192) //config maxium size
                .setDefaultCacheDirectory(context)
                .setPasswordEncryption("key") //turn on encrypt
                .initialize()
```
save data to disk
```kotlin
CacheLRU.put("key data", data).execute()
```
save data to disk with expire time
```java
CacheLRU.put("key data", data)
        .setExpiry(5, TimeUnit.SECONDS) //time and type time
        .execute()
```
get data
```kotlin
val dataCache = CacheLRU.get("key data",Data::class.java).execute()
```
get data with async
```kotlin
CacheLRU.put("data", data).async(object : Callback<Boolean> {
            override fun onResult(result: Boolean) {
                //handle data
            }
        })
```
get data by Coroutine
```kotlin
val result = runBlocking { CoroutineCache.with(CacheLRU.get("data", Data::class.java)) }
val result = CoroutineCache.getAsync("data", Data::class.java)
```
