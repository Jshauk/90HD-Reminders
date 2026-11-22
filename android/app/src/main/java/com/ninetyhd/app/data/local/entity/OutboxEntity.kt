package com.ninetyhd.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A queued local change waiting to be pushed to the server.
 *
 * Every local create/update/delete enqueues one of these with a FULL snapshot of
 * the reminder as of the change (stored as JSON). On sync, entries are flushed to
 * the server in timestamp order; the server upserts by the snapshot's updated_at
 * (last-write-wins). Storing full snapshots (rather than field-level deltas) keeps
 * reconciliation simple: "make the server's copy match this."
 */
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val reminderId: String,
    val operation: String,       // CREATE | UPDATE | DELETE
    val snapshotJson: String,    // full ReminderEntity serialized as JSON
    val timestamp: String,       // when the change was made (client clock, ISO-8601)
    val synced: Boolean = false
) {
    companion object {
        const val OP_CREATE = "CREATE"
        const val OP_UPDATE = "UPDATE"
        const val OP_DELETE = "DELETE"
    }
}
