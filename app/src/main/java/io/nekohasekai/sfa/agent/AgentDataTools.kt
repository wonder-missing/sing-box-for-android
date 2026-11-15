package io.nekohasekai.sfa.agent

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * File, diagnostics and transfer tools. They are deliberately generic: they
 * describe capabilities, not one app workflow. The working directory is the
 * read root; [ConductorFileStore.workspaceDir] is the only write root.
 */
class AgentDataTools(
    private val fileStore: ConductorFileStore,
    private val diagnostics: DiagnosticsSource? = null,
    private val taildrop: TaildropPort? = null,
    private val fileSelection: FileSelectionPort? = null,
    private val releaseNotes: ReleaseNotesSource? = null,
    private val vision: VisionPort? = null,
) {
    private val fileList =
        object : AgentTool {
            override val definition =
                AgentToolDefinition(
                    name = "file_list",
                    description =
                        "List files and directories under the app working directory. " +
                            "Paths are relative to that directory; an empty path lists its root. " +
                            "This is the local app directory, not the VFS /vfs root; use the " +
                            "vfs_connect result's visibleRoots for what a container can see.",
                    permission = AgentPermission.READ_FILES,
                    parameters =
                        listOf(
                            AgentParameterSpec(
                                name = "path",
                                type = AgentParameterType.STRING,
                                required = false,
                                description = "Directory path relative to the working directory. Empty for the root.",
                                allowBlank = true,
                            ),
                        ),
                    confirmationPolicy = ConfirmationPolicy.NEVER,
                    category = "files",
                    aliases = listOf("files", "list files", "directory", "dir"),
                )

            override suspend fun execute(
                args: JsonObject,
                cancellation: ToolCancellation,
            ): ToolExecution =
                runCatching {
                    val path = AgentJson.string(args, "path").orEmpty()
                    val entries = fileStore.list(path)
                    buildJsonObject {
                        put("path", path)
                        put(
                            "entries",
                            buildJsonArray {
                                entries.forEach { entry ->
                                    add(
                                        buildJsonObject {
                                            put("path", entry.path)
                                            put("type", if (entry.directory) "directory" else "file")
                                            if (!entry.directory) put("size", entry.size)
                                            put("modifiedAt", entry.modifiedAt)
                                        },
                                    )
                                }
                            },
                        )
                    }
                }.fold(
                    onSuccess = { ToolExecution(output = it) },
                    onFailure = { failure(it) },
                )
        }

    private val fileStat =
        object : AgentTool {
            override val definition =
                AgentToolDefinition(
                    name = "file_stat",
                    description = "Return metadata for a file or directory under the working directory.",
                    permission = AgentPermission.READ_FILES,
                    parameters =
                        listOf(
                            AgentParameterSpec(
                                name = "path",
                                type = AgentParameterType.STRING,
                                required = true,
                                description = "Path relative to the working directory.",
                            ),
                        ),
                    confirmationPolicy = ConfirmationPolicy.NEVER,
                    category = "files",
                    aliases = listOf("stat", "metadata", "file info"),
                )

            override suspend fun execute(
                args: JsonObject,
                cancellation: ToolCancellation,
            ): ToolExecution =
                withPath(args) { path ->
                    ToolExecution(output = fileEntryJson(fileStore.stat(path)))
                }
        }

    private val fileRead =
        object : AgentTool {
            override val definition =
                AgentToolDefinition(
                    name = "file_read",
                    description =
                        "Read a UTF-8 text file under the working directory. Without offset/limit it returns " +
                            "up to max_bytes from the start; with offset/limit it reads that 0-based line range. " +
                            "Binary files return metadata only.",
                    permission = AgentPermission.READ_FILES,
                    parameters =
                        listOf(
                            AgentParameterSpec(
                                name = "path",
                                type = AgentParameterType.STRING,
                                required = true,
                                description = "Path relative to the working directory.",
                            ),
                            AgentParameterSpec(
                                name = "offset",
                                type = AgentParameterType.INTEGER,
                                required = false,
                                description = "0-based line offset. Omit for a byte-range read.",
                            ),
                            AgentParameterSpec(
                                name = "limit",
                                type = AgentParameterType.INTEGER,
                                required = false,
                                description = "Number of lines to read. Defaults to 200; capped at 2000.",
                            ),
                            AgentParameterSpec(
                                name = "max_bytes",
                                type = AgentParameterType.INTEGER,
                                required = false,
                                description = "Maximum bytes to read. Defaults to 65536; capped at 262144.",
                            ),
                        ),
                    confirmationPolicy = ConfirmationPolicy.NEVER,
                    category = "files",
                    aliases = listOf("read file", "cat", "open file"),
                )

            override suspend fun execute(
                args: JsonObject,
                cancellation: ToolCancellation,
            ): ToolExecution {
                val path = AgentJson.string(args, "path") ?: return missingPath()
                val maxBytes = AgentJson.int(args, "max_bytes") ?: ConductorFileStore.DEFAULT_READ_BYTES
                val offset = AgentJson.int(args, "offset")
                val limit = AgentJson.int(args, "limit")
                return runCatching {
                    if (offset != null || limit != null) {
                        val result =
                            fileStore.readLines(
                                path,
                                offset ?: 0,
                                limit ?: ConductorFileStore.DEFAULT_PAGE_LINES,
                                maxBytes,
                            )
                        buildJsonObject {
                            put("path", result.path)
                            put("size", result.size)
                            put("offset", result.offset)
                            put("lineCount", result.lineCount)
                            put("truncated", result.truncated)
                            put("text", AgentSanitizer.scrubForModelRead(result.text))
                        }
                    } else {
                        val result = fileStore.readText(path, maxBytes)
                        buildJsonObject {
                            put("path", result.path)
                            put("size", result.size)
                            put("truncated", result.truncated)
                            put("text", AgentSanitizer.scrubForModelRead(result.text))
                        }
                    }
                }.fold(
                    onSuccess = { ToolExecution(output = it) },
                    onFailure = { failure(it) },
                )
            }
        }

    private val fileTail =
        object : AgentTool {
            override val definition =
                AgentToolDefinition(
                    name = "file_tail",
                    description = "Read the last N lines of a UTF-8 text file under the working directory.",
                    permission = AgentPermission.READ_FILES,
                    parameters =
                        listOf(
                            AgentParameterSpec(
                                name = "path",
                                type = AgentParameterType.STRING,
                                required = true,
                                description = "Path relative to the working directory.",
                            ),
                            AgentParameterSpec(
                                name = "lines",
                                type = AgentParameterType.INTEGER,
                                required = false,
                                description = "Number of trailing lines. Defaults to 50; capped at 2000.",
                            ),
                            AgentParameterSpec(
                                name = "max_bytes",
                                type = AgentParameterType.INTEGER,
                                required = false,
                                description = "Maximum bytes to return. Defaults to 65536; capped at 262144.",
                            ),
                        ),
                    confirmationPolicy = ConfirmationPolicy.NEVER,
                    category = "files",
                    aliases = listOf("tail file", "last lines", "end of file"),
                )

            override suspend fun execute(
                args: JsonObject,
                cancellation: ToolCancellation,
            ): ToolExecution {
                val path = AgentJson.string(args, "path") ?: return missingPath()
                val lines = AgentJson.int(args, "lines") ?: ConductorFileStore.DEFAULT_TAIL_LINES
                val maxBytes = AgentJson.int(args, "max_bytes") ?: ConductorFileStore.DEFAULT_READ_BYTES
                return runCatching {
                    val result = fileStore.tail(path, lines, maxBytes)
                    buildJsonObject {
                        put("path", result.path)
                        put("size", result.size)
                        put("offset", result.offset)
                        put("lineCount", result.lineCount)
                        put("truncated", result.truncated)
                        put("text", AgentSanitizer.scrubForModelRead(result.text))
                    }
                }.fold(
                    onSuccess = { ToolExecution(output = it) },
                    onFailure = { failure(it) },
                )
            }
        }

    private val fileGrep =
        object : AgentTool {
            override val definition =
                AgentToolDefinition(
                    name = "file_grep",
                    description =
                        "Search a UTF-8 text file under the working directory for a literal string or regex. " +
                            "Returns matching line numbers with bounded context. Files larger than 8MB are " +
                            "rejected; use file_read with a line range instead.",
                    permission = AgentPermission.READ_FILES,
                    parameters =
                        listOf(
                            AgentParameterSpec(
                                name = "path",
                                type = AgentParameterType.STRING,
                                required = true,
                                description = "Path relative to the working directory.",
                            ),
                            AgentParameterSpec(
                                name = "query",
                                type = AgentParameterType.STRING,
                                required = true,
                                description = "Literal string or regex to find.",
                            ),
                            AgentParameterSpec(
                                name = "regex",
                                type = AgentParameterType.BOOLEAN,
                                required = false,
                                description = "Treat query as a regex. Defaults to false.",
                            ),
                            AgentParameterSpec(
                                name = "context_lines",
                                type = AgentParameterType.INTEGER,
                                required = false,
                                description = "Lines of context before and after each match. 0-3; default 0.",
                            ),
                            AgentParameterSpec(
                                name = "max_matches",
                                type = AgentParameterType.INTEGER,
                                required = false,
                                description = "Maximum matches. Defaults to 20; capped at 50.",
                            ),
                            AgentParameterSpec(
                                name = "max_bytes",
                                type = AgentParameterType.INTEGER,
                                required = false,
                                description = "Maximum bytes to return. Defaults to 65536; capped at 262144.",
                            ),
                        ),
                    confirmationPolicy = ConfirmationPolicy.NEVER,
                    category = "files",
                    aliases = listOf("grep", "search file", "find in file"),
                )

            override suspend fun execute(
                args: JsonObject,
                cancellation: ToolCancellation,
            ): ToolExecution {
                val path = AgentJson.string(args, "path") ?: return missingPath()
                val query = AgentJson.string(args, "query") ?: return invalid("query is required")
                val regex = AgentJson.boolean(args, "regex") ?: false
                val context = AgentJson.int(args, "context_lines") ?: 0
                val maxMatches = AgentJson.int(args, "max_matches") ?: ConductorFileStore.DEFAULT_GREP_MATCHES
                val maxBytes = AgentJson.int(args, "max_bytes") ?: ConductorFileStore.DEFAULT_READ_BYTES
                return runCatching {
                    val result = fileStore.grep(path, query, regex, context, maxMatches, maxBytes)
                    buildJsonObject {
                        put("path", result.path)
                        put("scannedLines", result.scannedLines)
                        put("truncated", result.truncated)
                        put(
                            "matches",
                            buildJsonArray {
                                result.matches.forEach { match ->
                                    add(
                                        buildJsonObject {
                                            put("line", match.line)
                                            put("text", AgentSanitizer.scrubForModelRead(match.text))
                                        },
                                    )
                                }
                            },
                        )
                    }
                }.fold(
                    onSuccess = { ToolExecution(output = it) },
                    onFailure = { failure(it) },
                )
            }
        }

    private val fileWrite =
        object : AgentTool {
            override val definition =
                AgentToolDefinition(
                    name = "file_write",
                    description =
                        "Create or replace a UTF-8 text file inside the Conductor workspace. " +
                            "The workspace is the only writable area; parent directories are created.",
                    permission = AgentPermission.WRITE_FILES,
                    parameters =
                        listOf(
                            AgentParameterSpec(
                                name = "path",
                                type = AgentParameterType.STRING,
                                required = true,
                                description = "Path relative to the working directory; it must be under conductor/.",
                            ),
                            AgentParameterSpec(
                                name = "text",
                                type = AgentParameterType.STRING,
                                required = true,
                                description = "UTF-8 text content.",
                                allowBlank = true,
                            ),
                            AgentParameterSpec(
                                name = "overwrite",
                                type = AgentParameterType.BOOLEAN,
                                required = false,
                                description = "Replace an existing file. Defaults to false.",
                            ),
                        ),
                    confirmationPolicy = ConfirmationPolicy.AGENT_ONLY,
                    category = "files",
                    aliases = listOf("write file", "create file", "save file"),
                    confirmationReason = "This writes a file inside the Conductor workspace.",
                )

            override suspend fun execute(
                args: JsonObject,
                cancellation: ToolCancellation,
            ): ToolExecution {
                val path = AgentJson.string(args, "path") ?: return missingPath()
                val text = AgentJson.string(args, "text") ?: ""
                val overwrite = AgentJson.boolean(args, "overwrite") ?: false
                return runCatching {
                    val entry = fileStore.writeText(path, text, overwrite)
                    ToolExecution(
                        output =
                            buildJsonObject {
                                put("path", entry.path)
                                put("size", entry.size)
                                put("sha256", fileStore.sha256(fileStore.resolveRead(entry.path)))
                            },
                    )
                }.getOrElse { failure(it) }
            }
        }

    private val fileMkdir =
        object : AgentTool {
            override val definition =
                AgentToolDefinition(
                    name = "file_mkdir",
                    description = "Create a directory inside the Conductor workspace.",
                    permission = AgentPermission.WRITE_FILES,
                    parameters =
                        listOf(
                            AgentParameterSpec(
                                name = "path",
                                type = AgentParameterType.STRING,
                                required = true,
                                description = "Directory path relative to the working directory; it must be under conductor/.",
                            ),
                        ),
                    confirmationPolicy = ConfirmationPolicy.AGENT_ONLY,
                    category = "files",
                    aliases = listOf("mkdir", "create directory"),
                    confirmationReason = "This creates a directory inside the Conductor workspace.",
                )

            override suspend fun execute(
                args: JsonObject,
                cancellation: ToolCancellation,
            ): ToolExecution =
                withPath(args) { path ->
                    ToolExecution(output = fileEntryJson(fileStore.mkdir(path)))
                }
        }

    private val fileDelete =
        object : AgentTool {
            override val definition =
                AgentToolDefinition(
                    name = "file_delete",
                    description = "Delete a file or directory inside the Conductor workspace.",
                    permission = AgentPermission.WRITE_FILES,
                    parameters =
                        listOf(
                            AgentParameterSpec(
                                name = "path",
                                type = AgentParameterType.STRING,
                                required = true,
                                description = "Path relative to the working directory; it must be under conductor/.",
                            ),
                        ),
                    confirmationPolicy = ConfirmationPolicy.AGENT_ONLY,
                    category = "files",
                    aliases = listOf("delete file", "remove"),
                    confirmationReason = "This deletes an item inside the Conductor workspace.",
                )

            override suspend fun execute(
                args: JsonObject,
                cancellation: ToolCancellation,
            ): ToolExecution =
                withPath(args) { path ->
                    val deleted = fileStore.delete(path)
                    ToolExecution(output = fileEntryJson(deleted))
                }
        }

    private val diagnosticsExport =
        object : AgentTool {
            override val definition =
                AgentToolDefinition(
                    name = "diagnostics_export",
                    description =
                        "Write a diagnostics artifact to the Conductor workspace. " +
                            "Supported kinds: conversation (the current chat transcript) and trace (the API request trace). " +
                            "Artifacts are Markdown; the file name is normalized to .md. " +
                            "For trace, tail keeps only the last N requests; since_minutes keeps only entries from the last N minutes. " +
                            "Returns the file path, size, sha256, entry count and the covered time range.",
                    permission = AgentPermission.READ_FILES,
                    parameters =
                        listOf(
                            AgentParameterSpec(
                                name = "kind",
                                type = AgentParameterType.STRING,
                                required = true,
                                description = "Artifact kind: conversation or trace.",
                            ),
                            AgentParameterSpec(
                                name = "name",
                                type = AgentParameterType.STRING,
                                required = false,
                                description = "Optional file name. Defaults to a timestamped name.",
                                allowBlank = true,
                            ),
                            AgentParameterSpec(
                                name = "overwrite",
                                type = AgentParameterType.BOOLEAN,
                                required = false,
                                description = "Overwrite an existing workspace file. Defaults to false.",
                            ),
                            AgentParameterSpec(
                                name = "tail",
                                type = AgentParameterType.INTEGER,
                                required = false,
                                description = "For kind=trace, keep only the last N requests (1-20).",
                            ),
                            AgentParameterSpec(
                                name = "since_minutes",
                                type = AgentParameterType.INTEGER,
                                required = false,
                                description =
                                    "For kind=trace, keep only entries from the last N minutes (1-1440). " +
                                        "The result reports missingBefore when older bodies were already dropped.",
                            ),
                        ),
                    confirmationPolicy = ConfirmationPolicy.AGENT_ONLY,
                    category = "diagnostics",
                    aliases = listOf("export trace", "export conversation", "diagnostics"),
                    confirmationReason = "This reads the current conversation or API trace and writes it to the workspace.",
                )

            override suspend fun execute(
                args: JsonObject,
                cancellation: ToolCancellation,
            ): ToolExecution {
                val source = diagnostics ?: return unavailable()
                val kind = AgentJson.string(args, "kind")?.trim()?.lowercase(Locale.US)
                    ?: return invalid("kind is required")
                val tail = AgentJson.int(args, "tail")
                val sinceMinutes = AgentJson.int(args, "since_minutes")
                if (tail != null && tail !in 1..20) return invalid("tail must be between 1 and 20")
                if (sinceMinutes != null && sinceMinutes !in 1..1440) {
                    return invalid("since_minutes must be between 1 and 1440")
                }
                if (kind != "trace" && (tail != null || sinceMinutes != null)) {
                    return invalid("tail and since_minutes are only supported for trace")
                }
                val traceExport =
                    if (kind == "trace") {
                        source.traceMarkdown(
                            tail,
                            sinceMinutes?.let { System.currentTimeMillis() - it * 60_000L },
                        )
                    } else {
                        null
                    }
                val text = traceExport?.text ?: source.conversationMarkdown()
                val requested = AgentJson.string(args, "name")?.trim().orEmpty()
                val overwrite = AgentJson.boolean(args, "overwrite") ?: false
                return runCatching {
                    val name =
                        if (requested.isNotEmpty()) {
                            markdownName(sanitizeName(requested))
                        } else {
                            uniqueName("$kind-", "md")
                        }
                    val path = "conductor/$name"
                    val entry = fileStore.writeText(path, AgentSanitizer.scrubForModelRead(text), overwrite = overwrite)
                    ToolExecution(
                        output =
                            buildJsonObject {
                                put("path", entry.path)
                                put("size", entry.size)
                                put("sha256", fileStore.sha256(fileStore.resolveRead(entry.path)))
                                traceExport?.let { export ->
                                    put("entries", export.count)
                                    export.firstTimestamp?.let { put("rangeStart", formatTraceTime(it)) }
                                    export.lastTimestamp?.let { put("rangeEnd", formatTraceTime(it)) }
                                    export.availableFrom?.let { put("availableFrom", formatTraceTime(it)) }
                                    put("missingBefore", export.missingBefore)
                                    if (export.missingBefore) {
                                        put(
                                            "message",
                                            if (export.availableFrom != null) {
                                                "Full request bodies start at ${formatTraceTime(export.availableFrom)}; " +
                                                    "earlier entries exist only as request-history summaries."
                                            } else {
                                                "Full request/response bodies are not retained; " +
                                                    "earlier entries exist only as request-history summaries."
                                            },
                                        )
                                    }
                                }
                            },
                    )
                }.getOrElse { failure(it) }
            }
        }

    private val taildropPeers =
        object : AgentTool {
            override val definition =
                AgentToolDefinition(
                    name = "taildrop_peers",
                    description =
                        "List Tailscale peers that can receive files, with online state and last seen. " +
                            "Use this before taildrop_send to find the peer name.",
                    permission = AgentPermission.FILE_TRANSFER,
                    parameters = emptyList(),
                    confirmationPolicy = ConfirmationPolicy.NEVER,
                    category = "transfer",
                    core = true,
                    aliases = listOf("peers", "devices", "taildrop", "deliver"),
                )

            override suspend fun execute(
                args: JsonObject,
                cancellation: ToolCancellation,
            ): ToolExecution {
                val port = taildrop ?: return unavailable()
                return runCatching {
                    val peers = port.peers()
                    ToolExecution(
                        output =
                            buildJsonObject {
                                put(
                                    "peers",
                                    buildJsonArray {
                                        peers.forEach { peer ->
                                            add(
                                                buildJsonObject {
                                                    put("name", peer.name)
                                                    put("online", peer.online)
                                                    peer.lastSeen?.let { put("lastSeen", it) }
                                                },
                                            )
                                        }
                                    },
                                )
                            },
                    )
                }.getOrElse { failure(it) }
            }
        }

    private val taildropSend =
        object : AgentTool {
            override val definition =
                AgentToolDefinition(
                    name = "taildrop_send",
                    description =
                        "Send files from the working directory to a Tailscale peer's Taildrop inbox. Success means " +
                            "the peer's Tailscale daemon received the file into its inbox; it does not mean the file " +
                            "was saved to the peer's disk or opened. The peer must save it from the Taildrop inbox. " +
                            "Use taildrop_peers for the peer name; the peer must be online. Requires confirmation.",
                    permission = AgentPermission.DATA_EGRESS,
                    parameters =
                        listOf(
                            AgentParameterSpec(
                                name = "peer",
                                type = AgentParameterType.STRING,
                                required = true,
                                description = "Peer name from taildrop_peers or tailscale_status.",
                            ),
                            AgentParameterSpec(
                                name = "files",
                                type = AgentParameterType.STRING_ARRAY,
                                required = true,
                                description = "File paths relative to the working directory.",
                            ),
                        ),
                    confirmationPolicy = ConfirmationPolicy.ALWAYS,
                    category = "transfer",
                    core = true,
                    aliases = listOf("send files", "taildrop", "transfer", "deliver", "send to peer", "send to device"),
                    confirmationReason = "This sends the selected files from this device to a Tailscale peer.",
                )

            override suspend fun execute(
                args: JsonObject,
                cancellation: ToolCancellation,
            ): ToolExecution {
                val port = taildrop ?: return unavailable()
                val peer = AgentJson.string(args, "peer")?.trim() ?: return invalid("peer is required")
                val paths =
                    AgentJson.stringList(args, "files")
                        ?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }
                        .orEmpty()
                if (paths.isEmpty()) return invalid("files must contain at least one path")
                val resolved = mutableListOf<File>()
                for (path in paths) {
                    val file =
                        try {
                            fileStore.resolveRead(path)
                        } catch (e: Exception) {
                            return failure(e)
                        }
                    if (!file.isFile) return invalid("Not a file: $path")
                    resolved += file
                }
                val total = resolved.sumOf { it.length() }
                if (total > MAX_SEND_BYTES) {
                    return invalid("Total size is larger than $MAX_SEND_BYTES bytes.")
                }
                return try {
                    val result = port.send(peer, resolved, cancellation)
                    val output = taildropOutput(peer, resolved.map { it.name }, result)
                    if (result.ok) {
                        ToolExecution(output = output)
                    } else {
                        ToolExecution(
                            output = output,
                            errorCode = "tool_failed",
                            errorMessage = result.message ?: "Taildrop send failed.",
                        )
                    }
                } catch (e: Exception) {
                    failure(e)
                }
            }
        }

    private val requestFiles =
        object : AgentTool {
            override val definition =
                AgentToolDefinition(
                    name = "request_files",
                    description =
                        "Ask the user to choose files with the system file picker. " +
                            "Returns file names, sizes and opaque temporary handles; it does not read, copy or modify " +
                            "the files. Handles work only while the app session lasts. " +
                            "Images and other binary files can be selected, but file_read_selected only reads UTF-8 text.",
                    permission = AgentPermission.READ_FILES,
                    parameters =
                        listOf(
                            AgentParameterSpec(
                                name = "reason",
                                type = AgentParameterType.STRING,
                                required = false,
                                description = "Short explanation shown to the user. A generic reason is used when omitted.",
                            ),
                            AgentParameterSpec(
                                name = "multiple",
                                type = AgentParameterType.BOOLEAN,
                                required = false,
                                description = "Allow selecting more than one file. Defaults to true.",
                            ),
                            AgentParameterSpec(
                                name = "mime_types",
                                type = AgentParameterType.STRING_ARRAY,
                                required = false,
                                description = "Optional MIME filters, e.g. [\"text/*\", \"application/json\"]. Defaults to */*.",
                            ),
                        ),
                    confirmationPolicy = ConfirmationPolicy.NEVER,
                    category = "files",
                    aliases = listOf("request files", "choose files", "attach files"),
                    timeoutMs = FILE_PICK_TIMEOUT_MS,
                )

            override suspend fun execute(
                args: JsonObject,
                cancellation: ToolCancellation,
            ): ToolExecution {
                val port = fileSelection ?: return unavailable()
                val reason =
                    AgentJson.string(args, "reason")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: "The assistant needs a file from this device."
                val multiple = AgentJson.boolean(args, "multiple") ?: true
                val mimeTypes =
                    AgentJson.stringList(args, "mime_types")
                        ?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }
                        ?.takeIf { it.isNotEmpty() }
                return try {
                    val result = port.request(reason, multiple, mimeTypes, cancellation)
                    ToolExecution(
                        output =
                            buildJsonObject {
                                put("cancelled", result.cancelled)
                                put("grant", "temporary")
                                result.message?.let { put("message", it) }
                                put(
                                    "files",
                                    buildJsonArray {
                                        result.files.forEach { file ->
                                            add(
                                                buildJsonObject {
                                                    put("id", file.id)
                                                    put("name", file.name)
                                                    put("size", file.size)
                                                    file.mimeType?.let { put("mime", it) }
                                                },
                                            )
                                        }
                                    },
                                )
                            },
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failure(e)
                }
            }
        }

    private val fileReadSelected =
        object : AgentTool {
            override val definition =
                AgentToolDefinition(
                    name = "file_read_selected",
                    description =
                        "Read a UTF-8 text file the user selected in this session. " +
                            "The handle comes from request_files and goes in the `id` parameter. Images, PDFs and " +
                            "other binary files are rejected; use analyze_image for images.",
                    permission = AgentPermission.READ_FILES,
                    parameters =
                        listOf(
                            AgentParameterSpec(
                                name = "id",
                                type = AgentParameterType.STRING,
                                required = true,
                                description = "Handle returned by request_files.",
                            ),
                            AgentParameterSpec(
                                name = "max_bytes",
                                type = AgentParameterType.INTEGER,
                                required = false,
                                description = "Maximum bytes to read. Defaults to 65536; capped at 262144.",
                            ),
                        ),
                    confirmationPolicy = ConfirmationPolicy.NEVER,
                    category = "files",
                    aliases = listOf("read selected file", "open selected file"),
                )

            override suspend fun execute(
                args: JsonObject,
                cancellation: ToolCancellation,
            ): ToolExecution {
                val port = fileSelection ?: return unavailable()
                val id = AgentJson.string(args, "id") ?: return invalid("id is required")
                val maxBytes = AgentJson.int(args, "max_bytes") ?: ConductorFileStore.DEFAULT_READ_BYTES
                return runCatching {
                    val result = port.read(id, maxBytes)
                    buildJsonObject {
                        put("id", result.id)
                        put("name", result.name)
                        put("size", result.size)
                        put("truncated", result.truncated)
                        put("text", AgentSanitizer.scrubForModelRead(result.text))
                    }
                }.fold(
                    onSuccess = { ToolExecution(output = it) },
                    onFailure = { failure(it) },
                )
            }
        }

    private val taildropSendSelected =
        object : AgentTool {
            override val definition =
                AgentToolDefinition(
                    name = "taildrop_send_selected",
                    description =
                        "Send files the user selected in this session to a Tailscale peer's Taildrop inbox. Success " +
                            "means the peer's Tailscale daemon received the file into its inbox; it does not mean " +
                            "the file was saved to the peer's disk or opened. The peer must save it from the " +
                            "Taildrop inbox. The peer must be online. Requires confirmation.",
                    permission = AgentPermission.DATA_EGRESS,
                    parameters =
                        listOf(
                            AgentParameterSpec(
                                name = "peer",
                                type = AgentParameterType.STRING,
                                required = true,
                                description = "Peer name from taildrop_peers or tailscale_status.",
                            ),
                            AgentParameterSpec(
                                name = "ids",
                                type = AgentParameterType.STRING_ARRAY,
                                required = true,
                                description = "Handles returned by request_files.",
                            ),
                        ),
                    confirmationPolicy = ConfirmationPolicy.ALWAYS,
                    category = "transfer",
                    aliases = listOf("send selected files", "send attachments", "taildrop selected"),
                    confirmationReason = "This sends the user-selected files from this device to a Tailscale peer.",
                )

            override suspend fun execute(
                args: JsonObject,
                cancellation: ToolCancellation,
            ): ToolExecution {
                val port = taildrop ?: return unavailable()
                val peer = AgentJson.string(args, "peer")?.trim() ?: return invalid("peer is required")
                val ids =
                    AgentJson.stringList(args, "ids")
                        ?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }
                        .orEmpty()
                if (ids.isEmpty()) return invalid("ids must contain at least one handle")
                return try {
                    val result = port.sendSelected(peer, ids, cancellation)
                    val output = taildropOutput(peer, result.files, result)
                    if (result.ok) {
                        ToolExecution(output = output)
                    } else {
                        ToolExecution(
                            output = output,
                            errorCode = "tool_failed",
                            errorMessage = result.message ?: "Taildrop send failed.",
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failure(e)
                }
            }
        }

    private val analyzeImageTool =
        object : AgentTool {
            override val definition =
                AgentToolDefinition(
                    name = "analyze_image",
                    description =
                        "Ask the user to pick one image from the gallery, send it to the configured vision-capable " +
                            "model, and return the text analysis. Use this for screenshots or photos. The image leaves " +
                            "the device for the provider; file_read_selected cannot read images.",
                    permission = AgentPermission.DATA_EGRESS,
                    parameters =
                        listOf(
                            AgentParameterSpec(
                                name = "question",
                                type = AgentParameterType.STRING,
                                required = true,
                                description = "What to ask about the image. Be specific.",
                            ),
                        ),
                    confirmationPolicy = ConfirmationPolicy.NEVER,
                    category = "vision",
                    core = true,
                    aliases = listOf("image", "photo", "screenshot", "vision", "ocr", "read image"),
                    confirmationReason = "The selected image will be sent to the configured model provider for analysis.",
                    timeoutMs = IMAGE_ANALYSIS_TIMEOUT_MS,
                )

            override suspend fun execute(
                args: JsonObject,
                cancellation: ToolCancellation,
            ): ToolExecution {
                val picker = fileSelection ?: return unavailable()
                val analyzer = vision ?: return unavailable()
                val question = AgentJson.string(args, "question")?.trim()
                if (question.isNullOrEmpty()) return invalid("question is required")
                return try {
                    val selection =
                        picker.request(
                            reason = "Pick one image to analyze. It will be sent to the configured model provider.",
                            multiple = false,
                            mimeTypes = listOf("image/*"),
                            cancellation = cancellation,
                        )
                    if (selection.cancelled || selection.files.isEmpty()) {
                        return ToolExecution(
                            output =
                                buildJsonObject {
                                    put("cancelled", true)
                                    selection.message?.let { put("message", it) }
                                },
                        )
                    }
                    val file = selection.files.first()
                    val image = picker.readBytes(file.id, MAX_IMAGE_BYTES)
                    val analysis = analyzer.analyze(image.bytes, image.mimeType, question)
                    ToolExecution(
                        output =
                            buildJsonObject {
                                put("file", file.name)
                                put("mime", image.mimeType)
                                put("analysis", analysis.take(12_000))
                            },
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failure(e)
                }
            }
        }

    private val releaseNotesTool =
        object : AgentTool {
            override val definition =
                AgentToolDefinition(
                    name = "release_notes",
                    description =
                        "Read the release notes for the installed WonderSing version. Use this when the user asks " +
                            "what changed, what is new in this version, or wants to try a new feature. Returns the " +
                            "installed version, tag and the public release notes text.",
                    permission = AgentPermission.READ_NETWORK_STATE,
                    parameters = emptyList(),
                    confirmationPolicy = ConfirmationPolicy.NEVER,
                    category = "about",
                    core = true,
                    aliases = listOf("release notes", "changelog", "what's new", "new features", "version changes"),
                )

            override suspend fun execute(
                args: JsonObject,
                cancellation: ToolCancellation,
            ): ToolExecution {
                val source = releaseNotes ?: return unavailable()
                return try {
                    val notes = source.current()
                    ToolExecution(
                        output =
                            buildJsonObject {
                                put("version", notes.version)
                                put("tag", notes.tag)
                                put("notes", notes.notes)
                            },
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failure(e)
                }
            }
        }

    val all: List<AgentTool>
        get() =
            buildList {
                add(fileList)
                add(fileStat)
                add(fileRead)
                add(fileTail)
                add(fileGrep)
                add(fileWrite)
                add(fileMkdir)
                add(fileDelete)
                if (diagnostics != null) add(diagnosticsExport)
                if (releaseNotes != null) add(releaseNotesTool)
                if (taildrop != null) {
                    add(taildropPeers)
                    add(taildropSend)
                }
                if (fileSelection != null) {
                    add(requestFiles)
                    add(fileReadSelected)
                    if (taildrop != null) add(taildropSendSelected)
                    if (vision != null) add(analyzeImageTool)
                }
            }

    private fun withPath(
        args: JsonObject,
        block: (String) -> ToolExecution,
    ): ToolExecution {
        val path = AgentJson.string(args, "path") ?: return missingPath()
        return runCatching { block(path) }.getOrElse { failure(it) }
    }

    private fun fileEntryJson(entry: StoredFile): JsonObject =
        buildJsonObject {
            put("path", entry.path)
            put("type", if (entry.directory) "directory" else "file")
            if (!entry.directory) put("size", entry.size)
            put("modifiedAt", entry.modifiedAt)
        }

    private fun uniqueName(
        prefix: String,
        extension: String,
    ): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        var candidate = "$prefix$stamp.$extension"
        var index = 2
        while (File(fileStore.workspaceDir, candidate).exists()) {
            candidate = "$prefix$stamp-$index.$extension"
            index += 1
        }
        return candidate
    }

    private fun taildropOutput(
        peer: String,
        files: List<String>,
        result: TaildropSendResult,
    ): JsonObject =
        buildJsonObject {
            put("peer", peer)
            put(
                "files",
                buildJsonArray { files.forEach { add(it) } },
            )
            put("ok", result.ok)
            if (result.ok) {
                put("delivery", "peer_inbox")
                put("receiverActionRequired", true)
                put(
                    "note",
                    "The peer's Tailscale daemon received the file into its Taildrop inbox. " +
                        "It is not saved to the peer's disk until the receiver saves it, for example with " +
                        "`tailscale file get` or the Taildrop inbox UI.",
                )
            }
            result.message?.let { put("message", it) }
        }

    private fun sanitizeName(raw: String): String {
        val name = File(raw).name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
        if (name.isBlank() || name == "." || name == "..") throw IllegalArgumentException("Invalid file name.")
        return name
    }

    /** Diagnostics artifacts are Markdown, so the requested extension is corrected rather than trusted. */
    private fun markdownName(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return "$name.md"
        if (name.substring(dot + 1).equals("md", ignoreCase = true)) return name
        return name.substring(0, dot) + ".md"
    }

    private fun formatTraceTime(epochMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(epochMs))

    private fun missingPath(): ToolExecution = invalid("path is required")

    private fun invalid(message: String): ToolExecution = ToolExecution(errorCode = "invalid_parameter", errorMessage = message)

    private fun unavailable(): ToolExecution =
        ToolExecution(errorCode = "tool_unavailable", errorMessage = "this tool is not available in the current build")

    private fun failure(error: Throwable): ToolExecution =
        ToolExecution(
            errorCode = "tool_failed",
            errorMessage = error.message ?: error.toString(),
        )

    companion object {
        private const val MAX_SEND_BYTES = 8L * 1024 * 1024
        private const val MAX_IMAGE_BYTES = 5 * 1024 * 1024
        private const val FILE_PICK_TIMEOUT_MS = 10L * 60L * 1000L
        private const val IMAGE_ANALYSIS_TIMEOUT_MS = 5L * 60L * 1000L
    }
}
