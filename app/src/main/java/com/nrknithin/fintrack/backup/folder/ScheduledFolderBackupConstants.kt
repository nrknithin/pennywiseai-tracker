package com.nrknithin.fintrack.backup.folder

object ScheduledFolderBackupConstants {
    const val BACKUP_FILE_NAME = "FinTrack_Backup.fintrackbackup"
    /** Sibling temp file written first, then swapped into [BACKUP_FILE_NAME]. */
    const val TEMP_BACKUP_FILE_NAME = "FinTrack_Backup.fintrackbackup.tmp"
    const val BACKUP_HOUR_OF_DAY = 2
}
