package uk.edge.move.handheld

import org.json.JSONObject

internal fun validateParkedDiscard(reviewed:JSONObject,current:JSONObject?,pendingSave:Boolean,pairing:JSONObject) {
    check(!pendingSave) {"A shared save is awaiting confirmation; this local copy cannot be discarded yet."}
    check(current!=null && canonicalJson(reviewed)==canonicalJson(current)) {"The saved draft changed. Review it again."}
    check(current.optString("merchantCode")==pairing.getString("merchantCode") && current.optString("terminalCode")==pairing.getString("terminalCode")) {"Saved draft belongs to another assignment."}
}
