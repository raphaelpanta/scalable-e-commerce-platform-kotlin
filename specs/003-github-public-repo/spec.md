# Feature Specification: Publish Repository Publicly on GitHub

**Feature Branch**: `003-github-public-repo`

**Created**: 2026-10-02

**Status**: Draft

**Input**: User description: "create public repo on github"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Project lives in a public hosted repository (Priority: P1)

The maintainer puts the project under version control and publishes it as a public repository on
the chosen hosting service, so that the history is preserved, the project is discoverable, and
collaborators and automated checks can work against a shared remote.

**Why this priority**: Every later feature (pull-request gates, continuous integration,
contributions) depends on a shared remote existing. Nothing else in this feature matters until the
repository is published.

**Independent Test**: Open the repository's public address in a private browser window while
signed out and confirm the project files, history and description are visible; clone it anonymously
and confirm the clone matches the local working copy.

**Acceptance Scenarios**:

1. **Given** the local project directory with no version control, **When** the maintainer
   publishes it, **Then** a public repository exists under the maintainer's account with the
   project's name, a one-line description and topic tags, and the complete local content is pushed
   as the initial history.
2. **Given** the published repository, **When** anyone who is signed out opens its address,
   **Then** they can browse the files and history and read the project overview.
3. **Given** the published repository, **When** the maintainer runs the clone command on another
   machine, **Then** the clone builds the same content with no local-only files included.

---

### User Story 2 - Nothing sensitive or temporary is published (Priority: P1)

Before the first publication and on every later push, the maintainer can be sure that no secret,
credential, personal data, local tooling cache or review scratch material leaves the machine.

**Why this priority**: A public repository is permanent in practice; a leaked secret or personal
data cannot be reliably withdrawn once pushed. The constitution's Security by Design principle
forbids secrets in version control.

**Independent Test**: Place a fake credential in a tracked file and attempt to push; confirm the
push is refused and the credential is named. Confirm that the agent caches, local settings and the
constitution's review comment are absent from the published content.

**Acceptance Scenarios**:

1. **Given** a tracked file containing a secret-shaped value, **When** the maintainer attempts to
   push, **Then** the push is blocked and the finding identifies the file and line.
2. **Given** the local working copy, **When** the initial publication is made, **Then** build
   outputs, dependency caches, agent and editor caches, local-only settings and the constitution's
   temporary review comment are not part of the published content.
3. **Given** the published repository, **When** the hosting service scans it, **Then** secret
   scanning and push protection are active and report zero findings.

---

### User Story 3 - Repository is ready for public collaboration (Priority: P2)

A visitor to the repository understands what the project is, how to build it, how to contribute,
under which terms the code may be used, and how to report a security issue. The main branch is
protected so changes arrive only through reviewed pull requests whose checks pass.

**Why this priority**: Public visibility without these basics produces confused visitors,
unlicensed code and unreviewed changes on the main branch, which undermines the constitution's
quality gates.

**Independent Test**: As a signed-out visitor, read the overview, locate the license, the
contribution guide and the security policy within two minutes; as a collaborator, attempt a direct
push to the main branch and confirm it is rejected.

**Acceptance Scenarios**:

1. **Given** the published repository, **When** a visitor opens it, **Then** they find an overview
   stating purpose, architecture summary, how to build and verify, and links to the constitution
   and the specifications.
2. **Given** the published repository, **When** a visitor looks for usage terms, **Then** a license
   file is present at the repository root and the hosting service displays its name.
3. **Given** the published repository, **When** a contributor attempts to push directly to the
   main branch, **Then** the push is rejected and only pull requests with passing required checks
   and at least one approval can be merged.
4. **Given** a security researcher, **When** they look for how to report a vulnerability, **Then**
   a security policy explains the private reporting channel and expected response time.
5. **Given** a new contributor, **When** they open a pull request, **Then** a template asks them to
   confirm the constitution's checklist items: tests at each layer, threat model considered,
   mutation threshold held, and surviving-mutant justifications.

---

### Edge Cases

- The chosen repository name is already taken under the account: the maintainer is told before
  anything is created and can choose another name.
- The maintainer is not signed in to the hosting service, or lacks permission to create public
  repositories in the chosen owner: the operation stops with a clear message before any local
  change is made.
- A file that should be ignored was already staged: it is removed from the staged set before the
  initial publication, and the ignore rules prevent it from returning.
