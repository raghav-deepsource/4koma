package cc.ekblad.toml

import TomlParser
import java.lang.NumberFormatException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

internal fun TomlBuilder.extractDocument(ctx: TomlParser.DocumentContext) {
    ctx.expression().forEach { extractExpression(it) }
}

private fun TomlBuilder.extractExpression(ctx: TomlParser.ExpressionContext) {
    ctx.key_value()?.let { set(ctx.start.line, it.key().extractKey(), extractValue(it.value())) }
        ?: ctx.table()?.let { extractTable(it) }
        ?: ctx.comment()
        ?: error("unreachable")
}

private fun TomlBuilder.extractTable(ctx: TomlParser.TableContext) {
    ctx.standard_table()?.let { defineTable(ctx.start.line, it.key().extractKey()) }
        ?: ctx.array_table()?.let { addTableArrayEntry(ctx.start.line, it.key().extractKey()) }
        ?: error("not a table context: ${ctx::class}")
}

private fun extractValue(value: TomlParser.ValueContext): MutableTomlValue =
    value.string()?.let { MutableTomlValue.Primitive(TomlValue.String(it.extractString())) }
        ?: value.integer()?.let { MutableTomlValue.Primitive(TomlValue.Integer(it.extractInteger())) }
        ?: value.floating_point()?.let { MutableTomlValue.Primitive(TomlValue.Double(it.extractDouble())) }
        ?: value.bool_()?.let { MutableTomlValue.Primitive(TomlValue.Bool(it.extractBool())) }
        ?: value.date_time()?.let { MutableTomlValue.Primitive(it.extractDateTime()) }
        ?: value.array_()?.let { MutableTomlValue.InlineList(it.extractList()) }
        ?: value.inline_table()?.let { MutableTomlValue.InlineMap(it.extractInlineTable()) }
        ?: error("unreachable")

private fun TomlParser.IntegerContext.extractInteger(): Long {
    val text = text.replace("_", "")
    try {
        return when {
            DEC_INT() != null -> text.toLong(10)
            HEX_INT() != null -> text.substring(2).toLong(16)
            BIN_INT() != null -> text.substring(2).toLong(2)
            OCT_INT() != null -> text.substring(2).toLong(8)
            else -> error("unreachable")
        }
    } catch (e: NumberFormatException) {
        throw TomlException("integer '$text' is out of range", start.line, e)
    }
}

private fun TomlParser.Floating_pointContext.extractDouble(): Double =
    FLOAT()?.text?.replace("_", "")?.toDouble()
        ?: INF()?.text?.parseInfinity()
        ?: NAN()?.let { Double.NaN }
        ?: error("unreachable")

private fun String.parseInfinity(): Double = when (first()) {
    '-' -> Double.NEGATIVE_INFINITY
    else -> Double.POSITIVE_INFINITY
}

private fun TomlParser.Bool_Context.extractBool(): Boolean =
    BOOLEAN().text.toBooleanStrict()

private fun TomlParser.Date_timeContext.extractDateTime(): TomlValue.Primitive = try {
    OFFSET_DATE_TIME()?.text?.let { TomlValue.OffsetDateTime(OffsetDateTime.parse(it.replace(' ', 'T'))) }
        ?: LOCAL_DATE_TIME()?.text?.let { TomlValue.LocalDateTime(LocalDateTime.parse(it.replace(' ', 'T'))) }
        ?: LOCAL_DATE()?.text?.let { TomlValue.LocalDate(LocalDate.parse(it)) }
        ?: LOCAL_TIME()?.text?.let { TomlValue.LocalTime(LocalTime.parse(it)) }
        ?: error("unreachable")
} catch (e: DateTimeParseException) {
    throw TomlException("date/time '$text' has invalid format", start.line, e)
}

private fun TomlParser.Array_Context.extractList(): TomlValue.List {
    val list = mutableListOf<TomlValue>()
    var ctx = array_values()
    while (ctx != null) {
        list.add(extractValue(ctx.value()).freeze())
        ctx = ctx.array_values()
    }
    return TomlValue.List(list)
}

private fun TomlParser.Inline_tableContext.extractInlineTable(): TomlValue.Map =
    TomlBuilder.create().apply {
        var ctx = inline_table_keyvals().inline_table_keyvals_non_empty()
        while (ctx != null) {
            val key = ctx.key().extractKey()
            set(ctx.start.line, key, extractValue(ctx.value()))
            ctx = ctx.inline_table_keyvals_non_empty()
        }
    }.build()

private fun TomlParser.StringContext.extractString(): String =
    BASIC_STRING()?.text?.stripQuotes(1)?.convertEscapeCodes()
        ?: ML_BASIC_STRING()?.text?.stripQuotes(3)?.trimFirstNewline()?.convertEscapeCodes()
        ?: LITERAL_STRING()?.text?.stripQuotes(1)
        ?: ML_LITERAL_STRING()?.text?.stripQuotes(3)?.trimFirstNewline()
        ?: error("unreachable")

private fun String.stripQuotes(quoteSize: Int): String =
    substring(quoteSize, length - quoteSize)

private fun String.trimFirstNewline(): String = when {
    startsWith('\n') -> drop(1)
    startsWith("\r\n") -> drop(2)
    else -> this
}

private fun TomlParser.KeyContext.extractKey(): List<String> =
    simple_key()?.extractSimpleKey()
        ?: dotted_key()?.extractDottedKey()
        ?: error("unreachable")

private fun TomlParser.Dotted_keyContext.extractDottedKey(): List<String> =
    simple_key().flatMap { it.extractSimpleKey() }

private fun TomlParser.Simple_keyContext.extractSimpleKey(): List<String> =
    quoted_key()?.extractQuotedKey()
        ?: unquoted_key()?.extractUnquotedKey()
        ?: error("unreachable")

private fun TomlParser.Quoted_keyContext.extractQuotedKey(): List<String> =
    BASIC_STRING()?.let { listOf(it.text.stripQuotes(1).convertEscapeCodes()) }
        ?: LITERAL_STRING()?.let { listOf(it.text.stripQuotes(1)) }
        ?: error("unreachable")

/**
 * Because of the parser hack required to support keys that can overlap with values, we need to deal with the fact
 * that some "simple" keys may actually be dotted keys, and that the parser lets '+' signs through.
 */
private fun TomlParser.Unquoted_keyContext.extractUnquotedKey(): List<String> {
    val fragments = text.split('.')
    if (fragments.any { it.contains('+') }) {
        throw TomlException("illegal character '+' encountered in key", start.line)
    }
    return fragments
}

private fun String.convertEscapeCodes(): String =
    escapeRegex.replace(this, ::replaceEscapeMatch)

private fun replaceEscapeMatch(match: MatchResult): String = when (match.value[1]) {
    '"' -> "\""
    '\\' -> "\\"
    'b' -> "\b"
    'f' -> "\u000C"
    'n' -> "\n"
    'r' -> "\r"
    't' -> "\t"
    'u' -> String(Character.toChars(match.groupValues[2].toInt(16)))
    'U' -> String(Character.toChars(match.groupValues[3].toInt(16)))
    else -> error("unreachable")
}

val escapeRegex = Regex("\\\\([\\\\\"bnfrt]|u([0-9a-fA-F]{4})|U([0-9a-fA-F]{8}))")
