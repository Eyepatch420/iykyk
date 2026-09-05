package com.example.ikyky.features.people.presentation.state

import com.example.ikyky.features.people.domain.model.Appearance
import com.example.ikyky.features.people.domain.model.Person

data class PersonDetailUiState(
    val loading: Boolean = true,
    val person: Person? = null,
    val appearances: List<Appearance> = emptyList(),
    val error: String? = null,
)
