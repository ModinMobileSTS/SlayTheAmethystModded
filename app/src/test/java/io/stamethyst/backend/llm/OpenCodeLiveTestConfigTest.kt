package io.stamethyst.backend.llm

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import org.json.JSONObject

class OpenCodeLiveTestConfigTest {
    @Test fun requestedModelUsesDefaultProvidersDeclarationWithoutFallingBackToItsDefaultModel() {
        val config = JSONObject("""{"model":"preferred/gpt-6.1-sol","providers":{
            "preferred":{"models":{"gpt-6.1-sol":{},"gpt-6.1-terra":{}}},
            "other":{"models":{"gpt-6.1-terra":{}}}
        }}""")
        assertEquals("preferred", OpenCodeLiveTestConfig.selectProvider(config, "gpt-6.1-terra"))
        assertEquals("other", OpenCodeLiveTestConfig.selectProvider(config, "gpt-6.1-terra", "other"))
        assertThrows(IllegalStateException::class.java) {
            OpenCodeLiveTestConfig.selectProvider(config, "missing-model")
        }
    }

    @Test fun ambiguousOrUndeclaredExplicitProvidersFailBeforeLookingUpCredentials() {
        val config = JSONObject("""{"provider":{
            "a":{"models":{"gpt-6.1-terra":{}}},"b":{"models":{"gpt-6.1-terra":{}}},
            "wrong":{"models":{"deepseek-v4-flash":{}}}
        }}""")
        assertThrows(IllegalStateException::class.java) {
            OpenCodeLiveTestConfig.selectProvider(config, "gpt-6.1-terra")
        }
        assertThrows(IllegalStateException::class.java) {
            OpenCodeLiveTestConfig.selectProvider(config, "gpt-6.1-terra", "wrong")
        }
        assertEquals("wrong", OpenCodeLiveTestConfig.selectProvider(config, "deepseek-v4-flash"))
    }

    @Test fun databaseLookupUsesOnlyActiveApiCredentialAndDoesNotModifyDatabase() {
        val root = File("../agent-tmp").apply { mkdirs() }
        val directory = Files.createTempDirectory(root.toPath(), "cache-credential-fixture-").toFile()
        try {
            val database = File(directory, "fixture.db")
            val setup = ProcessBuilder("python3", "-c", """
                import sqlite3, json, sys
                db = sqlite3.connect(sys.argv[1])
                db.execute('CREATE TABLE credential (integration_id TEXT, value TEXT, active INTEGER, time_updated INTEGER)')
                db.executemany('INSERT INTO credential VALUES (?, ?, ?, ?)', [
                    ('fixture', json.dumps({'type':'key','key':'synthetic-inactive'}), 0, 3),
                    ('fixture', json.dumps({'type':'key','key':'synthetic-active'}), 1, 2),
                    ('another', json.dumps({'type':'key','key':'synthetic-other'}), 1, 4),
                    ('oauth', json.dumps({'type':'oauth','access':'synthetic-token'}), 1, 5),
                ])
                db.commit()
                db.close()
            """.trimIndent(), database.absolutePath).start()
            assertEquals(0, setup.waitFor())
            val before = database.readBytes()
            assertEquals("synthetic-active", OpenCodeLiveTestConfig.activeDatabaseKey(database, "fixture"))
            assertEquals("", OpenCodeLiveTestConfig.activeDatabaseKey(database, "missing"))
            assertEquals("", OpenCodeLiveTestConfig.activeDatabaseKey(database, "oauth"))
            assertEquals("", OpenCodeLiveTestConfig.activeDatabaseKey(database, "fixture' OR 1=1 --"))
            assertArrayEquals(before, database.readBytes())
        } finally {
            directory.deleteRecursively()
        }
    }
}
