package io.horizontalsystems.xrpkit.codec

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.horizontalsystems.xrpkit.crypto.toHex
import io.horizontalsystems.xrpkit.models.Amount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigDecimal

class CodecTest {

    private fun resource(name: String): JsonObject =
        JsonParser.parseReader(javaClass.classLoader!!.getResourceAsStream(name)!!.reader()).asJsonObject

    /** Converts fixture JSON to the map shape BinarySerializer takes. */
    private fun toMap(json: JsonObject): Map<String, Any?> = json.entrySet().associate { (k, v) -> k to toValue(k, v) }

    private fun toValue(key: String, v: JsonElement): Any? = when {
        v.isJsonNull -> null
        v.isJsonPrimitive && v.asJsonPrimitive.isNumber -> v.asLong
        v.isJsonPrimitive -> v.asString
        v.isJsonArray -> v.asJsonArray.map { toMap(it.asJsonObject) }
        v.isJsonObject && v.asJsonObject.has("currency") -> v.asJsonObject.entrySet().associate { it.key to it.value.asString }
        v.isJsonObject -> toMap(v.asJsonObject)
        else -> throw IllegalArgumentException("unsupported $key")
    }

    private fun isSupported(json: JsonObject): Boolean =
        json.entrySet().all { (k, v) ->
            k == "hash" || (FieldDefinitions.contains(k) && (k != "TransactionType" || runCatching { TransactionType.code(v.asString) }.isSuccess))
        }

    @Test
    fun signingDataEncoding() {
        // xrpl.js ripple-binary-codec signing-data-encoding.test.ts
        val tx = mapOf(
            "Account" to "r9LqNeG6qHxjeUocjvVki2XR35weJ9mZgQ",
            "Amount" to "1000",
            "Destination" to "rHb9CJAWyB4rj91VRWn96DkukG4bwdtyTh",
            "Fee" to "10",
            "Flags" to 2147483648L,
            "Sequence" to 1L,
            "TransactionType" to "Payment",
            "TxnSignature" to "30440220718D264EF05CAED7C781FF6DE298DCAC68D002562C9BF3A07C1E721B420C0DAB02203A5A4779EF4D2CCC7BC3EF886676D803A9981B928D3B8ACA483B80ECA3CD7B9B",
            "SigningPubKey" to "ED5F5AC8B98974A3CA843326D9B88CEBD0560177B973EE0B149F782CFAA06DC66A",
        )
        val expected = "120000" + "2280000000" + "2400000001" + "6140000000000003E8" + "68400000000000000A" +
            "7321ED5F5AC8B98974A3CA843326D9B88CEBD0560177B973EE0B149F782CFAA06DC66A" +
            "81145B812C9D57731E27A2DA8B1830195F88EF32A3B6" +
            "8314B5F762798A53D543A014CAF8B297CFF8F2F937E8"
        assertEquals(expected, BinarySerializer.serialize(tx, forSigning = true).toHex())
        // the full serialization includes the signature between SigningPubKey and Account
        assertTrue(BinarySerializer.serialize(tx).toHex().contains("7446" + "30440220718D264E"))
    }

    @Test
    fun codecFixtureTransactions() {
        val fixtures = resource("codec-fixtures.json").getAsJsonArray("transactions")
        var checked = 0
        for (element in fixtures) {
            val json = element.asJsonObject.getAsJsonObject("json")
            if (!isSupported(json)) continue
            val expected = element.asJsonObject.get("binary").asString
            assertEquals(json.get("TransactionType").asString, expected, BinarySerializer.serialize(toMap(json)).toHex())
            checked++
        }
        assertTrue("no fixtures checked", checked > 0)
    }

    @Test
    fun dataDrivenWholeObjects() {
        val objects = resource("data-driven-tests.json").getAsJsonArray("whole_objects")
        var checked = 0
        for (element in objects) {
            val obj = element.asJsonObject
            val txJson = obj.getAsJsonObject("tx_json")
            if (!isSupported(txJson)) continue
            val expected = obj.get("blob_with_no_signing").asString
            assertEquals(expected, BinarySerializer.serialize(toMap(txJson)).toHex())
            checked++
        }
        assertTrue("no whole objects checked", checked > 0)
    }

