package com.pocketagent.android.runtime.modelmanager.gguf

import android.content.Context
import android.net.Uri
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Streaming GGUF header parser. Only the metadata key-value section is read;
 * tensor data is never loaded into memory.
 */
internal class GgufMetadataReaderImpl(
    private val skipKeys: Set<String>,
    private val arraySummariseThreshold: Int,
) : GgufMetadataReader {
    companion object {
        private const val ARCH_LLAMA = "llama"
        private const val MAX_METADATA_ENTRIES = 100_000L
        private const val MAX_METADATA_ARRAY_ELEMENTS = 10_000_000L
        private const val MAX_METADATA_STRING_BYTES = 16 * 1024 * 1024L
        private const val MAX_METADATA_KEY_BYTES = 64 * 1024L
        private const val MAX_TENSOR_COUNT = 1_000_000L
        private const val MAX_TENSOR_DIMENSIONS = 4
        private const val DEFAULT_ALIGNMENT = 32L
        private const val MAX_ALIGNMENT = 4096L
    }

    enum class MetadataType(val code: Int) {
        UINT8(0), INT8(1), UINT16(2), INT16(3),
        UINT32(4), INT32(5), FLOAT32(6), BOOL(7),
        STRING(8), ARRAY(9), UINT64(10), INT64(11), FLOAT64(12);
        companion object {
            private val codeMap = entries.associateBy(MetadataType::code)
            fun fromCode(code: Int): MetadataType = codeMap[code]
                ?: throw IOException("Unknown metadata value type code: $code")
        }
    }

    sealed class MetadataValue {
        data class UInt8(val value: UByte) : MetadataValue()
        data class Int8(val value: Byte) : MetadataValue()
        data class UInt16(val value: UShort) : MetadataValue()
        data class Int16(val value: Short) : MetadataValue()
        data class UInt32(val value: UInt) : MetadataValue()
        data class Int32(val value: Int) : MetadataValue()
        data class Float32(val value: Float) : MetadataValue()
        data class Bool(val value: Boolean) : MetadataValue()
        data class StringVal(val value: String) : MetadataValue()
        data class ArrayVal(val elementType: MetadataType, val elements: List<MetadataValue>) : MetadataValue()
        data class UInt64(val value: ULong) : MetadataValue()
        data class Int64(val value: Long) : MetadataValue()
        data class Float64(val value: Double) : MetadataValue()
    }

    override suspend fun ensureSourceFileFormat(file: java.io.File): Boolean =
        file.inputStream().buffered().use { ensureMagic(it) }

    override suspend fun ensureSourceFileFormat(context: Context, uri: Uri): Boolean =
        context.contentResolver.openInputStream(uri)?.buffered()?.use { ensureMagic(it) } == true

    private fun ensureMagic(input: InputStream): Boolean =
        ByteArray(4).let {
            if (input.read(it) != 4) throw IOException("Not a valid file!")
            it.contentEquals(byteArrayOf(0x47, 0x47, 0x55, 0x46))
        }

    override fun readStructuredMetadata(input: InputStream): GgufMetadata {
        val version = ensureMagicAndVersion(input)
        val tensorCount = readLittleLong(input)
        val kvCount = readLittleLong(input)
        requireNonNegativeCount("tensor count", tensorCount)
        requireBoundedCount("metadata entry count", kvCount, MAX_METADATA_ENTRIES)
        val meta = readMetaMap(input, kvCount)
        return buildStructured(meta, version, tensorCount, kvCount)
    }

    fun readValidatedModelStructure(input: InputStream, fileSize: Long): GgufMetadata {
        val countingInput = CountingInputStream(input)
        val version = ensureMagicAndVersion(countingInput)
        val tensorCount = readLittleLong(countingInput)
        val kvCount = readLittleLong(countingInput)
        requireBoundedCount("tensor count", tensorCount, MAX_TENSOR_COUNT)
        if (tensorCount == 0L) {
            throw IOException("GGUF model must contain at least one tensor")
        }
        requireBoundedCount("metadata entry count", kvCount, MAX_METADATA_ENTRIES)
        val meta = readMetaMap(countingInput, kvCount)
        validateTensorDataBounds(
            input = countingInput,
            fileSize = fileSize,
            tensorCount = tensorCount,
            alignment = metadataAlignment(meta),
        )
        return buildStructured(meta, version, tensorCount, kvCount)
    }

    private fun ensureMagicAndVersion(input: InputStream): GgufMetadata.GgufVersion {
        if (!ensureMagic(input)) throw InvalidFileFormatException()
        return GgufMetadata.GgufVersion.fromCode(readLEUInt32(input))
    }

    private fun readLEUInt32(input: InputStream): Int {
        val b0 = input.read(); val b1 = input.read(); val b2 = input.read(); val b3 = input.read()
        if (b3 == -1) throw IOException("Unexpected EOF while reading UInt32")
        return (b3 and 0xFF shl 24) or (b2 and 0xFF shl 16) or (b1 and 0xFF shl 8) or (b0 and 0xFF)
    }

    private fun readMetaMap(input: InputStream, kvCnt: Long): Map<String, MetadataValue> =
        mutableMapOf<String, MetadataValue>().apply {
            repeat(kvCnt.toInt()) {
                val key = readString(input, MAX_METADATA_KEY_BYTES, "metadata key")
                val valueT = MetadataType.fromCode(littleEndianBytesToInt(input.readNBytesExact(4)))
                if (key in skipKeys) {
                    skipValue(input, valueT)
                } else {
                    this[key] = parseValue(input, valueT)
                }
            }
        }

    private fun metadataAlignment(meta: Map<String, MetadataValue>): Long {
        val alignment = (meta["general.alignment"] as? MetadataValue.UInt32)?.value?.toLong()
            ?: DEFAULT_ALIGNMENT
        if (alignment <= 0L || alignment > MAX_ALIGNMENT || alignment and (alignment - 1L) != 0L) {
            throw IOException("Invalid GGUF alignment: $alignment")
        }
        return alignment
    }

    @Suppress("ThrowsCount")
    private fun validateTensorDataBounds(
        input: CountingInputStream,
        fileSize: Long,
        tensorCount: Long,
        alignment: Long,
    ) {
        requireNonNegativeCount("file size", fileSize)
        val names = mutableSetOf<String>()
        val tensors = buildList {
            repeat(tensorCount.toInt()) {
                val name = readString(input, MAX_METADATA_KEY_BYTES, "tensor name")
                if (name.isBlank() || !names.add(name)) {
                    throw IOException("Invalid or duplicate GGUF tensor name: $name")
                }
                val dimensionCount = readLEUInt32(input)
                if (dimensionCount !in 1..MAX_TENSOR_DIMENSIONS) {
                    throw IOException("Invalid GGUF tensor dimension count: $dimensionCount")
                }
                val dimensions = List(dimensionCount) {
                    readLittleLong(input).also { dimension ->
                        if (dimension <= 0L) throw IOException("Invalid GGUF tensor dimension: $dimension")
                    }
                }
                val typeCode = readLEUInt32(input)
                val layout = GGML_TENSOR_LAYOUTS[typeCode]
                    ?: throw IOException("Unsupported GGUF tensor type: $typeCode")
                if (dimensions.first() % layout.blockSize != 0L) {
                    throw IOException(
                        "GGUF tensor row is not divisible by block size: " +
                            "name=$name;row=${dimensions.first()};block=${layout.blockSize}",
                    )
                }
                val offset = readLittleLong(input)
                requireNonNegativeCount("tensor offset", offset)
                if (offset % alignment != 0L) {
                    throw IOException("Misaligned GGUF tensor offset: name=$name;offset=$offset;alignment=$alignment")
                }
                add(
                    TensorRange(
                        name = name,
                        offset = offset,
                        byteCount = tensorByteCount(dimensions, layout),
                    ),
                )
            }
        }
        val dataStart = alignUp(input.bytesRead, alignment)
        if (dataStart >= fileSize) {
            throw IOException("GGUF tensor data section is missing")
        }
        val sorted = tensors.sortedBy(TensorRange::offset)
        var previousEnd = 0L
        sorted.forEach { tensor ->
            if (tensor.offset < previousEnd) {
                throw IOException("Overlapping GGUF tensor data: ${tensor.name}")
            }
            val tensorEnd = addExact(tensor.offset, tensor.byteCount, "tensor range")
            val absoluteEnd = addExact(dataStart, tensorEnd, "tensor data bound")
            if (absoluteEnd > fileSize) {
                throw IOException(
                    "GGUF tensor data is truncated: name=${tensor.name};requiredEnd=$absoluteEnd;fileSize=$fileSize",
                )
            }
            previousEnd = tensorEnd
        }
    }

    private fun tensorByteCount(dimensions: List<Long>, layout: TensorLayout): Long {
        var blocks = dimensions.first() / layout.blockSize
        dimensions.drop(1).forEach { dimension ->
            blocks = multiplyExact(blocks, dimension, "tensor element count")
        }
        return multiplyExact(blocks, layout.typeSize, "tensor byte count")
    }

    private fun alignUp(value: Long, alignment: Long): Long {
        val remainder = value % alignment
        return if (remainder == 0L) value else addExact(value, alignment - remainder, "tensor data alignment")
    }

    private fun multiplyExact(left: Long, right: Long, label: String): Long {
        return try {
            Math.multiplyExact(left, right)
        } catch (error: ArithmeticException) {
            throw IOException("GGUF $label overflow", error)
        }
    }

    private fun addExact(left: Long, right: Long, label: String): Long {
        return try {
            Math.addExact(left, right)
        } catch (error: ArithmeticException) {
            throw IOException("GGUF $label overflow", error)
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun buildStructured(
        m: Map<String, MetadataValue>,
        version: GgufMetadata.GgufVersion,
        tensorCnt: Long,
        kvCnt: Long
    ): GgufMetadata {
        fun String.str() = (m[this] as? MetadataValue.StringVal)?.value
        fun String.bool() = (m[this] as? MetadataValue.Bool)?.value
        fun String.i32() = (m[this] as? MetadataValue.Int32)?.value
        fun String.u32() = (m[this] as? MetadataValue.UInt32)?.value?.toInt()
        fun String.f32() = (m[this] as? MetadataValue.Float32)?.value
        fun String.f64() = (m[this] as? MetadataValue.Float64)?.value?.toFloat()
        fun String.strList(): List<String>? =
            (m[this] as? MetadataValue.ArrayVal)
                ?.elements
                ?.mapNotNull { (it as? MetadataValue.StringVal)?.value }

        val declaredArch = "general.architecture".str()
        val arch = declaredArch ?: ARCH_LLAMA

        val basic = GgufMetadata.BasicInfo(
            uuid = "general.uuid".str(),
            name = "general.basename".str(),
            nameLabel = "general.name".str(),
            sizeLabel = "general.size_label".str()
        )

        val author = GgufMetadata.AuthorInfo(
            organization = "general.organization".str(),
            author = "general.author".str(),
            doi = "general.doi".str(),
            url = "general.url".str(),
            repoUrl = "general.repo_url".str(),
            license = "general.license".str(),
            licenseLink = "general.license.link".str()
        ).takeUnless {
            organization == null && author == null && doi == null &&
                url == null && repoUrl == null && license == null && licenseLink == null
        }

        val additional = GgufMetadata.AdditionalInfo(
            type = "general.type".str(),
            description = "general.description".str(),
            tags = "general.tags".strList(),
            languages = "general.languages".strList()
        ).takeUnless {
            type == null && description == null && tags == null && languages == null
        }

        val architectureInfo = GgufMetadata.ArchitectureInfo(
            architecture = declaredArch,
            fileType = "general.file_type".u32(),
            vocabSize = "$arch.vocab_size".u32(),
            finetune = "general.finetune".str(),
            quantizationVersion = "general.quantization_version".u32()
        ).takeUnless {
            architecture == null &&
                fileType == null &&
                vocabSize == null &&
                finetune == null &&
                quantizationVersion == null
        }

        val baseModels = buildList {
            val n = "general.base_model.count".u32() ?: 0
            for (i in 0 until n) {
                fun k(s: String) = "general.base_model.$i.$s"
                add(
                    GgufMetadata.BaseModelInfo(
                        name = k("name").str(),
                        author = k("author").str(),
                        version = k("version").str(),
                        organization = k("organization").str(),
                        url = k("url").str(),
                        doi = k("doi").str(),
                        uuid = k("uuid").str(),
                        repoUrl = k("repo_url").str(),
                    )
                )
            }
        }.takeIf { it.isNotEmpty() }

        val tokenizer = GgufMetadata.TokenizerInfo(
            model = "tokenizer.ggml.model".str(),
            bosTokenId = "tokenizer.ggml.bos_token_id".u32(),
            eosTokenId = "tokenizer.ggml.eos_token_id".u32(),
            unknownTokenId = "tokenizer.ggml.unknown_token_id".u32(),
            paddingTokenId = "tokenizer.ggml.padding_token_id".u32(),
            addBosToken = "tokenizer.ggml.add_bos_token".bool(),
            addEosToken = "tokenizer.ggml.add_eos_token".bool(),
            chatTemplate = "tokenizer.chat_template".str()
        ).takeUnless {
            model == null && bosTokenId == null && eosTokenId == null &&
                unknownTokenId == null && paddingTokenId == null &&
                addBosToken == null && addEosToken == null && chatTemplate == null
        }

        val dimensions = GgufMetadata.DimensionsInfo(
            contextLength = "$arch.context_length".u32(),
            embeddingSize = "$arch.embedding_length".u32(),
            blockCount = "$arch.block_count".u32(),
            feedForwardSize = "$arch.feed_forward_length".u32()
        ).takeUnless { contextLength == null && embeddingSize == null && blockCount == null && feedForwardSize == null }

        val attention = GgufMetadata.AttentionInfo(
            headCount = "$arch.attention.head_count".u32(),
            headCountKv = "$arch.attention.head_count_kv".u32(),
            keyLength = "$arch.attention.key_length".u32(),
            valueLength = "$arch.attention.value_length".u32(),
            layerNormEpsilon = "$arch.attention.layer_norm_epsilon".f32(),
            layerNormRmsEpsilon = "$arch.attention.layer_norm_rms_epsilon".f32(),
        ).takeUnless {
            headCount == null && headCountKv == null && keyLength == null && valueLength == null &&
                layerNormEpsilon == null && layerNormRmsEpsilon == null
        }

        val rope = GgufMetadata.RopeInfo(
            frequencyBase = "$arch.rope.freq_base".f32(),
            dimensionCount = "$arch.rope.dimension_count".u32(),
            scalingType = "$arch.rope.scaling.type".str(),
            scalingFactor = "$arch.rope.scaling.factor".f32(),
            attnFactor = "$arch.rope.scaling.attn_factor".f32(),
            originalContextLength = "$arch.rope.scaling.original_context_length".u32(),
            finetuned = "$arch.rope.scaling.finetuned".bool()
        ).takeUnless {
            frequencyBase == null && dimensionCount == null &&
                scalingType == null && scalingFactor == null && attnFactor == null &&
                originalContextLength == null && finetuned == null
        }

        val experts = GgufMetadata.ExpertsInfo(
            count = "$arch.expert_count".u32(),
            usedCount = "$arch.expert_used_count".u32()
        ).takeUnless { count == null && usedCount == null }

        return GgufMetadata(
            version = version,
            tensorCount = tensorCnt,
            kvCount = kvCnt,
            basic = basic,
            author = author,
            additional = additional,
            architecture = architectureInfo,
            baseModels = baseModels,
            tokenizer = tokenizer,
            dimensions = dimensions,
            attention = attention,
            rope = rope,
            experts = experts
        )
    }

    @Suppress("CyclomaticComplexMethod")
    private fun parseValue(input: InputStream, type: MetadataType): MetadataValue = when (type) {
        MetadataType.UINT8 -> {
            val byteVal = input.read()
            if (byteVal == -1) throw IOException("Unexpected EOF while reading uint8 value.")
            MetadataValue.UInt8(byteVal.toUByte())
        }
        MetadataType.INT8 -> {
            val byteVal = input.read()
            if (byteVal == -1) throw IOException("Unexpected EOF while reading int8 value.")
            MetadataValue.Int8(byteVal.toByte())
        }
        MetadataType.UINT16 -> {
            val bytes = ByteArray(2)
            if (input.read(bytes) != 2) throw IOException("Unexpected EOF while reading uint16 value.")
            val u16 = ((bytes[1].toInt() and 0xFF) shl 8) or (bytes[0].toInt() and 0xFF)
            MetadataValue.UInt16(u16.toUShort())
        }
        MetadataType.INT16 -> {
            val bytes = ByteArray(2)
            if (input.read(bytes) != 2) throw IOException("Unexpected EOF while reading int16 value.")
            val i16 = ((bytes[1].toInt() and 0xFF) shl 8) or (bytes[0].toInt() and 0xFF)
            MetadataValue.Int16(i16.toShort())
        }
        MetadataType.UINT32 -> {
            val bytes = ByteArray(4)
            if (input.read(bytes) != 4) throw IOException("Unexpected EOF while reading uint32 value.")
            val u32 = (bytes[3].toLong() and 0xFFL shl 24) or
                (bytes[2].toLong() and 0xFFL shl 16) or
                (bytes[1].toLong() and 0xFFL shl 8) or
                (bytes[0].toLong() and 0xFFL)
            MetadataValue.UInt32(u32.toUInt())
        }
        MetadataType.INT32 -> {
            val bytes = ByteArray(4)
            if (input.read(bytes) != 4) throw IOException("Unexpected EOF while reading int32 value.")
            val i32 = (bytes[3].toInt() and 0xFF shl 24) or
                (bytes[2].toInt() and 0xFF shl 16) or
                (bytes[1].toInt() and 0xFF shl 8) or
                (bytes[0].toInt() and 0xFF)
            MetadataValue.Int32(i32)
        }
        MetadataType.FLOAT32 -> {
            val bytes = ByteArray(4)
            if (input.read(bytes) != 4) throw IOException("Unexpected EOF while reading float32 value.")
            val bits = (bytes[3].toInt() and 0xFF shl 24) or
                (bytes[2].toInt() and 0xFF shl 16) or
                (bytes[1].toInt() and 0xFF shl 8) or
                (bytes[0].toInt() and 0xFF)
            MetadataValue.Float32(Float.fromBits(bits))
        }
        MetadataType.BOOL -> {
            val byteVal = input.read()
            if (byteVal == -1) throw IOException("Unexpected EOF while reading boolean value.")
            if (byteVal != 0 && byteVal != 1) {
                throw IOException("Invalid boolean value: $byteVal (must be 0 or 1).")
            }
            MetadataValue.Bool(byteVal != 0)
        }
        MetadataType.STRING -> {
            MetadataValue.StringVal(readString(input, MAX_METADATA_STRING_BYTES, "metadata string"))
        }
        MetadataType.ARRAY -> {
            val elemType = MetadataType.fromCode(littleEndianBytesToInt(input.readNBytesExact(4)))
            val len = readLittleLong(input)
            requireBoundedCount("metadata array length", len, MAX_METADATA_ARRAY_ELEMENTS)
            val count = len.toInt()
            if (arraySummariseThreshold >= 0 && count > arraySummariseThreshold) {
                repeat(count) { skipValue(input, elemType) }
                MetadataValue.StringVal("Array($elemType, $count items) /* summarised */")
            } else {
                val list = ArrayList<MetadataValue>(count)
                repeat(count) { list += parseValue(input, elemType) }
                MetadataValue.ArrayVal(elemType, list)
            }
        }
        MetadataType.UINT64 -> {
            val bytes = ByteArray(8)
            if (input.read(bytes) != 8) throw IOException("Unexpected EOF while reading uint64 value.")
            val u64 = (bytes[7].toULong() and 0xFFuL shl 56) or
                (bytes[6].toULong() and 0xFFuL shl 48) or
                (bytes[5].toULong() and 0xFFuL shl 40) or
                (bytes[4].toULong() and 0xFFuL shl 32) or
                (bytes[3].toULong() and 0xFFuL shl 24) or
                (bytes[2].toULong() and 0xFFuL shl 16) or
                (bytes[1].toULong() and 0xFFuL shl 8) or
                (bytes[0].toULong() and 0xFFuL)
            MetadataValue.UInt64(u64)
        }
        MetadataType.INT64 -> {
            val bytes = ByteArray(8)
            if (input.read(bytes) != 8) throw IOException("Unexpected EOF while reading int64 value.")
            val i64 = (bytes[7].toLong() and 0xFFL shl 56) or
                (bytes[6].toLong() and 0xFFL shl 48) or
                (bytes[5].toLong() and 0xFFL shl 40) or
                (bytes[4].toLong() and 0xFFL shl 32) or
                (bytes[3].toLong() and 0xFFL shl 24) or
                (bytes[2].toLong() and 0xFFL shl 16) or
                (bytes[1].toLong() and 0xFFL shl 8) or
                (bytes[0].toLong() and 0xFFL)
            MetadataValue.Int64(i64)
        }
        MetadataType.FLOAT64 -> {
            val bytes = ByteArray(8)
            if (input.read(bytes) != 8) throw IOException("Unexpected EOF while reading float64 value.")
            val bits = (bytes[7].toLong() and 0xFFL shl 56) or
                (bytes[6].toLong() and 0xFFL shl 48) or
                (bytes[5].toLong() and 0xFFL shl 40) or
                (bytes[4].toLong() and 0xFFL shl 32) or
                (bytes[3].toLong() and 0xFFL shl 24) or
                (bytes[2].toLong() and 0xFFL shl 16) or
                (bytes[1].toLong() and 0xFFL shl 8) or
                (bytes[0].toLong() and 0xFFL)
            MetadataValue.Float64(Double.fromBits(bits))
        }
    }

    private fun <T> T?.takeUnless(check: T.() -> Boolean): T? =
        this?.takeIf { !it.check() }

    private fun skipValue(input: InputStream, type: MetadataType) {
        when (type) {
            MetadataType.UINT8, MetadataType.INT8, MetadataType.BOOL -> input.skipFully(1)
            MetadataType.UINT16, MetadataType.INT16 -> input.skipFully(2)
            MetadataType.UINT32, MetadataType.INT32, MetadataType.FLOAT32 -> input.skipFully(4)
            MetadataType.UINT64, MetadataType.INT64, MetadataType.FLOAT64 -> input.skipFully(8)
            MetadataType.STRING -> {
                val len = readLittleLong(input)
                requireBoundedCount("metadata string length", len, MAX_METADATA_STRING_BYTES)
                input.skipFully(len)
            }
            MetadataType.ARRAY -> {
                val elemType = MetadataType.fromCode(littleEndianBytesToInt(input.readNBytesExact(4)))
                val len = readLittleLong(input)
                requireBoundedCount("metadata array length", len, MAX_METADATA_ARRAY_ELEMENTS)
                repeat(len.toInt()) { skipValue(input, elemType) }
            }
        }
    }

    private fun readLittleLong(input: InputStream): Long {
        val bytes = ByteArray(8)
        input.readFully(bytes)
        return (bytes[7].toLong() and 0xFFL shl 56) or
            (bytes[6].toLong() and 0xFFL shl 48) or
            (bytes[5].toLong() and 0xFFL shl 40) or
            (bytes[4].toLong() and 0xFFL shl 32) or
            (bytes[3].toLong() and 0xFFL shl 24) or
            (bytes[2].toLong() and 0xFFL shl 16) or
            (bytes[1].toLong() and 0xFFL shl 8) or
            (bytes[0].toLong() and 0xFFL)
    }

    private fun readString(
        input: InputStream,
        maxBytes: Long,
        label: String,
    ): String =
        readLittleLong(input).let { len ->
            requireBoundedCount("$label length", len, maxBytes)
            ByteArray(len.toInt()).let {
                if (it.isNotEmpty()) input.readFully(it)
                String(it, Charsets.UTF_8)
            }
        }

    private fun requireNonNegativeCount(label: String, count: Long) {
        if (count < 0) {
            throw IOException("Invalid $label: $count")
        }
    }

    private fun requireBoundedCount(label: String, count: Long, maximum: Long) {
        requireNonNegativeCount(label, count)
        if (count > maximum) {
            throw IOException("$label exceeds supported maximum: $count > $maximum")
        }
    }

    private fun littleEndianBytesToInt(bytes: ByteArray): Int =
        (bytes[3].toInt() and 0xFF shl 24) or
            (bytes[2].toInt() and 0xFF shl 16) or
            (bytes[1].toInt() and 0xFF shl 8) or
            (bytes[0].toInt() and 0xFF)

    private fun InputStream.skipFully(n: Long) {
        var remaining = n
        val scratch = ByteArray(8192)
        while (remaining > 0) {
            val skipped = skip(remaining)
            when {
                skipped > 0 -> remaining -= skipped
                skipped == 0L -> {
                    val read = read(scratch, 0, minOf(remaining, scratch.size.toLong()).toInt())
                    if (read == -1) throw IOException("EOF while skipping $n bytes")
                    remaining -= read
                }
                else -> throw IOException("Skip returned negative value")
            }
        }
    }

    private fun InputStream.readFully(buf: ByteArray, len: Int = buf.size) {
        var off = 0
        while (off < len) {
            val n = read(buf, off, len - off)
            if (n == -1) throw IOException("EOF after $off of $len bytes")
            off += n
        }
    }

    private fun InputStream.readNBytesExact(n: Int) = ByteArray(n).also {
        if (read(it) != n) throw IOException("Unexpected EOF")
    }

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var bytesRead: Long = 0L
            private set

        override fun read(): Int {
            return super.read().also { value -> if (value >= 0) bytesRead += 1L }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            return super.read(buffer, offset, length).also { count ->
                if (count > 0) bytesRead += count.toLong()
            }
        }

        override fun skip(byteCount: Long): Long {
            return super.skip(byteCount).also { skipped -> bytesRead += skipped }
        }
    }

    private data class TensorRange(
        val name: String,
        val offset: Long,
        val byteCount: Long,
    )

    private data class TensorLayout(
        val blockSize: Long,
        val typeSize: Long,
    )

    private val GGML_TENSOR_LAYOUTS = mapOf(
        0 to TensorLayout(1, 4),
        1 to TensorLayout(1, 2),
        2 to TensorLayout(32, 18),
        3 to TensorLayout(32, 20),
        6 to TensorLayout(32, 22),
        7 to TensorLayout(32, 24),
        8 to TensorLayout(32, 34),
        9 to TensorLayout(32, 36),
        10 to TensorLayout(256, 84),
        11 to TensorLayout(256, 110),
        12 to TensorLayout(256, 144),
        13 to TensorLayout(256, 176),
        14 to TensorLayout(256, 210),
        15 to TensorLayout(256, 292),
        16 to TensorLayout(256, 66),
        17 to TensorLayout(256, 74),
        18 to TensorLayout(256, 98),
        19 to TensorLayout(256, 50),
        20 to TensorLayout(32, 18),
        21 to TensorLayout(256, 110),
        22 to TensorLayout(256, 82),
        23 to TensorLayout(256, 136),
        24 to TensorLayout(1, 1),
        25 to TensorLayout(1, 2),
        26 to TensorLayout(1, 4),
        27 to TensorLayout(1, 8),
        28 to TensorLayout(1, 8),
        29 to TensorLayout(256, 56),
        30 to TensorLayout(1, 2),
        34 to TensorLayout(256, 54),
        35 to TensorLayout(256, 66),
        39 to TensorLayout(32, 17),
        40 to TensorLayout(64, 36),
        41 to TensorLayout(128, 18),
        42 to TensorLayout(64, 18),
    )
}
