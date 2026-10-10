package uk.edge.move.handheld

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LegacySharedArchiveTest {
    @Test fun archiveCanRetryButCannotOverwriteAnotherOrderOrAssignment() {
        val old=JSONObject("""{"merchantCode":"m1","terminalCode":"t1","sharedState":{"id":"old","revision":2},"lines":[]}""")
        validateLegacyArchive(old,null)
        validateLegacyArchive(JSONObject(old.toString()),old)
        for(field in listOf("merchantCode","terminalCode")) {
            val other=JSONObject(old.toString()).put(field,"other")
            assertThrows(IllegalArgumentException::class.java) {validateLegacyArchive(other,old)}
        }
        val changed=JSONObject(old.toString()).put("sharedState",JSONObject().put("id","another"))
        assertThrows(IllegalArgumentException::class.java) {validateLegacyArchive(changed,old)}
        assertEquals("old",old.getJSONObject("sharedState").getString("id"))
    }
    @Test fun localTableDraftKeepsItsContextWithoutSharedState() {
        val draft=OrderingDraft().apply {changeContext(com.edge.poslite.domain.model.ServiceMode.RESTAURANT,"table-1","Table 1")}
        val restored=decodeDraft(draft.encode())
        assertEquals("table-1",restored.tableId)
        assertEquals("Table 1",restored.orderReference)
        assertNull(restored.sharedState)
    }
    @Test fun legacyDraftRetainsIdentityForRecoveryInsteadOfBecomingLocal() {
        val draft=OrderingDraft().apply {
            changeContext(com.edge.poslite.domain.model.ServiceMode.RESTAURANT,"table-1","Table 1")
            sharedState=JSONObject().put("id","old-order").put("revision",5)
        }
        val restored=decodeDraft(draft.encode())
        assertEquals("old-order",restored.sharedState!!.getString("id"))
        assertEquals(5,restored.sharedState!!.getInt("revision"))
    }
}
