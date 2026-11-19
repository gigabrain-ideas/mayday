package mayday.adapters.grpc

import com.google.protobuf.ByteString
import com.google.protobuf.Descriptors.Descriptor
import com.google.protobuf.Descriptors.EnumDescriptor
import com.google.protobuf.Descriptors.EnumValueDescriptor
import com.google.protobuf.Descriptors.FieldDescriptor
import com.google.protobuf.Descriptors.FieldDescriptor.JavaType
import com.google.protobuf.ListValue
import com.google.protobuf.Message
import com.google.protobuf.MessageOrBuilder
import com.google.protobuf.NullValue
import com.google.protobuf.ProtocolMessageEnum
import com.google.protobuf.Struct
import com.google.protobuf.Timestamp
import com.google.protobuf.Value
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import mayday.shared.DomainException
import mayday.shared.Invalid
import mayday.shared.JSON
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Converts between protobuf messages and the JSON shape of the domain model.
 * Rules: a proto field matches the domain property with the same JSON name (team_ids = teamIds);
 * an enum value carries the enum type as prefix (SEVERITY_SEV1 = SEV1) and value 0 means "not set";
 * Timestamp is an ISO-8601 string; Struct is a JSON object. Domain properties without a proto field are dropped,
 * so secrets such as token hashes never leave the server.
 */
object ProtoJson {
    private val prefixes = ConcurrentHashMap<EnumDescriptor, String>()
    private val fieldsByJsonName = ConcurrentHashMap<Descriptor, Map<String, FieldDescriptor>>()

