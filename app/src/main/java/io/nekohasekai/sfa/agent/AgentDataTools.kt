package io.nekohasekai.sfa.agent

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.net.URLConnection
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
    private val chatImage: ((String) -> AgentImageData?)? = null,
    private val planStore: PlanStore? = null,
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
                            "up to max_bytes from the start; with offset/limit it reads that 0-based line range; " +
                            "with last_lines it reads the end of the file. last_lines wins over offset/limit. " +
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
                                name = "last_lines",
                                type = AgentParameterType.INTEGER,
                                required = false,
                                description =
                                    "Read the last N lines instead of a start/offset range. Capped at 2000.",
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
                val lastLines = AgentJson.int(args, "last_lines")
                return runCatching {
                    if (lastLines != null) {
                        val result = fileStore.tail(path, lastLines, maxBytes)
                        buildJsonObject {
                            put("path", result.path)
                            put("size", result.size)
                            put("sha256", fileStore.sha256(fileStore.resolveRead(result.path)))
                            put("offset", result.offset)
                            put("lineCount", result.lineCount)
                            put("truncated", result.truncated)
                            put("text", AgentSanitizer.scrubForModelRead(result.text))
                        }
                    } else if (offset != null || limit != null) {
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
                            put("sha256", fileStore.sha256(fileStore.resolveRead(result.path)))
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
                            put("sha256", fileStore.sha256(fileStore.resolveRead(result.path)))
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
                            AgentParameterSpec(
                                name = "max_line_chars",
                                type = AgentParameterType.INTEGER,
                                required = false,
                                description = "Maximum characters returned per matching line. Defaults to 400; capped at 2000.",
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
                val maxLineChars =
                    AgentJson.int(args, "max_line_chars") ?: ConductorFileStore.DEFAULT_GREP_LINE_CHARS
                return runCatching {
                    val result =
                        fileStore.grep(
                            path = path,
                            query = query,
                            regex = regex,
                            contextLines = context,
                            maxMatches = maxMatches,
                            maxBytes = maxBytes,
                            maxLineChars = maxLineChars,
                        )
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
                                            if (match.truncated) put("lineTruncated", true)
                                        },
                                    )
                                }
                            },
                        )
                        if (result.matches.isEmpty() && !regex && hasRegexMeta(query)) {
                            put(
                                "hint",
                                "No literal match. The query contains regex metacharacters; " +
                                    "pass regex=true to treat it as a regex.",
                            )
                        }
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
                    confirmationKind = ConfirmationKind.WORKSPACE_WRITE,
                    bulkApproval = BulkApproval.TURN,
                    approvalScope = "workspace_write",
                )

            override suspend fun preflight(args: JsonObject): ToolExecution? =
                preflightPath(args) { path ->
                    fileStore.preflightWrite(path, AgentJson.boolean(args, "overwrite") ?: false)
                }

            override fun confirmationHint(args: JsonObject): ConfirmationHint? {
                val path = AgentJson.string(args, "path") ?: return null
                val exists = runCatching { !fileStore.stat(path).directory }.getOrDefault(false)
                return if (exists && !fileStore.isSessionCreated(path)) {
                    ConfirmationHint(
                        kind = ConfirmationKind.WORKSPACE_OVERWRITE,
                        bulkApproval = BulkApproval.NONE,
                        approvalScope = "workspace_overwrite",
                    )
                } else {
                    null
                }
            }

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

    private val fileAppend =
        object : AgentTool {
            override val definition =
                AgentToolDefinition(
                    name = "file_append",
                    description =
                        "Append UTF-8 text to a file inside the Conductor workspace, creating it when absent. " +
                            "Use it for logs, notes and incremental output instead of reading and rewriting the file. " +
                            "The workspace is the only writable area.",
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
                                description = "UTF-8 text to append. An empty string is allowed.",
                                allowBlank = true,
                            ),
                        ),
                    confirmationPolicy = ConfirmationPolicy.AGENT_ONLY,
                    category = "files",
                    aliases = listOf("append file", "append text", "add to file"),
                    confirmationReason = "This appends text to a file inside the Conductor workspace.",
                    confirmationKind = ConfirmationKind.WORKSPACE_WRITE,
                    bulkApproval = BulkApproval.TURN,
                    approvalScope = "workspace_write",
                )

            override suspend fun preflight(args: JsonObject): ToolExecution? =
                preflightPath(args) { path -> fileStore.preflightAppend(path) }

            override fun confirmationHint(args: JsonObject): ConfirmationHint? {
                val path = AgentJson.string(args, "path") ?: return null
                val exists = runCatching { !fileStore.stat(path).directory }.getOrDefault(false)
                return if (exists && !fileStore.isSessionCreated(path)) {
                    ConfirmationHint(
                        kind = ConfirmationKind.WORKSPACE_OVERWRITE,
                        bulkApproval = BulkApproval.NONE,
                        approvalScope = "workspace_overwrite",
                    )
                } else {
                    null
                }
            }

            override suspend fun execute(
                args: JsonObject,
                cancellation: ToolCancellation,
            ): ToolExecution {
                val path = AgentJson.string(args, "path") ?: return missingPath()
                val text = AgentJson.string(args, "text") ?: ""
                return runCatching {
                    val existed = runCatching { fileStore.resolveRead(path).exists() }.getOrDefault(false)
                    val entry = fileStore.appendText(path, text)
                    ToolExecution(
                        output =
                            buildJsonObject {
                                put("path", entry.path)
                                put("created", !existed)
                                put("appendedBytes", text.toByteArray(Charsets.UTF_8).size)
                                put("size", entry.size)
                                put("sha256", fileStore.sha256(fileStore.resolveRead(entry.path)))
                            },
                    )
                }.getOrElse { failure(it) }
            }
        }

    private val fileEdit =
        object : AgentTool {
            override val definition =
                AgentToolDefinition(
                    name = "file_edit",
                    description =
                        "Apply one or more exact text edits to a UTF-8 file inside the Conductor workspace. " +
                            "Each old_text must occur exactly once unless replace_all is true. " +
                            "Edits apply in order and all-or-nothing; a missing or ambiguous match writes nothing. " +
                            "Use file_read first to get the current text and sha256.",
                    permission = AgentPermission.WRITE_FILES,
                    parameters = emptyList(),
                    confirmationPolicy = ConfirmationPolicy.AGENT_ONLY,
                    category = "files",
                    aliases = listOf("patch", "edit file", "replace in file", "apply patch"),
                    passthroughArguments = true,
                    parametersSchemaOverride =
                        buildJsonObject {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("path") {
                                    put("type", "string")
                                    put("description", "Path relative to the working directory; it must be under conductor/.")
                                }
                                putJsonObject("edits") {
                                    put("type", "array")
                                    put("minItems", 1)
                                    put("maxItems", 20)
                                    putJsonObject("items") {
                                        put("type", "object")
                                        putJsonObject("properties") {
                                            putJsonObject("old_text") {
                                                put("type", "string")
                                                put("description", "Exact text to find. Must not be empty.")
                                            }
                                            putJsonObject("new_text") {
                                                put("type", "string")
                                                put("description", "Replacement text. An empty string deletes the match.")
                                            }
                                            putJsonObject("replace_all") {
                                                put("type", "boolean")
                                                put("description", "Replace every occurrence instead of requiring exactly one.")
                                            }
                                        }
                                        putJsonArray("required") {
                                            add("old_text")
                                            add("new_text")
                                        }
                                        put("additionalProperties", false)
                                    }
                                }
                                putJsonObject("expected_sha256") {
                                    put("type", "string")
                                    put(
                                        "description",
                                        "Optional SHA-256 from file_read or file_stat. The edit is refused when the file changed.",
                                    )
                                }
                            }
                            putJsonArray("required") {
                                add("path")
                                add("edits")
                            }
                            put("additionalProperties", false)
                        },
                    confirmationReason = "This edits a file inside the Conductor workspace.",
                    confirmationKind = ConfirmationKind.WORKSPACE_WRITE,
                    bulkApproval = BulkApproval.TURN,
                    approvalScope = "workspace_write",
                )

            override suspend fun preflight(args: JsonObject): ToolExecution? {
                val path = AgentJson.string(args, "path") ?: return missingPath()
                val rawEdits = args["edits"] as? JsonArray ?: return invalid("edits must be an array")
                if (rawEdits.isEmpty() || rawEdits.size > MAX_FILE_EDITS) {
                    return invalid("edits must contain between 1 and $MAX_FILE_EDITS edits")
                }
                val edits = mutableListOf<TextEditSpec>()
                for ((index, element) in rawEdits.withIndex()) {
                    val edit = element as? JsonObject ?: return invalid("edits[$index] must be an object")
                    val oldText = AgentJson.string(edit, "old_text")
                    if (oldText.isNullOrEmpty()) return invalid("edits[$index].old_text must not be empty")
                    val newText = AgentJson.string(edit, "new_text")
                        ?: return invalid("edits[$index].new_text is required")
                    val replaceAll = AgentJson.boolean(edit, "replace_all") ?: false
                    edits += TextEditSpec(oldText, newText, replaceAll)
                }
                val expected = AgentJson.string(args, "expected_sha256")?.trim()?.takeIf { it.isNotEmpty() }
                if (expected != null && !SHA256_HEX.matches(expected)) {
                    return invalid("expected_sha256 must be 64 hex characters")
                }
                return runCatching {
                    fileStore.preflightEdit(path, edits, expected)
                    null
                }.getOrElse { failure(it) }
            }

            override fun confirmationHint(args: JsonObject): ConfirmationHint? {
                val path = AgentJson.string(args, "path") ?: return null
                return if (!fileStore.isSessionCreated(path)) {
                    ConfirmationHint(
                        kind = ConfirmationKind.WORKSPACE_OVERWRITE,
                        bulkApproval = BulkApproval.NONE,
                        approvalScope = "workspace_overwrite",
                    )
                } else {
                    null
                }
            }

            override suspend fun execute(
                args: JsonObject,
                cancellation: ToolCancellation,
            ): ToolExecution {
                val path = AgentJson.string(args, "path") ?: return missingPath()
                val rawEdits = args["edits"] as? JsonArray ?: return invalid("edits must be an array")
                if (rawEdits.isEmpty() || rawEdits.size > MAX_FILE_EDITS) {
                    return invalid("edits must contain between 1 and $MAX_FILE_EDITS edits")
                }
                val edits = mutableListOf<TextEditSpec>()
                for ((index, element) in rawEdits.withIndex()) {
                    val edit = element as? JsonObject ?: return invalid("edits[$index] must be an object")
                    val oldText = AgentJson.string(edit, "old_text")
                    if (oldText.isNullOrEmpty()) return invalid("edits[$index].old_text must not be empty")
                    val newText = AgentJson.string(edit, "new_text")
                        ?: return invalid("edits[$index].new_text is required")
                    val replaceAll = AgentJson.boolean(edit, "replace_all") ?: false
                    if (edit.containsKey("replace_all") && AgentJson.boolean(edit, "replace_all") == null) {
                        return invalid("edits[$index].replace_all must be a boolean")
                    }
                    edits += TextEditSpec(oldText, newText, replaceAll)
                }
                if (args.containsKey("expected_sha256") && AgentJson.string(args, "expected_sha256") == null) {
                    return invalid("expected_sha256 must be a string")
                }
                val expected = AgentJson.string(args, "expected_sha256")?.trim()?.takeIf { it.isNotEmpty() }
                if (expected != null && !SHA256_HEX.matches(expected)) {
                    return invalid("expected_sha256 must be 64 hex characters")
                }
                return runCatching {
                    val entry = fileStore.editText(path, edits, expected)
                    ToolExecution(
                        output =
                            buildJsonObject {
                                put("path", entry.path)
                                put("edits", edits.size)
                                put("size", entry.size)
                                put("sha256", fileStore.sha256(fileStore.resolveRead(entry.path)))
                            },
                    )
                }.getOrElse { failure(it) }
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
                    confirmationKind = ConfirmationKind.WORKSPACE_DELETE_FILE,
                    bulkApproval = BulkApproval.BATCH,
                    approvalScope = "workspace_delete",
                )

            override suspend fun preflight(args: JsonObject): ToolExecution? =
                preflightPath(args) { path -> fileStore.preflightDelete(path) }

            override fun confirmationHint(args: JsonObject): ConfirmationHint? {
                val path = AgentJson.string(args, "path") ?: return null
                val directory = runCatching { fileStore.stat(path).directory }.getOrNull() ?: return null
                return if (directory) {
                    ConfirmationHint(
                        kind = ConfirmationKind.WORKSPACE_DELETE_DIR,
                        bulkApproval = BulkApproval.NONE,
                        approvalScope = "workspace_delete_dir",
                    )
                } else if (fileStore.isSessionCreated(path)) {
                    ConfirmationHint(
                        kind = ConfirmationKind.WORKSPACE_DELETE_FILE,
                        bulkApproval = BulkApproval.BATCH,
                        approvalScope = "workspace_delete",
                    )
                } else {
                    ConfirmationHint(
                        kind = ConfirmationKind.WORKSPACE_DELETE_UNTRACKED,
                        bulkApproval = BulkApproval.NONE,
                        approvalScope = "workspace_delete_untracked",
                    )
                }
            }

            override suspend fun execute(
                args: JsonObject,
                cancellation: ToolCancellation,
            ): ToolExecution =
                withPath(args) { path ->
                    val deleted = fileStore.delete(path)
                    ToolExecution(output = fileEntryJson(deleted))
                }
        }

    private val planUpdate =
        object : AgentTool {
            override val definition =
                AgentToolDefinition(
                    name = PLAN_UPDATE_TOOL,
                    description =
                        "Create or replace the current task plan. Send the full step list each time; " +
                            "an empty items array clears it. Use it for multi-step tasks, and keep the " +
                            "current step, completed steps and any blocker up to date. The plan is shown " +
                            "to the user and re-injected after each tool result. " +
                            "Args: goal, items[{step,status}], note.",
                    permission = AgentPermission.UTILITY,
                    parameters = emptyList(),
                    confirmationPolicy = ConfirmationPolicy.NEVER,
                    category = "utility",
                    aliases = listOf("plan", "todo", "task list", "checklist", "steps"),
                    core = true,
                    passthroughArguments = true,
                    parametersSchemaOverride =
                        buildJsonObject {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("goal") {
                                    put("type", "string")
                                    put("description", "One-line goal. Required when items is non-empty; at most $MAX_PLAN_GOAL_CHARS characters.")
                                }
                                putJsonObject("items") {
                                    put("type", "array")
                                    put("maxItems", MAX_PLAN_ITEMS)
                                    put(
                                        "description",
                                        "The complete step list. Empty clears the plan. Each step must be one line.",
                                    )
                                    putJsonObject("items") {
                                        put("type", "object")
                                        putJsonObject("properties") {
                                            putJsonObject("step") {
                                                put("type", "string")
                                                put("description", "One line of work; at most $MAX_PLAN_STEP_CHARS characters.")
                                            }
                                            putJsonObject("status") {
                                                put("type", "string")
                                                put("enum", buildJsonArray {
                                                    add("pending")
                                                    add("in_progress")
                                                    add("done")
                                                })
                                            }
                                        }
                                        putJsonArray("required") {
                                            add("step")
                                            add("status")
                                        }
                                        put("additionalProperties", false)
                                    }
                                }
                                putJsonObject("note") {
                                    put("type", "string")
                                    put("description", "Optional current blocker or dead end; at most $MAX_PLAN_NOTE_CHARS characters.")
                                }
                            }
                            putJsonArray("required") { add("items") }
                            put("additionalProperties", false)
                        },
                )

            override suspend fun execute(
                args: JsonObject,
                cancellation: ToolCancellation,
            ): ToolExecution {
                val store = planStore ?: return unavailable("plan_update is not available in the current build")
                val rawItems = args["items"] as? JsonArray ?: return invalid("items must be an array")
                if (rawItems.size > MAX_PLAN_ITEMS) {
                    return invalid("items must contain at most $MAX_PLAN_ITEMS steps")
                }
                val items = mutableListOf<PlanItem>()
                for ((index, element) in rawItems.withIndex()) {
                    val item = element as? JsonObject ?: return invalid("items[$index] must be an object")
                    val step = planLine(AgentJson.string(item, "step"))
                        ?: return invalid("items[$index].step must be one non-empty line")
                    if (step.length > MAX_PLAN_STEP_CHARS) {
                        return invalid("items[$index].step must be at most $MAX_PLAN_STEP_CHARS characters")
                    }
                    val status =
                        when (AgentJson.string(item, "status")?.lowercase(Locale.US)) {
                            "pending" -> PlanItemStatus.PENDING
                            "in_progress" -> PlanItemStatus.IN_PROGRESS
                            "done" -> PlanItemStatus.DONE
                            else -> return invalid("items[$index].status must be pending, in_progress or done")
                        }
                    items += PlanItem(step, status)
                }
                val goal = planLine(AgentJson.string(args, "goal")).orEmpty()
                if (goal.length > MAX_PLAN_GOAL_CHARS) {
                    return invalid("goal must be at most $MAX_PLAN_GOAL_CHARS characters")
                }
                if (items.isNotEmpty() && goal.isEmpty()) {
                    return invalid("goal is required when items is non-empty")
                }
                val note = planLine(AgentJson.string(args, "note")).orEmpty()
                if (note.length > MAX_PLAN_NOTE_CHARS) {
                    return invalid("note must be at most $MAX_PLAN_NOTE_CHARS characters")
                }
                val result = store.update(goal, items, note)
                return ToolExecution(
                    output =
                        buildJsonObject {
                            put("changed", result.changed)
                            put("cleared", result.cleared)
                            result.state?.let {
                                put("version", it.version)
                                put("goal", it.goal)
                                put(
                                    "items",
                                    buildJsonArray {
                                        it.items.forEach { item ->
                                            add(
                                                buildJsonObject {
                                                    put("step", item.step)
                                                    put("status", item.status.wire)
                                                },
                                            )
                                        }
                                    },
                                )
                                if (it.note.isNotBlank()) put("note", it.note)
                                put("recitation", it.recitation())
                            }
                        },
                )
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
                    confirmationKind = ConfirmationKind.DIAGNOSTICS,
                    bulkApproval = BulkApproval.TURN,
                    approvalScope = "diagnostics",
                )

            override suspend fun preflight(args: JsonObject): ToolExecution? {
                val kind = AgentJson.string(args, "kind")?.trim()?.lowercase(Locale.US)
                    ?: return invalid("kind is required")
                if (kind != "conversation" && kind != "trace") {
                    return invalid("kind must be conversation or trace")
                }
                val tail = AgentJson.int(args, "tail")
                val sinceMinutes = AgentJson.int(args, "since_minutes")
                if (tail != null && tail !in 1..20) return invalid("tail must be between 1 and 20")
                if (sinceMinutes != null && sinceMinutes !in 1..1440) {
                    return invalid("since_minutes must be between 1 and 1440")
                }
                if (kind != "trace" && (tail != null || sinceMinutes != null)) {
                    return invalid("tail and since_minutes are only supported for trace")
                }
                val requested = AgentJson.string(args, "name")?.trim().orEmpty()
                if (requested.isNotEmpty()) {
                    val path = "conductor/${markdownName(sanitizeName(requested))}"
                    return runCatching {
                        fileStore.preflightWrite(path, AgentJson.boolean(args, "overwrite") ?: false)
                        null
                    }.getOrElse { failure(it) }
                }
                return null
            }

            override fun confirmationHint(args: JsonObject): ConfirmationHint? {
                val requested = AgentJson.string(args, "name")?.trim().orEmpty()
                if (requested.isEmpty()) return null
                val path = "conductor/${markdownName(sanitizeName(requested))}"
                val exists = runCatching { !fileStore.stat(path).directory }.getOrDefault(false)
                return if (exists && !fileStore.isSessionCreated(path)) {
                    ConfirmationHint(
                        kind = ConfirmationKind.WORKSPACE_OVERWRITE,
                        bulkApproval = BulkApproval.NONE,
                        approvalScope = "workspace_overwrite",
                    )
                } else {
                    null
                }
            }

            override suspend fun execute(
                args: JsonObject,
                cancellation: ToolCancellation,
            ): ToolExecution {
                val source = diagnostics ?: return unavailable()
                val kind = AgentJson.string(args, "kind")?.trim()?.lowercase(Locale.US)
                    ?: return invalid("kind is required")
                if (kind != "conversation" && kind != "trace") {
                    return invalid("kind must be conversation or trace")
                }
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
                            val stem = if (kind == "conversation") "transcript" else kind
                            uniqueName("$stem-", "md")
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
                                    put("totalRequests", export.totalCount)
                                    put("droppedRequests", export.droppedCount)
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

    private val taildropSend =
        object : AgentTool {
            override val definition =
                AgentToolDefinition(
                    name = "taildrop_send",
                    description =
                        "Send files from the working directory to a Tailscale peer's Taildrop inbox. Success means " +
                            "the peer's Tailscale daemon received the file into its inbox; it does not mean the file " +
                            "was saved to the peer's disk or opened. The peer must save it from the Taildrop inbox. " +
                            "Use tailscale_devices for the peer name; the peer must be online. Requires confirmation.",
                    permission = AgentPermission.DATA_EGRESS,
                    parameters =
                        listOf(
                            AgentParameterSpec(
                                name = "peer",
                                type = AgentParameterType.STRING,
                                required = true,
                                description = "Peer name from tailscale_devices.",
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
                    confirmationKind = ConfirmationKind.FILE_TRANSFER,
                    bulkApproval = BulkApproval.NONE,
                    approvalScope = "taildrop",
                )

            override suspend fun preflight(args: JsonObject): ToolExecution? {
                val port = taildrop ?: return unavailable()
                val peer = AgentJson.string(args, "peer")?.trim() ?: return invalid("peer is required")
                val paths =
                    AgentJson.stringList(args, "files")
                        ?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }
                        .orEmpty()
                if (paths.isEmpty()) return invalid("files must contain at least one path")
                var total = 0L
                for (path in paths) {
                    val file =
                        runCatching { fileStore.resolveRead(path) }.getOrElse { return failure(it) }
                    if (!file.isFile) return invalid("Not a file: $path")
                    total += file.length()
                }
                if (total > MAX_SEND_BYTES) {
                    return invalid("Total size is larger than $MAX_SEND_BYTES bytes.")
                }
                if (port.peerOnline(peer) == false) {
                    return invalid("Peer is offline: $peer")
                }
                return null
            }

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
                            "Returns file names, sizes, opaque temporary handles and a logical inbox path " +
                            "(conductor/resources/inbox/<id>/<name>). The file itself is not copied or modified. " +
                            "Handles work only while the app session lasts; the inbox path is a read-only VFS view " +
                            "when the conductor scope is granted. file_read_selected only reads UTF-8 text; use " +
                            "analyze_image for images.",
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
                                                    if (file.inboxReady) {
                                                        put(
                                                            "path",
                                                            InboxPaths.path(
                                                                file.id,
                                                                file.name,
                                                                file.mimeType ?: "application/octet-stream",
                                                            ),
                                                        )
                                                    }
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
                            "other binary files are rejected; use analyze_image with the same handle as file_id.",
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
                                description = "Peer name from tailscale_devices.",
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
                    confirmationKind = ConfirmationKind.FILE_TRANSFER,
                    bulkApproval = BulkApproval.NONE,
                    approvalScope = "taildrop",
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
                        "Analyze one image with the configured vision-capable model and return text. The image " +
                            "leaves the device for the provider. With file_id, reuse a file selected by " +
                            "request_files or an image attached to the conversation. With path, read an image " +
                            "under conductor/ (for example conductor/resources/artifacts/...). Omit both to open " +
                            "the gallery picker. file_read_selected cannot read images.",
                    permission = AgentPermission.DATA_EGRESS,
                    parameters =
                        listOf(
                            AgentParameterSpec(
                                name = "question",
                                type = AgentParameterType.STRING,
                                required = true,
                                description = "What to ask about the image. Be specific.",
                            ),
                            AgentParameterSpec(
                                name = "file_id",
                                type = AgentParameterType.STRING,
                                required = false,
                                description =
                                    "Optional image handle from request_files, or the id of an attached image. " +
                                        "Omit to open the gallery picker.",
                            ),
                            AgentParameterSpec(
                                name = "path",
                                type = AgentParameterType.STRING,
                                required = false,
                                description =
                                    "Optional image path under conductor/, for example " +
                                        "conductor/resources/artifacts/<run>/<name>.",
                            ),
                        ),
                    confirmationPolicy = ConfirmationPolicy.NEVER,
                    category = "vision",
                    core = true,
                    aliases = listOf("image", "photo", "screenshot", "vision", "ocr", "read image"),
                    confirmationReason = "The selected image will be sent to the configured model provider for analysis.",
                    timeoutMs = IMAGE_ANALYSIS_TIMEOUT_MS,
                    confirmationKind = ConfirmationKind.DATA_EGRESS,
                    bulkApproval = BulkApproval.NONE,
                    approvalScope = "image_egress",
                )

            override suspend fun preflight(args: JsonObject): ToolExecution? {
                val question = AgentJson.string(args, "question")?.trim()
                if (question.isNullOrEmpty()) return invalid("question is required")
                val path =
                    AgentJson.string(args, "path")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: return null
                val normalizedPath = normalizeConductorPath(path) ?: return invalid("path must be under conductor/.")
                val file =
                    runCatching { fileStore.resolveRead(normalizedPath) }
                        .getOrElse { return invalid("path is not a readable file.") }
                if (!file.isFile) return invalid("path must be a file.")
                if (file.length() > MAX_IMAGE_BYTES) {
                    return invalid("Image is larger than ${MAX_IMAGE_BYTES / (1024 * 1024)} MB.")
                }
                val mime = URLConnection.guessContentTypeFromName(file.name) ?: "image/jpeg"
                if (!mime.startsWith("image/", ignoreCase = true)) {
                    return invalid("path must reference an image.")
                }
                return null
            }

            override suspend fun execute(
                args: JsonObject,
                cancellation: ToolCancellation,
            ): ToolExecution {
                val analyzer = vision ?: return unavailable()
                val question = AgentJson.string(args, "question")?.trim()
                if (question.isNullOrEmpty()) return invalid("question is required")
                val fileId =
                    AgentJson.string(args, "file_id")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                val path =
                    AgentJson.string(args, "path")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                return try {
                    if (path != null) {
                        val normalizedPath = normalizeConductorPath(path)
                        if (normalizedPath == null) {
                            return invalid("path must be under conductor/.")
                        }
                        val file =
                            runCatching { fileStore.resolveRead(normalizedPath) }
                                .getOrElse { return invalid("path is not a readable file.") }
                        if (!file.isFile) return invalid("path must be a file.")
                        if (file.length() > MAX_IMAGE_BYTES) {
                            return invalid("Image is larger than ${MAX_IMAGE_BYTES / (1024 * 1024)} MB.")
                        }
                        val mime = URLConnection.guessContentTypeFromName(file.name) ?: "image/jpeg"
                        if (!mime.startsWith("image/", ignoreCase = true)) {
                            return invalid("path must reference an image.")
                        }
                        return analyzeImageBytes(file.name, mime, file.readBytes(), analyzer, question)
                    }
                    if (fileId != null) {
                        val selected =
                            fileSelection?.let {
                                runCatching { it.readBytes(fileId, MAX_IMAGE_BYTES) }.getOrNull()
                            }
                        if (selected != null) {
                            if (!selected.mimeType.startsWith("image/", ignoreCase = true)) {
                                return invalid("file_id must reference an image.")
                            }
                            return analyzeImageBytes(
                                selected.name,
                                selected.mimeType,
                                selected.bytes,
                                analyzer,
                                question,
                            )
                        }
                        val attached =
                            chatImage?.invoke(fileId)
                                ?: return invalid(
                                    "Unknown image handle '$fileId'. Use request_files or an attached image id.",
                                )
                        if (!attached.mimeType.startsWith("image/", ignoreCase = true)) {
                            return invalid("file_id must reference an image.")
                        }
                        return analyzeImageBytes(fileId, attached.mimeType, attached.bytes, analyzer, question)
                    }
                    val picker =
                        fileSelection
                            ?: return ToolExecution(
                                errorCode = "tool_unavailable",
                                errorMessage = "No image picker is available.",
                            )
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
                    val first = selection.files.first()
                    val image = picker.readBytes(first.id, MAX_IMAGE_BYTES)
                    if (!image.mimeType.startsWith("image/", ignoreCase = true)) {
                        return invalid("file_id must reference an image.")
                    }
                    analyzeImageBytes(image.name, image.mimeType, image.bytes, analyzer, question)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failure(e)
                }
            }
        }

    private fun normalizeConductorPath(path: String): String? {
        val clean = path.trim().removePrefix("./").trimStart('/')
        return when {
            clean.startsWith("conductor/") -> clean
            clean.startsWith("resources/") -> "conductor/$clean"
            else -> null
        }
    }

    private suspend fun analyzeImageBytes(
        name: String,
        mimeType: String,
        bytes: ByteArray,
        analyzer: VisionPort,
        question: String,
    ): ToolExecution {
        val analysis = analyzer.analyze(bytes, mimeType, question)
        val text = analysis.text
        val truncated = analysis.truncated || text.length > MAX_ANALYSIS_CHARS
        return ToolExecution(
            output =
                buildJsonObject {
                    put("file", name)
                    put("mime", mimeType)
                    put("analysis", text.take(MAX_ANALYSIS_CHARS))
                    put("truncated", truncated)
                    analysis.finishReason?.let { put("finishReason", it) }
                    if (analysis.reasoningFallback) put("reasoningFallback", true)
                },
        )
    }

    private val releaseNotesTool =
        object : AgentTool {
            override val definition =
                AgentToolDefinition(
                    name = "release_notes",
                    description =
                        "Read the release notes bundled in the installed WonderSing build. Use this when the user " +
                            "asks what changed, what is new in this version, or wants to try a new feature. Returns " +
                            "the installed version, tag and the bundled release notes text.",
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
                add(fileGrep)
                add(fileWrite)
                add(fileAppend)
                add(fileEdit)
                add(fileDelete)
                if (planStore != null) add(planUpdate)
                if (diagnostics != null) add(diagnosticsExport)
                if (releaseNotes != null) add(releaseNotesTool)
                if (taildrop != null) {
                    add(taildropSend)
                }
                if (fileSelection != null) {
                    add(requestFiles)
                    add(fileReadSelected)
                    if (taildrop != null) add(taildropSendSelected)
                }
                if (vision != null) {
                    add(analyzeImageTool)
                }
            }

    private fun withPath(
        args: JsonObject,
        block: (String) -> ToolExecution,
    ): ToolExecution {
        val path = AgentJson.string(args, "path") ?: return missingPath()
        return runCatching { block(path) }.getOrElse { failure(it) }
    }

    private fun preflightPath(
        args: JsonObject,
        block: (String) -> Unit,
    ): ToolExecution? {
        val path = AgentJson.string(args, "path") ?: return missingPath()
        val error = runCatching { block(path) }.exceptionOrNull()
        return error?.let { failure(it) }
    }

    private fun fileEntryJson(entry: StoredFile): JsonObject =
        buildJsonObject {
            put("path", entry.path)
            put("type", if (entry.directory) "directory" else "file")
            if (!entry.directory) {
                put("size", entry.size)
                runCatching { fileStore.sha256(fileStore.resolveRead(entry.path)) }
                    .getOrNull()
                    ?.let { put("sha256", it) }
            }
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

    private fun unavailable(message: String = "this tool is not available in the current build"): ToolExecution =
        ToolExecution(errorCode = "tool_unavailable", errorMessage = message)

    private fun planLine(value: String?): String? =
        value
            ?.trim()
            ?.replace(Regex("\\s+"), " ")
            ?.takeIf { it.isNotEmpty() }

    private fun failure(error: Throwable): ToolExecution =
        ToolExecution(
            errorCode = "tool_failed",
            errorMessage = error.message ?: error.toString(),
        )

    private fun hasRegexMeta(query: String): Boolean =
        query.any {
            it == '\\' || it == '.' || it == '^' || it == '$' || it == '*' || it == '+' ||
                it == '?' || it == '(' || it == ')' || it == '[' || it == ']' || it == '{' ||
                it == '}' || it == '|'
        }

    companion object {
        private const val MAX_SEND_BYTES = 8L * 1024 * 1024
        private const val MAX_IMAGE_BYTES = 5 * 1024 * 1024
        private const val MAX_ANALYSIS_CHARS = 12_000
        private const val MAX_FILE_EDITS = 20
        private val SHA256_HEX = Regex("[0-9a-fA-F]{64}")
        private const val FILE_PICK_TIMEOUT_MS = 10L * 60L * 1000L
        private const val IMAGE_ANALYSIS_TIMEOUT_MS = 5L * 60L * 1000L
    }
}
