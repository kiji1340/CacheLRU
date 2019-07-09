package com.db1608.cache

import org.junit.Test

import org.junit.Assert.*

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * @see [Testing documentation](http://d.android.com/tools/testing)
 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, (2 + 2).toLong())
        val a = A()
        val b = B(a.boolean)
        b.update()
        print("${a.boolean}")
    }

    class A{
        var boolean: Boolean = true
    }

    class B(var boolean: Boolean){
        fun update(){
            boolean = false
        }
    }
}