package com.learntoplay.app.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/** An entertainment app the parent has chosen to gate behind the quiz (e.g. YouTube Kids, games). */
@Entity(tableName = "gated_apps")
data class GatedAppEntity(
    @PrimaryKey val packageName: String,
    val displayName: String,
    val isEnabled: Boolean = true
)
