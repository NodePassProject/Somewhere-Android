// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere

import android.app.Application
import eu.nodepass.somewhere.data.NodeRepository
import eu.nodepass.somewhere.data.NodeStore
import kotlinx.coroutines.Dispatchers
import java.io.File

/**
 * The object graph, built by hand.
 *
 * No injection framework: this app has one repository and one dialer, and a
 * container that can be read top to bottom is worth more here than one that
 * generates itself. If the graph grows past a screenful, revisit.
 */
class SomewhereApplication : Application() {
    val nodes: NodeRepository by lazy {
        NodeRepository(
            store = NodeStore(File(filesDir, "nodes/nodes.txt")),
            io = Dispatchers.IO,
        )
    }
}
