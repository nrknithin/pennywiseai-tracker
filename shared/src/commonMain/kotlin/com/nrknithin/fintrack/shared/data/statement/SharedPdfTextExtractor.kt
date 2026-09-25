package com.nrknithin.fintrack.shared.data.statement

expect object SharedPdfTextExtractor {
    fun extractText(filePath: String): String
}
