// SPDX-FileCopyrightText: 2024 City of Espoo
//
// SPDX-License-Identifier: LGPL-2.1-or-later

package fi.espoo.evaka.document.archival

import fi.espoo.evaka.s3.Document

interface ArchivalClient {
    fun putDocument(
        documentContent: Document,
        metadataXml: String,
        masterId: String,
        classId: String,
        virtualArchiveId: String,
    ): Pair<Int, String?>
}
