Hoard
=====

> Some pithy quote about a dragon's hoard.

Hoard is a command-line tool for managing backups of your important files. It
is designed to safely store backup snapshots of a directory tree in insecure
storage mediums like a portable drive or a cloud provider.

**NOTE:** I worked on this until the prototype stage, then discovered that [Borg](https://www.borgbackup.org/) already exists and does everything I planned to and more. This was a fun experiment, but I'm shelving it in favor of a widely-supported tool.


## Design

Hoard is built around three concepts - file archives, snapshot versions, and
storage repositories. Put simply, you work locally with files in an archive,
then you store new versions of those files in one or more repositories.

### Archives

An _archive_ is defined by a local working tree that contains a `.hoard`
directory. All of the files under the root are part of the archive and will be
preserved in new versions by default.

```
local
├── .hoard
│   ├── config                      archive configuration
│   ├── ignore                      list of patterns of files to ignore
│   ├── versions                    directory of locally-known versions
│   │   ├── 20201204-01482-abcd
│   │   ├── 20201210-57391-defg
│   │   └── ...
│   └── cache                       cache directory, may be deleted
│       └── tree                    content hashes of local files
└── ...files...
```

The archive configuration contains:
- archive name
- encoding/decoding settings
- created-at timestamp

Locally, the files in an archive are regular, raw files. When they are stored
into a repository, they are _encoded_ using the command specified in the
archive configuration. When they are read from the repo, they are _decoded_
using the corresponding command from the config. This is the way files are
secured for storage in untrusted or insecure mediums. For example, to use `gpg`
to encrypt your files, you'd set the following config:

```
encode-command = gpg --encrypt --armor --recipient you@example.com
decode-command = gpg --decrypt --batch --status-fd 2
```

### Versions

A _version_ captures the exact state of the files within an archive at a
specific point in time. This includes directories, symlinks, permissions, and
timestamps in addition to file contents.

Each version is stored as an index of the tree with pointers to both the raw
file hashes as well as the encoded block hashes. When comparing the current
archive state to the most recent version, this lets the tool determine just
the files that are different and need to be encoded and stored again.

### Repositories

A _repository_ is a storage location which can hold versions and data for
multiple archives. For example, you might define a repository for a local
filesystem on a USB storage drive, to back up your files to.

Repository configuration is stored in `$HOME/.config/hoard/config`. Each
repository is configured in a section under the `repository` key, so a repo
named "local" would be under `[repository.local]`.

Conceptually, file-backed repositories are laid out like this:

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

Note that _only_ the `config` file is stored raw (as it contains the
instructions to decode the remaining data). All version and block files are
encoded using the related archive's commands. This allows for different
archives to be encrypted with different mechanisms, or for different audiences.


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


## Usage

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
