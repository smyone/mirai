/*
 * Copyright 2020 Mamoe Technologies and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/mamoe/mirai/blob/master/LICENSE
 */

/*
 * Copyright 2017-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package net.mamoe.mirai.qqandroid.io.serialization

import kotlinx.io.*
import kotlinx.serialization.*
import kotlinx.serialization.internal.*
import kotlinx.serialization.modules.EmptyModule
import kotlinx.serialization.modules.SerialModule
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumberType
import kotlinx.serialization.protobuf.ProtoType
import net.mamoe.mirai.qqandroid.io.serialization.ProtoBufWithNullableSupport.Varint.encodeVarint

internal typealias ProtoDesc = Pair<Int, ProtoNumberType>

internal fun extractParameters(desc: SerialDescriptor, index: Int, zeroBasedDefault: Boolean = false): ProtoDesc {
    val idx = getSerialId(desc, index) ?: (if (zeroBasedDefault) index else index + 1)
    val format = desc.findAnnotation<ProtoType>(index)?.type
        ?: ProtoNumberType.DEFAULT
    return idx to format
}


/**
 * 带有 null (optional) support 的 Protocol buffers 序列化器.
 * 所有的为 null 的属性都将不会被序列化. 以此实现可选属性.
 *
 * 代码复制自 kotlinx.serialization. 修改部分已进行标注 (详见 "MIRAI MODIFY START")
 */
class ProtoBufWithNullableSupport(context: SerialModule = EmptyModule) : AbstractSerialFormat(context), BinaryFormat {

    internal open inner class ProtobufWriter(val encoder: ProtobufEncoder) : TaggedEncoder<ProtoDesc>() {
        public override val context
            get() = this@ProtoBufWithNullableSupport.context

        override fun beginStructure(desc: SerialDescriptor, vararg typeParams: KSerializer<*>): CompositeEncoder = when (desc.kind) {
            StructureKind.LIST -> RepeatedWriter(encoder, currentTag)
            StructureKind.CLASS, UnionKind.OBJECT, is PolymorphicKind -> ObjectWriter(currentTagOrNull, encoder)
            StructureKind.MAP -> MapRepeatedWriter(currentTagOrNull, encoder)
            else -> throw SerializationException("Primitives are not supported at top-level")
        }

        override fun encodeTaggedInt(tag: ProtoDesc, value: Int) = encoder.writeInt(value, tag.first, tag.second)
        override fun encodeTaggedByte(tag: ProtoDesc, value: Byte) = encoder.writeInt(value.toInt(), tag.first, tag.second)
        override fun encodeTaggedShort(tag: ProtoDesc, value: Short) = encoder.writeInt(value.toInt(), tag.first, tag.second)
        override fun encodeTaggedLong(tag: ProtoDesc, value: Long) = encoder.writeLong(value, tag.first, tag.second)
        override fun encodeTaggedFloat(tag: ProtoDesc, value: Float) = encoder.writeFloat(value, tag.first)
        override fun encodeTaggedDouble(tag: ProtoDesc, value: Double) = encoder.writeDouble(value, tag.first)
        override fun encodeTaggedBoolean(tag: ProtoDesc, value: Boolean) = encoder.writeInt(if (value) 1 else 0, tag.first, ProtoNumberType.DEFAULT)
        override fun encodeTaggedChar(tag: ProtoDesc, value: Char) = encoder.writeInt(value.toInt(), tag.first, tag.second)
        override fun encodeTaggedString(tag: ProtoDesc, value: String) = encoder.writeString(value, tag.first)
        override fun encodeTaggedEnum(
            tag: ProtoDesc,
            enumDescription: SerialDescriptor,
            ordinal: Int
        ) = encoder.writeInt(
            extractParameters(enumDescription, ordinal, zeroBasedDefault = true).first,
            tag.first,
            ProtoNumberType.DEFAULT
        )

        override fun SerialDescriptor.getTag(index: Int) = this.getProtoDesc(index)

        // MIRAI MODIFY START:
        override fun encodeTaggedNull(tag: ProtoDesc) {

        }

        override fun <T : Any> encodeNullableSerializableValue(serializer: SerializationStrategy<T>, value: T?) {
            if (value == null) {
                encodeTaggedNull(popTag())
            } else encodeSerializableValue(serializer, value)
        }
        // MIRAI MODIFY END

        @Suppress("UNCHECKED_CAST", "NAME_SHADOWING")
        override fun <T> encodeSerializableValue(serializer: SerializationStrategy<T>, value: T) = when {
            // encode maps as collection of map entries, not merged collection of key-values
            serializer.descriptor is MapLikeDescriptor -> {
                val serializer = (serializer as MapLikeSerializer<Any?, Any?, T, *>)
                val mapEntrySerial = MapEntrySerializer(serializer.keySerializer, serializer.valueSerializer)
                HashSetSerializer(mapEntrySerial).serialize(this, (value as Map<*, *>).entries)
            }
            serializer.descriptor == ByteArraySerializer.descriptor -> encoder.writeBytes(value as ByteArray, popTag().first)
            else -> serializer.serialize(this, value)
        }
    }

    internal open inner class ObjectWriter(
        val parentTag: ProtoDesc?, private val parentEncoder: ProtobufEncoder,
        private val stream: ByteArrayOutputStream = ByteArrayOutputStream()
    ) : ProtobufWriter(ProtobufEncoder(stream)) {
        override fun endEncode(desc: SerialDescriptor) {
            if (parentTag != null) {
                parentEncoder.writeBytes(stream.toByteArray(), parentTag.first)
            } else {
                parentEncoder.out.write(stream.toByteArray())
            }
        }
    }

