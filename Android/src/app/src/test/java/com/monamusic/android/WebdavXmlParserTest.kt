package com.monamusic.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebdavXmlParserTest {
    @Test
    fun `可解析目录与文件`() {
        val xml = """
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/music/</d:href>
                <d:propstat><d:prop><d:displayname>music</d:displayname><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat>
              </d:response>
              <d:response>
                <d:href>/music/test%20song.mp3</d:href>
                <d:propstat><d:prop><d:displayname>test song.mp3</d:displayname><d:resourcetype/></d:prop></d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()

        val list = WebdavXmlParser.parseResponses(xml)
        assertEquals(2, list.size)
        assertTrue(list[0].isCollection)
        assertEquals("/music/", list[0].path)
        assertEquals("/music/test song.mp3", list[1].path)
        assertTrue(!list[1].isCollection)
    }
}
