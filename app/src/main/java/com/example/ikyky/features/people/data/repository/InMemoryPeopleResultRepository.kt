package com.example.ikyky.features.people.data.repository

import com.example.ikyky.features.people.domain.model.IdentityDiagnostics
import com.example.ikyky.features.people.domain.model.Person
import com.example.ikyky.features.people.domain.repository.PeopleResultRepository
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-lifetime store for the current session's people. Single-session app,
 * so no persistence.
 */
class InMemoryPeopleResultRepository : PeopleResultRepository {

    private data class Entry(
        val people: List<Person>,
        val diagnostics: IdentityDiagnostics?,
    )

    private val bySession = ConcurrentHashMap<String, Entry>()

    override fun setPeople(sessionId: String, people: List<Person>, diagnostics: IdentityDiagnostics?) {
        bySession[sessionId] = Entry(people, diagnostics)
    }

    override fun getPeople(sessionId: String): List<Person> = bySession[sessionId]?.people.orEmpty()

    override fun getDiagnostics(sessionId: String): IdentityDiagnostics? =
        bySession[sessionId]?.diagnostics

    override fun clear() = bySession.clear()
}
