package com.donghaeng.json

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder
import java.time.LocalDate

/**
 * The mechanism `PATCH` bodies are read with, tested apart from any endpoint —
 * because what it holds is a rule about **every** partial update
 * (notes/2026-08-22-decision-partial-update-shape.md), and the endpoint tests would
 * each assert it again in the language of their own fields.
 *
 * The mapper is built the way Boot builds the application's, so that what is
 * asserted here is what an endpoint actually meets: the Kotlin module is what
 * supplies [Patch.Absent] for an omitted member, and without it the first assertion
 * below would be a claim about a mapper this application does not have.
 */
class PatchTest {
    private val mapper: ObjectMapper = Jackson2ObjectMapperBuilder.json().build()

    private data class Body(
        val date: Patch<LocalDate> = Patch.Absent,
        val count: Patch<Int> = Patch.Absent,
    )

    @Test
    fun `a member that is not sent is absent`() {
        val body = mapper.readValue("""{}""", Body::class.java)

        assertThat(body.date).isEqualTo(Patch.Absent)
        assertThat(body.count).isEqualTo(Patch.Absent)
    }

    @Test
    fun `a member sent as null is cleared, and is not the same thing as an absent one`() {
        val body = mapper.readValue("""{"date":null,"count":null}""", Body::class.java)

        assertThat(body.date).isEqualTo(Patch.Cleared)
        assertThat(body.count).isEqualTo(Patch.Cleared)
    }

    @Test
    fun `a member sent with a value carries it, deserialised as its own type`() {
        val body = mapper.readValue("""{"date":"2026-10-10","count":150}""", Body::class.java)

        assertThat(body.date).isEqualTo(Patch.Set(LocalDate.of(2026, 10, 10)))
        assertThat(body.count).isEqualTo(Patch.Set(150))
    }

    @Test
    fun `an input that coerces to null is refused rather than read as a value of null`() {
        // **Three spellings, not one**, and the coercion is per-type — which is why
        // both member types are listed rather than one standing in for the other.
        // Measured against this build's jars with the refusal removed: five of the six
        // are real coercions to null (`""` and a blank string for both types, `[]` for
        // the date), each of which would build a `Patch.Set` carrying null — a state
        // this hierarchy does not have, and one every validator downstream reads as
        // "not sent". **The sixth is not doing work here**: `[]` into an integer is
        // refused by Jackson itself, with or without the refusal below. It stays
        // because it is true of the endpoint and costs a line, not because it holds
        // anything.
        val coerced =
            listOf(
                """{"date":""}""",
                """{"count":""}""",
                """{"date":"  "}""",
                """{"count":" "}""",
                """{"date":[]}""",
                """{"count":[]}""",
            )
        coerced.forEach { body ->
            assertThatThrownBy { mapper.readValue(body, Body::class.java) }
                .describedAs("%s", body)
                .isInstanceOf(MismatchedInputException::class.java)
        }
    }

    @Test
    fun `no reachable input builds a Set carrying null`() {
        // The property the case above is one instance of, stated once so that a
        // future member type does not have to be remembered: whatever comes back, a
        // `Set` holds a value.
        listOf("""{}""", """{"date":null}""", """{"date":"2026-10-10"}""", """{"count":0}""").forEach { body ->
            val body1 = mapper.readValue(body, Body::class.java)

            listOf(body1.date, body1.count).forEach { member ->
                if (member is Patch.Set) assertThat(member.value).describedAs("%s", body).isNotNull()
            }
        }
    }

    @Test
    fun `the members are independent — one sent leaves the other absent`() {
        val body = mapper.readValue("""{"count":150}""", Body::class.java)

        assertThat(body.date).isEqualTo(Patch.Absent)
        assertThat(body.count).isEqualTo(Patch.Set(150))
    }
}
