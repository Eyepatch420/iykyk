package com.example.ikyky.features.people.domain.repository

import com.example.ikyky.features.people.domain.model.IdentityDiagnostics
import com.example.ikyky.features.people.domain.model.Person

/**
 * In-memory holder for the current processing session's people/identity
 * results. Populated by the Phase-4 identity stage, read by the people /
 * collage / result screens. Not persisted — the assignment is single-session.
 */
interface PeopleResultRepository {
    fun setPeople(sessionId: String, people: List<Person>, diagnostics: IdentityDiagnostics? = null)
    fun getPeople(sessionId: String): List<Person>
    fun getDiagnostics(sessionId: String): IdentityDiagnostics?
    fun clear()
}