    internal inner class MapRepeatedWriter(parentTag: ProtoDesc?, parentEncoder: ProtobufEncoder) : ObjectWriter(parentTag, parentEncoder) {
        override fun SerialDescriptor.getTag(index: Int): ProtoDesc =
            if (index % 2 == 0) 1 to (parentTag?.second ?: ProtoNumberType.DEFAULT)
            else 2 to (parentTag?.second ?: ProtoNumberType.DEFAULT)
    }

    internal inner class RepeatedWriter(encoder: ProtobufEncoder, val curTag: ProtoDesc) : ProtobufWriter(encoder) {
        override fun SerialDescriptor.getTag(index: Int) = curTag
    }

    internal class ProtobufEncoder(val out: ByteArrayOutputStream) {

        fun writeBytes(bytes: ByteArray, tag: Int) {
            val header = encode32((tag shl 3) or SIZE_DELIMITED)
            val len = encode32(bytes.size)
            out.write(header)
            out.write(len)
            out.write(bytes)
        }

        fun writeInt(value: Int, tag: Int, format: ProtoNumberType) {
            val wireType = if (format == ProtoNumberType.FIXED) i32 else VARINT
            val header = encode32((tag shl 3) or wireType)
            val content = encode32(value, format)
            out.write(header)
            out.write(content)
        }

        fun writeLong(value: Long, tag: Int, format: ProtoNumberType) {
            val wireType = if (format == ProtoNumberType.FIXED) i64 else VARINT
            val header = encode32((tag shl 3) or wireType)
            val content = encode64(value, format)
            out.write(header)
            out.write(content)
        }

        fun writeString(value: String, tag: Int) {
            val bytes = value.toUtf8Bytes()
            writeBytes(bytes, tag)
        }

        fun writeDouble(value: Double, tag: Int) {
            val header = encode32((tag shl 3) or i64)
            val content = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(value).array()
            out.write(header)
            out.write(content)
        }

        fun writeFloat(value: Float, tag: Int) {
            val header = encode32((tag shl 3) or i32)
            val content = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array()
            out.write(header)
            out.write(content)
        }

        private fun encode32(number: Int, format: ProtoNumberType = ProtoNumberType.DEFAULT): ByteArray =
            when (format) {
                ProtoNumberType.FIXED -> ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(number).array()
                ProtoNumberType.DEFAULT -> encodeVarint(number.toLong())
                ProtoNumberType.SIGNED -> encodeVarint(((number shl 1) xor (number shr 31)))
            }


        private fun encode64(number: Long, format: ProtoNumberType = ProtoNumberType.DEFAULT): ByteArray =
            when (format) {
                ProtoNumberType.FIXED -> ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(number).array()
                ProtoNumberType.DEFAULT -> encodeVarint(number)
                ProtoNumberType.SIGNED -> encodeVarint((number shl 1) xor (number shr 63))
            }
    }

    /**
     *  Source for all varint operations:
     *  https://github.com/addthis/stream-lib/blob/master/src/main/java/com/clearspring/analytics/util/Varint.java
     */
    internal object Varint {
        internal fun encodeVarint(inp: Int): ByteArray {
            var value = inp
            val byteArrayList = ByteArray(10)
            var i = 0
            while (value and 0xFFFFFF80.toInt() != 0) {
                byteArrayList[i++] = ((value and 0x7F) or 0x80).toByte()
                value = value ushr 7
            }
            byteArrayList[i] = (value and 0x7F).toByte()
            val out = ByteArray(i + 1)
            while (i >= 0) {
                out[i] = byteArrayList[i]
                i--
            }
            return out
        }

        internal fun encodeVarint(inp: Long): ByteArray {
            var value = inp
            val byteArrayList = ByteArray(10)
            var i = 0
            while (value and 0x7FL.inv() != 0L) {
                byteArrayList[i++] = ((value and 0x7F) or 0x80).toByte()
                value = value ushr 7
            }
            byteArrayList[i] = (value and 0x7F).toByte()
            val out = ByteArray(i + 1)
            while (i >= 0) {
                out[i] = byteArrayList[i]
                i--
            }
            return out
        }
    }

    companion object : BinaryFormat {
        override val context: SerialModule get() = plain.context

        private fun SerialDescriptor.getProtoDesc(index: Int): ProtoDesc {
            return extractParameters(this, index)
        }

        internal const val VARINT = 0
        internal const val i64 = 1
        internal const val SIZE_DELIMITED = 2
        internal const val i32 = 5

        val plain = ProtoBufWithNullableSupport()

        override fun <T> dump(serializer: SerializationStrategy<T>, obj: T): ByteArray = plain.dump(serializer, obj)
        override fun <T> load(deserializer: DeserializationStrategy<T>, bytes: ByteArray): T = plain.load(deserializer, bytes)
        override fun install(module: SerialModule) = throw IllegalStateException("You should not install anything to global instance")
    }

    override fun <T> dump(serializer: SerializationStrategy<T>, obj: T): ByteArray {
        val encoder = ByteArrayOutputStream()
        val dumper = ProtobufWriter(ProtobufEncoder(encoder))
        dumper.encode(serializer, obj)
        return encoder.toByteArray()
    }

    override fun <T> load(deserializer: DeserializationStrategy<T>, bytes: ByteArray): T {
        return ProtoBuf.load(deserializer, bytes)
    }

}

internal fun InputStream.readExactNBytes(bytes: Int): ByteArray {
    val array = ByteArray(bytes)
    var read = 0
    while (read < bytes) {
        val i = this.read(array, read, bytes - read)
        if (i == -1) throw IOException("Unexpected EOF")
        read += i
    }
    return array
}

internal fun InputStream.readToByteBuffer(bytes: Int): ByteBuffer {
    val arr = readExactNBytes(bytes)
    val buf = ByteBuffer.allocate(bytes)
    buf.put(arr).flip()
    return buf
}