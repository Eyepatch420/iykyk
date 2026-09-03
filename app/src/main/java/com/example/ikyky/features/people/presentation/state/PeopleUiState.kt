package com.example.ikyky.features.people.presentation.state

import com.example.ikyky.features.people.domain.model.IdentityDiagnostics
import com.example.ikyky.features.people.domain.model.Person

data class PeopleUiState(
    val loading: Boolean = false,
    val people: List<Person> = emptyList(),
    val diagnostics: IdentityDiagnostics? = null,
    val error: String? = null,
) {
    val uniqueCount: Int get() = people.size
    val totalAppearances: Int get() = people.sumOf { it.appearanceCount }
}
