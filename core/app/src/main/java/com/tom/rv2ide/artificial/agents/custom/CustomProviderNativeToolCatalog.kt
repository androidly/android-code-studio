package com.tom.rv2ide.artificial.agents.custom

import org.json.JSONArray
import org.json.JSONObject
import java.util.LinkedHashMap

internal object CustomProviderNativeToolCatalog {

    fun buildOpenAIChatTools(strictToolSchemas: Boolean): JSONArray {
        return JSONArray().apply {
            nativeToolDefinitions().forEach { definition ->
                put(
                    JSONObject().apply {
                        put("type", "function")
                        put(
                            "function",
                            JSONObject().apply {
                                put("name", definition.name)
                                put("description", definition.description)
                                put("parameters", buildOpenAICompatibleToolSchema(definition.inputSchema, strictToolSchemas))
                                if (strictToolSchemas) {
                                    put("strict", true)
                                }
                            }
                        )
                    }
                )
            }
        }
    }

    fun buildOpenAIResponsesTools(strictToolSchemas: Boolean): JSONArray {
        return JSONArray().apply {
            nativeToolDefinitions().forEach { definition ->
                put(
                    JSONObject().apply {
                        put("type", "function")
                        put("name", definition.name)
                        put("description", definition.description)
                        put("parameters", buildOpenAICompatibleToolSchema(definition.inputSchema, strictToolSchemas))
                        if (strictToolSchemas) {
                            put("strict", true)
                        }
                    }
                )
            }
        }
    }

    fun buildClaudeTools(): JSONArray {
        return JSONArray().apply {
            nativeToolDefinitions().forEach { definition ->
                put(
                    JSONObject().apply {
                        put("name", definition.name)
                        put("description", definition.description)
                        put("input_schema", definition.inputSchema)
                    }
                )
            }
        }
    }

    private fun buildOpenAICompatibleToolSchema(
        schema: JSONObject,
        strictMode: Boolean
    ): JSONObject {
        return JSONObject(schema.toString()).also { sanitizeOpenAICompatibleToolSchema(it, strictMode) }
    }

    private fun sanitizeOpenAICompatibleToolSchema(
        schema: JSONObject,
        strictMode: Boolean
    ) {
        val normalizedType = normalizeSchemaType(schema.opt("type"), strictMode)
            ?: inferSchemaType(schema)
        schema.put("type", normalizedType)

        if (normalizedType == "object") {
            val properties = schema.optJSONObject("properties") ?: JSONObject().also {
                schema.put("properties", it)
            }
            val propertyNames = mutableListOf<String>()
            val iterator = properties.keys()
            while (iterator.hasNext()) {
                val propertyName = iterator.next()
                propertyNames += propertyName
                when (val child = properties.opt(propertyName)) {
                    is JSONObject -> sanitizeOpenAICompatibleToolSchema(child, strictMode)
                    else -> properties.put(propertyName, JSONObject().put("type", "string"))
                }
            }
            schema.put("additionalProperties", false)
            schema.put("required", JSONArray(propertyNames))
        } else if (normalizedType == "array") {
            val items = schema.opt("items")
            if (items is JSONObject) {
                sanitizeOpenAICompatibleToolSchema(items, strictMode)
            } else {
                schema.put("items", JSONObject().put("type", "string"))
            }
        }
    }

    private fun normalizeSchemaType(
        typeValue: Any?,
        strictMode: Boolean
    ): String? {
        return when (typeValue) {
            is String -> typeValue
            is JSONArray -> {
                var firstNonNull: String? = null
                for (index in 0 until typeValue.length()) {
                    val candidate = typeValue.optString(index).trim()
                    if (candidate.isBlank()) {
                        continue
                    }
                    if (candidate == "null" && !strictMode) {
                        continue
                    }
                    if (candidate != "null") {
                        firstNonNull = candidate
                        if (!strictMode) {
                            break
                        }
                    }
                }
                firstNonNull ?: typeValue.optString(0).trim().ifBlank { null }
            }
            else -> null
        }
    }

    private fun inferSchemaType(schema: JSONObject): String {
        return when {
            schema.has("properties") || schema.has("required") || schema.has("additionalProperties") -> "object"
            schema.has("items") -> "array"
            schema.has("minimum") || schema.has("maximum") -> "integer"
            else -> "string"
        }
    }

