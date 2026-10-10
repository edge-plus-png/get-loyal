package uk.edge.move.handheld

import org.json.JSONObject
import org.json.JSONArray

internal fun canonicalJson(value: Any?): String = when(value) {
    null, JSONObject.NULL -> "null"
    is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(prefix="{",postfix="}") { JSONObject.quote(it)+":"+canonicalJson(value.get(it)) }
    is JSONArray -> (0 until value.length()).joinToString(prefix="[",postfix="]") { canonicalJson(value.get(it)) }
    is String -> JSONObject.quote(value)
    else -> value.toString()
}
