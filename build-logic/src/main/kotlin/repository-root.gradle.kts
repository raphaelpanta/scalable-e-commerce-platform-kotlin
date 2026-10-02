import com.ecommerce.build.CheckVersionLiteralsTask
import com.ecommerce.build.MonorepoExtension
import com.ecommerce.build.ScaffoldServiceTask
import com.ecommerce.build.ToolchainConsistencyTask
import com.ecommerce.build.VersionLiterals
import com.ecommerce.build.verifyConventions
import org.gradle.util.GradleVersion

// Internal convention for the root project only (root build.gradle.kts applies nothing else): the `verify`
// lifecycle task (FR-001) with its toolchain check (FR-010), the version-literal check (FR-004), the
// `monorepo { exempt(...) }` extension and convention guard (FR-013), the frontend hook (FR-015) and the
// `newService` scaffold (FR-012).

val monorepo = extensions.create<MonorepoExtension>("monorepo")
val catalog = the<VersionCatalogsExtension>().named("libs")

val checkToolchain =
    tasks.register<ToolchainConsistencyTask>("checkToolchain") {
        group = "verification"
        description = "Fails when a JDK or Gradle pin disagrees with gradle/libs.versions.toml"
        jdk.set(catalog.findVersion("jdk").get().requiredVersion)
        gradle.set(catalog.findVersion("gradle").get().requiredVersion)
        runningGradle.set(GradleVersion.current().version)
        javaVersionFile.set(layout.projectDirectory.file(".java-version"))
        sdkmanrcFile.set(layout.projectDirectory.file(".sdkmanrc"))
        daemonJvmFile.set(layout.projectDirectory.file("gradle/gradle-daemon-jvm.properties"))
        wrapperPropertiesFile.set(layout.projectDirectory.file("gradle/wrapper/gradle-wrapper.properties"))
        marker.set(layout.buildDirectory.file("toolchain/ok"))
    }

val checkVersionLiterals =
    tasks.register<CheckVersionLiteralsTask>("checkVersionLiterals") {
        group = "verification"
        description = "Fails on any version declared outside gradle/libs.versions.toml"
        rootDirectory.set(layout.projectDirectory)
        scripts.from(
            fileTree(layout.projectDirectory) {
                include("**/*.gradle.kts")
                exclude(VersionLiterals.EXCLUDES)
            },
        )
        marker.set(layout.buildDirectory.file("version-literals/ok"))
    }

val verify =
    tasks.register("verify") {
        group = "verification"
        description = "Builds and checks the whole repository: toolchain, versions, build logic, modules, frontend"
        dependsOn(checkToolchain, checkVersionLiterals)
        // The build-logic included build carries the TestKit suite of the conventions (FR-018).
        gradle.includedBuilds.find { it.name == "build-logic" }?.let { dependsOn(it.task(":check")) }
    }

tasks.register<ScaffoldServiceTask>("newService") {
    group = "build setup"
    description =
        "Scaffold services/<name> with domain, application and infrastructure modules; " +
        "usage: ./gradlew newService -Pname=<context>"
    serviceName.set(providers.gradleProperty("name"))
    repositoryRoot.set(layout.projectDirectory)
}

subprojects {
    pluginManager.withPlugin("base") {
        val moduleCheck = tasks.named("check")
        verify.configure { dependsOn(moduleCheck) }
    }
}

// The web storefront is a separate feature; its npm scripts join verify as soon as it exists (FR-015).
if (file("frontend/package.json").exists()) {
    val npm = providers.gradleProperty("npmExecutable").orElse("npm")
    val frontendLint =
        tasks.register<Exec>("frontendLint") {
            group = "verification"
            description = "Runs the frontend lint script"
            executable = npm.get()
            args("--silent", "--prefix", "frontend", "run", "lint")
        }
    val frontendTest =
        tasks.register<Exec>("frontendTest") {
            group = "verification"
            description = "Runs the frontend test script"
            executable = npm.get()
            args("--silent", "--prefix", "frontend", "run", "test")
            mustRunAfter(frontendLint)
        }
    val frontendCheck =
        tasks.register("frontendCheck") {
            group = "verification"
            description = "Runs the frontend lint and test scripts"
            dependsOn(frontendLint, frontendTest)
        }
    verify.configure { dependsOn(frontendCheck) }
}

gradle.projectsEvaluated {
    project.verifyConventions(monorepo.exemptions.get())
}
