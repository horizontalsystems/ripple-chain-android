package io.horizontalsystems.ripplekit.codec

import io.horizontalsystems.ripplekit.crypto.AccountId
import io.horizontalsystems.ripplekit.crypto.Hashes
import io.horizontalsystems.ripplekit.crypto.hexToBytes
import io.horizontalsystems.ripplekit.models.Amount
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Canonical binary serialization of a transaction given as a JSON-like map.
 *
 * Values are Kotlin types by field type: numbers ([Int]/[Long]) for UInt fields, [String] hex for
 * Hash and Blob fields, `r...` address [String] for AccountID fields, [Amount] or a drops string
 * (or a value/currency/issuer map) for Amount fields, a list of single-key maps for STArray, and
 * a map for a nested STObject.
 */
internal object BinarySerializer {

    private val TRANSACTION_SIGN_PREFIX = byteArrayOf(0x53, 0x54, 0x58, 0x00)   // "STX\0"
    private val TRANSACTION_ID_PREFIX = byteArrayOf(0x54, 0x58, 0x4E, 0x00)     // "TXN\0"

    fun serialize(tx: Map<String, Any?>, forSigning: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        writeObject(out, tx, forSigning)
        return out.toByteArray()
    }

    /** Hash that is signed: SHA-512Half over the "STX\0" prefix and the signing-field serialization. */
    fun signingHash(tx: Map<String, Any?>): ByteArray =
        Hashes.sha512Half(TRANSACTION_SIGN_PREFIX, serialize(tx, forSigning = true))

    /** Transaction id: SHA-512Half over the "TXN\0" prefix and the full signed serialization. */
    fun transactionHash(signedBlob: ByteArray): ByteArray =
        Hashes.sha512Half(TRANSACTION_ID_PREFIX, signedBlob)

    private fun writeObject(out: ByteArrayOutputStream, obj: Map<String, Any?>, forSigning: Boolean) {
        val entries = obj.entries
            .filter { it.value != null }
            .map { FieldDefinitions.get(it.key) to it.value!! }
            .filter { (field, _) -> !forSigning || field.isSigningField }
            .sortedBy { (field, _) -> field.ordinal }

        for ((field, value) in entries) {
            out.write(field.header())
            val body = encodeValue(field, value, forSigning)
            if (field.isVLEncoded) {
                out.write(encodeLength(body.size))
            }
            out.write(body)
        }
    }

    private fun encodeValue(field: Field, value: Any, forSigning: Boolean): ByteArray = when (field.typeCode) {
        TypeCode.UINT8 -> byteArrayOf(toLong(value).toByte())
        TypeCode.UINT16 -> {
            val v = if (field.name == "TransactionType" && value is String) TransactionType.code(value) else toLong(value).toInt()
            ByteBuffer.allocate(2).putShort(v.toShort()).array()
        }
        TypeCode.UINT32 -> ByteBuffer.allocate(4).putInt(toLong(value).toInt()).array()
        TypeCode.UINT64 -> ByteBuffer.allocate(8).putLong(toLong(value)).array()
        TypeCode.HASH128 -> fixedHex(value, 16, field.name)
        TypeCode.HASH256 -> fixedHex(value, 32, field.name)
        TypeCode.AMOUNT -> AmountCodec.encode(toAmount(value))
        TypeCode.BLOB -> (value as String).hexToBytes()
        TypeCode.ACCOUNT_ID -> AccountId.fromAddress(value as String).bytes
        TypeCode.ST_OBJECT -> {
            @Suppress("UNCHECKED_CAST")
            val inner = ByteArrayOutputStream()
            writeObject(inner, value as Map<String, Any?>, forSigning)
            inner.write(FieldDefinitions.objectEndMarker.toInt())
            inner.toByteArray()
        }
        TypeCode.ST_ARRAY -> {
            @Suppress("UNCHECKED_CAST")
            val items = value as List<Map<String, Any?>>
            val inner = ByteArrayOutputStream()
            for (item in items) {
                // each array element is a single-key object naming the wrapped STObject field (e.g. Memo)
                require(item.size == 1) { "STArray element must have exactly one field" }
                val (name, obj) = item.entries.first()
                val objField = FieldDefinitions.get(name)
                inner.write(objField.header())
                @Suppress("UNCHECKED_CAST")
                writeObject(inner, obj as Map<String, Any?>, forSigning)
                inner.write(FieldDefinitions.objectEndMarker.toInt())
            }
            inner.write(FieldDefinitions.arrayEndMarker.toInt())
            inner.toByteArray()
        }
        else -> throw IllegalArgumentException("Unsupported type code ${field.typeCode} for ${field.name}")
    }

    private fun fixedHex(value: Any, size: Int, name: String): ByteArray {
        val bytes = (value as String).hexToBytes()
        require(bytes.size == size) { "$name must be $size bytes" }
        return bytes
    }

    private fun toLong(value: Any): Long = when (value) {
        is Int -> value.toLong()
        is Long -> value
        is Number -> value.toLong()
        is String -> value.toLong()
        else -> throw IllegalArgumentException("Cannot convert $value to an integer")
    }

    private fun toAmount(value: Any): Amount = when (value) {
        is Amount -> value
        is String -> AmountCodec.fromDropsString(value)
        is Map<*, *> -> AmountCodec.issued(
            value["value"] as String,
            value["currency"] as String,
            value["issuer"] as String,
        )
        else -> throw IllegalArgumentException("Cannot convert $value to an Amount")
    }

    /** Variable-length prefix for Blob and AccountID fields. */
    fun encodeLength(length: Int): ByteArray = when {
        length <= 192 -> byteArrayOf(length.toByte())
        length <= 12480 -> {
            val v = length - 193
            byteArrayOf((193 + (v shr 8)).toByte(), (v and 0xFF).toByte())
        }
        length <= 918744 -> {
            val v = length - 12481
            byteArrayOf((241 + (v shr 16)).toByte(), ((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte())
        }
        else -> throw IllegalArgumentException("Length $length exceeds the maximum variable-length field size")
    }
}
