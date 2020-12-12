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

```
repository
├── meta.properties
├── archive
│   ├── foo
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

### Defaults

Default configuration lives in the `[defaults]` section.

### Repositories

Each repository is configured in a section under the `repository` key, so a
local repository would be `[repository.local]`.


## Operations

### Initialize Repository

```
hoard init <repo>
```

Initialize a new empty repository structure. May not be necessary for all
repository types.

### Sync Repository

```
hoard sync <from-repo> <to-repo> [archive...]
```

Synchronize the versions and data from one repository to another. Specific
archive names may be provided, or all archives will be synchronized by default.

### List Archives

```
hoard list <repo>
```

List the archive namespaces present in the repository.
- optionally include metadata?

### Repository Status

```
hoard status <repo> [archive] [version]
```

View information about an archive including metadata, storage statistics, and
available versions.

### Archive Data

```
hoard archive <repo> <archive> [source-path] [--update] [--exclude PATH ...]
```

creating a backup:
- iterating over all of the files on disk
- computing a hash of each file
- if the hash is not present on the other end, encrypt and send the file
- generate an index of all files with hash/mtime/size/path
- encrypt and store the timestamped index

### Restore Data

```
hoard restore <repo> <archive> [target-path] [--version ID] [--prefix PATH ...] [--exclude PATH ...]
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
```

- verify index by checking that all files are present in target storage
- deep verify would decrypt and re-hash files

### Trim Data

```
hoard trim <repo> [archive] [--keep-days D] [--keep-versions N]
```

- prune data outside a list of indexes to keep