    @Test
    fun dataDrivenAmountValues() {
        val tests = resource("data-driven-tests.json").getAsJsonArray("values_tests")
        var checked = 0
        for (element in tests) {
            val test = element.asJsonObject
            if (test.get("type").asString != "Amount") continue
            val json = test.get("test_json")
            val amount: Amount = if (json.isJsonPrimitive) {
                try {
                    Amount.Xrp(java.math.BigInteger(json.asString))
                } catch (e: NumberFormatException) {
                    continue
                }
            } else {
                val o = json.asJsonObject
                // Multi-Purpose Token amounts are not supported by this codec
                if (o.has("mpt_issuance_id") || !o.has("value") || !o.has("currency") || !o.has("issuer")) continue
                Amount.Issued(BigDecimal(o.get("value").asString), o.get("currency").asString, o.get("issuer").asString)
            }
            if (test.has("error")) {
                try {
                    AmountCodec.encode(amount)
                    fail("expected error for ${test.get("test_json")}")
                } catch (e: IllegalArgumentException) {
                    // expected
                } catch (e: ArithmeticException) {
                    // expected
                }
            } else {
                assertEquals(test.get("test_json").toString(), test.get("expected_hex").asString, AmountCodec.encode(amount).toHex())
            }
            checked++
        }
        assertTrue(checked > 20)
    }

    @Test
    fun fieldHeaders() {
        val tests = resource("data-driven-tests.json").getAsJsonArray("fields_tests")
        for (element in tests) {
            val test = element.asJsonObject
            val name = test.get("name").asString
            if (!FieldDefinitions.contains(name)) continue
            assertEquals(name, test.get("expected_hex").asString, FieldDefinitions.get(name).header().toHex())
        }
    }

    @Test
    fun currencyCodes() {
        assertEquals("XRP", CurrencyCodec.fromBytes(ByteArray(20)))
        assertEquals("USD", CurrencyCodec.fromBytes(CurrencyCodec.toBytes("USD")))
        val rlusd = "524C555344000000000000000000000000000000"
        assertEquals(rlusd, CurrencyCodec.fromBytes(CurrencyCodec.toBytes(rlusd)))
        assertEquals("RLUSD", CurrencyCodec.displayCode(rlusd))
        assertEquals("USD", CurrencyCodec.displayCode("USD"))
        assertEquals(rlusd, CurrencyCodec.normalize(rlusd.lowercase()))
        assertTrue(CurrencyCodec.isValid("EUR"))
        assertTrue(!CurrencyCodec.isValid("XRP"))
        assertTrue(!CurrencyCodec.isValid("EURO"))
    }

    @Test
    fun variableLengthPrefix() {
        assertEquals("00", BinarySerializer.encodeLength(0).toHex())
        assertEquals("C0", BinarySerializer.encodeLength(192).toHex())
        assertEquals("C100", BinarySerializer.encodeLength(193).toHex())
        assertEquals("F0FF", BinarySerializer.encodeLength(12480).toHex())
        assertEquals("F10000", BinarySerializer.encodeLength(12481).toHex())
    }

    @Test
    fun memosSerializeAsArrayOfObjects() {
        val tx = mapOf(
            "TransactionType" to "Payment",
            "Account" to "rHb9CJAWyB4rj91VRWn96DkukG4bwdtyTh",
            "Destination" to "rHb9CJAWyB4rj91VRWn96DkukG4bwdtyTh",
            "Amount" to "1",
            "Fee" to "10",
            "Sequence" to 1L,
            "SigningPubKey" to "",
            "Memos" to listOf(mapOf("Memo" to mapOf("MemoType" to "4D656D6F", "MemoData" to "6869"))),
        )
        val hex = BinarySerializer.serialize(tx).toHex()
        // AccountID fields (type 8) precede the STArray (type 15):
        // Account (81) Destination (83) Memos (F9) Memo (EA) MemoType (7C) MemoData (7D) end object (E1) end array (F1)
        assertTrue(hex, hex.endsWith("8114B5F762798A53D543A014CAF8B297CFF8F2F937E8" + "8314B5F762798A53D543A014CAF8B297CFF8F2F937E8" + "F9EA7C044D656D6F7D026869E1F1"))
    }
}
