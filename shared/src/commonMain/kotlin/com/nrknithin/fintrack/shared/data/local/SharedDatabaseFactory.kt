package com.nrknithin.fintrack.shared.data.local

expect class SharedDatabaseFactory() {
    fun createDatabase(): SharedDatabase
}
