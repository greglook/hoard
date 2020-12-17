Hoard
=====

Hoard is a command-line tool for incrementally managing secure, encrypted
backups of your important files.


## Design

git-like index into a content-addressed store of files, but all the objects are encrypted

index preserves attributes:
- path
- type (directory, file, symlink)
- permissions
- mtime

### Local Structure

```
local
├── .hoard
│   ├── config
│   ├── ignore
│   └── cache
└── ...
```

### Repository Structure

```
repository
├── archive
│   ├── foo
│   │   ├── config
│   │   ├── 20201204-01482-abcd
│   │   ├── 20201210-57391-defg
│   │   └── ...
│   └── bar
│       └── ...
└── data
    ├── meta.properties
    └── blocks
        ├── 11140000
        │   ├── debc06fba391088613aafb041a23f0cb8f5ceaad9b487e2928897a75933778
        │   ├── b2c7eef7421670bd4aca894ed27a94c8219e181d7b63006bea3038240164c1
        │   └── ...
        ├── 11140001
        │   └── ...
        └── ...
```


## Configuration

`hoard` draws its configuration from `$XDG_CONFIG/hoard/config`. This is a
simple INI file with a few sections.

- `encrypt`

  Set the command used to encrypt data. This command must accept the plaintext
  on STDIN and write the ciphertext to STDOUT. It should not require user
  interaction.

- `decrypt`

  Set the command used to decrypt data. This command must accept the ciphertext
  on STDIN and write the plaintext to STDOUT. It should not require user
  interaction.

- `type`

  Specify the type of repository. Currently supports `file`.

- `trim.keep-versions`

  The number of versions of the archive to preserve when trimming.

- `trim.keep-days`

  The number of days of archive versions to preserve when trimming.

### Defaults Section

Default configuration lives in the `[defaults]` section. This will be used for
any repository that does not specify a value for the configuration set here.

### Repository Sections

Each repository is configured in a section under the `repository` key, so a
local repository would be `[repository.local]`.


## Workflow

What are the workflows/use-cases that need to be supported by the tool?

- I want to initialize a new repository.
- I want to inspect the archives and versions stored in a repository.
- I have a local directory I want to archive that has not been stored before.
- I have a local directory that has been archived, and I want to check its
  state compared to the latest version in a repository.
- I have a local directory that has been archived, and I want to store a new
  version in a repository.
- I have a local directory that has been archived, and I want to restore some
  files in it to match the version in a repository.
- I want to restore a version of an archive into a new local directory.


## Operations

### Initialize Repository

```
hoard create [repo]
```

Initialize a new empty repository structure.

### List Archives

```
hoard list [repo...]
```

List the archive namespaces present in the repositories.
- optionally include metadata?

### Repository Information

```
hoard show <repo> [archive] [version]
```

View information about an archive including metadata, storage statistics, and
available versions.

### Sync Repository

```
hoard sync <from-repo> <to-repo> [archive...]
    [--dry-run]
```

Synchronize the versions and data from one repository to another. Specific
archive names may be provided, or all archives will be synchronized by default.

### Initialize Archive

```
hoard archive <repo> <archive> [source-path]
```

### Store Data

```
hoard store <repo> <archive> [source-path]
    [--include PATTERN ...]
    [--exclude PATTERN ...]
    [--update]
    [--dry-run]
```

creating a backup:
- iterating over all of the files on disk
- computing a hash of each file
- if the hash is not present on the other end, encrypt and send the file
- generate an index of all files with hash/mtime/size/path
- encrypt and store the timestamped index

### Restore Data

```
hoard restore <repo> <archive> [target-path]
    [--version ID]
    [--prefix PATH ...]
    [--exclude PATH ...]
    [--overwrite]
    [--dry-run]
```

restoring:
- selecting index to restore from and a location to restore to
    - could accept prefix to scope restored files to
- pull and decrypt index
- compare index data to files on disk
- for each missing (or non-matching) file, pull, decrypt, and place file
    - probably want to prompt to overwrite or --force

### Verify Archive

```
hoard verify <repo> [archive] [version]
    [--full]
```

- verify index by checking that all files are present in target storage
- deep verify would decrypt and re-hash files

### Trim Data

```
hoard trim <repo> [archive]
    [--keep-versions N]
    [--keep-days D]
    [--dry-run]
```

- prune data outside a list of indexes to keep
