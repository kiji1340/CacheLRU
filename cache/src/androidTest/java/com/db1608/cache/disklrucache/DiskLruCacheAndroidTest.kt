package com.db1608.cache.disklrucache


import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.apache.commons.io.FileUtils
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.*

@RunWith(AndroidJUnit4::class)
class DiskLruCacheAndroidTest {

    private val appVersion = 100
    private lateinit var cacheDir: File
    private lateinit var cacheFile: File
    private lateinit var cacheBkpFile: File
    private lateinit var diskLruCache: DiskLruCache


    @get:Rule
    val tempDir = TemporaryFolder()

    @Before
    @Throws(Exception::class)
    fun setUp() {
        cacheDir = tempDir.newFolder("DiskLruCacheTest")
        cacheFile = File(cacheDir, DiskLruCache.CACHE_FILE)
        cacheBkpFile = File(cacheDir, DiskLruCache.CACHE_FILE_BACKUP)
        for (file in cacheDir.listFiles()) {
            file.delete()
        }
        diskLruCache = DiskLruCache.open(cacheDir, appVersion, 2, Integer.MAX_VALUE.toLong())
    }

    @After
    @Throws(Exception::class)
    fun tearDown() {
        diskLruCache.close()
    }

    @Test
    @Throws(Exception::class)
    fun emptyCache() {
        diskLruCache.close()
        assertCacheEqual()
    }

    @Test
    @Throws(Exception::class)
    fun validateKey() {
        var key: String = ""

        try {
            key = "has_space"
            diskLruCache.edit(key)
//            fail("Expecting an IllegalArgumentException as \"$key\" was invalid.")
        } catch (ex: IllegalArgumentException) {
            assertThat(ex.message).isEqualTo("keys must match regex [a-z0-9_-]{1,120}: \"$key\"")
        }

        try {
            key = "has_CR\r"
            diskLruCache.edit(key)
            fail("Expecting an IllegalArgumentException as \"$key\" was invalid.")
        } catch (ex: IllegalArgumentException) {
            assertThat(ex.message).isEqualTo(
                "keys must match regex [a-z0-9_-]{1,120}: \"$key\""
            )
        }

        try {
            key = "has_LF\n"
            diskLruCache.edit(key)
            fail("Expecting an IllegalArgumentException as \"$key\" was invalid.")
        } catch (ex: IllegalArgumentException) {
            assertThat(ex.message).isEqualTo(
                "keys must match regex [a-z0-9_-]{1,120}: \"$key\""
            )
        }

        try {
            key = "has_invalid/"
            diskLruCache.edit(key)
            fail("Exepcting an IllegalArgumentException as $key was invalid.")
        } catch (ex: IllegalArgumentException) {
            assertThat(ex.message).isEqualTo(
                "keys must match regex [a-z0-9_-]{1,120}: \"$key\""
            )
        }

        try {
            key = "has_invalid\u2603"
            diskLruCache.edit(key)
            fail("Expecting an IllegalArgumentException as $key was invalid.")
        } catch (iae: IllegalArgumentException) {
            assertThat(iae.message).isEqualTo(
                "keys must match regex [a-z0-9_-]{1,120}: \"$key\""
            )
        }

        try {
            key =
                "this_is_way_too_long_this_is_way_too_long_this_is_way_too_long_" +
                        "this_is_way_too_long_this_is_way_too_long_this_is_way_too_long"
            diskLruCache.edit(key)
            fail("Expecting an IllegalArgumentException as $key was too long.")
        } catch (iae: IllegalArgumentException) {
            assertThat(iae.message).isEqualTo(
                "keys must match regex [a-z0-9_-]{1,120}: \"$key\""
            )
        }

        // Exactly 120.
        key =
            "0123456789012345678901234567890123456789012345678901234567890123456789" +
                    "01234567890123456789012345678901234567890123456789"
        diskLruCache.edit(key)?.abort()
        // Contains all valid characters.
        key = "abcdefghijklmnopqrstuvwxyz_0123456789"
        diskLruCache.edit(key)?.abort()
        // Contains dash.
        key = "-20384573948576"
        diskLruCache.edit(key)?.abort()
    }

    @Test
    @Throws(Exception::class)
    fun writeAndReadEntry() {
        val creator = diskLruCache.edit("kl")
        creator?.set(0, "ABC")
        creator?.set(1, "DE")
        assertThat(creator?.getString(0)).isNull()
        assertThat(creator?.newInputStream(0)).isNull()
        assertThat(creator?.getString(1)).isNull()
        assertThat(creator?.newInputStream(1)).isNull()
        creator?.commit()

        val snapshot = diskLruCache.get("kl")
        assertThat(snapshot?.getString(0)).isEqualTo("ABC")
        assertThat(snapshot?.getLength(0)).isEqualTo(3)
        assertThat(snapshot?.getString(1)).isEqualTo("DE")
        assertThat(snapshot?.getLength(1)).isEqualTo(2)
    }

