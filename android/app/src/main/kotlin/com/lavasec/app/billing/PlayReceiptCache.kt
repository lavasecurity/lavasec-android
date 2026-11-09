package com.lavasec.app.billing

import com.lavasec.app.foundation.subscription.LavaSecurityPlusPolicy
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Previously server-verified receipts, retained solely for later native re-verification. */
internal interface PlayReceiptCache {
    suspend fun load(): List<PlayPurchase>
    suspend fun save(receipts: List<PlayPurchase>)
}

internal object PlayReceiptCodec {
    const val MAX_RECEIPTS = 100
    private const val MAX_BYTES = 512 * 1024
    private const val MAGIC = 0x4c505231 // LPR1

    fun encode(receipts: List<PlayPurchase>): ByteArray {
        validate(receipts)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(receipts.size)
            for (receipt in receipts) {
                out.writeUTF(receipt.token)
                out.writeUTF(receipt.products.single())
                out.writeByte(if (receipt.acknowledged) 1 else 0)
            }
        }
        return bytes.toByteArray().also { require(it.size <= MAX_BYTES) }
    }

    fun decode(bytes: ByteArray): List<PlayPurchase> {
        require(bytes.size in 8..MAX_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC)
            val count = input.readInt()
            require(count in 0..MAX_RECEIPTS)
            val receipts = List(count) {
                val token = input.readUTF()
                val product = input.readUTF()
                val acknowledged = input.readUnsignedByte().also { require(it in 0..1) }
                PlayPurchase(token, setOf(product), true, acknowledged == 1)
            }
            require(input.read() == -1)
            validate(receipts)
            receipts
        }
    }

    private fun validate(receipts: List<PlayPurchase>) {
        require(receipts.size <= MAX_RECEIPTS && receipts.map { it.token }.distinct().size == receipts.size)
        for (receipt in receipts) {
            require(receipt.purchased && receipt.products.size == 1 &&
                receipt.products.single() in LavaSecurityPlusPolicy.paywallProductIDs)
            require(receipt.token.length in 1..4096 && receipt.token.all { it.code in 33..126 })
        }
    }
}
