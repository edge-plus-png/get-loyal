package uk.edge.move.handheld

import org.json.JSONObject
import org.junit.Assert.assertThrows
import org.junit.Test

class ParkedDraftTest {
    private fun pair()=JSONObject().put("merchantCode","m").put("terminalCode","t")
    @Test fun onlyReviewedSameAssignmentDraftCanBeDiscarded() {
        val draft=pair().put("reference","Old table")
        validateParkedDiscard(draft,JSONObject(draft.toString()),false,pair())
        assertThrows(IllegalStateException::class.java) {validateParkedDiscard(draft,null,false,pair())}
        assertThrows(IllegalStateException::class.java) {validateParkedDiscard(draft,JSONObject(draft.toString()).put("reference","Changed"),false,pair())}
        assertThrows(IllegalStateException::class.java) {validateParkedDiscard(draft,draft,false,pair().put("merchantCode","other"))}
        assertThrows(IllegalStateException::class.java) {validateParkedDiscard(draft,draft,false,pair().put("terminalCode","other"))}
    }
    @Test fun unconfirmedSaveCannotBeDiscarded() {
        val draft=pair()
        assertThrows(IllegalStateException::class.java) {validateParkedDiscard(draft,draft,true,pair())}
    }
}
