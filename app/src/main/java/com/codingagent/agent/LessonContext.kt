package com.codingagent.agent

/**
 * ONE JOB: Hold the current experience and evolution loaders so prompts can read lessons
 * without AutonomousAgent growing another field.
 */
object LessonContext {
    @Volatile
    private var experienceLines: () -> List<String> = { emptyList() }

    @Volatile
    private var evolutionVersions: () -> List<EvolutionVersion> = { emptyList() }

    fun bindExperience(loader: () -> List<String>) {
        experienceLines = loader
    }

    fun bindEvolution(loader: () -> List<EvolutionVersion>) {
        evolutionVersions = loader
    }

    fun prompt(): String = LessonSynthesizer.synthesize(experienceLines(), evolutionVersions())
}
