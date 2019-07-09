package com.db1608.cache.disklrucache

import java.io.*
import java.lang.RuntimeException
import java.nio.charset.Charset

class Util {
    companion object {
        val US_ASCII = Charset.forName("US-ASCII")!!
        val UTF_8 = Charset.forName("UTF-8")!!

        @Throws(IOException::class)
        fun readFully(reader: Reader): String {
            reader.use {
                val writer = StringWriter()
                val buffer = CharArray(1024)
                var count = 0
                while ({ count = it.read(buffer); count }() != -1) {
                    writer.write(buffer, 0, count)
                }
                return writer.toString()
            }
        }

        @Throws(IOException::class)
        fun deleteContents(dir: File) {
            val files = dir.listFiles() ?: throw IOException("not a readable directory: $dir")

            for (file in files) {
                if (file.isDirectory) {
                    deleteContents(file)
                }
                if (!file.delete()) {
                    throw IOException("fail to delete file: $file")
                }
            }
        }

        fun closeQuietly(closeable: Closeable?) {
            try {
                closeable?.close()
            } catch (eRuntime: RuntimeException) {
                throw eRuntime
            } catch (ignored: Exception) {

            }
        }
    }


}