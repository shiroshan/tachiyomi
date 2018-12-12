/*
 * Copyright (C) 2018 The Tachiyomi Open Source Project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package tachiyomi.data.category.table

import android.database.sqlite.SQLiteDatabase
import tachiyomi.core.db.DbOpenCallback
import tachiyomi.data.manga.table.MangaTable

internal object MangaCategoryTable : DbOpenCallback {

  const val TABLE = "mangas_categories"

  const val COL_ID = "mc_id"
  const val COL_MANGA_ID = "mc_manga_id"
  const val COL_CATEGORY_ID = "mc_category_id"

  private val createTableQuery: String
    get() = """CREATE TABLE $TABLE(
            $COL_ID INTEGER NOT NULL PRIMARY KEY,
            $COL_MANGA_ID INTEGER NOT NULL,
            $COL_CATEGORY_ID INTEGER NOT NULL,
            FOREIGN KEY($COL_CATEGORY_ID) REFERENCES ${CategoryTable.TABLE} (${CategoryTable.COL_ID})
            ON DELETE CASCADE,
            FOREIGN KEY($COL_MANGA_ID) REFERENCES ${MangaTable.TABLE} (${MangaTable.COL_ID})
            ON DELETE CASCADE
            )"""

  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL(createTableQuery)
  }

}