    @Test
    @Throws(Exception::class)
    fun readAndWriteEntryAcrossCacheOpenAndClose() {
        val creator = diskLruCache.edit("kl")
        creator?.set(0, "A")
        creator?.set(1, "B")
        assertThat(creator?.getString(0)).isNull()
        assertThat(creator?.newInputStream(0)).isNull()
        assertThat(creator?.getString(1)).isNull()
        assertThat(creator?.newInputStream(1)).isNull()
        creator?.commit()
        diskLruCache.close()

        val cache = DiskLruCache.open(cacheDir, appVersion, 2, Integer.MAX_VALUE.toLong())
        val snapshot = cache.get("kl")
        assertThat(snapshot?.getString(0)).isEqualTo("A")
        assertThat(snapshot?.getLength(0)).isEqualTo(1)
        assertThat(snapshot?.getString(1)).isEqualTo("B")
        assertThat(snapshot?.getLength(1)).isEqualTo(1)
        snapshot?.close()
    }

    @Test
    @Throws(Exception::class)
    fun readAndWriteEntryWithoutProperClose() {
        val creator = diskLruCache.edit("k1")
        creator?.set(0, "A")
        creator?.set(1, "B")
        creator?.commit()

        // Simulate a dirty close of 'cache' by opening the cache directory again.
        val cache2 = DiskLruCache.open(cacheDir, appVersion, 2, Integer.MAX_VALUE.toLong())
        val snapshot = cache2.get("k1")
        assertThat(snapshot?.getString(0)).isEqualTo("A")
        assertThat(snapshot?.getLength(0)).isEqualTo(1)
        assertThat(snapshot?.getString(1)).isEqualTo("B")
        assertThat(snapshot?.getLength(1)).isEqualTo(1)
        snapshot?.close()
        cache2.close()
    }

    @Test
    @Throws(Exception::class)
    fun cacheWithEditAndPublish() {
        val creator = diskLruCache.edit("kl")
        assertCacheEqual("DIRTY kl")
        creator?.set(0, "AB")
        creator?.set(1, "C")
        creator?.commit()
        diskLruCache.close()
        assertCacheEqual("DIRTY kl", "CLEAN kl 2 1")
    }

    @Test
    @Throws(Exception::class)
    fun revertedNewFileIsRemoveInCache() {
        val creator = diskLruCache.edit("kl")
        assertCacheEqual("DIRTY kl")
        creator?.set(0, "AB")
        creator?.set(1, "C")
        creator?.abort()
        diskLruCache.close()
        assertCacheEqual("DIRTY kl", "REMOVE kl")
    }

    @Test
    @Throws(Exception::class)
    fun unterminatedEditIsRevertedOnClose() {
        diskLruCache.edit("kl")
        diskLruCache.close()
        assertCacheEqual("DIRTY kl", "REMOVE kl")
    }

    @Test
    @Throws(Exception::class)
    fun cacheDoesNotIncludeReadOfYetUnpublishedValue() {
        val creator = diskLruCache.edit("kl")
        assertThat(diskLruCache.get("kl")).isNull()
        creator?.set(0, "A")
        creator?.set(1, "BC")
        creator?.commit()
        diskLruCache.close()
        assertCacheEqual("DIRTY kl", "CLEAN kl 1 2")
    }

    @Test
    @Throws(Exception::class)
    fun cacheWithEditAndPublishAndRead() {
        val k1Creator = diskLruCache.edit("k1")
        k1Creator?.set(0, "AB")
        k1Creator?.set(1, "C")
        k1Creator?.commit()
        val k2Creator = diskLruCache.edit("k2")
        k2Creator?.set(0, "DEF")
        k2Creator?.set(1, "G")
        k2Creator?.commit()
        val k1Snapshot = diskLruCache.get("k1")
        k1Snapshot?.close()
        diskLruCache.close()
        assertCacheEqual("DIRTY k1", "CLEAN k1 2 1", "DIRTY k2", "CLEAN k2 3 1", "READ k1")
    }

    @Test
    @Throws(Exception::class)
    fun cannotOperateOnEditAfterPublish() {
        val editor = diskLruCache.edit("k1")
        editor?.set(0, "A")
        editor?.set(1, "B")
        editor?.commit()
        assertInoperable(editor!!)
    }

    @Test
    @Throws(Exception::class)
    fun cannotOperateOnEditAfterRevert() {
        val editor = diskLruCache.edit("k1")
        editor?.set(0, "A")
        editor?.set(1, "B")
        editor?.abort()
        assertInoperable(editor!!)
    }

