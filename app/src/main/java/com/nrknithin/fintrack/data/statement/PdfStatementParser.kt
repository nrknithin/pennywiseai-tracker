package com.nrknithin.fintrack.data.statement

import com.nrknithin.fintrack.parser.core.ParsedTransaction

interface PdfStatementParser {
    fun canHandle(text: String): Boolean
    fun parse(text: String): List<ParsedTransaction>
}
