package io.stamethyst.backend.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import kotlin.io.path.createTempDirectory

class AgentWorkspaceImageReadTest {
    @Test
    fun readsABitmapAsAnAttachmentInsteadOfBase64Text() {
        val workspace = createTempDirectory("agent-image-read").toFile().apply {
            resolve("source/screenshot.png").apply { parentFile.mkdirs(); writeBytes(Base64.getDecoder().decode(ONE_PIXEL_PNG_BASE64)) }
        }
        val tool = AgentWorkspaceFileTool(workspace)

        val output = tool.executeWithMedia("""{"path":"/source/screenshot.png"}""")

        assertEquals(1, output.attachments.size)
        val image = output.attachments.single()
        assertEquals("screenshot.png", image.name)
        assertEquals("image/png", image.mime)
        assertEquals(ONE_PIXEL_PNG_BASE64, image.data)
        val summary = Json.parseToJsonElement(output.text).jsonObject
        assertEquals("image", summary["type"]!!.jsonPrimitive.content)
        assertEquals("/source/screenshot.png", summary["path"]!!.jsonPrimitive.content)
        assertEquals("image/png", summary["mime"]!!.jsonPrimitive.content)
        // The text result must not embed the payload; the image part carries it.
        assertEquals(false, output.text.contains(ONE_PIXEL_PNG_BASE64.take(24)))
    }

    @Test
    fun explicitBase64EncodingStillReturnsCopyableText() {
        val workspace = createTempDirectory("agent-image-base64").toFile().apply {
            resolve("source/screenshot.png").apply { parentFile.mkdirs(); writeBytes(Base64.getDecoder().decode(ONE_PIXEL_PNG_BASE64)) }
        }

        val output = AgentWorkspaceFileTool(workspace)
            .executeWithMedia("""{"path":"source/screenshot.png","encoding":"base64"}""")

        assertTrue(output.attachments.isEmpty())
        assertEquals(ONE_PIXEL_PNG_BASE64, Json.parseToJsonElement(output.text).jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun textFilesAreUnaffected() {
        val workspace = createTempDirectory("agent-image-text").toFile().apply {
            resolve("source/note.txt").apply { parentFile.mkdirs(); writeText("artifact validation passed") }
        }

        val output = AgentWorkspaceFileTool(workspace).executeWithMedia("""{"path":"/source/note.txt"}""")

        assertTrue(output.attachments.isEmpty())
        assertTrue(output.text.contains("artifact validation passed"))
        assertNotNull(output.text)
    }

    private companion object {
        const val ONE_PIXEL_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
    }
}
