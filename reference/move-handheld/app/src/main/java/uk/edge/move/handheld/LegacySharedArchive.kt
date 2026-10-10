package uk.edge.move.handheld

import org.json.JSONObject

/** Retain old shared-order data without turning it into another payable local order. */
internal fun validateLegacyArchive(draft:JSONObject, existing:JSONObject?) {
    require(existing == null || canonicalJson(existing)==canonicalJson(draft)) {
        "An earlier shared draft is already retained. Review it before archiving another draft."
    }
}
