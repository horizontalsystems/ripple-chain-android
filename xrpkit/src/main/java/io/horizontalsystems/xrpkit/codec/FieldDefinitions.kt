package io.horizontalsystems.xrpkit.codec

/** Serialized type codes from rippled's SField definitions. */
internal object TypeCode {
    const val UINT16 = 1
    const val UINT32 = 2
    const val UINT64 = 3
    const val HASH128 = 4
    const val HASH256 = 5
    const val AMOUNT = 6
    const val BLOB = 7
    const val ACCOUNT_ID = 8
    const val ST_OBJECT = 14
    const val ST_ARRAY = 15
    const val UINT8 = 16
}

internal class Field(
    val name: String,
    val typeCode: Int,
    val fieldCode: Int,
    val isVLEncoded: Boolean = false,
    val isSigningField: Boolean = true,
) {
    val ordinal: Long get() = (typeCode.toLong() shl 16) or fieldCode.toLong()

    /** Field ID header: 1 to 3 bytes depending on the size of the type and field codes. */
    fun header(): ByteArray = when {
        typeCode < 16 && fieldCode < 16 -> byteArrayOf(((typeCode shl 4) or fieldCode).toByte())
        typeCode < 16 -> byteArrayOf((typeCode shl 4).toByte(), fieldCode.toByte())
        fieldCode < 16 -> byteArrayOf(fieldCode.toByte(), typeCode.toByte())
        else -> byteArrayOf(0, typeCode.toByte(), fieldCode.toByte())
    }
}

/**
 * The subset of rippled's field table a wallet needs for Payment, TrustSet, AccountSet and
 * AccountDelete transactions, including Memos. Source: xrpl.js ripple-binary-codec definitions.json.
 */
internal object FieldDefinitions {

    private val fields: List<Field> = listOf(
        // UInt8
        Field("TickSize", TypeCode.UINT8, 16),
        // UInt16
        Field("TransactionType", TypeCode.UINT16, 2),
        // UInt32
        Field("NetworkID", TypeCode.UINT32, 1),
        Field("Flags", TypeCode.UINT32, 2),
        Field("SourceTag", TypeCode.UINT32, 3),
        Field("Sequence", TypeCode.UINT32, 4),
        Field("TransferRate", TypeCode.UINT32, 11),
        Field("DestinationTag", TypeCode.UINT32, 14),
        Field("QualityIn", TypeCode.UINT32, 20),
        Field("QualityOut", TypeCode.UINT32, 21),
        Field("LastLedgerSequence", TypeCode.UINT32, 27),
        Field("SetFlag", TypeCode.UINT32, 33),
        Field("ClearFlag", TypeCode.UINT32, 34),
        Field("TicketSequence", TypeCode.UINT32, 41),
        // Hash128
        Field("EmailHash", TypeCode.HASH128, 1),
        // Hash256
        Field("AccountTxnID", TypeCode.HASH256, 9),
        Field("InvoiceID", TypeCode.HASH256, 17),
        // Amount
        Field("Amount", TypeCode.AMOUNT, 1),
        Field("LimitAmount", TypeCode.AMOUNT, 3),
        Field("Fee", TypeCode.AMOUNT, 8),
        Field("SendMax", TypeCode.AMOUNT, 9),
        Field("DeliverMin", TypeCode.AMOUNT, 10),
        // Blob (variable length)
        Field("MessageKey", TypeCode.BLOB, 2, isVLEncoded = true),
        Field("SigningPubKey", TypeCode.BLOB, 3, isVLEncoded = true),
        Field("TxnSignature", TypeCode.BLOB, 4, isVLEncoded = true, isSigningField = false),
        Field("Domain", TypeCode.BLOB, 7, isVLEncoded = true),
        Field("MemoType", TypeCode.BLOB, 12, isVLEncoded = true),
        Field("MemoData", TypeCode.BLOB, 13, isVLEncoded = true),
        Field("MemoFormat", TypeCode.BLOB, 14, isVLEncoded = true),
        // AccountID (variable length, always 20 bytes)
        Field("Account", TypeCode.ACCOUNT_ID, 1, isVLEncoded = true),
        Field("Destination", TypeCode.ACCOUNT_ID, 3, isVLEncoded = true),
        Field("RegularKey", TypeCode.ACCOUNT_ID, 8, isVLEncoded = true),
        // STObject
        Field("Memo", TypeCode.ST_OBJECT, 10),
        // STArray
        Field("Memos", TypeCode.ST_ARRAY, 9),
    )

    private val byName: Map<String, Field> = fields.associateBy { it.name }

    val objectEndMarker: Byte = 0xE1.toByte()
    val arrayEndMarker: Byte = 0xF1.toByte()

    fun get(name: String): Field = byName[name]
        ?: throw IllegalArgumentException("Field '$name' is not supported by this codec")

    fun contains(name: String): Boolean = byName.containsKey(name)
}

internal object TransactionType {
    const val PAYMENT = 0
    const val ACCOUNT_SET = 3
    const val TRUST_SET = 20
    const val ACCOUNT_DELETE = 21

    private val codes = mapOf(
        "Payment" to PAYMENT,
        "AccountSet" to ACCOUNT_SET,
        "TrustSet" to TRUST_SET,
        "AccountDelete" to ACCOUNT_DELETE,
    )

    fun code(name: String): Int = codes[name]
        ?: throw IllegalArgumentException("Transaction type '$name' is not supported by this codec")
}
