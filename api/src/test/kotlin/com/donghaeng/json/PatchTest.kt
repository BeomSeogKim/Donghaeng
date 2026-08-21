package com.donghaeng.json

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
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
    fun `the members are independent — one sent leaves the other absent`() {
        val body = mapper.readValue("""{"count":150}""", Body::class.java)

        assertThat(body.date).isEqualTo(Patch.Absent)
        assertThat(body.count).isEqualTo(Patch.Set(150))
    }
}
