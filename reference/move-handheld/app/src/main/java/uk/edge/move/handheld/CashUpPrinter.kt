package uk.edge.move.handheld

import android.content.Context
import com.pax.neptunelite.api.NeptuneLiteUser
import com.pax.dal.entity.EFontTypeAscii
import com.pax.dal.entity.EFontTypeExtCode

/** Prints the local cash ledger only; never invokes processor settlement. */
internal object CashUpPrinter {
    fun print(context:Context,text:String) {
        val printer=NeptuneLiteUser.getInstance().getDalWithProcessSafe(context.applicationContext).printer
        printer.init();printer.setGray(4);printer.leftIndent(0)
        printer.fontSet(EFontTypeAscii.FONT_12_24,EFontTypeExtCode.FONT_24_24)
        printer.printStr(text,null);printer.step(100)
        check(printer.start()==0) {"Cash-up is saved. Check printer paper and try again."}
    }
}
