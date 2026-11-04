package xyz.mdhv.asom.widget.model

import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import xyz.mdhv.asom.contract.CostBasis
import xyz.mdhv.asom.contract.Egress
import xyz.mdhv.asom.contract.RouteRecord

/** Echoes keys and arguments, so a test can see which sentence a face chose. */
class FakeCopy : WidgetCopy {
    override fun text(key: Txt, vararg args: Any): String =
        if (args.isEmpty()) key.name else "${key.name}(${args.joinToString("|")})"

    override fun count(key: Cnt, n: Int, vararg args: Any): String =
        if (args.isEmpty()) "${key.name}[$n]" else "${key.name}[$n](${args.joinToString("|")})"

    override fun time(epochMs: Long): String = "t$epochMs"
    override fun day(epochMs: Long): String = "d$epochMs"
    override fun dayTime(epochMs: Long): String = "dt$epochMs"
    override fun number(n: Long): String = n.toString()
}

/**
 * The real English copy, read from `src/main/res/values/strings.xml` and
 * formatted the way `Resources.getString(id, args)` formats (String.format).
 * Records every key used, so a test can prove each sentence was exercised
 * with a matching argument count.
 */
class XmlCopy : WidgetCopy {
    val usedText = mutableSetOf<Txt>()
    val usedCount = mutableSetOf<Cnt>()

    override fun text(key: Txt, vararg args: Any): String {
        usedText += key
        val pattern = Strings.strings.getValue("widget_${key.name.lowercase()}")
        return if (args.isEmpty()) pattern else String.format(Locale.US, pattern, *args)
    }

    override fun count(key: Cnt, n: Int, vararg args: Any): String {
        usedCount += key
        val forms = Strings.plurals.getValue("widget_${key.name.lowercase()}")
        val pattern = forms.getValue(if (n == 1) "one" else "other")
        val formatArgs: Array<out Any> = if (args.isEmpty()) arrayOf(n) else args
        return String.format(Locale.US, pattern, *formatArgs)
    }

    override fun time(epochMs: Long): String = "14:05"
    override fun day(epochMs: Long): String = "4 Nov"
    override fun dayTime(epochMs: Long): String = "4 Nov, 14:05"
    override fun number(n: Long): String = String.format(Locale.US, "%,d", n)
}

/** `app/src/main/res` parsed once. Unit tests run with the module directory as the working directory. */
object Strings {
    val res = File("src/main/res")

    fun xml(path: String): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, path)).documentElement

    val strings: Map<String, String> by lazy {
        val root = xml("values/strings.xml")
        val nodes = root.getElementsByTagName("string")
        (0 until nodes.length).associate { i ->
            val e = nodes.item(i) as Element
            e.getAttribute("name") to unescape(e.textContent)
        }
    }

    val plurals: Map<String, Map<String, String>> by lazy {
        val root = xml("values/strings.xml")
        val nodes = root.getElementsByTagName("plurals")
        (0 until nodes.length).associate { i ->
            val e = nodes.item(i) as Element
            val items = e.getElementsByTagName("item")
            e.getAttribute("name") to (0 until items.length).associate { j ->
                val item = items.item(j) as Element
                item.getAttribute("quantity") to unescape(item.textContent)
            }
        }
    }

    private fun unescape(s: String): String = s.replace("\\'", "'").replace("\\\"", "\"")
}

/** Every string a face would put on the home screen, including its TalkBack text. */
fun Face.allText(): List<String> = buildList {
    add(title)
    asOf?.let(::add)
    add(description)
    when (val f = this@allText) {
        is StatFace -> {
            add(f.value.display)
            f.unit?.let(::add)
            f.lines.forEach { add(it.display) }
            f.footer?.let(::add)
            f.action?.let { add(it.label); add(it.description) }
        }
        is CardFace -> {
            add(f.headline.display)
            f.lines.forEach { add(it.display) }
            f.footer?.let(::add)
        }
        is ListFace -> {
            f.rows.forEach { add(it.lead.display); it.trailing?.let(::add) }
            f.footer?.let(::add)
        }
        is ProgressFace -> {
            add(f.value.display)
            f.unit?.let(::add)
            f.lines.forEach { add(it.display) }
            f.footer?.let(::add)
        }
    }
}

/** Every toned line on a face, for the §1.6 shape-and-word check. */
fun Face.allLines(): List<Line> = when (this) {
    is StatFace -> listOf(value) + lines
    is CardFace -> listOf(headline) + lines
    is ListFace -> rows.map { it.lead }
    is ProgressFace -> listOf(value) + lines
}

const val DAY_START = 1_000_000L
const val NOW = DAY_START + 50_000L

fun route(
    ts: Long = NOW,
    egress: Egress = Egress.CLOUD,
    provider: String? = "openrouter",
    model: String? = "llama-3.3-70b",
    status: Int = 200,
    cost: Double? = null,
    basis: CostBasis = CostBasis.NONE,
    caller: String = "com.example.private.journal",
    requested: String = "auto",
    bytesOut: Long = 0,
    tokensIn: Long? = null,
    tokensOut: Long? = null,
) = RouteRecord(
    ts = ts,
    callerPkg = caller,
    requestedModel = requested,
    servedProvider = provider,
    servedModel = model,
    egress = egress,
    bytesOut = bytesOut,
    tokensIn = tokensIn,
    tokensOut = tokensOut,
    costEst = cost,
    costBasis = basis,
    latencyMs = 420,
    status = status,
)