    @Test
    @Throws(Exception::class)
    fun explicitRemoveAppliedToDiskImmediately() {
        val editor = diskLruCache.edit("k1")
        editor?.set(0, "ABC")
        editor?.set(1, "B")
        editor?.commit()
        val k1 = getCleanFile(key = "k1", index = 0)
        assertThat(readFile(k1)).isEqualTo("ABC")
        diskLruCache.remove("k1")
        assertThat(k1.exists()).isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun readAndWriteOverlapsMaintainConsistency() {
        val v1Creator = diskLruCache.edit("k1")
        v1Creator?.set(0, "AAaa")
        v1Creator?.set(1, "BBbb")
        v1Creator?.commit()

        val snapshot1 = diskLruCache.get("k1")
        val inV1 = snapshot1?.getInputStream(0)
        assertThat(inV1?.read()).isEqualTo('A')
        assertThat(inV1?.read()).isEqualTo('A')

        val v1Updater = diskLruCache.edit("k1")
        v1Updater?.set(0, "CCcc")
        v1Updater?.set(1, "DDdd")
        v1Updater?.commit()

        val snapshot2 = diskLruCache.get("k1")
        assertThat(snapshot2?.getString(0)).isEqualTo("CCcc")
        assertThat(snapshot2?.getLength(0)).isEqualTo(4)
        assertThat(snapshot2?.getString(1)).isEqualTo("DDdd")
        assertThat(snapshot2?.getLength(1)).isEqualTo(4)
        snapshot2?.close()

        assertThat(inV1?.read()).isEqualTo('a')
        assertThat(inV1?.read()).isEqualTo('a')
        assertThat(snapshot1?.getString(1)).isEqualTo("BBbb")
        assertThat(snapshot1?.getLength(1)).isEqualTo(4)
        snapshot1?.close()
    }

    @Test
    @Throws(Exception::class)
    fun openWithDirtyKeyDeletesAllFilesForThatKey() {
        diskLruCache.close()
        val cleanFile0 = getCleanFile("k1", 0)
        val cleanFile1 = getCleanFile("k1", 1)
        val dirtyFile0 = getDirtyFile("k1", 0)
        val dirtyFile1 = getDirtyFile("kl", 1)
        writeFile(cleanFile0, "A")
        writeFile(cleanFile1, "B")
        writeFile(dirtyFile0, "C")
        writeFile(dirtyFile1, "D")
        createCache("CLEAN k1 1 1", "DIRTY   k1")
        diskLruCache = DiskLruCache.open(cacheDir, appVersion, 2, Integer.MAX_VALUE.toLong())
        assertThat(cleanFile0.exists()).isFalse()
        assertThat(cleanFile1.exists()).isFalse()
        assertThat(cleanFile0.exists()).isFalse()
        assertThat(cleanFile1.exists()).isFalse()
        assertThat(diskLruCache.get("k1")).isNull()
    }

    @Test
    @Throws(Exception::class)
    fun openWithInvalidVersionClearsDirectory() {
        diskLruCache.close()
        generateSomeGarbageFiles()
        createCacheWithHeader(DiskLruCache.MAGIC, "0", "100", "2", "")
        diskLruCache = DiskLruCache.open(cacheDir, appVersion, 2, Integer.MAX_VALUE.toLong())
        assertGarbageFilesAllDeleted()
    }

    @Test
    @Throws(Exception::class)
    fun openWithInvalidAppVersionClearsDirectory() {
        diskLruCache.close()
        generateSomeGarbageFiles()
        createCacheWithHeader(DiskLruCache.MAGIC, "1", "101", "2", "")
        diskLruCache = DiskLruCache.open(cacheDir, appVersion, 2, Integer.MAX_VALUE.toLong())
        assertGarbageFilesAllDeleted()
    }

    @Test
    @Throws(Exception::class)
    fun openWithInvalidValueCountClearsDirectory() {
        diskLruCache.close()
        generateSomeGarbageFiles()
        createCacheWithHeader(DiskLruCache.MAGIC, "1", "100", "1", "")
        diskLruCache = DiskLruCache.open(cacheDir, appVersion, 2, Integer.MAX_VALUE.toLong())
        assertGarbageFilesAllDeleted()
    }

    @Test
    @Throws(Exception::class)
    fun openWithInvalidBlankLineClearsDirectory() {
        diskLruCache.close()
        generateSomeGarbageFiles()
        createCacheWithHeader(DiskLruCache.MAGIC, "1", "100", "2", "x")
        diskLruCache = DiskLruCache.open(cacheDir, appVersion, 2, Integer.MAX_VALUE.toLong())
        assertGarbageFilesAllDeleted()
    }

    @Test
    @Throws(Exception::class)
    fun openWithInvalidJournalLineClearsDirectory() {
        diskLruCache.close()
        generateSomeGarbageFiles()
        createCache("CLEAN k1 1 1", "BOGUS")
        diskLruCache = DiskLruCache.open(cacheDir, appVersion, 2, Integer.MAX_VALUE.toLong())
        assertGarbageFilesAllDeleted()
        assertThat(diskLruCache.get("k1")).isNull()
    }

    @Test
    @Throws(Exception::class)
    fun openWithInvalidFileSizeClearsDirectory() {
        diskLruCache.close()
        generateSomeGarbageFiles()
        createCache("CLEAN k1 0000x001 1")
        diskLruCache = DiskLruCache.open(cacheDir, appVersion, 2, Integer.MAX_VALUE.toLong())
        assertGarbageFilesAllDeleted()
        assertThat(diskLruCache.get("k1")).isNull()
    }

    @Test
    @Throws(Exception::class)
    fun openWithTruncatedLineDiscardsThatLine() {
        diskLruCache.close()
        writeFile(getCleanFile("k1", 0), "A")
        writeFile(getCleanFile("k1", 1), "B")
        val writer = FileWriter(cacheFile)
        writer.write(DiskLruCache.MAGIC + "\n" + DiskLruCache.VERSION_1 + "\n100\n2\n\nCLEAN k1 1 1") // no trailing newline
        writer.close()
        diskLruCache = DiskLruCache.open(cacheDir, appVersion, 2, Integer.MAX_VALUE.toLong())
        assertThat(diskLruCache.get("k1")).isNull()

        // The journal is not corrupt when editing after a truncated line.
        set("k1", "C", "D")
        diskLruCache.close()
        diskLruCache = DiskLruCache.open(cacheDir, appVersion, 2, Integer.MAX_VALUE.toLong())
        assertValue("k1", "C", "D")
    }

    @Test
    @Throws(Exception::class)
    fun openWithTooManyFileSizesClearsDirectory() {
        diskLruCache.close()
        generateSomeGarbageFiles()
        createCache("CLEAN k1 1 1 1")
        diskLruCache = DiskLruCache.open(cacheDir, appVersion, 2, Integer.MAX_VALUE.toLong())
        assertGarbageFilesAllDeleted()
        assertThat(diskLruCache.get("k1")).isNull()
    }

    @Test
    @Throws(Exception::class)
    fun keyWithSpaceNotPermitted() {
        try {
            diskLruCache.edit("my key")
            fail()
        } catch (expected: IllegalArgumentException) {
        }

    }

    @Test
    @Throws(Exception::class)
    fun keyWithNewlineNotPermitted() {
        try {
            diskLruCache.edit("my\nkey")
            fail()
        } catch (expected: IllegalArgumentException) {
        }

    }

    @Test
    @Throws(Exception::class)
    fun keyWithCarriageReturnNotPermitted() {
        try {
            diskLruCache.edit("my\rkey")
            fail()
        } catch (expected: IllegalArgumentException) {
        }

    }


    @Test
    @Throws(Exception::class)
    fun createNewEntryWithTooFewValuesFails() {
        val creator = diskLruCache.edit("k1")
        creator?.set(1, "A")
        try {
            creator?.commit()
            fail()
        } catch (expected: IllegalStateException) {
        }

        assertThat(getCleanFile("k1", 0).exists()).isFalse()
        assertThat(getCleanFile("k1", 1).exists()).isFalse()
        assertThat(getDirtyFile("k1", 0).exists()).isFalse()
        assertThat(getDirtyFile("k1", 1).exists()).isFalse()
        assertThat(diskLruCache.get("k1")).isNull()

        val creator2 = diskLruCache.edit("k1")
        creator2?.set(0, "B")
        creator2?.set(1, "C")
        creator2?.commit()
    }

    @Test
    @Throws(Exception::class)
    fun revertWithTooFewValues() {
        val creator = diskLruCache.edit("k1")
        creator?.set(1, "A")
        creator?.abort()
        assertThat(getCleanFile("k1", 0).exists()).isFalse()
        assertThat(getCleanFile("k1", 1).exists()).isFalse()
        assertThat(getDirtyFile("k1", 0).exists()).isFalse()
        assertThat(getDirtyFile("k1", 1).exists()).isFalse()
        assertThat(diskLruCache.get("k1")).isNull()
    }

    @Test
    @Throws(Exception::class)
    fun updateExistingEntryWithTooFewValuesReusesPreviousValues() {
        val creator = diskLruCache.edit("k1")
        creator?.set(0, "A")
        creator?.set(1, "B")
        creator?.commit()

        val updater = diskLruCache.edit("k1")
        updater?.set(0, "C")
        updater?.commit()

        val snapshot = diskLruCache.get("k1")
        assertThat(snapshot?.getString(0)).isEqualTo("C")
        assertThat(snapshot?.getLength(0)).isEqualTo(1)
        assertThat(snapshot?.getString(1)).isEqualTo("B")
        assertThat(snapshot?.getLength(1)).isEqualTo(1)
        snapshot?.close()
    }

    @Test
    @Throws(Exception::class)
    fun growMaxSize() {
        diskLruCache.close()
        diskLruCache = DiskLruCache.open(cacheDir, appVersion, 2, 10)
        set("a", "a", "aaa") // size 4
        set("b", "bb", "bbbb") // size 6
        diskLruCache.setMaxSize(20)
        set("c", "c", "c") // size 12
        assertThat(diskLruCache.size()).isEqualTo(12)
    }

    @Test
    @Throws(Exception::class)
    fun shrinkMaxSizeEvicts() {
        diskLruCache.close()
        diskLruCache = DiskLruCache.open(cacheDir, appVersion, 2, 20)
        set("a", "a", "aaa") // size 4
        set("b", "bb", "bbbb") // size 6
        set("c", "c", "c") // size 12
        diskLruCache.setMaxSize(10)
        assertThat(diskLruCache.executorService.queue.size).isEqualTo(1)
        diskLruCache.executorService.purge()
    }

    @Test
    @Throws(Exception::class)
    fun evictOnInsert() {
        diskLruCache.close()
        diskLruCache = DiskLruCache.open(cacheDir, appVersion, 2, 10)

        set("a", "a", "aaa") // size 4
        set("b", "bb", "bbbb") // size 6
        assertThat(diskLruCache.size()).isEqualTo(10)

        // Cause the size to grow to 12 should evict 'A'.
        set("c", "c", "c")
        diskLruCache.flush()
        assertThat(diskLruCache.size()).isEqualTo(8)
        assertAbsent("a")
        assertValue("b", "bb", "bbbb")
        assertValue("c", "c", "c")

        // Causing the size to grow to 10 should evict nothing.
        set("d", "d", "d")
        diskLruCache.flush()
        assertThat(diskLruCache.size()).isEqualTo(10)
        assertAbsent("a")
        assertValue("b", "bb", "bbbb")
        assertValue("c", "c", "c")
        assertValue("d", "d", "d")

        // Causing the size to grow to 18 should evict 'B' and 'C'.
        set("e", "eeee", "eeee")
        diskLruCache.flush()
        assertThat(diskLruCache.size()).isEqualTo(10)
        assertAbsent("a")
        assertAbsent("b")
        assertAbsent("c")
        assertValue("d", "d", "d")
        assertValue("e", "eeee", "eeee")
    }

    @Test
    @Throws(Exception::class)
    fun evictOnUpdate() {
        diskLruCache.close()
        diskLruCache = DiskLruCache.open(cacheDir, appVersion, 2, 10)

        set("a", "a", "aa") // size 3
        set("b", "b", "bb") // size 3
        set("c", "c", "cc") // size 3
        assertThat(diskLruCache.size()).isEqualTo(9)

        // Causing the size to grow to 11 should evict 'A'.
        set("b", "b", "bbbb")
        diskLruCache.flush()
        assertThat(diskLruCache.size()).isEqualTo(8)
        assertAbsent("a")
        assertValue("b", "b", "bbbb")
        assertValue("c", "c", "cc")
    }

    @Test
    @Throws(Exception::class)
    fun evictionHonorsLruFromCurrentSession() {
        diskLruCache.close()
        diskLruCache = DiskLruCache.open(cacheDir, appVersion, 2, 10)
        set("a", "a", "a")
        set("b", "b", "b")
        set("c", "c", "c")
        set("d", "d", "d")
        set("e", "e", "e")
        diskLruCache.get("b")?.close() // 'B' is now least recently used.

        // Causing the size to grow to 12 should evict 'A'.
        set("f", "f", "f")
        // Causing the size to grow to 12 should evict 'C'.
        set("g", "g", "g")
        diskLruCache.flush()
        assertThat(diskLruCache.size()).isEqualTo(10)
        assertAbsent("a")
        assertValue("b", "b", "b")
        assertAbsent("c")
        assertValue("d", "d", "d")
        assertValue("e", "e", "e")
        assertValue("f", "f", "f")
    }

    @Test
    @Throws(Exception::class)
    fun evictionHonorsLruFromPreviousSession() {
        set("a", "a", "a")
        set("b", "b", "b")
        set("c", "c", "c")
        set("d", "d", "d")
        set("e", "e", "e")
        set("f", "f", "f")
        diskLruCache.get("b")?.close() // 'B' is now least recently used.
        assertThat(diskLruCache.size()).isEqualTo(12)
        diskLruCache.close()
        diskLruCache = DiskLruCache.open(cacheDir, appVersion, 2, 10)

        set("g", "g", "g")
        diskLruCache.flush()
        assertThat(diskLruCache.size()).isEqualTo(10)
        assertAbsent("a")
        assertValue("b", "b", "b")
        assertAbsent("c")
        assertValue("d", "d", "d")
        assertValue("e", "e", "e")
        assertValue("f", "f", "f")
        assertValue("g", "g", "g")
    }

    @Test
    @Throws(Exception::class)
    fun cacheSingleEntryOfSizeGreaterThanMaxSize() {
        diskLruCache.close()
        diskLruCache = DiskLruCache.open(cacheDir, appVersion, 2, 10)
        set("a", "aaaaa", "aaaaaa") // size=11
        diskLruCache.flush()
        assertAbsent("a")
    }

    @Test
    @Throws(Exception::class)
    fun cacheSingleValueOfSizeGreaterThanMaxSize() {
        diskLruCache.close()
        diskLruCache = DiskLruCache.open(cacheDir, appVersion, 2, 10)
        set("a", "aaaaaaaaaaa", "a") // size=12
        diskLruCache.flush()
        assertAbsent("a")
    }

    @Test
    @Throws(Exception::class)
    fun constructorDoesNotAllowZeroCacheSize() {
        try {
            DiskLruCache.open(cacheDir, appVersion, 2, 0)
            fail()
        } catch (expected: IllegalArgumentException) {
        }

    }

    @Test
    @Throws(Exception::class)
    fun constructorDoesNotAllowZeroValuesPerEntry() {
        try {
            DiskLruCache.open(cacheDir, appVersion, 0, 10)
            fail()
        } catch (expected: IllegalArgumentException) {
        }

    }

    @Test
    @Throws(Exception::class)
    fun removeAbsentElement() {
        diskLruCache.remove("a")
    }

    @Test
    @Throws(Exception::class)
    fun readingTheSameStreamMultipleTimes() {
        set("a", "a", "b")
        val snapshot = diskLruCache.get("a")
        assertThat(snapshot?.getInputStream(0)).isSameAs(snapshot?.getInputStream(0))
        snapshot?.close()
    }

    @Test
    @Throws(Exception::class)
    fun rebuildJournalOnRepeatedReads() {
        set("a", "a", "a")
        set("b", "b", "b")
        var lastJournalLength: Long = 0
        while (true) {
            val journalLength = cacheFile.length()
            assertValue("a", "a", "a")
            assertValue("b", "b", "b")
            if (journalLength < lastJournalLength) {
                System.out
                    .printf(
                        "Journal compacted from %s bytes to %s bytes\n", lastJournalLength,
                        journalLength
                    )
                break // Test passed!
            }
            lastJournalLength = journalLength
        }
    }

    @Test
    @Throws(Exception::class)
    fun rebuildJournalOnRepeatedEdits() {
        var lastJournalLength: Long = 0
        while (true) {
            val journalLength = cacheFile.length()
            set("a", "a", "a")
            set("b", "b", "b")
            if (journalLength < lastJournalLength) {
                System.out
                    .printf(
                        "Journal compacted from %s bytes to %s bytes\n", lastJournalLength,
                        journalLength
                    )
                break
            }
            lastJournalLength = journalLength
        }

        // Sanity check that a rebuilt journal behaves normally.
        assertValue("a", "a", "a")
        assertValue("b", "b", "b")
    }

    /** @see [Issue .28](https://github.com/JakeWharton/DiskLruCache/issues/28)
     */
    @Test
    @Throws(Exception::class)
    fun rebuildJournalOnRepeatedReadsWithOpenAndClose() {
        set("a", "a", "a")
        set("b", "b", "b")
        var lastJournalLength: Long = 0
        while (true) {
            val journalLength = cacheFile.length()
            assertValue("a", "a", "a")
            assertValue("b", "b", "b")
            diskLruCache.close()
            diskLruCache = DiskLruCache.open(cacheDir, appVersion, 2, Integer.MAX_VALUE.toLong())
            if (journalLength < lastJournalLength) {
                System.out
                    .printf(
                        journalLength.toString()
                    )
                break // Test passed!
            }

            lastJournalLength = journalLength
        }
    }

    /** @see [Issue .28](https://github.com/JakeWharton/DiskLruCache/issues/28)
     */
    @Test
    @Throws(Exception::class)
    fun rebuildJournalOnRepeatedEditsWithOpenAndClose() {
        var lastJournalLength: Long = 0
        while (true) {
            val journalLength = cacheFile.length()
            set("a", "a", "a")
            set("b", "b", "b")
            diskLruCache.close()
            diskLruCache = DiskLruCache.open(cacheDir, appVersion, 2, Integer.MAX_VALUE.toLong())
            if (journalLength < lastJournalLength) {
                System.out
                    .printf(
                        "Journal compacted from %s bytes to %s bytes\n", lastJournalLength,
                        journalLength
                    )
                break
            }
            lastJournalLength = journalLength
        }
    }

    @Test
    @Throws(Exception::class)
    fun restoreBackupFile() {
        val creator = diskLruCache.edit("k1")
        creator?.set(0, "ABC")
        creator?.set(1, "DE")
        creator?.commit()
        diskLruCache.close()

        assertThat(cacheFile.renameTo(cacheBkpFile)).isTrue()
        assertThat(cacheFile.exists()).isFalse()

        diskLruCache = DiskLruCache.open(cacheDir, appVersion, 2, Integer.MAX_VALUE.toLong())

        val snapshot = diskLruCache.get("k1")
        assertThat(snapshot?.getString(0)).isEqualTo("ABC")
        assertThat(snapshot?.getLength(0)).isEqualTo(3)
        assertThat(snapshot?.getString(1)).isEqualTo("DE")
        assertThat(snapshot?.getLength(1)).isEqualTo(2)

        assertThat(cacheBkpFile.exists()).isFalse()
        assertThat(cacheFile.exists()).isTrue()
    }

    @Test
    @Throws(Exception::class)
    fun journalFileIsPreferredOverBackupFile() {
        var creator = diskLruCache.edit("k1")
        creator?.set(0, "ABC")
        creator?.set(1, "DE")
        creator?.commit()
        diskLruCache.flush()

        FileUtils.copyFile(cacheFile, cacheBkpFile)

        creator = diskLruCache.edit("k2")
        creator?.set(0, "F")
        creator?.set(1, "GH")
        creator?.commit()
        diskLruCache.close()

        assertThat(cacheFile.exists()).isTrue()
        assertThat(cacheBkpFile.exists()).isTrue()

        diskLruCache = DiskLruCache.open(cacheDir, appVersion, 2, Integer.MAX_VALUE.toLong())

        val snapshotA = diskLruCache.get("k1")
        assertThat(snapshotA?.getString(0)).isEqualTo("ABC")
        assertThat(snapshotA?.getLength(0)).isEqualTo(3)
        assertThat(snapshotA?.getString(1)).isEqualTo("DE")
        assertThat(snapshotA?.getLength(1)).isEqualTo(2)

        val snapshotB = diskLruCache.get("k2")
        assertThat(snapshotB?.getString(0)).isEqualTo("F")
        assertThat(snapshotB?.getLength(0)).isEqualTo(1)
        assertThat(snapshotB?.getString(1)).isEqualTo("GH")
        assertThat(snapshotB?.getLength(1)).isEqualTo(2)

        assertThat(cacheBkpFile.exists()).isFalse()
        assertThat(cacheFile.exists()).isTrue()
    }

    @Test
    @Throws(Exception::class)
    fun openCreatesDirectoryIfNecessary() {
        diskLruCache.close()
        val dir = tempDir.newFolder("testOpenCreatesDirectoryIfNecessary")
        diskLruCache = DiskLruCache.open(dir, appVersion, 2, Integer.MAX_VALUE.toLong())
        set("a", "a", "a")
        assertThat(File(dir, "a.0").exists()).isTrue()
        assertThat(File(dir, "a.1").exists()).isTrue()
        assertThat(File(dir, "file").exists()).isTrue()
    }

    @Test
    @Throws(Exception::class)
    fun fileDeletedExternally() {
        set("a", "a", "a")
        getCleanFile("a", 1).delete()
        assertThat(diskLruCache.get("a")).isNull()
    }

    @Test
    @Throws(Exception::class)
    fun editSameVersion() {
        set("a", "a", "a")
        val snapshot = diskLruCache.get("a")
        val editor = snapshot?.edit()
        editor?.set(1, "a2")
        editor?.commit()
        assertValue("a", "a", "a2")
    }

    @Test
    @Throws(Exception::class)
    fun editSnapshotAfterChangeAborted() {
        set("a", "a", "a")
        val snapshot = diskLruCache.get("a")
        val toAbort = snapshot?.edit()
        toAbort?.set(0, "b")
        toAbort?.abort()
        val editor = snapshot?.edit()
        editor?.set(1, "a2")
        editor?.commit()
        assertValue("a", "a", "a2")
    }

    @Test
    @Throws(Exception::class)
    fun editSnapshotAfterChangeCommitted() {
        set("a", "a", "a")
        val snapshot = diskLruCache.get("a")
        val toAbort = snapshot?.edit()
        toAbort?.set(0, "b")
        toAbort?.commit()
        assertThat(snapshot?.edit()).isNull()
    }

    @Test
    @Throws(Exception::class)
    fun editSinceEvicted() {
        diskLruCache.close()
        diskLruCache = DiskLruCache.open(cacheDir, appVersion, 2, 10)
        set("a", "aa", "aaa") // size 5
        val snapshot = diskLruCache.get("a")
        set("b", "bb", "bbb") // size 5
        set("c", "cc", "ccc") // size 5; will evict 'A'
        diskLruCache.flush()
        assertThat(snapshot?.edit()).isNull()
    }

    @Test
    @Throws(Exception::class)
    fun editSinceEvictedAndRecreated() {
        diskLruCache.close()
        diskLruCache = DiskLruCache.open(cacheDir, appVersion, 2, 10)
        set("a", "aa", "aaa") // size 5
        val snapshot = diskLruCache.get("a")
        set("b", "bb", "bbb") // size 5
        set("c", "cc", "ccc") // size 5; will evict 'A'
        set("a", "a", "aaaa") // size 5; will evict 'B'
        diskLruCache.flush()
        assertThat(snapshot?.edit()).isNull()
    }

    /** @see [Issue .2](https://github.com/JakeWharton/DiskLruCache/issues/2)
     */
    @Test
    @Throws(Exception::class)
    fun aggressiveClearingHandlesWrite() {
        FileUtils.deleteDirectory(cacheDir)
        set("a", "a", "a")
        assertValue("a", "a", "a")
    }

    /** @see [Issue .2](https://github.com/JakeWharton/DiskLruCache/issues/2)
     */
    @Test
    @Throws(Exception::class)
    fun aggressiveClearingHandlesEdit() {
        set("a", "a", "a")
        val a = diskLruCache.get("a")?.edit()
        FileUtils.deleteDirectory(cacheDir)
        a?.set(1, "a2")
        a?.commit()
    }

    @Test
    @Throws(Exception::class)
    fun removeHandlesMissingFile() {
        set("a", "a", "a")
        getCleanFile("a", 0).delete()
        diskLruCache.remove("a")
    }

    /** @see [Issue .2](https://github.com/JakeWharton/DiskLruCache/issues/2)
     */
    @Test
    @Throws(Exception::class)
    fun aggressiveClearingHandlesPartialEdit() {
        set("a", "a", "a")
        set("b", "b", "b")
        val a = diskLruCache.get("a")?.edit()
        a?.set(0, "a1")
        FileUtils.deleteDirectory(cacheDir)
        a?.set(1, "a2")
        a?.commit()
        assertThat(diskLruCache.get("a")).isNull()
    }

    /** @see [Issue .2](https://github.com/JakeWharton/DiskLruCache/issues/2)
     */
    @Test
    @Throws(Exception::class)
    fun aggressiveClearingHandlesRead() {
        FileUtils.deleteDirectory(cacheDir)
        assertThat(diskLruCache.get("a")).isNull()
    }

    private fun getCleanFile(key: String, index: Int): File {
        return File(cacheDir, "$key.$index")
    }

    private fun getDirtyFile(key: String, index: Int): File {
        return File(cacheDir, "$key.$index.tmp")
    }

    @Throws(Exception::class)
    private fun readFile(file: File): String {
        val reader = FileReader(file)
        val writer = StringWriter()
        val buffer = CharArray(1024)
        var count: Int = 0
        while ({ count = reader.read(buffer); count }() != -1) {
            writer.write(buffer, 0, count)
        }
        reader.close()
        return writer.toString()
    }

    @Throws(Exception::class)
    private fun writeFile(file: File, content: String) {
        val writer = FileWriter(file)
        writer.write(content)
        writer.close()
    }

    @Throws(Exception::class)
    private fun assertInoperable(editor: DiskLruCache.Editor) {
        try {
            editor.getString(0)
            fail()
        } catch (expected: IllegalStateException) {

        }

        try {
            editor.set(0, "A")
            fail()
        } catch (ex: IllegalStateException) {

        }

        try {
            editor.newInputStream(0)
            fail()
        } catch (ex: IllegalStateException) {

        }

        try {
            editor.newOutputStream(0)
            fail()
        } catch (ex: IllegalStateException) {

        }

        try {
            editor.commit()
            fail()
        } catch (ex: IllegalStateException) {

        }

        try {
            editor.abort()
            fail()
        } catch (ex: IllegalStateException) {

        }
    }

    @Throws(Exception::class)
    private fun generateSomeGarbageFiles() {
        val dir1 = File(cacheDir, "dir1")
        val dir2 = File(dir1, "dir2")
        writeFile(getCleanFile("g1", 0), "A")
        writeFile(getCleanFile("g1", 1), "B")
        writeFile(getCleanFile("g2", 0), "C")
        writeFile(getCleanFile("g2", 1), "D")
        writeFile(getCleanFile("g2", 1), "D")
        writeFile(File(cacheDir, "otherFile0"), "E")
        dir1.mkdir()
        dir2.mkdir()
        writeFile(File(dir2, "otherFile1"), "F")
    }

    @Throws(Exception::class)
    private fun assertGarbageFilesAllDeleted() {
        assertThat(getCleanFile("g1", 0).exists()).isFalse()
        assertThat(getCleanFile("g1", 1).exists()).isFalse()
        assertThat(getCleanFile("g2", 0).exists()).isFalse()
        assertThat(getCleanFile("g2", 1).exists()).isFalse()
        assertThat(File(cacheDir, "otherFile0").exists()).isFalse()
        assertThat(File(cacheDir, "dir1").exists()).isFalse()
    }

    @Throws(Exception::class)
    private operator fun set(key: String, value0: String, value1: String) {
        val editor = diskLruCache.edit(key)
        editor?.set(0, value0)
        editor?.set(1, value1)
        editor?.commit()
    }

    @Throws(Exception::class)
    private fun assertAbsent(key: String) {
        val snapshot = diskLruCache.get(key)
        if (snapshot != null) {
            snapshot.close()
            fail()
        }
        assertThat(getCleanFile(key, 0).exists()).isFalse()
        assertThat(getCleanFile(key, 1).exists()).isFalse()
        assertThat(getDirtyFile(key, 0).exists()).isFalse()
        assertThat(getDirtyFile(key, 1).exists()).isFalse()
    }

    @Throws(Exception::class)
    private fun assertValue(key: String, value0: String, value1: String) {
        val snapshot = diskLruCache.get(key)
        assertThat(snapshot?.getString(0)).isEqualTo(value0)
        assertThat(snapshot?.getLength(0)).isEqualTo(value0.length)
        assertThat(snapshot?.getString(1)).isEqualTo(value1)
        assertThat(snapshot?.getLength(1)).isEqualTo(value1.length)
        assertThat(getCleanFile(key, 0).exists()).isTrue()
        assertThat(getCleanFile(key, 1).exists()).isTrue()

        snapshot?.close()
    }

    @Throws(Exception::class)
    private fun assertCacheEqual(vararg expectedBodyLines: String) {
        val expectedLines = ArrayList<String>()
        expectedLines.add(DiskLruCache.MAGIC)
        expectedLines.add(DiskLruCache.VERSION_1)
        expectedLines.add("100")
        expectedLines.add("2")
        expectedLines.add("")
        expectedLines.addAll(listOf(*expectedBodyLines))
        assertThat(readCacheLines()).isEqualTo(expectedLines)
    }

    @Throws(Exception::class)
    private fun createCache(vararg bodyLines: String) {
        createCacheWithHeader(
            DiskLruCache.MAGIC
            , DiskLruCache.VERSION_1
            , "100"
            , "2"
            , ""
            , *bodyLines
        )
    }

    @Throws(Exception::class)
    private fun createCacheWithHeader(
        magic: String
        , version: String
        , appVersion: String
        , valueCount: String
        , blank: String
        , vararg bodyLines: String
    ) {
        val writer = FileWriter(cacheFile)
        writer.write(magic + "\n")
        writer.write(version + "\n")
        writer.write(appVersion + "\n")
        writer.write(valueCount + "\n")
        writer.write(blank + "\n")
        for (line in bodyLines) {
            writer.write(line)
            writer.write("\n")
        }
        writer.close()
    }


    @Throws(Exception::class)
    private fun readCacheLines(): List<String> {
        val result = ArrayList<String>()
        val reader = BufferedReader(FileReader(cacheFile))
        var line: String? = null
        while ({ line = reader.readLine(); line }() != null) {
            line?.let { result.add(it) }
        }
        reader.close()
        return result
    }


}