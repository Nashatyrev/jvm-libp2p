/*
 * Copyright 2019 BLK Technologies Limited (web3labs.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package io.libp2p.core.security.secio

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec


class SecioCodecTest {


    private val b64Encoder = Base64.getEncoder();
    private val b64Decoder = Base64.getDecoder();

    private fun decodeB64(data: ByteArray): ByteArray = b64Decoder.decode(data)

    private fun decodeB64(data: String): ByteArray = b64Decoder.decode(data)

    private fun encodeB64(data: ByteArray): ByteArray = b64Encoder.encode(data)


    // Set up test data.
    private val localCipherKey = decodeB64("gnwcRZjqKQvmho9ydCCdeg==")
    private val localIv = decodeB64("KCDZcNJvWfpqBJ7vO70r2Q==")
    private val localMacKey = decodeB64("WL19M4qcwAjfwZAmOaFH3DSu69s=")
    private val localMac: HMac = HMac(SHA256Digest()).also { it.init(KeyParameter(localMacKey)) }
    private val localMacSize = localMac.macSize // expected: 32


    @Test
    fun testUsingSunJceCipherWithUpdateMethod() {
        val cipherUsingSunJCE = Cipher.getInstance("AES/CTR/NoPadding")
            .also { it.init(Cipher.DECRYPT_MODE, SecretKeySpec(localCipherKey, "AES"), IvParameterSpec(localIv)) }

        testCipherUsingUpdateOrFinalMethod(cipherUsingSunJCE, false)
    }

    @Test
    fun testUsingSunJceCipherWithDoFinalMethod() {
        val cipherUsingSunJCE = Cipher.getInstance("AES/CTR/NoPadding")
            .also { it.init(Cipher.DECRYPT_MODE, SecretKeySpec(localCipherKey, "AES"), IvParameterSpec(localIv)) }

        testCipherUsingUpdateOrFinalMethod(cipherUsingSunJCE, true)
    }

    @Test
    fun testUsingBCCipherWithUpdateMethod() {
        val cipherUsingBC = Cipher.getInstance("AES/CTR/NoPadding", BouncyCastleProvider())
            .also { it.init(Cipher.DECRYPT_MODE, SecretKeySpec(localCipherKey, "AES"), IvParameterSpec(localIv)) }

        testCipherUsingUpdateOrFinalMethod(cipherUsingBC, false)
    }

    @Test
    fun testUsingBCCipherWithDoFinalMethod() {
        val cipherUsingBC = Cipher.getInstance("AES/CTR/NoPadding", BouncyCastleProvider())
            .also { it.init(Cipher.DECRYPT_MODE, SecretKeySpec(localCipherKey, "AES"), IvParameterSpec(localIv)) }

        testCipherUsingUpdateOrFinalMethod(cipherUsingBC, true)
    }

    private fun testCipherUsingUpdateOrFinalMethod(cipherToTest: Cipher, useDoFinal: Boolean) {

        // This is step 1 in the SecIO handshake: ensure we can decrypt our nonce when that is sent from the remote party.
        val encryptedNonceSentByRemotePeerB64 =
            "AAAAMANv6ZEpzxdL1xOjRBf/tVQPCLldztJqAzeqYEZpiKFqIVf+/81XKeT2+XH1CU6sbA=="
        val ourNonceB64 = "h3BVoG08BfZVecmRjSwYMg=="

        // ===== Step 1: try to decode the remote nonce ===== //
        // Drop the leading 4 bytes that indicate length.
        val encodedNonceData = decodeB64(encryptedNonceSentByRemotePeerB64).drop(4).toByteArray()

        // Decode first.
        var decryptedNonceData =
            SecIoCodec.decodeByteArray(encodedNonceData, localMacSize, localMac, cipherToTest, useDoFinal)

        var nonceDataB64 = String(encodeB64(decryptedNonceData))
        Assertions.assertEquals(ourNonceB64, nonceDataB64, "Unexpected nonce value")

        // ===== Step 2: We should now process a /multistream/1.0.0 message ===== //
        // Step 2 in SecIO: expect "/multistream/1.0.0" (length-prefixed)
        val encryptedMultistreamB64 = "AAAANHUrlky7lv4TJvPphjR4DmHJY5umFvsx2l6fyF8ocJUz+ZQIp/mZ98pcG64AQ4CuBH2rY74="

        // Drop the leading 4 bytes indicative of length.
        val encodedMultistreamData = decodeB64(encryptedMultistreamB64).drop(4).toByteArray()

        // Drop the 1st byte as it's the length.
        val decryptedMultistreamData =
            SecIoCodec.decodeByteArray(encodedMultistreamData, localMacSize, localMac, cipherToTest, useDoFinal)
                .drop(1).toByteArray()

        Assertions.assertEquals("/multistream/1.0.0\n", String(decryptedMultistreamData))
    }

}