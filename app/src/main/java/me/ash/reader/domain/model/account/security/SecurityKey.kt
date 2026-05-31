package me.ash.reader.domain.model.account.security

import com.google.gson.Gson

abstract class SecurityKey {

    fun <T> decode(value: String?, classOfT: Class<T>): T {
        val json = value?.takeIf { it.isNotEmpty() }?.let { v ->
            runCatching { AESUtils.decrypt(v) }.getOrElse {
                // Fallback: attempt to decrypt legacy DES-encoded values written by older app versions.
                runCatching { DESUtils.decrypt(v) }.getOrElse { "{}" }
            }
        } ?: "{}"
        return Gson().fromJson(json, classOfT)
    }

    override fun toString(): String = AESUtils.encrypt(Gson().toJson(this))

    override fun equals(other: Any?): Boolean = this.toString() == other.toString()

    override fun hashCode(): Int = javaClass.hashCode()
}
