// Adapted from NewPipe (GPL-3.0): uses Android's org.json instead of nanojson.
package com.faithscroll.app.potoken

import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses the raw challenge data obtained from the Create endpoint and returns an object that can be
 * embedded in a JavaScript snippet.
 */
fun parseChallengeData(rawChallengeData: String): String {
    val scrambled = JSONArray(rawChallengeData)
    val challengeData = if (scrambled.length() > 1 && scrambled.opt(1) is String) {
        JSONArray(descramble(scrambled.getString(1)))
    } else {
        scrambled.getJSONArray(0)
    }

    val messageId = challengeData.getString(0)
    val interpreterHash = challengeData.getString(3)
    val program = challengeData.getString(4)
    val globalName = challengeData.getString(5)
    val clientExperimentsStateBlob = challengeData.getString(7)

    fun firstString(a: JSONArray?): Any = a?.let { arr -> (0 until arr.length()).map { arr.opt(it) }.firstOrNull { it is String } } ?: JSONObject.NULL

    return JSONObject()
        .put("messageId", messageId)
        .put(
            "interpreterJavascript", JSONObject()
                .put("privateDoNotAccessOrElseSafeScriptWrappedValue", firstString(challengeData.optJSONArray(1)))
                .put("privateDoNotAccessOrElseTrustedResourceUrlWrappedValue", firstString(challengeData.optJSONArray(2)))
        )
        .put("interpreterHash", interpreterHash)
        .put("program", program)
        .put("globalName", globalName)
        .put("clientExperimentsStateBlob", clientExperimentsStateBlob)
        .toString()
}

/**
 * Parses the raw integrity token data obtained from the GenerateIT endpoint to a JavaScript
 * `Uint8Array` that can be embedded directly in JavaScript code, and the token lifetime in seconds.
 */
fun parseIntegrityTokenData(rawIntegrityTokenData: String): Pair<String, Long> {
    val integrityTokenData = JSONArray(rawIntegrityTokenData)
    return base64ToU8(integrityTokenData.getString(0)) to integrityTokenData.getLong(1)
}

/** Converts a string to a JavaScript `Uint8Array` literal. */
fun stringToU8(identifier: String): String = newUint8Array(identifier.toByteArray())

/** Converts "97,98,99" (JavaScript Uint8Array::toString) to the base64 form used for poTokens. */
fun u8ToBase64(poToken: String): String =
    poToken.split(",").map { it.toUByte().toByte() }.toByteArray().toByteString().base64()
        .replace("+", "-").replace("/", "_")

private fun descramble(scrambledChallenge: String): String =
    base64ToByteString(scrambledChallenge).map { (it + 97).toByte() }.toByteArray().decodeToString()

private fun base64ToU8(base64: String): String = newUint8Array(base64ToByteString(base64))

private fun newUint8Array(contents: ByteArray): String =
    "new Uint8Array([" + contents.joinToString(separator = ",") { it.toUByte().toString() } + "])"

private fun base64ToByteString(base64: String): ByteArray {
    val base64Mod = base64.replace('-', '+').replace('_', '/').replace('.', '=')
    return (base64Mod.decodeBase64() ?: throw PoTokenException("Cannot base64 decode")).toByteArray()
}
