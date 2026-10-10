package uk.edge.move.handheld

/** 384-dot PAX paper, 12-dot native font: 32 columns. Never truncate receipt data. */
internal class ReceiptText(private val width:Int=32) {
    private val out=StringBuilder()
    fun line(value:String="") {
        if(value.isBlank()) {out.appendLine();return}
        wrap(value,width).forEach {out.appendLine(it)}
    }
    fun center(value:String) {wrap(value,width).forEach {out.appendLine(" ".repeat((width-it.length)/2)+it)}}
    fun rule() {line("-".repeat(width))}
    fun section(value:String) {line();center(value);rule()}
    fun row(label:String,value:String) {
        val right=value.replace('−','-')
        if(right.length>=width-1) {line(label);line(right);return}
        val left=wrap(label,width-right.length-1)
        left.dropLast(1).forEach(::line)
        val last=left.lastOrNull().orEmpty()
        out.appendLine(last+" ".repeat(width-last.length-right.length)+right)
    }
    override fun toString()=out.toString()
    private fun wrap(value:String,limit:Int):List<String> = buildList {
        value.replace('−','-').lines().forEach {source ->
            var remaining=source.trim()
            while(remaining.length>limit) {
                val space=remaining.lastIndexOf(' ',limit).takeIf {it>0} ?: limit
                add(remaining.take(space));remaining=remaining.drop(space).trimStart()
            }
            if(remaining.isNotEmpty()) add(remaining)
        }
    }
}

/** Only decorative rules change: processor fields and wording remain verbatim. */
internal fun formatCustomerCardCopy(returned:String):String {
    val lines=returned.replace("\r\n","\n").replace('\r','\n').trimEnd('\n').lines()
    return lines.joinToString("\n") { line ->
        val trimmed=line.trim()
        if(trimmed.length>32 && trimmed.all {it=='-'}) "-".repeat(32) else line
    }+"\n\n\n\n"
}
