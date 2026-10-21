// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BackupTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val node = "nowhere://0123456789abcdef0123456789abcdef@fra.example.net:443#Frankfurt"

    private fun device(name: String) = folder.newFolder(name)

    @Test
    fun `everything kept comes back on another device`() {
        val from = device("from")
        File(from, "nodes").mkdirs()
        File(from, "nodes/nodes.txt").writeText("$node\n")
        File(from, "subscription").mkdirs()
        File(from, "subscription/subscription.txt").writeText("url=https://dash.example/sub?token=x\nid=0000000a\n")
        File(from, "settings").mkdirs()
        File(from, "settings/app.txt").writeText("theme=Dark\n")
        File(from, "settings/launch-guard.txt").writeText("2")

        val text = Backup.export(from, "0.1.0")
        val read = Backup.read(text.toByteArray())
        assertTrue(read is Backup.Read.Ok)

        val to = device("to")
        assertTrue(Backup.restore(to, (read as Backup.Read.Ok).files))
        assertEquals("$node\n", File(to, "nodes/nodes.txt").readText())
        assertEquals("theme=Dark\n", File(to, "settings/app.txt").readText())
        assertFalse("the crash counter describes a device, not the user's setup", File(to, "settings/launch-guard.txt").exists())
        // And the stores read it.
        assertEquals(
            "fra.example.net",
            NodeStore(File(to, "nodes/nodes.txt"))
                .load()
                .single()
                .url.host,
        )
        assertEquals(ThemeMode.Dark, AppSettingsStore(File(to, "settings/app.txt")).settings.value.theme)
    }

    @Test
    fun `restoring replaces, including what the backup did not have`() {
        val to = device("to")
        File(to, "subscription").mkdirs()
        File(to, "subscription/subscription.txt").writeText("url=https://old.example/sub?token=old\n")
        Backup.restore(to, mapOf("nodes/nodes.txt" to "$node\n"))
        assertFalse("an old credential must not survive a restore", File(to, "subscription/subscription.txt").exists())
    }

    @Test
    fun `a backup cannot write outside the stores`() {
        val text = """{"format":"somewhere-backup","version":1,"files":{"../../evil":"x","nodes/nodes.txt":"$node"}}"""
        val read = Backup.read(text.toByteArray()) as Backup.Read.Ok
        assertEquals(setOf("nodes/nodes.txt"), read.files.keys)
    }

    @Test
    fun `wrong files are refused with a reason`() {
        assertEquals(Backup.Read.Refused(Backup.Refusal.NotABackup), Backup.read("not json".toByteArray()))
        assertEquals(Backup.Read.Refused(Backup.Refusal.NotABackup), Backup.read("""{"format":"other","version":1}""".toByteArray()))
        assertEquals(
            Backup.Read.Refused(Backup.Refusal.FromNewerVersion),
            Backup.read("""{"format":"somewhere-backup","version":99,"files":{}}""".toByteArray()),
        )
        assertEquals(
            Backup.Read.Refused(Backup.Refusal.Empty),
            Backup.read("""{"format":"somewhere-backup","version":1,"files":{"x":"y"}}""".toByteArray()),
        )
        assertEquals(Backup.Read.Refused(Backup.Refusal.TooLarge), Backup.read(ByteArray(Backup.MAX_BYTES + 1)))
    }
}
