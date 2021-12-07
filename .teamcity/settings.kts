
import jetbrains.buildServer.configs.kotlin.v2019_2.version
import jetbrains.buildServer.configs.kotlin.v2019_2.project
import jetbrains.buildServer.configs.kotlin.v2019_2.buildSteps.gradle
import jetbrains.buildServer.configs.kotlin.v2019_2.triggers.vcs
import jetbrains.buildServer.configs.kotlin.v2019_2.vcs.GitVcsRoot
import jetbrains.buildServer.configs.kotlin.v2019_2.triggers.VcsTrigger.QuietPeriodMode.USE_DEFAULT
import jetbrains.buildServer.configs.kotlin.v2019_2.DslContext

import com.github.rodm.teamcity.pipeline
import com.github.rodm.teamcity.gradle.gradleInitScript
import com.github.rodm.teamcity.gradle.switchGradleBuildStep
import com.github.rodm.teamcity.project.githubIssueTracker

/*
The settings script is an entry point for defining a single
TeamCity project. TeamCity looks for the 'settings.kts' file in a
project directory and runs it if it's found, so the script name
shouldn't be changed and its package should be the same as the
project's external id.

The script should contain a single call to the project() function
with a Project instance or an init function as an argument, you
can also specify both of them to refine the specified Project in
the init function.

VcsRoots, BuildTypes, and Templates of this project must be
registered inside project using the vcsRoot(), buildType(), and
template() methods respectively.

Subprojects can be defined either in their own settings.kts or by
calling the subProjects() method in this project.
*/

version = "2020.2"

project {
    val settingsBranch = DslContext.getParameter("settings.branch", "master")
    description = "Gradle plugin for developing TeamCity plugins (Settings: [${settingsBranch}])"

    val settingsVcs = GitVcsRoot {
        id("TeamcitySettings")
        name = "teamcity-settings"
        url = "https://github.com/balajikammili/teamcity-settings"
    }
    vcsRoot(settingsVcs)

    features {
        githubIssueTracker {
            displayName = "GradleTeamCityPlugin"
            repository = "https://github.com/balajikammili/gradle-teamcity-plugin"
            pattern = """#(\d+)"""
        }
    }

    params {
        param("teamcity.ui.settings.readOnly", "true")
    }

    val vcs = GitVcsRoot {
        id("GradleTeamcityPlugin")
        name = "gradle-teamcity-plugin"
        url = "https://github.com/balajikammili/gradle-teamcity-plugin.git"
    }
    vcsRoot(vcs)

    val buildTemplate = template {
        id("Build")
        name = "build"
        

        vcs {
            root(vcs)
        }

        steps {
            gradle {
                id = "GRADLE_BUILD"
                tasks = "%gradle.tasks%"
                gradleParams = "%gradle.opts%"
                useGradleWrapper = true
                gradleWrapperPath = ""
                enableStacktrace = true
                jdkHome = "%java.home%"
            }
        }

        failureConditions {
            executionTimeoutMin = 10
        }

        features {
            feature {
                id = "perfmon"
                type = "perfmon"
            }
        }

        params {
            param("gradle.tasks", "clean build")
            param("gradle.opts", "")
            param("java.home", "%java8.home%")
        }
    }

    pipeline {
        stage ("Build") {
            build {
                id("BuildJava8")
                name = "Build - Java 8"
                templates(buildTemplate)
            }

            build {
                id("BuildJava11")
                name = "Build - Java 11"
                templates(buildTemplate)

                params {
                    param("java.home", "%java11.home%")
                }
            }

            build {
                id("ReportCodeQuality")
                name = "Report - Code Quality"
                templates(buildTemplate)
                params {
                    param("gradle.tasks", "clean build sonarqube")
                    param("gradle.opts", "%sonar.opts%")
                }

                features {
                    gradleInitScript {
                        scriptName = "sonarqube.gradle"
                    }
                }
            }
        }
        stage ("Functional tests") {
            defaults {
                failureConditions {
                    executionTimeoutMin = 20
                }
            }

            matrix {
                axes {
                    "Java"("8", "11", "12", "13")
                }
                build {
                    val javaVersion = axes["Java"]
                    id("FunctionalTestJava${javaVersion}")
                    name = "Functional Test - Java ${javaVersion}"
                    templates(buildTemplate)
                    failureConditions {
                        executionTimeoutMin = 20
                    }
                    params {
                        param("gradle.tasks", "clean functionalTest")
                        param("java.home", "%java${javaVersion}.home%")
                    }
                }
            }

            matrix {
                axes {
                    "Versions"("14, 6.3", "15, 6.7.1")
                }
                build {
                    val versions = axes["Versions"]?.split(",")
                    val javaVersion = versions?.get(0)?.trim()
                    val gradleVersion = versions?.get(1)?.trim()

                    id("FunctionalTestJava${javaVersion}")
                    name = "Functional Test - Java ${javaVersion}"
                    templates(buildTemplate)
                    params {
                        param("gradle.tasks", "clean functionalTest")
                        param("gradle.version", "${gradleVersion}")
                        param("java.home", "%java${javaVersion}.home%")
                    }
                    steps {
                        switchGradleBuildStep()
                        stepsOrder = arrayListOf("SWITCH_GRADLE", "GRADLE_BUILD")
                    }
                }
            }

            build {
                id("SamplesTestJava8")
                name = "Samples Test - Java 8"
                templates(buildTemplate)
                params{
                    param("gradle.tasks", "clean samplesTest")
                }
            }
        }

        stage ("Publish") {
            build {
                id("DummyPublish")
                name = "Publish to repository"
                templates(buildTemplate)

                params {
                    param("gradle.tasks", "clean build publishPluginPublicationToMavenLocal")
                }

                triggers {
                    vcs {
                        quietPeriodMode = USE_DEFAULT
                        branchFilter = ""
                        triggerRules = """
                            +:root=${DslContext.projectId.absoluteId}_TeamcitySettings;:**
                            +:root=${DslContext.projectId.absoluteId}_GradleTeamcityPlugin:**
                            """.trimIndent()
                    }
                }
            }
        }
    }
}
