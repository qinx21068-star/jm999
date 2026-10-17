package com.jmreader.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyImportTest {
    @Test fun detectsOldFavoriteAndHistoryFiles() {
        assertEquals(
            LegacyKind.FAVORITES_V1,
            detectLegacyKind("""[{"id":"123","name":"标题","tags":["tag"]}]"""),
        )
        assertEquals(
            LegacyKind.HISTORY,
            detectLegacyKind("""[{"comic":{"id":"1"},"chapterId":"c1","chapterTitle":"t","page":3,"updatedAt":1}]"""),
        )
        assertEquals(
            LegacyKind.BROWSE,
            detectLegacyKind("""[{"comic":{"id":"1"},"updatedAt":1}]"""),
        )
        assertEquals(
            LegacyKind.TEMPLATES,
            detectLegacyKind("""[{"name":"t","query":"abc","order":"latest"}]"""),
        )
    }

    @Test fun detectsFavoriteEntryArrayAndV2Object() {
        assertEquals(
            LegacyKind.FAVORITES_V1,
            detectLegacyKind("""[{"comic":{"id":"1","name":"a"},"folderId":null}]"""),
        )
        assertEquals(
            LegacyKind.FAVORITES_V2,
            detectLegacyKind("""{"version":2,"folders":[],"entries":[]}"""),
        )
    }

    @Test fun recognisesNewBackupAndRejectsGarbage() {
        assertEquals(
            LegacyKind.BACKUP,
            detectLegacyKind("""{"format":"jmreader-backup","version":1}"""),
        )
        assertEquals(LegacyKind.UNKNOWN, detectLegacyKind(""))
        assertEquals(LegacyKind.UNKNOWN, detectLegacyKind("not json at all"))
        assertEquals(LegacyKind.UNKNOWN, detectLegacyKind("[]"))
        assertEquals(LegacyKind.UNKNOWN, detectLegacyKind("""{"foo":1}"""))
    }
}
