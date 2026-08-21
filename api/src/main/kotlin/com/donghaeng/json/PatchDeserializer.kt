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
 *
 * **[Patch.Set] never carries `null`, and that has to be enforced here** (added
 * 2026-08-22 in review of `#173`). Jackson returns `null` from `deserialize` too,
 * not only from [getNullValue]: an empty or blank string coerces to null for most
 * types, and neither Boot nor `Jackson2ObjectMapperBuilder` changes that. Nothing
 * downstream would catch it — `Patch.Set`'s payload is `T`, whose upper bound is
 * `Any?`, so Kotlin emits no null check, and a validator reading `(value as?
 * Patch.Set)?.value ?: return true` reads such a member as "not sent". Observed:
 * `{"guaranteedHeadcount":""}` answered **200 and erased the 보증인원** the couple
 * had agreed with their venue.
 *
 * **It is refused rather than read as [Patch.Cleared]**, which is the same choice
 * `Patch` itself makes: three named states and no unnamed conventions. `""` meaning
 * "clear" would be a second spelling of `null`, and a client that has one spelling
 * available does not need two (notes/2026-08-22-decision-partial-update-shape.md
 * §1).
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
    ): Patch<*> {
        val read = checkNotNull(value) { "PatchDeserializer was used uncontextualised" }.deserialize(parser, context)
        // Carries no submitted text: `detail` is diagnostic, and Jackson's own
        // messages are already the reason `docs/api-spec.md` says never to render it.
        return Patch.Set(read ?: context.reportInputMismatch(Patch::class.java, NOT_A_VALUE))
    }

    override fun getNullValue(context: DeserializationContext): Patch<*> = Patch.Cleared

    private companion object {
        private const val NOT_A_VALUE =
            "not a value for this member: send null to clear it, or omit it to leave it alone"
    }
}
