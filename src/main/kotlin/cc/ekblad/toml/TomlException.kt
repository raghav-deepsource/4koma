package cc.ekblad.toml

import kotlin.reflect.KType
import kotlin.reflect.javaType

sealed class TomlException : RuntimeException() {
    /**
     * An error occurred while parsing a TOML document.
     */
    data class ParseError(
        val errorDescription: String,
        val line: Int,
        override val cause: Throwable?
    ) : TomlException() {
        constructor(errorDescription: String, line: Int) : this(errorDescription, line, null)

        override val message: String =
            "toml parse error, on line $line: $errorDescription"
    }

    /**
     * An error occurred while decoding a TOML value into some other Kotlin type.
     */
    data class DecodingError(
        val reason: String?,
        val sourceValue: TomlValue,
        val targetType: KType,
        override val cause: Throwable?
    ) : TomlException() {
        constructor(reason: String, sourceValue: TomlValue, targetType: KType) :
            this(reason, sourceValue, targetType, null)
        constructor(sourceValue: TomlValue, targetType: KType) :
            this(null, sourceValue, targetType, null)

        @OptIn(ExperimentalStdlibApi::class)
        override val message: String
            get() {
                val reasonSuffix = if (reason != null) ": $reason" else ""
                val type = targetType.javaType.typeName
                return "toml decoding error: unable to decode toml value '$sourceValue' to type '$type'$reasonSuffix"
            }
    }
}
