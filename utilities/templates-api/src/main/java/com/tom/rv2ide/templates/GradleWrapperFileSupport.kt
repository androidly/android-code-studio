package com.tom.rv2ide.templates

import java.io.File

fun normalizeProjectGradleWrapper(projectDir: File) {
  normalizeGeneratedTextFile(File(projectDir, "gradlew"))
}

fun normalizeGeneratedText(content: String): String {
  return content.replace("\r\n", "\n").replace('\r', '\n')
}

fun normalizeGeneratedTextFile(file: File) {
  if (!file.isFile || !isGeneratedTextCandidate(file)) {
    return
  }

  val originalBytes = file.readBytes()
  if (originalBytes.none { it == '\r'.code.toByte() } || containsBinaryNullByte(originalBytes)) {
    return
  }

  val normalizedBytes = normalizeLineEndingsToLf(originalBytes)
  if (!originalBytes.contentEquals(normalizedBytes)) {
    file.writeBytes(normalizedBytes)
  }
}

fun normalizeGeneratedTextTree(root: File) {
  if (!root.exists()) {
    return
  }

  if (root.isFile) {
    normalizeGeneratedTextFile(root)
    return
  }

  root.walkTopDown().filter(File::isFile).forEach(::normalizeGeneratedTextFile)
}

fun normalizeUnixShellScript(file: File) {
  if (!file.isFile || !isUnixShellScriptCandidate(file)) {
    return
  }

  normalizeGeneratedTextFile(file)
}

private fun isGeneratedTextCandidate(file: File): Boolean {
  val fileName = file.name.lowercase()
  if (fileName in GENERATED_TEXT_FILE_NAMES) {
    return true
  }

  val extension = file.extension.lowercase()
  if (extension in GENERATED_BINARY_EXTENSIONS) {
    return false
  }
  if (extension in GENERATED_TEXT_EXTENSIONS) {
    return true
  }

  return isUnixShellScriptCandidate(file)
}

private fun isUnixShellScriptCandidate(file: File): Boolean {
  val fileName = file.name.lowercase()
  if (fileName == "gradlew" || file.extension.lowercase() == "sh") {
    return true
  }

  val header = file.inputStream().buffered().use { input ->
    val buffer = ByteArray(32)
    val read = input.read(buffer)
    if (read <= 0) {
      return false
    }
    buffer.decodeToString(endIndex = read)
  }
  return header.startsWith("#!/") && !file.name.endsWith(".bat", ignoreCase = true)
}

private fun containsBinaryNullByte(bytes: ByteArray): Boolean {
  return bytes.any { it == 0.toByte() }
}

private fun normalizeLineEndingsToLf(bytes: ByteArray): ByteArray {
  val normalized = ByteArray(bytes.size)
  var readIndex = 0
  var writeIndex = 0

  while (readIndex < bytes.size) {
    val current = bytes[readIndex]
    if (current == '\r'.code.toByte()) {
      normalized[writeIndex++] = '\n'.code.toByte()
      if (readIndex + 1 < bytes.size && bytes[readIndex + 1] == '\n'.code.toByte()) {
        readIndex++
      }
    } else {
      normalized[writeIndex++] = current
    }
    readIndex++
  }

  return if (writeIndex == normalized.size) normalized else normalized.copyOf(writeIndex)
}

private val GENERATED_TEXT_EXTENSIONS =
    setOf(
        "aidl",
        "c",
        "cc",
        "cmake",
        "cpp",
        "css",
        "gradle",
        "groovy",
        "h",
        "hpp",
        "html",
        "java",
        "js",
        "json",
        "kt",
        "kts",
        "md",
        "mk",
        "pro",
        "properties",
        "sh",
        "toml",
        "txt",
        "xml",
        "yaml",
        "yml",
    )

private val GENERATED_TEXT_FILE_NAMES =
    setOf(
        ".editorconfig",
        ".gitattributes",
        ".gitignore",
        "cmakelists.txt",
        "gradlew",
        "makefile",
    )

private val GENERATED_BINARY_EXTENSIONS =
    setOf(
        "7z",
        "aab",
        "aar",
        "apk",
        "bin",
        "bmp",
        "class",
        "db",
        "dex",
        "gif",
        "gz",
        "ico",
        "jar",
        "jpeg",
        "jpg",
        "jks",
        "keystore",
        "mp3",
        "mp4",
        "ogg",
        "otf",
        "png",
        "so",
        "svg",
        "tar",
        "ttf",
        "wav",
        "webp",
        "woff",
        "woff2",
        "zip",
    )
