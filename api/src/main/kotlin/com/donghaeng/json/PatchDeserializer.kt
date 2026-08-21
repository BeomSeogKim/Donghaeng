package com.donghaeng.json

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.BeanProperty
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.deser.ContextualDeserializer

/**
 * Turns the three JSON states into the three [Patch] cases.
 *
 * **[Patch.Absent] is never produced here**, and cannot be: Jackson does not call a
 * deserializer for a member that is not in the body. What supplies it is the Kotlin
 * default on the request property (`= Patch.Absent`), applied by
 * `jackson-module-kotlin` — so a `Patch` property without that default would arrive
 * as a null the constructor refuses, which is why every one of them carries it.
 *
 * [getNullValue] is the whole reason this class is contextual work rather than a
 * `@JsonCreator`: it is the one hook that fires for an explicit `null`, and it is
 * what makes "the caller sent null" observable at all.
 */
internal class PatchDeserializer private constructor(
    private val value: JsonDeserializer<*>?,
) : JsonDeserializer<Patch<*>>(),
    ContextualDeserializer {
    constructor() : this(null)

    /**
     * `Patch<T>` is generic, so the deserializer for `T` can only be found once
     * Jackson says which property is being read. [BeanProperty.getType] is the
     * declared `Patch<T>`; its first contained type is `T`.
     */
    override fun createContextual(
        context: DeserializationContext,
        property: BeanProperty?,
    ): JsonDeserializer<*> {
        val declared: JavaType = property?.type ?: context.contextualType
        val payload = declared.containedTypeOrUnknown(0)
        return PatchDeserializer(context.findContextualValueDeserializer(payload, property))
    }

    override fun deserialize(
        parser: JsonParser,
        context: DeserializationContext,
    ): Patch<*> = Patch.Set(checkNotNull(value) { "PatchDeserializer was used uncontextualised" }.deserialize(parser, context))

    override fun getNullValue(context: DeserializationContext): Patch<*> = Patch.Cleared
}