    private fun nativeToolDefinitions(): List<NativeToolDefinition> {
        return listOf(
            NativeToolDefinition(
                name = "find_files",
                description = "Find project files by path or file-name pattern before reading any content.",
                inputSchema = objectSchema(
                    properties = linkedMapOf(
                        "pattern" to stringSchema("Glob-like file or path pattern such as *MainActivity*.kt."),
                        "max_results" to integerSchema(
                            description = "Maximum number of paths to return.",
                            nullable = true,
                            minimum = 1,
                            maximum = 120
                        )
                    ),
                    required = listOf("pattern", "max_results")
                )
            ),
            NativeToolDefinition(
                name = "search_project",
                description = "Search project text content to locate the relevant symbol, error, or string before reading a file range.",
                inputSchema = objectSchema(
                    properties = linkedMapOf(
                        "pattern" to stringSchema("Literal text or regex pattern to search for."),
                        "file_glob" to stringSchema(
                            description = "Optional file glob such as *.kt or **/build.gradle.",
                            nullable = true
                        ),
                        "case_sensitive" to booleanSchema(
                            description = "Whether the match should be case sensitive.",
                            nullable = true
                        ),
                        "regex" to booleanSchema(
                            description = "Treat PATTERN as a regular expression.",
                            nullable = true
                        ),
                        "max_results" to integerSchema(
                            description = "Maximum number of matches to return.",
                            nullable = true,
                            minimum = 1,
                            maximum = 100
                        )
                    ),
                    required = listOf("pattern", "file_glob", "case_sensitive", "regex", "max_results")
                )
            ),
            NativeToolDefinition(
                name = "read_file_range",
                description = "Read only a focused line range from a file. Prefer 50-200 relevant lines instead of the whole file.",
                inputSchema = objectSchema(
                    properties = linkedMapOf(
                        "file" to stringSchema("Relative file path from the project root."),
                        "start_line" to integerSchema(
                            description = "1-based start line.",
                            nullable = true,
                            minimum = 1
                        ),
                        "end_line" to integerSchema(
                            description = "1-based end line.",
                            nullable = true,
                            minimum = 1
                        )
                    ),
                    required = listOf("file", "start_line", "end_line")
                )
            ),
            NativeToolDefinition(
                name = "replace_file_range",
                description = "Apply a focused edit by replacing or inserting a line range in a file.",
                inputSchema = objectSchema(
                    properties = linkedMapOf(
                        "file" to stringSchema("Relative file path from the project root."),
                        "start_line" to integerSchema("1-based start line for replacement or insertion.", minimum = 1),
                        "end_line" to integerSchema("1-based end line. Use START_LINE-1 to insert.", minimum = 0),
                        "content" to stringSchema("Exact replacement text for the requested range.")
                    ),
                    required = listOf("file", "start_line", "end_line", "content")
                )
            ),
            NativeToolDefinition(
                name = "build_project",
                description = "Run Gradle wrapper tasks to sync, compile, or build the project.",
                inputSchema = objectSchema(
                    properties = linkedMapOf(
                        "tasks" to stringSchema("Space-separated Gradle tasks such as :core:app:assembleDebug."),
                        "args" to stringSchema(
                            description = "Optional extra Gradle flags such as --stacktrace --info.",
                            nullable = true
                        )
                    ),
                    required = listOf("tasks", "args")
                )
            ),
            NativeToolDefinition(
                name = "run_terminal_command",
                description = "Run one safe non-interactive Termux command without pipes, redirects, or chaining. Common supported commands include pkg, apt, git, rg, grep, sed, head, tail, cat, ls, find, curl, wget, stat, tree, du, df, ps, cp, mkdir, and touch. Gradle wrapper commands are better handled by build_project and may be rerouted there.",
                inputSchema = objectSchema(
                    properties = linkedMapOf(
                        "command" to stringSchema("Single command to execute, for example pkg install ripgrep -y."),
                        "workdir" to stringSchema(
                            description = "Optional working directory hint such as PROJECT_ROOT or HOME.",
                            nullable = true
                        )
                    ),
                    required = listOf("command", "workdir")
                )
            )
        )
    }

    private fun objectSchema(
        properties: LinkedHashMap<String, JSONObject>,
        required: List<String> = emptyList()
    ): JSONObject {
        return JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject(properties as Map<*, *>))
            put("additionalProperties", false)
            put("required", JSONArray(required))
        }
    }

    private fun stringSchema(
        description: String,
        nullable: Boolean = false
    ): JSONObject {
        return JSONObject().apply {
            put("type", schemaType("string", nullable))
            put("description", description)
        }
    }

    private fun integerSchema(
        description: String,
        nullable: Boolean = false,
        minimum: Int? = null,
        maximum: Int? = null
    ): JSONObject {
        return JSONObject().apply {
            put("type", schemaType("integer", nullable))
            put("description", description)
            minimum?.let { put("minimum", it) }
            maximum?.let { put("maximum", it) }
        }
    }

    private fun booleanSchema(
        description: String,
        nullable: Boolean = false
    ): JSONObject {
        return JSONObject().apply {
            put("type", schemaType("boolean", nullable))
            put("description", description)
        }
    }

    private fun schemaType(
        primaryType: String,
        nullable: Boolean
    ): Any {
        if (!nullable) {
            return primaryType
        }
        return JSONArray()
            .put(primaryType)
            .put("null")
    }
}

private data class NativeToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JSONObject
)
