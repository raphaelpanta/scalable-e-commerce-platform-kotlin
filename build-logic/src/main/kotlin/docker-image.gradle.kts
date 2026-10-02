import com.ecommerce.build.DockerImageExtension

// `dockerImage` builds the service image from the shared multi-stage platform/docker/Dockerfile, run from
// the repository root with the module path as build argument. Deliberately not part of `check`/`verify`.
val docker = extensions.create<DockerImageExtension>("docker")
docker.dockerExecutable.convention("docker")

val serviceName: String = requireNotNull(project.parent) { "$path must live inside a service directory" }.name

tasks.register<Exec>("dockerImage") {
    val repositoryRoot: Directory = isolated.rootProject.projectDirectory
    val dockerfile: File = repositoryRoot.file("platform/docker/Dockerfile").asFile
    group = "build"
    description = "Builds the $serviceName container image from platform/docker/Dockerfile"
    workingDir(repositoryRoot.asFile)
    executable = docker.dockerExecutable.get()
    args(
        "build",
        "-f",
        "platform/docker/Dockerfile",
        "--build-arg",
        "SERVICE_MODULE=${project.path}",
        "-t",
        "$serviceName:${project.version}",
        ".",
    )
    doFirst {
        if (!dockerfile.isFile) {
            throw GradleException("platform/docker/Dockerfile not found (expected at $dockerfile)")
        }
    }
}
