package com.db1608.cache.disklrucache

import java.io.*
import java.lang.AssertionError
import java.nio.charset.Charset

class StrictLineReader : Closeable {
    companion object {
        private val CR = '\r'.toByte()
        private val LF = '\n'.toByte()
    }

    private var buf: ByteArray? = null
    private var pos: Int = 0
    private var end: Int = 0

    private var inputStream: InputStream
    private var charset: Charset

    /**
     * Constructs a new {@code LineReader} with the specified charset and the default capacity.
     *
     * @param inputStream the {@code InputStream} to read data from.
     * @param charset the charset used to decode data. Only US-ASCII, UTF-8 and ISO-8859-1 are
     * supported.
     * @throws NullPointerException if {@code in} or {@code charset} is null.
     * @throws IllegalArgumentException if the specified charset is not supported.
     */
    constructor(inputStream: InputStream, charset: Charset) : this(inputStream, 8192, charset)

    /**
     * Constructs a new {@code LineReader} with the specified capacity and charset.
     *
     * @param inputStream the {@code InputStream} to read data from.
     * @param capacity the capacity of the buffer.
     * @param charset the charset used to decode data. Only US-ASCII, UTF-8 and ISO-8859-1 are
     * supported.
     * @throws NullPointerException if {@code in} or {@code charset} is null.
     * @throws IllegalArgumentException if {@code capacity} is negative or zero
     * or the specified charset is not supported.
     */
    constructor(inputStream: InputStream?, capacity: Int, charset: Charset?) {
        if (inputStream == null || charset == null) {
            throw NullPointerException()
        }
        if (capacity < 0) {
            throw IllegalArgumentException("capacity <= 0")
        }
        if (charset != Util.US_ASCII) {
            throw IllegalArgumentException("Unsupported encoding")
        }

        this.inputStream = inputStream
        this.charset = charset
        buf = ByteArray(capacity)
    }

    /**
     * Closes the reader by closing the underlying {@code InputStream} and
     * marking this reader as closed.
     *
     * @throws IOException for errors when closing the underlying {@code InputStream}.
     */
    @Throws(IOException::class)
    override fun close() {
        synchronized(inputStream) {
            if (buf != null) {
                buf = null
                inputStream.close()
            }
        }
    }

    /**
     * Reads the next line. A line ends with {@code "\n"} or {@code "\r\n"},
     * this end of line marker is not included in the result.
     *
     * @return the next line from the input.
     * @throws IOException for underlying {@code InputStream} errors.
     * @throws EOFException for the end of source stream.
     */
    @Throws(IOException::class)
    fun readLine(): String {
        synchronized(inputStream) {
            if (buf == null) {
                throw IOException("LineReader is closed")
            }

            // Read more data if we are at the end of the buffered data.
            // Though it's an error to read after an exception, we will let {@code fillBuf()}
            // throw again if that happens; thus we need to handle end == -1 as well as end == pos.
            if (pos >= end) {
                fillBuf()
            }

            // Try to find LF in the buffered data and return the line if successful.
            for (i in pos until end) {
                if (buf!![i] == LF) {
                    val lineEnd = if (i != pos && buf!![i - 1] == CR) i - 1 else i
                    val res = String(buf!!, pos, lineEnd - pos, charset)
                    pos = i + 1
                    return res
                }
            }

            // Let's anticipate up to 80 characters on top of those already read.
            val out = object : ByteArrayOutputStream(end - pos + 90) {
                override fun toString(): String {
                    val length = if (count > 0 && buf!![count - 1] == CR) count - 1 else count
                    try {
                        return String(buf, 0, length, charset)
                    } catch (e: UnsupportedEncodingException) {
                        throw AssertionError(e)
                    }
                }
            }

            while (true) {
                out.write(buf, pos, end - pos)
                // Mark unterminated line in case fillBuf throws EOFException or IOException.
                end = -1
                fillBuf()
                // Try to find LF in the buffered data and return the line if successful.
                for (i in pos.until(end)) {
                    if (buf!![i] == LF) {
                        if (i != pos) {
                            out.write(buf!!, pos, i - pos)
                        }
                        pos = i + 1
                        return out.toString()
                    }
                }
            }
        }
    }


    fun hasUnterminatedLine(): Boolean {
        return end == -1
    }

    /**
     * Reads new input data into the buffer. Call only with pos == end or end == -1,
     * depending on the desired outcome if the function throws.
     */
    @Throws(IOException::class)
    private fun fillBuf() {
        val result = inputStream.read(buf, 0, buf!!.size)
        if (result == -1) {
            throw EOFException()
        }
        pos = 0
        end = result
    }
}