- The hosting service is unreachable mid-operation: the local history remains intact and the
  publication can be retried without duplicating commits.
- A later push includes a secret: push protection blocks it, and the documented remediation
  explains how to rewrite the offending commit and rotate the exposed credential.
- The repository is accidentally created private: the visibility is corrected and verified while
  signed out.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The project MUST be placed under version control locally with a single initial
  history that contains the complete project content, excluding ignored paths.
- **FR-002**: Ignore rules MUST exclude build outputs, dependency caches, agent and editor caches,
  local-only settings, temporary files and operating-system artefacts, and MUST be in place
  before the initial publication.
- **FR-003**: The constitution's temporary review comment MUST be removed before the initial
  publication, as the constitution itself requires.
- **FR-004**: A public repository MUST be created under the maintainer's account, named after the
  project, with a one-line description and topic tags describing the domain and stack.
- **FR-005**: The complete local history MUST be pushed to the public repository and the local
  copy MUST track it as its default remote.
- **FR-006**: The repository MUST contain the MIT License at its root, with the current year and
  the maintainer as copyright holder, and the hosting service MUST recognise it as MIT.
- **FR-007**: The repository MUST contain an overview document stating the project's purpose,
  architecture summary, how to build and verify, and links to the constitution and specifications.
- **FR-008**: The repository MUST contain a contribution guide, a code of conduct, a security
  policy with a private reporting channel and response-time commitment, and a pull-request
  template reflecting the constitution's pull-request gate.
- **FR-009**: Secret scanning and push protection MUST be enabled on the repository, and a local
  pre-push check MUST block pushes containing secret-shaped values.
- **FR-010**: The main branch MUST be protected: direct pushes and force pushes rejected, pull
  requests required, at least one approving review required, required status checks enforced
  once continuous integration exists, and conversation resolution required before merge.
- **FR-011**: Pull requests MUST be merged by a method that keeps a linear history.
- **FR-012**: The operation MUST verify its own result: the repository is reachable while signed
  out, the default branch matches the local one, and the published tree contains no ignored
  paths.
- **FR-013**: All of the above MUST be reproducible from a documented procedure so the same
  setup can be applied to future repositories of the platform.

### Key Entities

- **Repository**: The published project with a name, visibility, description, topics, default
  branch and remote address.
- **Ignore Rule Set**: The list of paths that must never be published.
- **Community Documents**: Overview, license, contribution guide, code of conduct, security policy
  and pull-request template.
- **Branch Protection Policy**: The rules governing how changes reach the main branch.
- **Secret Finding**: A detected secret-shaped value with its file, line and remediation status.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A signed-out visitor can open the repository, read the overview and locate the
  license, contribution guide and security policy in under 2 minutes.
- **SC-002**: The published content contains zero ignored paths, zero secret-scanning findings
  and zero local-only files, verified by an anonymous clone compared to the ignore rules.
- **SC-003**: 100% of attempted direct pushes to the main branch are rejected; 100% of pushes
  containing a seeded secret-shaped value are blocked before reaching the remote.
- **SC-004**: An anonymous clone on a second machine passes the project's verify command once
  the build exists, demonstrating the published content is complete.
- **SC-005**: The hosting service displays the recognised license name and the community-health
  checklist shows every item satisfied.
- **SC-006**: The entire publication procedure, from an unversioned directory to a verified public
  repository, completes in under 30 minutes following the documentation.

## Assumptions

- The repository is created under the maintainer's personal account using the project directory
  name, `scalable-e-commerce-platform-kotlin`, as the repository name. Moving it to an
  organisation later is out of scope.
- The license is MIT, chosen by the maintainer on 2026-10-02 for maximum reuse with attribution
  only; contributions are accepted under the same terms.
- The default branch is named `main`.
- The hosting service's standard command-line tooling is installed and authenticated on the
  maintainer's machine; the operation stops with a clear message if it is not.
- Continuous integration workflows are delivered by the build bootstrap feature (specs/002);
  this feature only prepares branch protection to require their checks once they exist.
- Community documents are concise first versions; the overview links to the constitution and the
  specifications rather than duplicating them.
- Topic tags describe the domain (e-commerce, microservices) and the stack as named in the
  constitution.
- The private security-reporting channel is the hosting service's built-in private vulnerability
  reporting, with a stated first-response commitment of 5 business days.
