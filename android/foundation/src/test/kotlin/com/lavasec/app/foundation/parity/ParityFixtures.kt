package com.lavasec.app.foundation.parity

internal object ParityFixtures {
    fun objects(resourceName: String): List<Map<String, String?>> {
        val text = checkNotNull(javaClass.getResource("/parity/$resourceName")) {
            "Missing parity fixture: $resourceName"
        }.readText()
        return OBJECT.findAll(text).map { objectMatch ->
            FIELD.findAll(objectMatch.groupValues[1]).associate { field ->
                val rawValue = field.groupValues[2]
                field.groupValues[1] to when (rawValue) {
                    "null" -> null
                    "true", "false" -> rawValue
                    else -> rawValue.removeSurrounding("\"").unescapeJson()
                }
            }
        }.toList()
    }

    private fun String.unescapeJson(): String =
        replace(UNICODE_ESCAPE) { match -> match.groupValues[1].toInt(16).toChar().toString() }
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")

    private val OBJECT = Regex("\\{([^{}]*)}")
    private val FIELD = Regex("\\\"([^\\\"]+)\\\"\\s*:\\s*(\\\"(?:\\\\.|[^\\\"])*\\\"|true|false|null)")
    private val UNICODE_ESCAPE = Regex("\\\\u([0-9a-fA-F]{4})")
}
