package com.db1608.cache.model

import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

class EntryDataType(private val type: Type) : ParameterizedType{

    override fun getRawType(): Type {
        return EntryData::class.java
    }

    override fun getOwnerType(): Type? {
        return null
    }

    override fun getActualTypeArguments(): Array<Type> {
        return arrayOf(type)
    }

}