    fun prefix(e: EnumDescriptor): String = prefixes.computeIfAbsent(e) { it.name.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").uppercase() + "_" }

    fun enumName(v: EnumValueDescriptor): String = v.name.removePrefix(prefix(v.type))

    // ---- proto to JSON ----

    fun toJson(m: MessageOrBuilder): JsonElement = when (m.descriptorForType.fullName) {
        TIMESTAMP -> JsonPrimitive(instant(m as Timestamp).toString())
        STRUCT -> fromStruct(m as Struct)
        else -> buildJsonObject {
            for (f in m.descriptorForType.fields) {
                when {
                    f.isMapField -> {
                        val key = f.messageType.findFieldByName("key")
                        val value = f.messageType.findFieldByName("value")
                        put(f.jsonName, buildJsonObject {
                            (m.getField(f) as List<*>).forEach { e -> e as Message; put(e.getField(key).toString(), single(value, e.getField(value))) }
                        })
                    }
                    f.isRepeated -> put(f.jsonName, JsonArray((m.getField(f) as List<*>).map { single(f, it!!) }))
                    f.hasPresence() -> if (m.hasField(f)) put(f.jsonName, single(f, m.getField(f)))
                    f.javaType == JavaType.ENUM -> (m.getField(f) as EnumValueDescriptor).takeIf { it.number != 0 }?.let { put(f.jsonName, enumName(it)) }
                    else -> put(f.jsonName, single(f, m.getField(f)))
                }
            }
        }
    }

    private fun single(f: FieldDescriptor, v: Any): JsonElement = when (f.javaType) {
        JavaType.MESSAGE -> toJson(v as Message)
        JavaType.ENUM -> JsonPrimitive(enumName(v as EnumValueDescriptor))
        JavaType.STRING -> JsonPrimitive(v as String)
        JavaType.BOOLEAN -> JsonPrimitive(v as Boolean)
        JavaType.BYTE_STRING -> JsonPrimitive(Base64.getEncoder().encodeToString((v as ByteString).toByteArray()))
        else -> JsonPrimitive(v as Number)
    }

    /** Proto message to a domain value. Missing or malformed input becomes an [Invalid] error. */
    fun <T> decode(m: MessageOrBuilder, ser: DeserializationStrategy<T>): T = try {
        JSON.decodeFromJsonElement(ser, toJson(m))
    } catch (e: DomainException) {
        throw e
    } catch (e: SerializationException) {
        throw Invalid("Invalid request: ${e.message?.lineSequence()?.firstOrNull()?.take(200)}")
    } catch (e: IllegalArgumentException) {
        throw Invalid("Invalid request: ${e.message?.lineSequence()?.firstOrNull()?.take(200)}")
    }

    // ---- JSON to proto ----

    fun <B : Message.Builder> merge(json: JsonObject, b: B): B {
        val byName = fieldsByJsonName.computeIfAbsent(b.descriptorForType) { d -> d.fields.associateBy { it.jsonName } }
        for ((k, v) in json) {
            if (v is JsonNull) continue
            val f = byName[k] ?: continue
            when {
                f.isMapField -> {
                    val key = f.messageType.findFieldByName("key")
                    val value = f.messageType.findFieldByName("value")
                    for ((mk, mv) in v.jsonObject) {
                        if (mv is JsonNull) continue
                        val e = b.newBuilderForField(f)
                        e.setField(key, single(key, JsonPrimitive(mk), e))
                        e.setField(value, single(value, mv, e))
                        b.addRepeatedField(f, e.build())
                    }
                }
                f.isRepeated -> v.jsonArray.forEach { if (it !is JsonNull) b.addRepeatedField(f, single(f, it, b)) }
                else -> b.setField(f, single(f, v, b))
            }
        }
        return b
    }

    private fun single(f: FieldDescriptor, v: JsonElement, parent: Message.Builder): Any = when (f.javaType) {
        JavaType.MESSAGE -> when (f.messageType.fullName) {
            TIMESTAMP -> timestamp(Instant.parse(v.jsonPrimitive.content))
            STRUCT -> toStruct(v.jsonObject)
            else -> merge(v.jsonObject, parent.newBuilderForField(f)).build()
        }
        JavaType.ENUM -> f.enumType.findValueByName(prefix(f.enumType) + v.jsonPrimitive.content)
            ?: throw IllegalStateException("${f.enumType.fullName} has no value for ${v.jsonPrimitive.content}")
        JavaType.STRING -> v.jsonPrimitive.content
        JavaType.LONG -> v.jsonPrimitive.long
        JavaType.INT -> v.jsonPrimitive.int
        JavaType.DOUBLE -> v.jsonPrimitive.double
        JavaType.FLOAT -> v.jsonPrimitive.float
        JavaType.BOOLEAN -> v.jsonPrimitive.boolean
        JavaType.BYTE_STRING -> ByteString.copyFrom(Base64.getDecoder().decode(v.jsonPrimitive.content))
        else -> throw IllegalStateException("Unsupported field ${f.fullName}")
    }

    // ---- well-known types ----

    fun instant(t: Timestamp): Instant = Instant.ofEpochSecond(t.seconds, t.nanos.toLong())
    fun timestamp(i: Instant): Timestamp = Timestamp.newBuilder().setSeconds(i.epochSecond).setNanos(i.nano).build()

    fun toStruct(o: JsonObject): Struct = Struct.newBuilder().putAllFields(o.mapValues { toValue(it.value) }).build()
    fun fromStruct(s: Struct): JsonObject = JsonObject(s.fieldsMap.mapValues { fromValue(it.value) })

    private fun toValue(e: JsonElement): Value = when (e) {
        is JsonNull -> Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build()
        is JsonObject -> Value.newBuilder().setStructValue(toStruct(e)).build()
        is JsonArray -> Value.newBuilder().setListValue(ListValue.newBuilder().addAllValues(e.map(::toValue))).build()
        is JsonPrimitive -> when {
            e.isString -> Value.newBuilder().setStringValue(e.content).build()
            e.booleanOrNull != null -> Value.newBuilder().setBoolValue(e.booleanOrNull!!).build()
            else -> Value.newBuilder().setNumberValue(e.doubleOrNull ?: 0.0).build()
        }
    }

    private fun fromValue(v: Value): JsonElement = when (v.kindCase) {
        Value.KindCase.STRUCT_VALUE -> fromStruct(v.structValue)
        Value.KindCase.LIST_VALUE -> JsonArray(v.listValue.valuesList.map(::fromValue))
        Value.KindCase.STRING_VALUE -> JsonPrimitive(v.stringValue)
        Value.KindCase.BOOL_VALUE -> JsonPrimitive(v.boolValue)
        Value.KindCase.NUMBER_VALUE -> v.numberValue.let { if (it % 1.0 == 0.0 && kotlin.math.abs(it) < 1e15) JsonPrimitive(it.toLong()) else JsonPrimitive(it) }
        else -> JsonNull
    }

    private const val TIMESTAMP = "google.protobuf.Timestamp"
    private const val STRUCT = "google.protobuf.Struct"
}

/** Domain value to a proto builder. */
inline fun <reified T, B : Message.Builder> T.into(b: B): B = ProtoJson.merge(JSON.encodeToJsonElement(serializer<T>(), this).jsonObject, b)

/** Domain list to a proto wrapper message with a repeated "items" field. */
inline fun <reified T, B : Message.Builder> List<T>.itemsInto(b: B): B =
    ProtoJson.merge(buildJsonObject { put("items", JSON.encodeToJsonElement(serializer<List<T>>(), this@itemsInto)) }, b)

inline fun <reified T> MessageOrBuilder.decode(): T = ProtoJson.decode(this, serializer<T>())

/** Proto enum to the domain enum with the same name; value 0 (not set) is null. */
inline fun <reified E : Enum<E>> ProtocolMessageEnum.toDomain(): E? {
    val v = runCatching { valueDescriptor }.getOrNull()?.takeIf { it.number != 0 } ?: return null
    val name = ProtoJson.enumName(v)
    return enumValues<E>().firstOrNull { it.name == name } ?: throw Invalid("Unknown value $name")
}
