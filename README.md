# Job Backup & Restore (Jenkins Plugin)

**Job Backup & Restore** allows Jenkins administrators to export and import jobs and folders using a ZIP archive. It is designed for backup, migration, and replication of job configurations between Jenkins environments.

Access the plugin via: **Manage Jenkins → Job Backup & Restore**.

---

## Requirements

- **Java:** 17 or newer (minimum supported)
- **Jenkins:** Jenkins LTS (recommended)

> Note: Job types such as Pipeline, Multibranch, Maven, and Folders require their respective plugins to be installed on both source and target Jenkins instances.

---

## Features

### Export
- Select one or more jobs and/or folders from the current Jenkins instance.
- Download a ZIP file containing each selected item’s `config.xml`.
- Preserves folder hierarchy.
- Supports selecting folders even when the folder node is *synthetic* in the UI (ancestor paths that may not exist as real items).

The generated ZIP filename uses a UTC timestamp, for example:

`job-backup-2025-12-11T01_42_36.025Z.zip`

### Import
- Upload a ZIP file (typically created by this plugin).
- Preview detected jobs/folders before applying changes.
- Select items to apply (supports folder selection as a prefix, importing descendants).
- Automatically creates missing folders in the target Jenkins instance.
- Updates existing jobs/folders when an item with the same full name already exists.
- Creates new jobs/folders when they do not exist.

### Safety and UX
- Import uses a session-based workflow (upload → preview → apply) so the UI can remain responsive and predictable.
- ZIP extraction is protected against Zip Slip (path traversal) attempts.
- Validation errors are displayed as Jenkins UI notifications (instead of Jetty error pages).

---

## Important Notes / Limitations

- **Credentials are not exported.**  
  Jobs that reference credentials require those credentials to already exist in the target Jenkins instance.

- **Plugins must be compatible.**  
  If a job type requires specific plugins (Pipeline, Multibranch, Maven, Folder/Organization Folder, etc.), those plugins must be installed on the target Jenkins instance before importing.

- **Administrative permission required.**  
  Only users with **Jenkins ADMINISTER** permission can access this feature.

- **Configuration-only scope.**  
  This plugin exports/imports job configuration (`config.xml`). It does not export build history, artifacts, workspace contents, credentials, or global Jenkins configuration.

---

## How It Works

### Selection Semantics (Export and Import)
Selection is treated as a **prefix** to make the plugin resilient and intuitive:

- If you select `A/B`, the plugin includes:
    - `A/B` itself (if present)
    - everything whose full name starts with `A/B/` (children and deeper descendants)

This applies consistently across:
- Export selection (live Jenkins items)
- Import selection (entries discovered from ZIP structure)

### Folder Handling
- Export UI includes *synthetic folder nodes* for ancestor paths so users can select folders easily.
- Import can create missing folder paths automatically during apply (requires the folders plugin for folder creation).

---

## User Interface Overview

The plugin uses standard Jenkins Jelly views and the Jenkins Design Library patterns.

### Pages
- **Export** (`/manage/job-backup/`)
    - Tree view of jobs/folders with checkboxes
    - “Select all / partial selection” checkbox in header
    - Downloads ZIP

- **Import** (`/manage/job-backup/import`)
    - ZIP upload form
    - Displays last apply result for a session (applied count + failures table)

- **Preview Import** (`/manage/job-backup/preview?sessionId=...`)
    - Tree view of import candidates (from ZIP)
    - Shows whether each item already exists
    - Apply selected items into Jenkins

### Icons
The UI uses Ionicons symbols via Jenkins’ symbol system (e.g., folder, document, git-branch).

---

## Development

### Repository
This plugin is developed in:

`franciscoernestoteixeira/jenkins-job-backup-plugin`

(See the GitHub repository for source, issues, and contribution workflow.)

### Maven Setup: `settings.xml` and local `repositories/`
To simplify dependency resolution for plugin development, this repository includes a `settings.xml` that configures Maven repositories used by the project.

In addition:
- A **`repositories/` folder must exist in the project root** (as referenced by the project’s Maven setup).

Typical usage:

```bash
mvn -s settings.xml clean verify
```

If you use an IDE:
- Configure Maven to use the repository’s `settings.xml`.

### UI References (Jenkins)
These references are useful when developing or improving the plugin UI:

- Jenkins Design Library (look & feel patterns)  
  https://weekly.ci.jenkins.io/design-library/

- Jelly basics and conventions  
  https://wiki.jenkins.io/display/JENKINS/Basic+guide+to+Jelly+usage+in+Jenkins

- Jenkins symbols (icon system)  
  https://www.jenkins.io/doc/developer/views/symbols/

---

## Contributing

Contributions are welcome—especially around:
- UI/UX improvements (selection behavior, accessibility, layout consistency)
- Support for additional job/folder types and their labeling
- Hardening import/export edge cases
- Automated tests and CI improvements
- Documentation improvements

When reporting an issue, include:
- Jenkins version
- Plugin version
- What you exported/imported (job types, plugins involved)
- Relevant logs and screenshots

The UI includes a “Report an issue” link that pre-fills the plugin version when available.

---

## License

MIT License. See `LICENSE` for details.
